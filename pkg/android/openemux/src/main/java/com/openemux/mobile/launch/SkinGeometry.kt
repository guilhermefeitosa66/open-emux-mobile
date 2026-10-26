package com.openemux.mobile.launch

import com.openemux.mobile.ui.WindowLayout

// Where the tablet skin puts things, as plain numbers in the skin's design
// units (SkinRenderer draws [SkinGeometry.unit] pixels per unit): no Android
// types here, so the placement runs as JVM unit tests. The phone shapes keep
// their own maths in SkinRenderer; only the unit comes from here.

/** A box in design units. */
data class Box(val x: Float, val y: Float, val w: Float, val h: Float) {
    val right get() = x + w
    val bottom get() = y + h
    val cx get() = x + w / 2f

    fun intersects(o: Box) = x < o.right && o.x < right && y < o.bottom && o.y < bottom

    fun inside(width: Float, height: Float) = x >= 0f && y >= 0f && right <= width && bottom <= height
}

/** The control rows' centres, for SkinRow.UPPER, CENTER and LOWER. */
data class Rows(val upper: Float, val center: Float, val lower: Float)

/**
 * A tablet orientation: the picture and the strip under it, the shoulder rows, the menu key, the console name's top
 * left corner and width, the left / right cluster centres [lx] / [rx] on [rows], the scale [k] of the clusters, the
 * row of pills at [pillsTop], and the NDS layout key above the picture ([layoutKey], landscape only).
 */
data class TabletFrame(
    val picture: Box,
    val strip: Box,
    val shouldersLeft: Box,
    val shouldersRight: Box,
    val menu: Box,
    val name: Box,
    val lx: Float,
    val rx: Float,
    val rows: Rows,
    val k: Float,
    val pillsTop: Float,
    val layoutKey: Box?,
)

object SkinGeometry {
    /** The phone design's frame: 390 units across the short side. */
    const val FRAME = 390f

    /** The most a tablet's design unit may grow to, in dp: buttons stop growing with the screen. */
    const val TABLET_CAP = 1.4f

    /** The strip's height on a tablet. */
    const val STRIP = 40f

    /** Landscape: the narrowest side column; the picture takes the rest. */
    const val SIDE = 166f

    /** Landscape: the band over the picture, for the shoulders, the menu key and the NDS layout key. */
    const val TOP = 66f

    /** Landscape: where the upper and lower rows sit from the centre row, at full size. */
    const val UPPER_ROW = -60f
    const val LOWER_ROW = 70f

    /** Landscape: the least room between the lowest cluster and the Select / Start pills. */
    const val PILL_GAP = 16f

    /** Portrait: the least room left under the strip for the controls. */
    const val PANEL = 360f

    /** A window whose smallest width is 600dp or more draws the tablet skin, whichever way it is held. */
    fun tablet(widthPx: Int, heightPx: Int, density: Float) = WindowLayout.of(widthPx / density, heightPx / density).tablet

    /** Pixels per design unit: the phone frame across the short side, capped at [TABLET_CAP] dp on a tablet. */
    fun unit(minSidePx: Int, density: Float, tablet: Boolean): Float =
        if (tablet) minOf(minSidePx / FRAME, TABLET_CAP * density) else minSidePx / FRAME

    /**
     * Landscape [w] x [h]: the picture centred between two side columns wide enough for the widest cluster
     * ([footprint] units at full size), the strip under it. [depth] is how far the lowest cluster reaches below the
     * centre row at full size: the pills go under it, and the clusters shrink when the window is too short for both.
     * [layoutKey] reserves the NDS layout key over the picture.
     */
    fun landscape(w: Float, h: Float, aspect: Float, footprint: Float, depth: Float, layoutKey: Boolean): TabletFrame {
        val side = minOf(maxOf(SIDE, footprint + 24f), w * 0.3f)
        val center = h / 2f + 16f
        val lowest = h - 52f
        val k = minOf(1f, (side - 24f) / footprint, (h - 200f) / 260f, (lowest - PILL_GAP - center) / maxOf(depth, 1f))
        val room = h - TOP - STRIP - 16f
        val gameW = minOf(w - 2f * side, room * aspect)
        val gameH = gameW / aspect
        val picture = Box((w - gameW) / 2f, TOP + (room - gameH) / 2f, gameW, gameH)
        return TabletFrame(
            picture = picture,
            strip = Box(picture.x, picture.bottom, gameW, STRIP),
            shouldersLeft = Box(12f, 16f, side - 24f, 36f),
            shouldersRight = Box(w - side + 12f, 16f, side - 24f, 36f),
            menu = Box(side / 2f - 23f, 62f, 46f, 31f),
            name = Box(w - side, 70f, side, 10f),
            lx = side / 2f,
            rx = w - side / 2f,
            rows = Rows(center + UPPER_ROW * k, center, center + LOWER_ROW * k),
            k = k,
            pillsTop = minOf(lowest, maxOf(center + 130f, center + depth * k + PILL_GAP)),
            layoutKey = if (layoutKey) Box(picture.cx - 70f, picture.y - 39f, 140f, 31f) else null,
        )
    }

    /** Portrait [w] x [h]: the picture across the top, the strip under it, the controls in the room left below. */
    fun portrait(w: Float, h: Float, aspect: Float): TabletFrame {
        val gameH = minOf(w / aspect, h - STRIP - PANEL)
        val gameW = gameH * aspect
        val top = gameH + STRIP
        val shoulder = minOf(171f, w / 2f - 60f)
        val m0 = top + 70f
        val m1 = h - 110f
        return TabletFrame(
            picture = Box((w - gameW) / 2f, 0f, gameW, gameH),
            strip = Box(0f, gameH, w, STRIP),
            shouldersLeft = Box(17f, top + 16f, shoulder, 37f),
            shouldersRight = Box(w - 17f - shoulder, top + 16f, shoulder, 37f),
            menu = Box(w / 2f - 23f, top + 19f, 46f, 31f),
            name = Box(0f, h - 38f, w, 11f),
            lx = 110f,
            rx = w - 110f,
            rows = Rows(m0 + (m1 - m0) * 0.27f, (m0 + m1) / 2f, m0 + (m1 - m0) * 0.75f),
            k = minOf(1f, (m1 - m0) / 240f),
            pillsTop = h - 100f,
            layoutKey = null,
        )
    }
}
