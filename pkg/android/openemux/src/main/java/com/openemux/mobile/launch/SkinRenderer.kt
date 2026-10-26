package com.openemux.mobile.launch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.PathParser
import com.openemux.mobile.data.Console
import com.openemux.mobile.data.KEYPAD_BIND
import com.openemux.mobile.data.NDS_LAYOUTS
import com.openemux.mobile.data.NdsLayouts
import com.openemux.mobile.data.ScreenLayout
import com.openemux.mobile.data.Skin
import com.openemux.mobile.data.SkinCluster
import com.openemux.mobile.data.SkinColors
import com.openemux.mobile.data.SkinFamily
import com.openemux.mobile.data.SkinKey
import com.openemux.mobile.data.SkinRow
import com.openemux.mobile.data.SkinSide
import com.openemux.mobile.data.forCore
import com.openemux.mobile.data.skin
import com.openemux.mobile.ui.Icons
import com.retroarch.R
import java.io.File
import java.util.Locale

/**
 * Draws the console's controller skin (data/Skin.kt, the design's skin
 * boards) at the screen's exact size and writes it as a RetroArch overlay:
 * one image per orientation plus the touch areas, each bound to a RetroPad
 * button, an analog stick or a hotkey.
 */
class SkinRenderer(private val context: Context) {

    /** One overlay: [rotate] is the one turning the screen switches to, [layoutNext] the NDS layout key's target. */
    private class Spec(
        val name: String, val portrait: Boolean, val aspect: Float, val rotate: String,
        val keypadOpen: Boolean = false, val layoutNext: String? = null,
    )

    /** [radial] marks an analog stick, whose rect is its range; [next] is the overlay an overlay_next switches to. */
    private class Desc(val name: String, val rect: RectF, val radial: Boolean = false, val next: String? = null)

    private val inter: Typeface = ResourcesCompat.getFont(context, R.font.inter_bold) ?: Typeface.DEFAULT_BOLD
    private val grotesk: Typeface = ResourcesCompat.getFont(context, R.font.grotesk_bold) ?: Typeface.DEFAULT_BOLD

    /**
     * [cutout] is the camera cutout's depth along the long edge: the engine's
     * window, and so the overlay, only covers the screen past it.
     * [hardcore] greys out the strip's fast forward and takes its touch area away:
     * the engine still fast-forwards in RetroAchievements hardcore, the app does not offer it.
     */
    fun render(
        console: Console, width: Int, height: Int, outDir: File, cutout: Int = 0,
        ndsLayouts: Pair<ScreenLayout, ScreenLayout>? = null, hardcore: Boolean = false, coreId: String? = null,
    ): File {
        outDir.mkdirs()
        val skin = console.skin.forCore(coreId)
        val portraitW = minOf(width, height)
        val portraitH = maxOf(width, height) - cutout
        val unit = portraitW / 390f

        val specs = mutableListOf<Spec>()
        if (ndsLayouts != null) {
            // One overlay per NDS layout and orientation, the saved ones first: the layout key steps to the next,
            // turning switches to the other orientation's saved one, and the activity sets the core to match.
            val (port, land) = ndsLayouts
            for (orient in listOf("portrait", "landscape")) {
                val layouts = NDS_LAYOUTS.getValue(orient)
                val saved = layouts.indexOfFirst { it.id == (if (orient == "portrait") port else land).id }
                val other = if (orient == "portrait") "landscape@${land.id}" else "portrait@${port.id}"
                (layouts.drop(saved) + layouts.take(saved)).forEach { l ->
                    specs += Spec("$orient@${l.id}", orient == "portrait", l.aspect, other, layoutNext = "$orient@${NdsLayouts.next(orient, l.id).id}")
                }
            }
            // RetroArch opens the first overlay: the saved portrait layout.
            specs.sortBy { if (it.name == "portrait@${port.id}") 0 else 1 }
        } else {
            specs += Spec("portrait", true, console.gameAspect, "landscape")
            specs += Spec("landscape", false, console.gameAspect, "portrait")
            // The keypad is an overlay of its own per orientation, switched to by the keypad key.
            if (skin.keypad.isNotEmpty()) {
                specs += Spec("keypad-portrait", true, console.gameAspect, "keypad-landscape", keypadOpen = true)
                specs += Spec("keypad-landscape", false, console.gameAspect, "keypad-portrait", keypadOpen = true)
            }
        }
        val cfg = StringBuilder()
        cfg.append("overlays = ${specs.size}\n")
        specs.forEachIndexed { i, spec ->
            val w = if (spec.portrait) portraitW else portraitH
            val h = if (spec.portrait) portraitH else portraitW
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val toggle = if (spec.keypadOpen) spec.name.removePrefix("keypad-") else "keypad-${spec.name}"
            val layout = Layout(Canvas(bitmap), skin, unit, spec.aspect, spec.keypadOpen, toggle, spec.layoutNext, hardcore)
            val descs = if (spec.portrait) layout.portrait(h) else layout.landscape(w)
            val image = spec.name.replace('@', '-') + ".png"
            File(outDir, image).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            writeOverlay(cfg, i, spec.name, spec.rotate, image, descs, w, h, layout.picture)
        }
        val file = File(outDir, "skin.cfg")
        file.writeText(cfg.toString())
        return file
    }

    private fun writeOverlay(
        cfg: StringBuilder, index: Int, name: String, other: String, image: String,
        descs: List<Desc>, w: Int, h: Int, picture: RectF,
    ) {
        val p = "overlay$index"
        cfg.append("${p}_name = \"$name\"\n")
        // Where the engine draws the game, keeping its aspect inside this box; GL counts y from the bottom.
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
            // Touch areas a little larger than what is drawn: thumbs are wide.
            val grow = if (d.radial) 1f else 1.12f
            val hw = d.rect.width() / 2f * grow
            val hh = d.rect.height() / 2f * grow
            cfg.append(
                String.format(
                    Locale.US, "%s_desc%d = \"%s,%.5f,%.5f,%s,%.5f,%.5f\"\n",
                    p, i, d.name, d.rect.centerX() / w, d.rect.centerY() / h, if (d.radial) "radial" else "rect", hw / w, hh / h,
                )
            )
            if (d.radial) cfg.append("${p}_desc${i}_saturate_pct = 0.65\n")
            d.next?.let { cfg.append("${p}_desc${i}_next_target = \"$it\"\n") }
        }
        // Unreachable switch that lets auto-rotate find the other orientation.
        val n = descs.size
        cfg.append("${p}_desc$n = \"overlay_next,0.5,0.5,rect,0.00001,0.00001\"\n")
        cfg.append("${p}_desc${n}_next_target = \"$other\"\n")
    }

    /**
     * One orientation, drawn in the design's units (its phone frame is 390
     * wide in portrait and 390 tall in landscape): [unit] pixels each.
     */
    private inner class Layout(
        private val c: Canvas,
        /** The console's skin with the launched core's binds. */
        private val skin: Skin,
        private val unit: Float, private val aspect: Float,
        /** Whether the keypad takes the face buttons' place; [toggle] is the overlay its key switches to. */
        private val keypadOpen: Boolean, private val toggle: String,
        /** The overlay of the next NDS screen layout, for the strip's layout key. */
        private val layoutNext: String?,
        /** RetroAchievements hardcore: the strip's fast forward is drawn off and does nothing. */
        private val hardcore: Boolean,
    ) {
        private val colors: SkinColors = skin.colors
        private val descs = mutableListOf<Desc>()

        /** The game's box, in pixels, as the overlay's viewport. */
        var picture = RectF()
            private set

        /** Maps design units to pixels; the portrait panel squeezes through it when short. */
        private var ox = 0f
        private var oy = 0f
        private var f = 1f
        private fun x(v: Float) = (ox + v * f) * unit
        private fun y(v: Float) = (oy + v * f) * unit
        private fun s(v: Float) = v * f * unit
        private fun rect(l: Float, t: Float, w: Float, h: Float) = RectF(x(l), y(t), x(l + w), y(t + h))

        fun portrait(height: Int): List<Desc> {
            val hd = height / unit
            // The picture on top, at full width up to the design's cap: taller ones narrow, centred.
            val gameH = minOf(390f / aspect, if (skin.family == SkinFamily.DUAL) 466f else 380f)
            val gameW = gameH * aspect
            picture = RectF((390f - gameW) / 2f * unit, 0f, (390f + gameW) / 2f * unit, gameH * unit)
            fill(RectF(0f, gameH * unit, 390f * unit, height.toFloat()), colors.body)
            strip(RectF(0f, gameH * unit, 390f * unit, (gameH + 44f) * unit), 22f, opaque = true)

            // The design's panel under the strip is 420 tall; under a tall picture (two screens, a vertical one) it compacts.
            val top = gameH + 44f
            val room = hd - top
            if (room < 420f) return compact(top, room)
            f = minOf(1f, room / 420f)
            val pd = maxOf(room, 420f)
            ox = 195f * (1f - f)
            oy = top

            shoulderRow(skin.shouldersLeft, 16f, 167f, 16f, 40f, 15f)
            shoulderRow(skin.shouldersRight, 223f, 374f, 16f, 40f, 15f)
            menu(rect(171f, 14f, 48f, 44f))

            val m0 = 68f
            val m1 = pd - 108f
            clusters(96f, 290f, rows = mapOf(SkinRow.CENTER to (m0 + m1) / 2f, SkinRow.UPPER to m0 + (m1 - m0) * 0.27f, SkinRow.LOWER to m0 + (m1 - m0) * 0.75f), k = 1f, dpadK = 1f)
            if (keypadOpen) keypad(290f, (m0 + m1) / 2f, 180f, minOf(320f, m1 - m0 + 40f))

            val pills = skin.pills.filter { it.bind != null }
            pills.forEachIndexed { i, key -> pill(key, 195f + (i - (pills.size - 1) / 2f) * 96f, pd - 100f, 60f, 24f, 11f) }
            label(skin.name, 0f, pd - 38f, 390f, 11f, 0.18f)
            return descs
        }

        /** One row of shoulders, keys and menu on top, the controls under it, no name. */
        private fun compact(top: Float, room: Float): List<Desc> {
            f = minOf(1f, room / 200f)
            val pd = maxOf(room, 200f)
            ox = 195f * (1f - f)
            oy = top
            val pills = skin.pills.filter { it.bind != null }
            val shoulders = if (pills.isEmpty()) 151f else 100f
            shoulderRow(skin.shouldersLeft, 16f, 16f + shoulders, 8f, 32f, 13f)
            shoulderRow(skin.shouldersRight, 374f - shoulders, 374f, 8f, 32f, 13f)
            menu(rect(171f, 6f, 48f, 36f))
            val half = (pills.size + 1) / 2
            listOf(pills.take(half) to (22f + shoulders to 165f), pills.drop(half) to (225f to 368f - shoulders)).forEach { (keys, span) ->
                val w = (span.second - span.first - 6f * (keys.size - 1)) / keys.size.coerceAtLeast(1)
                keys.forEachIndexed { i, key -> pill(key, span.first + i * (w + 6f) + w / 2f, 13f, w, 22f, 0f) }
            }
            val band = pd - 56f
            val cy = 48f + band / 2f
            val k = minOf(1f, band / 180f)
            clusters(96f, 290f, rows = mapOf(SkinRow.CENTER to cy, SkinRow.UPPER to cy - band * 0.24f, SkinRow.LOWER to cy + band * 0.24f), k = k, dpadK = k)
            if (keypadOpen) keypad(290f, cy, 180f, band)
            return descs
        }

        fun landscape(width: Int): List<Desc> {
            val wd = width / unit
            // The picture centred between the control sides, with the strip under it.
            val gameW = minOf(wd - 2f * 170f, 354f * aspect)
            val gameH = gameW / aspect
            val left = (wd - gameW) / 2f
            val top = (390f - gameH - 36f) / 2f
            picture = RectF(left * unit, top * unit, (left + gameW) * unit, (top + gameH) * unit)
            // The overlay sits over the game: the picture's box stays clear.
            fill(RectF(0f, 0f, wd * unit, 390f * unit), colors.body)
            c.drawRect(picture, Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR) })
            strip(RectF(left * unit, (top + gameH) * unit, (left + gameW) * unit, (top + gameH + 36f) * unit), 20f, opaque = true)

            val side = left
            val lx = side / 2f
            val rx = wd - side / 2f
            shoulderRow(skin.shouldersLeft, 12f, side - 12f, 12f, 36f, 13f)
            shoulderRow(skin.shouldersRight, wd - side + 12f, wd - 12f, 12f, 36f, 13f)
            menu(rect(lx - 22f, 54f, 44f, 36f))

            clusters(lx, rx, rows = mapOf(SkinRow.CENTER to 196f, SkinRow.UPPER to 142f, SkinRow.LOWER to 262f), k = 0.82f, dpadK = 0.85f)
            if (keypadOpen) keypad(rx, 200f, side - 16f, 230f)

            val pills = skin.pills.filter { it.bind != null }
            if (pills.size == 1) pill(pills[0], rx, 330f, 52f, 20f, 10f)
            else {
                val half = (pills.size + 1) / 2
                val l = pills.take(half)
                val r = pills.drop(half)
                l.forEachIndexed { i, key -> pill(key, lx + (i - (l.size - 1) / 2f) * 80f, 330f, 52f, 20f, 10f) }
                r.forEachIndexed { i, key -> pill(key, rx + (i - (r.size - 1) / 2f) * 80f, 330f, 52f, 20f, 10f) }
            }
            label(skin.name, wd - side, 58f, side, 10f, 0.16f)
            return descs
        }

        private fun clusters(lx: Float, rx: Float, rows: Map<SkinRow, Float>, k: Float, dpadK: Float) {
            for (cl in skin.clusters) {
                if (keypadOpen && cl.side == SkinSide.RIGHT) continue
                val cx = if (cl.side == SkinSide.LEFT) lx else rx
                val cy = rows.getValue(cl.row)
                when (cl) {
                    is SkinCluster.DPad -> dpad(cx, cy, cl.size * dpadK, cl.binds)
                    is SkinCluster.Stick -> stick(cl, cx, cy, cl.size * k)
                    is SkinCluster.Face -> cl.slots.zip(cl.buttons).forEach { (slot, b) ->
                        val w = slot.w * k
                        val h = slot.h * k
                        val box = rect(cx + slot.dx * k - w / 2f, cy + slot.dy * k - h / 2f, w, h)
                        val radius = minOf(box.width(), box.height()) / 2f
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = b.bg
                            setShadowLayer(s(6f), 0f, s(5f), 0x66000000)
                        }
                        c.drawRoundRect(box, radius, radius, paint)
                        val size = maxOf(11f, minOf(slot.w, slot.h) * k * (if (b.label.length > 2) 0.22f else 0.34f))
                        text(b.label, box, b.fg, s(size), grotesk)
                        b.bind?.let { descs += Desc(it, box) }
                    }
                }
            }
        }

        private fun dpad(cx: Float, cy: Float, size: Float, binds: List<String>) {
            val box = rect(cx - size / 2f, cy - size / 2f, size, size)
            val u = box.width() / 120f
            fun at(px: Float, py: Float) = Pair(box.left + px * u, box.top + py * u)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.dpad }
            c.drawRoundRect(RectF(box.left + 40 * u, box.top + 2 * u, box.left + 80 * u, box.top + 118 * u), 9 * u, 9 * u, paint)
            c.drawRoundRect(RectF(box.left + 2 * u, box.top + 40 * u, box.left + 118 * u, box.top + 80 * u), 9 * u, 9 * u, paint)
            paint.color = colors.hub
            c.drawCircle(box.centerX(), box.centerY(), 9 * u, paint)
            paint.color = colors.mark
            for (tri in listOf(
                listOf(60f to 14f, 51f to 26f, 69f to 26f), listOf(60f to 106f, 51f to 94f, 69f to 94f),
                listOf(14f to 60f, 26f to 51f, 26f to 69f), listOf(106f to 60f, 94f to 51f, 94f to 69f),
            )) {
                val path = Path()
                tri.forEachIndexed { i, (px, py) ->
                    val (ax, ay) = at(px, py)
                    if (i == 0) path.moveTo(ax, ay) else path.lineTo(ax, ay)
                }
                path.close()
                c.drawPath(path, paint)
            }
            val cell = box.width() / 3f
            fun cellRect(col: Int, row: Int) =
                RectF(box.left + col * cell, box.top + row * cell, box.left + (col + 1) * cell, box.top + (row + 1) * cell)
            val (up, down, left, right) = binds
            descs += listOf(
                Desc(up, cellRect(1, 0)), Desc(down, cellRect(1, 2)),
                Desc(left, cellRect(0, 1)), Desc(right, cellRect(2, 1)),
                Desc("$up|$left", cellRect(0, 0)), Desc("$up|$right", cellRect(2, 0)),
                Desc("$down|$left", cellRect(0, 2)), Desc("$down|$right", cellRect(2, 2)),
            )
        }

        private fun stick(cl: SkinCluster.Stick, cx: Float, cy: Float, size: Float) {
            val box = rect(cx - size / 2f, cy - size / 2f, size, size)
            val r = box.width() / 2f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cl.color ?: colors.stick }
            c.drawCircle(box.centerX(), box.centerY(), r, paint)
            // The well's inner shadow, along its top.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = r * 0.08f
            paint.color = 0x40000000
            c.drawCircle(box.centerX(), box.centerY() + r * 0.03f, r * 0.94f, paint)
            paint.style = Paint.Style.FILL
            paint.color = cl.knob ?: colors.knob
            paint.setShadowLayer(s(6f), 0f, s(4f), 0x73000000)
            c.drawCircle(box.centerX(), box.centerY(), r * 0.56f, paint)
            if (cl.label.isNotEmpty()) text(cl.label, box, 0xFF3B2F7A.toInt(), s(13f), grotesk)
            descs += Desc(cl.bind, box, radial = true)
        }

        private fun shoulderRow(keys: List<SkinKey>, x0: Float, x1: Float, top: Float, h: Float, font: Float) {
            val shown = keys.filter { it.bind != null }
            if (shown.isEmpty()) return
            val w = (x1 - x0 - 8f * (shown.size - 1)) / shown.size
            shown.forEachIndexed { i, key ->
                val box = rect(x0 + i * (w + 8f), top, w, h)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.shoulder }
                c.drawRoundRect(box, s(12f), s(12f), paint)
                text(key.label, box, colors.shoulderInk, s(font), inter)
                descs += Desc(key.bind!!, box)
            }
        }

        private fun menu(box: RectF) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.menu }
            c.drawRoundRect(box, box.height() / 2f, box.height() / 2f, paint)
            val u = s(20f) / 24f
            val left = box.centerX() - 12f * u
            val top = box.centerY() - 12f * u
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 2f * u; color = colors.menuInk; strokeCap = Paint.Cap.ROUND
            }
            for (ly in floatArrayOf(7f, 12f, 17f)) c.drawLine(left + 5 * u, top + ly * u, left + 19 * u, top + ly * u, stroke)
            descs += Desc("menu_toggle", box)
        }

        private fun pill(key: SkinKey, cx: Float, top: Float, w: Float, h: Float, font: Float) {
            val box = rect(cx - w / 2f, top, w, h)
            val keypad = key.bind == KEYPAD_BIND
            // The keypad key stays lit while the keypad is open.
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (keypad && keypadOpen) colors.ink else colors.pill }
            c.drawRoundRect(box, s(12f), s(12f), paint)
            val text = key.labelRes?.let { context.getString(it).uppercase(context.resources.configuration.locales[0]) } ?: key.label
            // A [font] of 0 writes the label inside the key, for the compact panel.
            if (font == 0f) label(text, cx - w / 2f, top + h / 2f - 5.5f, w, 10f, 0.06f, alpha = 0xFF, color = colors.menuInk)
            else label(text, cx - 40f, top + h + 5f, 80f, font, 0.08f, alpha = 0xFF)
            descs += if (keypad) Desc("overlay_next", box, next = toggle) else Desc(key.bind!!, box)
        }

        /** The keypad's keys in a grid of [w] by [h] around ([cx], [cy]), on the face buttons' side. */
        private fun keypad(cx: Float, cy: Float, w: Float, h: Float) {
            val rows = skin.keypad
            val cols = rows.maxOf { it.size }
            val gap = 8f
            val key = minOf((w - gap * (cols - 1)) / cols, (h - gap * (rows.size - 1)) / rows.size)
            val kw = minOf((w - gap * (cols - 1)) / cols, key * 1.3f)
            val top = cy - (rows.size * key + gap * (rows.size - 1)) / 2f
            rows.forEachIndexed { r, row ->
                val left = cx - (row.size * kw + gap * (row.size - 1)) / 2f
                row.forEachIndexed { i, k ->
                    val box = rect(left + i * (kw + gap), top + r * (key + gap), kw, key)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = colors.pill
                        setShadowLayer(s(3f), 0f, s(2f), 0x4D000000)
                    }
                    c.drawRoundRect(box, s(10f), s(10f), paint)
                    text(k.label, box, colors.menuInk, s(minOf(18f, key * 0.4f)), grotesk)
                    k.bind?.let { descs += Desc(it, box) }
                }
            }
        }

        /** The quick actions: save, load, turbo, fast forward, in that order, then the NDS screen layout. */
        private fun strip(box: RectF, icon: Float, opaque: Boolean) {
            fill(box, if (opaque) colors.strip else (colors.strip and 0x00FFFFFF) or (0xCC shl 24))
            val actions = listOf("save_state", "load_state", "turbo_fire_toggle", "toggle_fast_forward") +
                listOfNotNull(layoutNext?.let { "screens" })
            val slot = box.width() / actions.size
            val size = s(icon)
            actions.forEachIndexed { i, action ->
                val cx = box.left + slot * (i + 0.5f)
                val cy = box.centerY()
                val path = PathParser.createPathFromPathData(
                    when (action) {
                        "save_state" -> Icons.SaveState
                        "load_state" -> Icons.LoadState
                        "turbo_fire_toggle" -> "M13 2L4 14h7l-1 8 9-12h-7z"
                        // Two screens, one over the other.
                        "screens" -> "M6 3h12a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1zM6 13h12a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1v-6a1 1 0 0 1 1-1z"
                        else -> "M3 6v12l9-6zM12 6v12l9-6z"
                    }
                )
                path.transform(Matrix().apply { setScale(size / 24f, size / 24f); postTranslate(cx - size / 2f, cy - size / 2f) })
                val off = hardcore && action == "toggle_fast_forward"
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    // At 0.45, like the pause sheet's disabled tiles.
                    color = if (off) (colors.icon and 0x00FFFFFF) or (0x73 shl 24) else colors.icon
                    if (action == "turbo_fire_toggle" || action == "screens") {
                        style = Paint.Style.STROKE; strokeWidth = 1.8f * size / 24f; strokeJoin = Paint.Join.ROUND
                    }
                }
                c.drawPath(path, paint)
                val area = RectF(box.left + slot * i, box.top, box.left + slot * (i + 1), box.bottom)
                if (off) return@forEachIndexed
                descs += if (action == "screens") Desc("overlay_next", area, next = layoutNext) else Desc(action, area)
            }
        }

        private fun label(text: String, left: Float, top: Float, w: Float, font: Float, spacing: Float, alpha: Int = 0xB3, color: Int = colors.ink) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = (color and 0x00FFFFFF) or (alpha shl 24); typeface = inter; textSize = s(font)
                textAlign = Paint.Align.CENTER; letterSpacing = spacing
            }
            // Long names shrink to fit a narrow side.
            val room = s(w) * 0.9f
            val measured = paint.measureText(text)
            if (measured > room) paint.textSize *= room / measured
            c.drawText(text, x(left + w / 2f), y(top) - paint.ascent(), paint)
        }

        private fun text(label: String, box: RectF, color: Int, size: Float, face: Typeface) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; typeface = face; textSize = size; textAlign = Paint.Align.CENTER
            }
            c.drawText(label, box.centerX(), box.centerY() - (paint.ascent() + paint.descent()) / 2f, paint)
        }

        private fun fill(box: RectF, color: Int) {
            c.drawRect(box, Paint().apply { this.color = color })
        }
    }
}
