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
 * that state on. A stick with a [well] (the circle drawn around it) has the knob's travel for its rect, [knob] the
 * image the engine moves with the thumb and [saturate] where along the travel the value peaks.
 */
internal class OverlayDesc(
    val name: String, val rect: RectF, val radial: Boolean = false, val next: String? = null, val lit: OverlayLit? = null,
    val well: RectF? = null, val knob: Bitmap? = null, val saturate: Float = 1f,
)

/** The image of a desc lit by an engine state (overlayN_descM_lit_by: fast_forward or turbo). */
internal class OverlayLit(val state: String, val bitmap: Bitmap)

/** What [SkinRenderer] and [ImportedSkinRenderer] write the same way: the overlays of a skin.cfg and their images. */
internal object OverlayWriter {

    /** The app accent (Oe.Accent) and its light tone (Oe.AccentOnSoft, dark), for a toggle that is on. */
    const val ACCENT = 0xFF7C3AED.toInt()
    const val ACCENT_RING = 0xFFD4C4FF.toInt()

    /** How far a stick's knob travels from the well's centre, as a share of the well's radius. */
    const val KNOB_TRAVEL = 0.65f

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

    /**
     * Writes the overlay's [bitmap] as [image] in [outDir], and its descs' lit images and sticks' knobs next to it;
     * a knob is recycled once written.
     */
    fun saveImages(outDir: File, image: String, bitmap: Bitmap, descs: List<OverlayDesc>) {
        File(outDir, image).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        descs.forEachIndexed { i, d ->
            d.lit?.let { lit ->
                File(outDir, litImage(image, lit)).outputStream().use { lit.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            d.knob?.let { knob ->
                File(outDir, knobImage(image, i)).outputStream().use { knob.compress(Bitmap.CompressFormat.PNG, 100, it) }
                knob.recycle()
            }
        }
    }

    private fun litImage(image: String, lit: OverlayLit) = image.removeSuffix(".png") + "-lit-${lit.state}.png"

    /** The knob of the stick at desc [i]. */
    private fun knobImage(image: String, i: Int) = image.removeSuffix(".png") + "-knob$i.png"

    /**
     * Overlay [index] of the cfg, [w] x [h] pixels, with the game in [picture]; [other] is the overlay of the other
     * orientation. Touch areas are [grow] times what is drawn, except a stick's range and a lit image, which RetroArch
     * stretches over its desc's range. A stick's zone reaches past its travel up to the other controls and the [clear]
     * areas, [gap] short of them.
     */
    fun write(
        cfg: StringBuilder, index: Int, name: String, other: String, image: String,
        descs: List<OverlayDesc>, w: Int, h: Int, picture: RectF, grow: Float, clear: List<RectF>, gap: Float,
    ) {
        fun touch(d: OverlayDesc): RectF {
            val g = if (d.radial || d.lit != null) 1f else grow
            val hw = d.rect.width() / 2f * g
            val hh = d.rect.height() / 2f * g
            return RectF(d.rect.centerX() - hw, d.rect.centerY() - hh, d.rect.centerX() + hw, d.rect.centerY() + hh)
        }
        // What a stick's touch zone must stay clear of: every other control, as touched, and the [clear] areas.
        val taken = descs.map { d -> d.well ?: touch(d) } + clear
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
            val area = touch(d)
            cfg.append(
                String.format(
                    Locale.US, "%s_desc%d = \"%s,%.5f,%.5f,%s,%.5f,%.5f\"\n",
                    p, i, d.name, area.centerX() / w, area.centerY() / h, if (d.radial) "radial" else "rect",
                    area.width() / 2f / w, area.height() / 2f / h,
                )
            )
            when {
                d.well != null -> stick(cfg, p, i, d, image, taken.filterIndexed { j, _ -> j != i }, gap)
                // A stick with no knob image (a package that has none), over its whole frame.
                d.radial -> cfg.append("${p}_desc${i}_saturate_pct = 0.65\n")
            }
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

    /**
     * Stick [d] at desc [i], whose rect is its knob's travel: the value saturates where the knob stops (or sooner, at
     * its [OverlayDesc.saturate]), its knob is the image the engine moves with the thumb, and the touch zone reaches
     * past the travel up to the [taken] areas, [gap] short of them.
     */
    private fun stick(cfg: StringBuilder, p: String, i: Int, d: OverlayDesc, image: String, taken: List<RectF>, gap: Float) {
        val pct = if (d.saturate == 1f) "1.0" else String.format(Locale.US, "%.3f", d.saturate)
        cfg.append("${p}_desc${i}_saturate_pct = $pct\n")
        if (d.knob != null) {
            cfg.append("${p}_desc${i}_overlay = \"${knobImage(image, i)}\"\n")
            cfg.append("${p}_desc${i}_movable = true\n")
        }
        val reach = stickReach(d.rect, taken, gap)
        listOf("up", "down", "left", "right").forEachIndexed { k, dir ->
            cfg.append(String.format(Locale.US, "%s_desc%d_reach_%s = %.3f\n", p, i, dir, reach[k]))
        }
        // Once held, the zone doubles and wins over the controls around it: drifting past the rim keeps steering.
        cfg.append("${p}_desc${i}_range_mod = 2.0\n")
        cfg.append("${p}_desc${i}_range_mod_exclusive = true\n")
    }

    /**
     * How far past its [travel] square a stick's touch zone reaches up, down, left and right, as the engine's
     * `reach_*` multiples of the range: 2 where it is free, short of [gap] before each of the [taken] areas,
     * never under 1. The engine's radial hitbox is the ellipse inscribed in that box, so each axis keeps the
     * same reach on both sides: an off-centre ellipse would leave part of the travel circle untouchable.
     * The screen's edge needs no pulling back, a touch never lands past it.
     */
    private fun stickReach(travel: RectF, taken: List<RectF>, gap: Float): FloatArray {
        val cx = travel.centerX()
        val cy = travel.centerY()
        val range = travel.width() / 2f
        // Vertical, horizontal.
        val reach = floatArrayOf(2f, 2f)
        for (r in taken) {
            val zone = RectF(cx - range * reach[1], cy - range * reach[0], cx + range * reach[1], cy + range * reach[0])
            if (!RectF.intersects(RectF(zone).apply { inset(-gap, -gap) }, r)) continue
            // Pull back the axis the area lies on; of two (a corner), the one that loses the least.
            val vertical = when {
                r.bottom <= travel.top -> (cy - r.bottom - gap) / range
                r.top >= travel.bottom -> (r.top - gap - cy) / range
                else -> -1f
            }
            val horizontal = when {
                r.right <= travel.left -> (cx - r.right - gap) / range
                r.left >= travel.right -> (r.left - gap - cx) / range
                else -> -1f
            }
            // An area over the travel itself (a neighbour's extended edges): neither axis clears it, keep both short.
            if (vertical < 0f && horizontal < 0f) {
                reach[0] = 1f
                reach[1] = 1f
                continue
            }
            val axis = if (vertical >= horizontal) 0 else 1
            val pulled = maxOf(vertical, horizontal)
            reach[axis] = minOf(reach[axis], pulled).coerceAtLeast(1f)
        }
        // Up, down, left, right.
        return floatArrayOf(reach[0], reach[0], reach[1], reach[1])
    }
}
