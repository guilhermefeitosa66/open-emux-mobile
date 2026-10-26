package com.openemux.mobile.launch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import com.openemux.mobile.data.Console
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns a controller skin in the package format of the iOS emulators Delta
 * and Manic EMU (a .deltaskin or .manicskin: a zip with info.json and PDF or
 * PNG art) into a RetroArch overlay, the same kind of skin.cfg
 * [SkinRenderer] writes for the drawn skins. The app's own skins for the
 * twelve best-known consoles ship in this format (assets/skins, built by
 * tools/skins).
 *
 * The device's portrait and landscape representations become one overlay
 * each; the art is scaled from the skin's mappingSize to the screen, the
 * game's screens are cut out of it and become the overlay's viewport, and
 * every item becomes a touch area bound to a RetroPad button or a hotkey.
 */
class ImportedSkinRenderer {

    /** What a drawOverlay laid out: the touch areas, the game's box, and what a stick's zone keeps clear of. */
    private class Drawn(val descs: List<OverlayDesc>, val viewport: RectF, val clear: List<RectF>, val gap: Float)

    /** Where the representation lands on screen: mapping units times [s], from ([ox], [oy]). */
    private class Placement(val s: Float, val ox: Float, val oy: Float) {
        fun rect(x: Double, y: Double, w: Double, h: Double) =
            RectF(ox + x.toFloat() * s, oy + y.toFloat() * s, ox + (x + w).toFloat() * s, oy + (y + h).toFloat() * s)
    }

    /**
     * [tablet] picks the skin's iPad art over its iPhone art; [hardcore] leaves fast forward out.
     * [first] ("portrait" or "landscape") is the overlay RetroArch opens on: a skin redrawn in game keeps the one shown.
     */
    fun render(
        skinFile: File, console: Console, width: Int, height: Int, outDir: File,
        cutout: Int = 0, tablet: Boolean = false, hardcore: Boolean = false, first: String? = null,
    ): File {
        this.console = console
        this.hardcore = hardcore
        outDir.mkdirs()
        val portraitW = minOf(width, height)
        val portraitH = maxOf(width, height) - cutout
        ZipFile(skinFile).use { zip ->
            val infoEntry = zip.entries().asSequence()
                .first { it.name.endsWith("info.json") && !it.name.startsWith("__MACOSX") }
            val text = zip.getInputStream(infoEntry).bufferedReader().readText().removePrefix("﻿")
            // Some community skins carry trailing commas, which strict JSON rejects.
            val info = JSONObject(text.replace(Regex(",(\\s*[}\\]])"), "$1"))
            Log.i(TAG, "importing ${info.optString("name")} (${info.optString("identifier")})")
            val reps = info.getJSONObject("representations")

            val cfg = StringBuilder("overlays = 2\n")
            val orients = listOf("portrait" to "landscape", "landscape" to "portrait")
            (if (first == "landscape") orients.reversed() else orients).forEachIndexed { i, (orient, other) ->
                val rep = representation(reps, orient, tablet)
                val portrait = orient == "portrait"
                val w = if (portrait) portraitW else portraitH
                val h = if (portrait) portraitH else portraitW
                val image = "$orient.png"
                val drawn = drawOverlay(zip, rep, portrait, w, h, outDir, image)
                // The skin's own extended edges already widen the touch areas.
                OverlayWriter.write(
                    cfg, i, orient, other, image, drawn.descs, w, h, drawn.viewport, grow = 1f, clear = drawn.clear, gap = drawn.gap,
                )
            }
            return File(outDir, "skin.cfg").apply { writeText(cfg.toString()) }
        }
    }

    private var console: Console = Console.GBA
    private var hardcore = false

    /** The device's own art first (iPad on a tablet, iPhone on a phone), edge-to-edge before the classic one. */
    private fun representation(reps: JSONObject, orient: String, tablet: Boolean): JSONObject =
        (if (tablet) listOf("ipad", "iphone") else listOf("iphone", "ipad")).firstNotNullOfOrNull { family ->
            listOf("edgeToEdge", "standard").firstNotNullOfOrNull { reps.optJSONObject(family)?.optJSONObject(it)?.optJSONObject(orient) }
        } ?: error("no $orient representation")

    private fun drawOverlay(
        zip: ZipFile, rep: JSONObject, portrait: Boolean, w: Int, h: Int, outDir: File, image: String,
    ): Drawn {
        val size = rep.getJSONObject("mappingSize")
        val mw = size.getDouble("width").toFloat()
        val mh = size.getDouble("height").toFloat()
        // The screens the game goes in (the DS has two, one over the other); the ones with filters are
        // blurred backdrops RetroArch cannot draw.
        val frames = rep.optJSONArray("screens")?.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }.filter { !it.has("filters") && it.has("outputFrame") }.map { it.getJSONObject("outputFrame") }
        }.orEmpty().ifEmpty { listOfNotNull(rep.optJSONObject("gameScreenFrame")) }
        val screen = frames.takeIf { it.isNotEmpty() }?.let { fs ->
            val l = fs.minOf { it.getDouble("x") }
            val t = fs.minOf { it.getDouble("y") }
            JSONObject().put("x", l).put("y", t)
                .put("width", fs.maxOf { it.getDouble("x") + it.getDouble("width") } - l)
                .put("height", fs.maxOf { it.getDouble("y") + it.getDouble("height") } - t)
        }

        val place: Placement
        val viewport: RectF
        if (screen != null) {
            // A whole-device skin: fit it, centred, and the game goes where it says.
            val s = minOf(w / mw, h / mh)
            place = Placement(s, (w - mw * s) / 2f, (h - mh * s) / 2f)
            viewport = place.rect(screen.getDouble("x"), screen.getDouble("y"), screen.getDouble("width"), screen.getDouble("height"))
        } else if (portrait) {
            // Only the controls (Delta's own skins): full width at the bottom, the game in the room above.
            val s = w / mw
            place = Placement(s, 0f, h - mh * s)
            viewport = RectF(0f, 0f, w.toFloat(), h - mh * s)
        } else {
            // Landscape controls laid over a full-screen game.
            val s = minOf(w / mw, h / mh)
            place = Placement(s, (w - mw * s) / 2f, (h - mh * s) / 2f)
            viewport = RectF(0f, 0f, w.toFloat(), h.toFloat())
        }

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val art = place.rect(0.0, 0.0, mw.toDouble(), mh.toDouble())
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        // Translucent skins sit over the game, so the picture shows through them.
        if (rep.optBoolean("translucent", false)) paint.alpha = 0xB3
        canvas.drawBitmap(rasterize(zip, assetName(rep.getJSONObject("assets")), art), null, art, paint)
        if (screen != null) canvas.drawRect(viewport, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })

        val defaultEdges = rep.optJSONObject("extendedEdges")
        val descs = mutableListOf<OverlayDesc>()
        val items = rep.optJSONArray("items") ?: JSONArray()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val f = item.getJSONObject("frame")
            val e = item.optJSONObject("extendedEdges") ?: defaultEdges
            fun edge(k: String) = e?.optDouble(k, 0.0) ?: 0.0
            val touch = place.rect(
                f.getDouble("x") - edge("left"), f.getDouble("y") - edge("top"),
                f.getDouble("width") + edge("left") + edge("right"), f.getDouble("height") + edge("top") + edge("bottom"),
            )
            when (val inputs = item.get("inputs")) {
                is JSONObject -> {
                    val frame = place.rect(f.getDouble("x"), f.getDouble("y"), f.getDouble("width"), f.getDouble("height"))
                    val knob = item.optJSONObject("thumbstick")?.let { knob(zip, it, frame, place, paint) }
                    directional(inputs, touch, item.has("thumbstick"), knob, descs)
                }
                is JSONArray -> {
                    val binds = (0 until inputs.length()).mapNotNull { bind(inputs.getString(it)) }
                    if (binds.isNotEmpty()) descs += OverlayDesc(binds.joinToString("|"), touch)
                    OverlayWriter.LIT_BY[binds.singleOrNull()]?.let { state ->
                        descs += litPill(bitmap, state, place.rect(f.getDouble("x"), f.getDouble("y"), f.getDouble("width"), f.getDouble("height")), place.s)
                    }
                }
            }
        }
        OverlayWriter.saveImages(outDir, image, bitmap, descs)
        // A whole-device skin keeps the game's picture clear of the sticks' zones; one over the game cannot.
        return Drawn(descs, viewport, listOfNotNull(viewport.takeIf { screen != null }), 2f * place.s)
    }

    /**
     * A stick's knob ([thumbstick], the item's knob image at its own size in mapping units) as an image the size
     * of the knob's travel, centred on the stick's [frame], or null when the image cannot be read.
     */
    private fun knob(zip: ZipFile, thumbstick: JSONObject, frame: RectF, place: Placement, paint: Paint): OverlayDesc? {
        val name = thumbstick.optString("name").ifEmpty { return null }
        val r = minOf(frame.width(), frame.height()) / 2f
        // A knob with no size given is half the well's.
        val kw = thumbstick.optDouble("width", 0.0).toFloat().let { if (it > 0f) it * place.s else r }
        val kh = thumbstick.optDouble("height", 0.0).toFloat().let { if (it > 0f) it * place.s else r }
        // The engine stretches the image over the stick's range: the range is the travel, unless the knob is
        // larger, and then the value still peaks at the travel. The app's own skins export the knob at the travel's size.
        val travel = r * OverlayWriter.KNOB_TRAVEL
        val half = maxOf(kw, kh) / 2f
        val range = if (half > travel * 1.02f) half else travel
        val side = Math.round(2f * range).coerceAtLeast(1)
        val target = RectF((side - kw) / 2f, (side - kh) / 2f, (side + kw) / 2f, (side + kh) / 2f)
        val image = try {
            rasterize(zip, name, target)
        } catch (e: Exception) {
            Log.w(TAG, "thumbstick '$name' not readable", e)
            return null
        }
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawBitmap(image, null, target, paint)
        image.recycle()
        val cx = frame.centerX()
        val cy = frame.centerY()
        return OverlayDesc("", RectF(cx - range, cy - range, cx + range, cy + range), radial = true, well = frame, knob = bitmap, saturate = travel / range)
    }

    /**
     * A toggle's "on" look over its item's [frame] in the drawn [art], [s] pixels per mapping unit: the accent pill of
     * the drawn skins' strip (46 x 30 at 80 % of the frame's height, no wider than the frame less 8 units), with the
     * skin's own glyph lifted off the art and drawn over it in white, so it works for any skin, not only the bundled ones.
     */
    private fun litPill(art: Bitmap, state: String, frame: RectF, s: Float): OverlayDesc {
        val h = frame.height() * 0.8f
        val bitmap = OverlayWriter.pill(minOf(h * 46f / 30f, frame.width() - 8f * s).toInt(), h.toInt(), h / 3f, h / 20f)
        val left = (frame.centerX() - bitmap.width / 2f).roundToInt()
        val top = (frame.centerY() - bitmap.height / 2f).roundToInt()
        glyph(art, left, top, bitmap.width, bitmap.height)?.let { Canvas(bitmap).drawBitmap(it, 0f, 0f, null) }
        val rect = RectF(left.toFloat(), top.toFloat(), (left + bitmap.width).toFloat(), (top + bitmap.height).toFloat())
        return OverlayDesc("nul", rect, lit = OverlayLit(state, bitmap))
    }

    /**
     * The glyph in the [w] x [h] box of [art] at ([left], [top]): whatever differs from the color around it (the median
     * of the box's edge), as white with that difference for alpha; null when nothing stands out.
     */
    private fun glyph(art: Bitmap, left: Int, top: Int, w: Int, h: Int): Bitmap? {
        val px = IntArray(w * h)
        // Only the part of the box inside the art is read; the rest stays transparent.
        val x0 = maxOf(0, left)
        val y0 = maxOf(0, top)
        val x1 = minOf(art.width, left + w)
        val y1 = minOf(art.height, top + h)
        if (x1 > x0 && y1 > y0) art.getPixels(px, (y0 - top) * w + (x0 - left), w, x0, y0, x1 - x0, y1 - y0)
        val edge = (0 until w).flatMap { listOf(px[it], px[(h - 1) * w + it]) } + (0 until h).flatMap { listOf(px[it * w], px[it * w + w - 1]) }
        fun median(shift: Int) = edge.map { (it shr shift) and 0xFF }.sorted()[edge.size / 2]
        val bg = intArrayOf(median(16), median(8), median(0))
        val diff = IntArray(px.size) { i ->
            val c = px[i]
            val d = maxOf(abs(((c shr 16) and 0xFF) - bg[0]), abs(((c shr 8) and 0xFF) - bg[1]), abs((c and 0xFF) - bg[2]))
            d * ((c ushr 24) and 0xFF) / 0xFF
        }
        val most = diff.maxOrNull() ?: 0
        if (most < GLYPH_MIN_DIFF) return null
        // The faintest fifth is the art's own shading; the glyph's anti-aliased edge ramps up to its full colour.
        val alphas = IntArray(px.size) { i -> (((diff[i] - most * 0.2f) / (most * 0.5f)).coerceIn(0f, 1f) * 0xFF).toInt() shl 24 or 0xFFFFFF }
        return Bitmap.createBitmap(alphas, w, h, Bitmap.Config.ARGB_8888)
    }

    /** A D-pad as a 3x3 grid, diagonals included, or a stick as an analog stick. */
    private fun directional(inputs: JSONObject, box: RectF, thumbstick: Boolean, knob: OverlayDesc?, descs: MutableList<OverlayDesc>) {
        // With a knob image the stick is laid out as the drawn skins' are; without one, as before, over its frame.
        fun stick(name: String) =
            knob?.let { OverlayDesc(name, it.rect, radial = true, well = it.well, knob = it.knob, saturate = it.saturate) }
                ?: OverlayDesc(name, box, radial = true)
        val names = listOf("up", "down", "left", "right").map { inputs.optString(it) }
        if (names.any { it.startsWith("rightAnalogStick") || it.startsWith("cStick") }) {
            descs += stick(RADIAL_RIGHT)
            return
        }
        if (names.any { it.startsWith("analogStick") }) {
            descs += stick(RADIAL)
            return
        }
        val up = bind(inputs.optString("up"))
        val down = bind(inputs.optString("down"))
        val left = bind(inputs.optString("left"))
        val right = bind(inputs.optString("right"))
        if (thumbstick || up == null || down == null || left == null || right == null) {
            descs += stick(RADIAL)
            return
        }
        val cw = box.width() / 3f
        val ch = box.height() / 3f
        fun cell(col: Int, row: Int) = RectF(box.left + col * cw, box.top + row * ch, box.left + (col + 1) * cw, box.top + (row + 1) * ch)
        descs += listOf(
            OverlayDesc(up, cell(1, 0)), OverlayDesc(down, cell(1, 2)), OverlayDesc(left, cell(0, 1)), OverlayDesc(right, cell(2, 1)),
            OverlayDesc("$up|$left", cell(0, 0)), OverlayDesc("$up|$right", cell(2, 0)),
            OverlayDesc("$down|$left", cell(0, 2)), OverlayDesc("$down|$right", cell(2, 2)),
        )
    }

    /**
     * The skin's input name as a RetroArch overlay bind, or null for one left out. The skin names the
     * console's own buttons; the RetroPad has them where data/Skin.kt puts them for that console.
     */
    private fun bind(input: String): String? {
        CONSOLE_BINDS[console]?.get(input)?.let { return it }
        return when (input) {
            "up", "down", "left", "right", "a", "b", "x", "y", "l", "r", "l2", "r2", "l3", "r3", "start", "select" -> input
            "l1" -> "l"
            "r1" -> "r"
            "menu" -> "menu_toggle"
            "quickSave" -> "save_state"
            "quickLoad" -> "load_state"
            "fastForward" -> if (hardcore) null else "hold_fast_forward"
            "toggleFastForward" -> if (hardcore) null else "toggle_fast_forward"
            "turbo" -> "turbo_fire_toggle"
            "screenshot" -> "screenshot"
            "restart", "reset" -> "reset"
            else -> null.also { Log.w(TAG, "input '$input' not mapped") }
        }
    }

    /** resizable (a PDF) when there is one, the largest PNG otherwise; the keys' case varies between skins. */
    private fun assetName(assets: JSONObject): String {
        val byKey = assets.keys().asSequence().associateBy({ it.lowercase(Locale.US) }, { assets.getString(it) })
        return listOf("resizable", "large", "medium", "small").firstNotNullOf { byKey[it] }
    }

    private fun rasterize(zip: ZipFile, name: String, target: RectF): Bitmap {
        val entry = zip.entries().asSequence().first { !it.name.startsWith("__MACOSX") && (it.name == name || it.name.endsWith("/$name")) }
        val tw = target.width().toInt().coerceAtLeast(1)
        val th = target.height().toInt().coerceAtLeast(1)
        if (!name.endsWith(".pdf", ignoreCase = true)) {
            return zip.getInputStream(entry).use { BitmapFactory.decodeStream(it) }
        }
        // PdfRenderer reads from a file descriptor: the page goes through a temporary copy.
        val tmp = File.createTempFile("skin", ".pdf")
        try {
            zip.getInputStream(entry).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { pdf ->
                    pdf.openPage(0).use { page ->
                        val bitmap = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
                        val m = Matrix().apply { setScale(tw.toFloat() / page.width, th.toFloat() / page.height) }
                        page.render(bitmap, Rect(0, 0, tw, th), m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        return bitmap
                    }
                }
            }
        } finally {
            tmp.delete()
        }
    }

    companion object {
        private const val TAG = "ImportedSkin"
        private const val RADIAL = "analog_left"
        private const val RADIAL_RIGHT = "analog_right"

        /** The least difference from its surroundings a toggle's glyph has, out of 255 per channel. */
        private const val GLYPH_MIN_DIFF = 48

        /** Where the skins' buttons sit on the RetroPad, for the consoles whose names differ (as in data/Skin.kt). */
        private val CONSOLE_BINDS = mapOf(
            Console.N64 to mapOf(
                "a" to "b", "b" to "y", "z" to "l2", "cUp" to "r_y_minus", "cDown" to "r_y_plus",
                "cLeft" to "r_x_minus", "cRight" to "r_x_plus",
            ),
            Console.MD to mapOf("a" to "y", "b" to "b", "c" to "a", "x" to "l", "y" to "x", "z" to "r", "mode" to "select"),
            Console.GC to mapOf("z" to "r", "l" to "l2", "r" to "r2"),
        )

        /**
         * The skin for [console]: one the user put in files/skins/<console> (the first .deltaskin or
         * .manicskin), else the one the app ships in assets/skins, copied out so it can be read as a zip.
         */
        fun find(context: Context, base: File, console: Console): File? {
            File(base, "skins/${console.id}").listFiles()
                ?.filter { it.name.endsWith(".deltaskin", true) || it.name.endsWith(".manicskin", true) }
                ?.minByOrNull { it.name }
                ?.let { return it }
            val name = context.assets.list("skins")?.firstOrNull { it.substringBeforeLast('.') == console.id } ?: return null
            val out = File(context.cacheDir, "skins/$name")
            out.parentFile?.mkdirs()
            context.assets.open("skins/$name").use { input -> out.outputStream().use { input.copyTo(it) } }
            return out
        }
    }
}
