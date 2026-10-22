package com.openemux.mobile.launch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.openemux.mobile.data.Console
import com.retroarch.R
import java.io.File
import java.util.Locale

/**
 * Draws the controller skin of the design at the screen's exact size and
 * writes it as a RetroArch overlay: one image per orientation plus the
 * touch areas, each bound to a RetroPad button or a hotkey.
 */
class SkinRenderer(private val context: Context, private val accent: Int = 0xFF7C3AED.toInt()) {

    private class Desc(val name: String, val rect: RectF)

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val bold: Typeface =
        ResourcesCompat.getFont(context, R.font.inter_bold) ?: Typeface.DEFAULT_BOLD
    private val grotesk: Typeface =
        ResourcesCompat.getFont(context, R.font.grotesk_bold) ?: Typeface.DEFAULT_BOLD

    private val buttonFill = 0xFF1F1F26.toInt()
    private val glyph = 0xFF6E6E7C.toInt()
    private val labelGray = 0xFF8C8C9A.toInt()

    fun render(console: Console, cart: Bitmap?, width: Int, height: Int, outDir: File, topInset: Int = 0): File {
        outDir.mkdirs()
        val portraitW = minOf(width, height)
        val portraitH = maxOf(width, height)

        val portrait = Bitmap.createBitmap(portraitW, portraitH, Bitmap.Config.ARGB_8888)
        val portraitDescs = drawPortrait(Canvas(portrait), console, cart, portraitW, portraitH, topInset)
        File(outDir, "portrait.png").outputStream().use { portrait.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val landscape = Bitmap.createBitmap(portraitH, portraitW, Bitmap.Config.ARGB_8888)
        val landscapeDescs = drawLandscape(Canvas(landscape), console, portraitH, portraitW)
        File(outDir, "landscape.png").outputStream().use { landscape.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val cfg = StringBuilder()
        cfg.append("overlays = 2\n")
        writeOverlay(cfg, 0, "portrait", "landscape", "portrait.png", portraitDescs, portraitW, portraitH)
        writeOverlay(cfg, 1, "landscape", "portrait", "landscape.png", landscapeDescs, portraitH, portraitW)
        val file = File(outDir, "skin.cfg")
        file.writeText(cfg.toString())
        return file
    }

    private fun writeOverlay(
        cfg: StringBuilder, index: Int, name: String, other: String, image: String,
        descs: List<Desc>, w: Int, h: Int,
    ) {
        val p = "overlay$index"
        cfg.append("${p}_name = \"$name\"\n")
        cfg.append("${p}_overlay = \"$image\"\n")
        cfg.append("${p}_full_screen = true\n")
        cfg.append("${p}_normalized = true\n")
        cfg.append("${p}_range_mod = 1.0\n")
        cfg.append("${p}_alpha_mod = 1.0\n")
        cfg.append("${p}_descs = ${descs.size + 1}\n")
        descs.forEachIndexed { i, d ->
            // Touch areas a little larger than what is drawn: thumbs are wide.
            val hw = d.rect.width() / 2f * 1.12f
            val hh = d.rect.height() / 2f * 1.12f
            cfg.append(
                String.format(
                    Locale.US, "%s_desc%d = \"%s,%.5f,%.5f,rect,%.5f,%.5f\"\n",
                    p, i, d.name, d.rect.centerX() / w, d.rect.centerY() / h, hw / w, hh / h,
                )
            )
        }
        // Unreachable switch that lets auto-rotate find the other orientation.
        val n = descs.size
        cfg.append("${p}_desc$n = \"overlay_next,0.5,0.5,rect,0.00001,0.00001\"\n")
        cfg.append("${p}_desc${n}_next_target = \"$other\"\n")
    }

    private fun drawPortrait(c: Canvas, console: Console, cart: Bitmap?, w: Int, h: Int, topInset: Int): List<Desc> {
        val descs = mutableListOf<Desc>()
        // The engine keeps the picture clear of the camera cutout.
        val gameH = topInset + w / console.gameAspect
        val band = dp(76f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = 0xFF0B0B0E.toInt()
        c.drawRect(0f, gameH, w.toFloat(), h.toFloat(), paint)

        // The cartridge sitting in its slot, only its top showing.
        if (cart != null) {
            val cartW = dp(150f)
            val cartH = cartW * cart.height / cart.width
            val left = (w - cartW) / 2f
            c.save()
            c.clipRect(0f, gameH, w.toFloat(), gameH + band - dp(8f))
            c.drawBitmap(cart, null, RectF(left, gameH + dp(10f), left + cartW, gameH + dp(10f) + cartH), Paint(Paint.FILTER_BITMAP_FLAG))
            c.restore()
        }
        val slot = RectF(w / 2f - dp(90f), gameH + band - dp(22f), w / 2f + dp(90f), gameH + band - dp(2f))
        paint.shader = LinearGradient(0f, slot.top, 0f, slot.bottom, 0xFF22222A.toInt(), 0xFF0B0B0E.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(slot, dp(10f), dp(10f), paint)
        paint.shader = null

        val sx = w / dp(390f)
        val top = gameH + band
        val avail = h - top
        val designH = dp(420f) * sx
        val vy = minOf(1f, avail / designH)
        val offset = if (avail > designH) (avail - designH) * 0.35f else 0f
        fun r(x: Float, y: Float, bw: Float, bh: Float) =
            RectF(dp(x) * sx, top + offset + dp(y) * sx * vy, dp(x + bw) * sx, top + offset + dp(y) * sx * vy + dp(bh) * sx)

        shoulder(c, r(16f, 10f, 104f, 40f), "L", left = true); descs += Desc("l", r(16f, 10f, 104f, 40f))
        shoulder(c, r(270f, 10f, 104f, 40f), "R", left = false); descs += Desc("r", r(270f, 10f, 104f, 40f))
        menuButton(c, r(167f, 8f, 56f, 44f)); descs += Desc("menu_toggle", r(167f, 8f, 56f, 44f))

        descs += dpad(c, r(28f, 92f, 150f, 150f))
        descs += faceButtons(c, console, sx, { x, y -> PointF2(dp(x) * sx, top + offset + dp(y) * sx * vy) }, 76f)

        pill(c, r(101f, 290f, 64f, 26f), null); descs += Desc("select", r(101f, 290f, 64f, 26f))
        pill(c, r(225f, 290f, 64f, 26f), null); descs += Desc("start", r(225f, 290f, 64f, 26f))
        smallLabel(c, "SELECT", r(101f, 322f, 64f, 14f))
        smallLabel(c, "START", r(225f, 322f, 64f, 14f))

        outlinedPill(c, r(109f, 360f, 82f, 36f), "2×", fastForward = true)
        descs += Desc("toggle_fast_forward", r(109f, 360f, 82f, 36f))
        outlinedPill(c, r(199f, 360f, 82f, 36f), "Salvar", fastForward = false)
        descs += Desc("save_state", r(199f, 360f, 82f, 36f))
        return descs
    }

    private fun drawLandscape(c: Canvas, console: Console, w: Int, h: Int): List<Desc> {
        val descs = mutableListOf<Desc>()
        val s = minOf(1f, h / dp(390f))
        fun r(x: Float, y: Float, bw: Float, bh: Float) = RectF(x, y, x + dp(bw) * s, y + dp(bh) * s)
        val alpha = 0xD0

        val l = r(0f, 0f, 120f, 52f)
        shoulder(c, l, "L", left = true, alpha = alpha); descs += Desc("l", l)
        val rr = r(w - dp(120f) * s, 0f, 120f, 52f)
        shoulder(c, rr, "R", left = false, alpha = alpha); descs += Desc("r", rr)
        val menu = r(w / 2f - dp(28f) * s, dp(10f) * s, 56f, 36f)
        menuButton(c, menu, alpha); descs += Desc("menu_toggle", menu)
        val ff = r(menu.left - dp(86f) * s, dp(10f) * s, 76f, 36f)
        outlinedPill(c, ff, "2×", fastForward = true, filled = true); descs += Desc("toggle_fast_forward", ff)
        val save = r(menu.right + dp(10f) * s, dp(10f) * s, 76f, 36f)
        outlinedPill(c, save, "Salvar", fastForward = false, filled = true); descs += Desc("save_state", save)

        descs += dpad(c, r(dp(26f) * s, h * 0.40f, 144f, 144f), alpha)
        val cx = w - dp(108f) * s
        val cy = h * 0.40f + dp(72f) * s
        descs += faceButtons(c, console, s, { x, y -> PointF2(cx + dp(x - 282f) * s, cy + dp(y - 167f) * s) }, 70f, alpha)

        val sel = r(dp(150f) * s, h - dp(44f) * s, 72f, 30f)
        pill(c, sel, "SELECT", alpha); descs += Desc("select", sel)
        val st = r(w - dp(150f + 72f) * s, h - dp(44f) * s, 72f, 30f)
        pill(c, st, "START", alpha); descs += Desc("start", st)
        return descs
    }

    private data class PointF2(val x: Float, val y: Float)

    /** Face buttons laid out in the 390-wide design, mapped by [at]. */
    private fun faceButtons(
        c: Canvas, console: Console, scale: Float, at: (Float, Float) -> PointF2, size: Float, alpha: Int = 0xFF,
    ): List<Desc> {
        val out = mutableListOf<Desc>()
        val d = dp(if (console.faceButtons == 4) size * 0.82f else size) * scale
        val buttons = if (console.faceButtons == 4) listOf(
            Triple("x", "X", at(282f, 111f)) to lighten(accent),
            Triple("a", "A", at(338f, 167f)) to accent,
            Triple("b", "B", at(282f, 223f)) to darken(accent),
            Triple("y", "Y", at(226f, 167f)) to darken(darken(accent)),
        ) else listOf(
            Triple("a", "A", at(324f, 136f)) to accent,
            Triple("b", "B", at(242f, 196f)) to darken(accent),
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for ((t, color) in buttons) {
            val (name, label, p) = t
            paint.color = withAlpha(color, alpha)
            paint.setShadowLayer(dp(6f), 0f, dp(4f), 0x80000000.toInt())
            c.drawCircle(p.x, p.y, d / 2f, paint)
            paint.clearShadowLayer()
            paint.color = 0xFFFFFFFF.toInt()
            paint.typeface = grotesk
            paint.textSize = d * 0.3f
            paint.textAlign = Paint.Align.CENTER
            c.drawText(label, p.x, p.y - (paint.ascent() + paint.descent()) / 2f, paint)
            out += Desc(name, RectF(p.x - d / 2f, p.y - d / 2f, p.x + d / 2f, p.y + d / 2f))
        }
        return out
    }

    private fun dpad(c: Canvas, box: RectF, alpha: Int = 0xFF): List<Desc> {
        val cell = box.width() / 3f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(buttonFill, alpha) }
        val radius = cell * 0.2f
        val vertical = RectF(box.left + cell, box.top, box.left + 2 * cell, box.bottom)
        val horizontal = RectF(box.left, box.top + cell, box.right, box.top + 2 * cell)
        c.drawRoundRect(vertical, radius, radius, paint)
        c.drawRoundRect(horizontal, radius, radius, paint)
        paint.color = 0xFF17171C.toInt()
        c.drawCircle(box.centerX(), box.centerY(), cell * 0.18f, paint)
        paint.color = glyph
        fun tri(cx: Float, cy: Float, dir: Int) {
            val s = cell * 0.16f
            val path = Path()
            when (dir) {
                0 -> { path.moveTo(cx, cy - s); path.lineTo(cx + s, cy + s * 0.7f); path.lineTo(cx - s, cy + s * 0.7f) }
                1 -> { path.moveTo(cx, cy + s); path.lineTo(cx + s, cy - s * 0.7f); path.lineTo(cx - s, cy - s * 0.7f) }
                2 -> { path.moveTo(cx - s, cy); path.lineTo(cx + s * 0.7f, cy - s); path.lineTo(cx + s * 0.7f, cy + s) }
                else -> { path.moveTo(cx + s, cy); path.lineTo(cx - s * 0.7f, cy - s); path.lineTo(cx - s * 0.7f, cy + s) }
            }
            path.close()
            c.drawPath(path, paint)
        }
        tri(box.centerX(), box.top + cell / 2f, 0)
        tri(box.centerX(), box.bottom - cell / 2f, 1)
        tri(box.left + cell / 2f, box.centerY(), 2)
        tri(box.right - cell / 2f, box.centerY(), 3)

        fun cellRect(col: Int, row: Int) =
            RectF(box.left + col * cell, box.top + row * cell, box.left + (col + 1) * cell, box.top + (row + 1) * cell)
        return listOf(
            Desc("up", cellRect(1, 0)), Desc("down", cellRect(1, 2)),
            Desc("left", cellRect(0, 1)), Desc("right", cellRect(2, 1)),
            Desc("up|left", cellRect(0, 0)), Desc("up|right", cellRect(2, 0)),
            Desc("down|left", cellRect(0, 2)), Desc("down|right", cellRect(2, 2)),
        )
    }

    private fun shoulder(c: Canvas, rect: RectF, label: String, left: Boolean, alpha: Int = 0xFF) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(buttonFill, alpha) }
        val small = rect.height() * 0.3f
        val big = rect.height() * 0.5f
        val radii = if (left) floatArrayOf(small, small, big, big, small, small, small, small)
        else floatArrayOf(big, big, small, small, small, small, small, small)
        val path = Path().apply { addRoundRect(rect, radii, Path.Direction.CW) }
        c.drawPath(path, paint)
        text(c, label, rect, 0xFFA3A3B2.toInt(), rect.height() * 0.38f)
    }

    private fun menuButton(c: Canvas, rect: RectF, alpha: Int = 0xFF) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(buttonFill, alpha) }
        c.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, paint)
        // The cartridge glyph from the design.
        val s = rect.height() * 0.42f / 24f
        val ox = rect.centerX() - 12f * s
        val oy = rect.centerY() - 12f * s
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f * s; color = 0xFFF3F3F6.toInt(); strokeJoin = Paint.Join.ROUND
        }
        val outer = Path().apply {
            moveTo(ox + 4 * s, oy + 5 * s); lineTo(ox + 20 * s, oy + 5 * s); lineTo(ox + 20 * s, oy + 16 * s)
            lineTo(ox + 18 * s, oy + 19 * s); lineTo(ox + 6 * s, oy + 19 * s); lineTo(ox + 4 * s, oy + 16 * s); close()
        }
        c.drawPath(outer, stroke)
        c.drawRect(ox + 8 * s, oy + 8 * s, ox + 16 * s, oy + 13 * s, stroke)
    }

    private fun pill(c: Canvas, rect: RectF, label: String?, alpha: Int = 0xFF) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(buttonFill, alpha) }
        c.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, paint)
        if (label != null) text(c, label, rect, 0xFFC9C9D3.toInt(), rect.height() * 0.36f)
    }

    private fun outlinedPill(c: Canvas, rect: RectF, label: String, fastForward: Boolean, filled: Boolean = false) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (filled) {
            paint.color = 0xB314141A.toInt()
            c.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = 0xFF2A2A33.toInt()
        c.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, paint)
        paint.style = Paint.Style.FILL
        paint.color = 0xFFA3A3B2.toInt()
        paint.typeface = bold
        paint.textSize = rect.height() * 0.34f
        val iconW = rect.height() * 0.42f
        val gap = rect.height() * 0.14f
        val textW = paint.measureText(label)
        val startX = rect.centerX() - (iconW + gap + textW) / 2f
        val cy = rect.centerY()
        val icon = Path()
        if (fastForward) {
            val hw = iconW / 2f
            icon.moveTo(startX, cy - hw * 0.8f); icon.lineTo(startX + hw, cy); icon.lineTo(startX, cy + hw * 0.8f); icon.close()
            icon.moveTo(startX + hw, cy - hw * 0.8f); icon.lineTo(startX + 2 * hw, cy); icon.lineTo(startX + hw, cy + hw * 0.8f); icon.close()
            c.drawPath(icon, paint)
        } else {
            val st = Paint(paint).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.6f); strokeJoin = Paint.Join.ROUND }
            val b = RectF(startX, cy - iconW / 2f, startX + iconW, cy + iconW / 2f)
            c.drawRoundRect(b, iconW * 0.12f, iconW * 0.12f, st)
            c.drawRect(b.left + iconW * 0.25f, b.top, b.right - iconW * 0.25f, b.top + iconW * 0.35f, st)
        }
        paint.textAlign = Paint.Align.LEFT
        c.drawText(label, startX + iconW + gap, cy - (paint.ascent() + paint.descent()) / 2f, paint)
    }

    private fun smallLabel(c: Canvas, label: String, rect: RectF) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelGray; typeface = bold; textSize = rect.height() * 0.8f
            textAlign = Paint.Align.CENTER; letterSpacing = 0.08f
        }
        c.drawText(label, rect.centerX(), rect.centerY() - (paint.ascent() + paint.descent()) / 2f, paint)
    }

    private fun text(c: Canvas, label: String, rect: RectF, color: Int, size: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; typeface = bold; textSize = size; textAlign = Paint.Align.CENTER; letterSpacing = 0.06f
        }
        c.drawText(label, rect.centerX(), rect.centerY() - (paint.ascent() + paint.descent()) / 2f, paint)
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

    private fun darken(color: Int): Int {
        val r = ((color shr 16) and 0xFF) * 62 / 100 + 0x12 * 38 / 100
        val g = ((color shr 8) and 0xFF) * 62 / 100 + 0x12 * 38 / 100
        val b = (color and 0xFF) * 62 / 100 + 0x16 * 38 / 100
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun lighten(color: Int): Int {
        val r = ((color shr 16) and 0xFF) + (255 - ((color shr 16) and 0xFF)) * 25 / 100
        val g = ((color shr 8) and 0xFF) + (255 - ((color shr 8) and 0xFF)) * 25 / 100
        val b = (color and 0xFF) + (255 - (color and 0xFF)) * 25 / 100
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
