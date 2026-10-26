package com.openemux.mobile.launch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import java.io.File
import java.util.Locale

/**
 * One touch area of an overlay, in pixels: [radial] marks an analog stick, whose rect is its range; [next] is the
 * overlay an overlay_next switches to; [lit] is a toggle's "on" image, drawn over its rect only while the engine has
 * that state on.
 */
internal class OverlayDesc(
    val name: String, val rect: RectF, val radial: Boolean = false, val next: String? = null, val lit: OverlayLit? = null,
)

/** The image of a desc lit by an engine state (overlayN_descM_lit_by: fast_forward or turbo). */
internal class OverlayLit(val state: String, val bitmap: Bitmap)

/** What [SkinRenderer] and [ImportedSkinRenderer] write the same way: the overlays of a skin.cfg and their images. */
internal object OverlayWriter {

    /** The app accent (Oe.Accent) and its light tone (Oe.AccentOnSoft, dark), for a toggle that is on. */
    const val ACCENT = 0xFF7C3AED.toInt()
    const val ACCENT_RING = 0xFFD4C4FF.toInt()

    /** The engine state each toggle hotkey lights its button with. */
    val LIT_BY = mapOf("turbo_fire_toggle" to "turbo", "toggle_fast_forward" to "fast_forward")

    /** A toggle's "on" pill, [w] x [h] pixels: the accent with its light inner ring of [ring] pixels. */
    fun pill(w: Int, h: Int, radius: Float, ring: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(maxOf(1, w), maxOf(1, h), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fw = bitmap.width.toFloat()
        val fh = bitmap.height.toFloat()
        canvas.drawRoundRect(RectF(0f, 0f, fw, fh), radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT })
        canvas.drawRoundRect(
            RectF(ring / 2f, ring / 2f, fw - ring / 2f, fh - ring / 2f), radius - ring / 2f, radius - ring / 2f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT_RING; style = Paint.Style.STROKE; strokeWidth = ring },
        )
        return bitmap
    }

    /** Writes the overlay's [bitmap] as [image] in [outDir], and its descs' lit images next to it. */
    fun saveImages(outDir: File, image: String, bitmap: Bitmap, descs: List<OverlayDesc>) {
        File(outDir, image).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        descs.forEach { d ->
            d.lit?.let { lit ->
                File(outDir, litImage(image, lit)).outputStream().use { lit.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }

    private fun litImage(image: String, lit: OverlayLit) = image.removeSuffix(".png") + "-lit-${lit.state}.png"

    /**
     * Overlay [index] of the cfg, [w] x [h] pixels, with the game in [picture]; [other] is the overlay of the other
     * orientation. Touch areas are [grow] times what is drawn, except a stick's range and a lit image, which RetroArch
     * stretches over its desc's range.
     */
    fun write(
        cfg: StringBuilder, index: Int, name: String, other: String, image: String,
        descs: List<OverlayDesc>, w: Int, h: Int, picture: RectF, grow: Float,
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
            val g = if (d.radial || d.lit != null) 1f else grow
            val hw = d.rect.width() / 2f * g
            val hh = d.rect.height() / 2f * g
            cfg.append(
                String.format(
                    Locale.US, "%s_desc%d = \"%s,%.5f,%.5f,%s,%.5f,%.5f\"\n",
                    p, i, d.name, d.rect.centerX() / w, d.rect.centerY() / h, if (d.radial) "radial" else "rect", hw / w, hh / h,
                )
            )
            if (d.radial) cfg.append("${p}_desc${i}_saturate_pct = 0.65\n")
            d.next?.let { cfg.append("${p}_desc${i}_next_target = \"$it\"\n") }
            d.lit?.let {
                cfg.append("${p}_desc${i}_overlay = \"${litImage(image, it)}\"\n")
                cfg.append("${p}_desc${i}_lit_by = \"${it.state}\"\n")
            }
        }
        // Unreachable switch that lets auto-rotate find the other orientation.
        val n = descs.size
        cfg.append("${p}_desc$n = \"overlay_next,0.5,0.5,rect,0.00001,0.00001\"\n")
        cfg.append("${p}_desc${n}_next_target = \"$other\"\n")
    }
}
