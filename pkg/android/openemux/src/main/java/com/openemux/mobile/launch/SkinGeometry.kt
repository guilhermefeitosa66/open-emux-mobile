package com.openemux.mobile.launch

import com.openemux.mobile.ui.FOLD_MARGIN_DP
import com.openemux.mobile.ui.Fold
import com.openemux.mobile.ui.MIN_TABLETOP_HALF_DP
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

/** The forbidden band around a fold across the skin's long side: a y range of its portrait frame, in pixels. */
data class TabletopBand(val startPx: Float, val endPx: Float)

/** The two NDS screens one per half: the frame's [scale] (units per core pixel), the core's screen [gap] and the box it fills. */
data class NdsFit(val scale: Float, val gap: Int, val viewport: Box)

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

    /** The fold's margin each side, in dp: the forbidden band of #560. */
    const val FOLD_MARGIN = FOLD_MARGIN_DP

    /** A button's touch area is this many times what is drawn, about its centre (a stick's range and a lit image excepted). */
    const val TOUCH_GROW = 1.12f

    /** Tabletop: the bottom half is wide enough for L | strip | R on one row from this width, in dp. */
    const val TABLETOP_WIDE = 600f

    /** Tabletop: the least room for the clusters between the top rows and the pills; under it they shrink. */
    const val TABLETOP_CONTROLS = 200f

    /**
     * The forbidden band of a [fold] seen in a [widthPx] x [heightPx] window, as a y range of the skin's portrait frame
     * (the window's long side, less the [cutoutPx] at its start): only a fold across that long side has one, whatever
     * way up the window was when it was seen. Null without a fold, or when either half would be under
     * [MIN_TABLETOP_HALF_DP].
     */
    fun tabletopBandFor(fold: Fold?, widthPx: Int, heightPx: Int, density: Float, cutoutPx: Int = 0): TabletopBand? {
        if (fold == null) return null
        val landscape = widthPx >= heightPx
        // The hinge runs across the long side: up and down in a landscape window, from side to side in a portrait one.
        if (fold.vertical != landscape) return null
        val long = (maxOf(widthPx, heightPx) - cutoutPx).toFloat()
        val margin = FOLD_MARGIN * density
        val (start, end) = if (landscape) fold.leftPx to fold.rightPx else fold.topPx to fold.bottomPx
        val band = TabletopBand((start - cutoutPx - margin).coerceIn(0f, long), (end - cutoutPx + margin).coerceIn(0f, long))
        val half = MIN_TABLETOP_HALF_DP * density
        return if (band.startPx < half || long - band.endPx < half) null else band
    }

    /**
     * [fold] as seen in a [widthPx] x [heightPx] window, in the frame a skin is drawn in (SkinSize: short side across,
     * long side down): a landscape window's fold is turned with it, as [tabletopBandFor] reads a landscape window.
     */
    fun portraitFold(fold: Fold?, widthPx: Int, heightPx: Int): Fold? =
        if (fold == null || widthPx <= heightPx) fold
        else Fold(!fold.vertical, fold.halfOpened, fold.topPx, fold.leftPx, fold.bottomPx, fold.rightPx)

    /** Whether [b] crosses the band from [start] to [end] (design units). */
    fun intersectsBand(b: Box, start: Float, end: Float) = b.y < end && b.bottom > start

    /**
     * Tabletop [w] x [h], the band from [start] to [end] (DobFoldJogo, DobFlipJogo): the picture in the top half, the
     * rest in the bottom one. [wide] (DobFoldJogo) puts L | strip | R on one row, the menu key under the strip and the
     * name at the foot; otherwise (DobFlipJogo) the strip goes across, then L | menu | R, and the name under the picture.
     * The clusters shrink when the bottom half is short, never past full size.
     */
    fun tabletop(w: Float, h: Float, start: Float, end: Float, aspect: Float, wide: Boolean): TabletFrame {
        val nameRoom = if (wide) 0f else 24f
        val gameH = minOf((w - 32f) / aspect, start - 28f - nameRoom)
        val gameW = gameH * aspect
        val gameY = (start - nameRoom - gameH) / 2f
        val picture = Box((w - gameW) / 2f, gameY, gameW, gameH)
        val top = end + 12f
        val shoulder: Float
        val strip: Box
        val menu: Box
        val rowsTop: Float
        if (wide) {
            shoulder = 66f
            strip = Box(29f + shoulder, top, w - 2f * (29f + shoulder), 37f)
            menu = Box(w / 2f - 23f, strip.bottom + 8f, 46f, 31f)
            rowsTop = strip.bottom + 8f
        } else {
            shoulder = minOf(102f, w / 2f - 50f)
            strip = Box(11f, top, w - 22f, 45f)
            menu = Box(w / 2f - 27f, strip.bottom + 10f, 54f, 40f)
            rowsTop = menu.bottom + 8f
        }
        val shoulderY = if (wide) top else menu.y
        val shoulderH = if (wide) 37f else 40f
        val pillsTop = h - if (wide) 66f else 50f
        val m0 = rowsTop
        val m1 = pillsTop - 8f
        val k = minOf(1f, (m1 - m0) / TABLETOP_CONTROLS)
        val center = (m0 + m1) / 2f
        val span = TABLETOP_CONTROLS * k
        return TabletFrame(
            picture = picture,
            strip = strip,
            shouldersLeft = Box(17f, shoulderY, shoulder, shoulderH),
            shouldersRight = Box(w - 17f - shoulder, shoulderY, shoulder, shoulderH),
            menu = menu,
            name = if (wide) Box(0f, h - 22f, w, 11f) else Box(0f, picture.bottom + 8f, w, 11f),
            lx = 110f,
            rx = w - 110f,
            rows = Rows(center - span * 0.24f, center, center + span * 0.26f),
            k = k,
            pillsTop = pillsTop,
            layoutKey = null,
        )
    }

    /**
     * Tabletop NDS [w] x [h] (DobFoldNDS): the bottom half keeps a column in the middle for the bottom screen, the strip
     * under it (its fifth slot the layout key); L and the menu key at the top left, R and the name under it at the top right, the
     * d-pad and the face buttons down the sides and the pills in the bottom corners. The [TabletFrame.picture] is that
     * column; where the screens go is [ndsHalvesFit]'s, or [fitIn]'s for a single one.
     */
    fun tabletopNds(w: Float, h: Float, end: Float, footprint: Float): TabletFrame {
        val side = minOf(maxOf(150f, footprint + 24f), w * 0.3f)
        val k = minOf(1f, (side - 24f) / footprint, (h - end - 150f) / TABLETOP_CONTROLS)
        val column = Box(side, end + 8f, w - 2f * side, h - end - STRIP - 24f)
        // L and the menu key share the left column's top row.
        val left = side - 24f - 54f
        val center = (end + 60f + h - 44f) / 2f
        // R grows towards the column when touched: far enough from it that its touch area stops short of the bottom screen.
        val rGap = maxOf(12f, (side - 12f) * (TOUCH_GROW - 1f) / 2f / (1f + (TOUCH_GROW - 1f) / 2f))
        return TabletFrame(
            picture = column,
            strip = Box(column.x, column.bottom, column.w, STRIP),
            shouldersLeft = Box(12f, end + 12f, left, 34f),
            shouldersRight = Box(w - side + rGap, end + 12f, side - 12f - rGap, 34f),
            menu = Box(20f + left, end + 13.5f, 46f, 31f),
            name = Box(w - side, end + 52f, side, 10f),
            lx = side / 2f,
            rx = w - side / 2f,
            rows = Rows(center - 50f * k, center, center + 55f * k),
            k = k,
            pillsTop = h - 44f,
            layoutKey = null,
        )
    }

    /** The biggest box of [aspect] inside [room] less [margin] each side, centred in it. */
    fun fitIn(room: Box, aspect: Float, margin: Float = 0f): Box {
        val rw = room.w - 2f * margin
        val rh = room.h - 2f * margin
        val bw = minOf(rw, rh * aspect)
        val bh = bw / aspect
        return Box(room.x + (room.w - bw) / 2f, room.y + (room.h - bh) / 2f, bw, bh)
    }

    /**
     * The NDS screens one per half: DeSmuME's stacked layout with a [gap] (in core pixels, one of [gaps]) that covers the
     * band from [start] to [end], the top screen ending at the band and the bottom one fitting [column]. The biggest
     * scale that fits, stepped down until a gap works; null when none does or the core offers no gap.
     */
    fun ndsHalvesFit(start: Float, end: Float, column: Box, gaps: List<Int>): NdsFit? {
        if (gaps.isEmpty()) return null
        val sorted = gaps.sorted()
        var scale = minOf(column.w / 256f, start / 192f, (column.bottom - end) / 192f)
        repeat(24) {
            if (scale <= 0f) return null
            val gap = sorted.firstOrNull { it * scale >= end - start && start + (it + 192) * scale <= column.bottom }
            if (gap != null) {
                return NdsFit(scale, gap, Box(column.cx - 128f * scale, start - 192f * scale, 256f * scale, (384f + gap) * scale))
            }
            scale *= 0.95f
        }
        return null
    }
}
