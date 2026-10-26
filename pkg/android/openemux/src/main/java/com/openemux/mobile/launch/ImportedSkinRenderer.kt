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

    private class Desc(val name: String, val rect: RectF)

    /** Where the representation lands on screen: mapping units times [s], from ([ox], [oy]). */
    private class Placement(val s: Float, val ox: Float, val oy: Float) {
        fun rect(x: Double, y: Double, w: Double, h: Double) =
            RectF(ox + x.toFloat() * s, oy + y.toFloat() * s, ox + (x + w).toFloat() * s, oy + (y + h).toFloat() * s)
    }

    /** [tablet] picks the skin's iPad art over its iPhone art; [hardcore] leaves fast forward out. */
    fun render(
        skinFile: File, console: Console, width: Int, height: Int, outDir: File,
        cutout: Int = 0, tablet: Boolean = false, hardcore: Boolean = false,
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
            listOf("portrait" to "landscape", "landscape" to "portrait").forEachIndexed { i, (orient, other) ->
                val rep = representation(reps, orient, tablet)
                val portrait = orient == "portrait"
                val w = if (portrait) portraitW else portraitH
                val h = if (portrait) portraitH else portraitW
                val image = "$orient.png"
                val (descs, viewport) = drawOverlay(zip, rep, portrait, w, h, File(outDir, image))
                writeOverlay(cfg, i, orient, other, image, descs, w, h, viewport)
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

    private fun drawOverlay(zip: ZipFile, rep: JSONObject, portrait: Boolean, w: Int, h: Int, out: File): Pair<List<Desc>, RectF> {
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
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val defaultEdges = rep.optJSONObject("extendedEdges")
        val descs = mutableListOf<Desc>()
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
                is JSONObject -> directional(inputs, touch, item.has("thumbstick"), descs)
                is JSONArray -> {
                    val binds = (0 until inputs.length()).mapNotNull { bind(inputs.getString(it)) }
                    if (binds.isNotEmpty()) descs += Desc(binds.joinToString("|"), touch)
                }
            }
        }
        return descs to viewport
    }

    /** A D-pad as a 3x3 grid, diagonals included, or a stick as an analog stick. */
    private fun directional(inputs: JSONObject, box: RectF, thumbstick: Boolean, descs: MutableList<Desc>) {
        val names = listOf("up", "down", "left", "right").map { inputs.optString(it) }
        if (names.any { it.startsWith("rightAnalogStick") || it.startsWith("cStick") }) {
            descs += Desc(RADIAL_RIGHT, box)
            return
        }
        if (names.any { it.startsWith("analogStick") }) {
            descs += Desc(RADIAL, box)
            return
        }
        val up = bind(inputs.optString("up"))
        val down = bind(inputs.optString("down"))
        val left = bind(inputs.optString("left"))
        val right = bind(inputs.optString("right"))
        if (thumbstick || up == null || down == null || left == null || right == null) {
            descs += Desc(RADIAL, box)
            return
        }
        val cw = box.width() / 3f
        val ch = box.height() / 3f
        fun cell(col: Int, row: Int) = RectF(box.left + col * cw, box.top + row * ch, box.left + (col + 1) * cw, box.top + (row + 1) * ch)
        descs += listOf(
            Desc(up, cell(1, 0)), Desc(down, cell(1, 2)), Desc(left, cell(0, 1)), Desc(right, cell(2, 1)),
            Desc("$up|$left", cell(0, 0)), Desc("$up|$right", cell(2, 0)),
            Desc("$down|$left", cell(0, 2)), Desc("$down|$right", cell(2, 2)),
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

    private fun writeOverlay(
        cfg: StringBuilder, index: Int, name: String, other: String, image: String,
        descs: List<Desc>, w: Int, h: Int, picture: RectF,
    ) {
        val p = "overlay$index"
        cfg.append("${p}_name = \"$name\"\n")
        // GL counts y from the bottom.
        cfg.append(
            String.format(
                Locale.US, "%s_viewport = \"%.5f,%.5f,%.5f,%.5f\"\n",
                p, picture.left / w, (h - picture.bottom) / h, picture.width() / w, picture.height() / h,
            )
        )
        cfg.append("${p}_overlay = \"$image\"\n")
        cfg.append("${p}_full_screen = true\n")
        cfg.append("${p}_normalized = true\n")
        cfg.append("${p}_range_mod = 1.0\n")
        cfg.append("${p}_alpha_mod = 1.0\n")
        cfg.append("${p}_descs = ${descs.size + 1}\n")
        descs.forEachIndexed { i, d ->
            val radial = d.name == RADIAL || d.name == RADIAL_RIGHT
            cfg.append(
                String.format(
                    Locale.US, "%s_desc%d = \"%s,%.5f,%.5f,%s,%.5f,%.5f\"\n",
                    p, i, d.name, d.rect.centerX() / w, d.rect.centerY() / h, if (radial) "radial" else "rect",
                    d.rect.width() / 2f / w, d.rect.height() / 2f / h,
                )
            )
            if (radial) cfg.append("${p}_desc${i}_saturate_pct = 0.65\n")
        }
        // Unreachable switch that lets auto-rotate find the other orientation.
        val n = descs.size
        cfg.append("${p}_desc$n = \"overlay_next,0.5,0.5,rect,0.00001,0.00001\"\n")
        cfg.append("${p}_desc${n}_next_target = \"$other\"\n")
    }

    companion object {
        private const val TAG = "ImportedSkin"
        private const val RADIAL = "analog_left"
        private const val RADIAL_RIGHT = "analog_right"

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
