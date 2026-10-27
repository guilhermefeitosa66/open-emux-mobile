package com.openemux.mobile.ui

// The window every layout decision reads, as plain values: no Android or
// Compose types here, so the decisions run as JVM unit tests. The window is
// measured once per window by ProvideWindowLayout and reaches the screens
// through LocalWindowLayout; screens decide by width, height and posture,
// never by which way up the device is held or by its model.

/** A window dimension bucketed at the Material 3 breakpoints. */
enum class SizeClass {
    Compact, Medium, Expanded;

    companion object {
        /** Width: Compact < 600dp <= Medium < 840dp <= Expanded. */
        fun widthOf(dp: Float) = when {
            dp < 600f -> Compact
            dp < 840f -> Medium
            else -> Expanded
        }

        /** Height: Compact < 480dp <= Medium < 900dp <= Expanded. */
        fun heightOf(dp: Float) = when {
            dp < 480f -> Compact
            dp < 900f -> Medium
            else -> Expanded
        }
    }
}

/** How a foldable is held; Flat for every other device. The hinge is in window pixels. */
sealed interface Posture {
    data object Flat : Posture
    /** Half open with the hinge across: the top half shows, the bottom half rests on a table. */
    data class Tabletop(val hingeTopPx: Int, val hingeBottomPx: Int) : Posture
    /** Half open with the hinge down the middle, held like a book. */
    data class Book(val hingeLeftPx: Int, val hingeRightPx: Int) : Posture
}

/** The posture of a folding feature: flat unless half opened, then by the hinge's direction. */
fun postureOf(halfOpened: Boolean, vertical: Boolean, left: Int, top: Int, right: Int, bottom: Int): Posture = when {
    !halfOpened -> Posture.Flat
    vertical -> Posture.Book(left, right)
    else -> Posture.Tabletop(top, bottom)
}

/**
 * A foldable's hinge as the window reports it, in any state, flat included:
 * an open Fold says FLAT and its list | detail split still lines up with it.
 * The bounds are in window pixels.
 */
data class Fold(val vertical: Boolean, val halfOpened: Boolean, val leftPx: Int, val topPx: Int, val rightPx: Int, val bottomPx: Int)

/** The fold of a folding feature, kept whatever its state. */
fun foldOf(halfOpened: Boolean, vertical: Boolean, left: Int, top: Int, right: Int, bottom: Int) =
    Fold(vertical, halfOpened, left, top, right, bottom)

data class WindowLayout(
    val widthDp: Float,
    val heightDp: Float,
    val width: SizeClass,
    val height: SizeClass,
    val posture: Posture,
    val fold: Fold? = null,
    /** Pixels per dp, so the fold's pixels turn into the dp every layout speaks. */
    val density: Float = 1f,
) {
    /** A short window (a phone on its side, a split-screen half): tabs in a rail, the library in one sideways row. */
    val short get() = height == SizeClass.Compact

    /** Wider than tall with room for two columns side by side. */
    val wide get() = width != SizeClass.Compact && widthDp > heightDp

    /** Smallest width 600dp or more, whichever way it is held: the tablet game skin and the pause side panel. */
    val tablet get() = minOf(widthDp, heightDp) >= 600f

    companion object {
        fun of(widthDp: Float, heightDp: Float, posture: Posture = Posture.Flat, fold: Fold? = null, density: Float = 1f) =
            WindowLayout(widthDp, heightDp, SizeClass.widthOf(widthDp), SizeClass.heightOf(heightDp), posture, fold, density)
    }
}

/**
 * The strip around a fold that nothing tappable, no text and no image may
 * sit in: an x range in window dp for a vertical fold, a y range for one
 * across. Laid out as background only.
 */
data class Band(val vertical: Boolean, val startDp: Float, val endDp: Float) {
    val centerDp get() = (startDp + endDp) / 2f
}

/** The forbidden band's margin each side of the fold, in dp; the tabletop skin keeps the same one (SkinGeometry). */
const val FOLD_MARGIN_DP = 12f

/** The fold's bounds plus [marginDp] on each side, inside the window; null without a fold. */
fun forbiddenBand(layout: WindowLayout, marginDp: Float = FOLD_MARGIN_DP): Band? {
    val fold = layout.fold ?: return null
    val (start, end, limit) =
        if (fold.vertical) Triple(fold.leftPx, fold.rightPx, layout.widthDp)
        else Triple(fold.topPx, fold.bottomPx, layout.heightDp)
    return Band(
        fold.vertical,
        (start / layout.density - marginDp).coerceIn(0f, limit),
        (end / layout.density + marginDp).coerceIn(0f, limit),
    )
}

/** Below this, a half is too small to hold a screen and the window keeps its flat layout. */
const val MIN_TABLETOP_HALF_DP = 200f

/**
 * The heights above and below the band of a box spanning [topDp]..[bottomDp]
 * of the window, when the device is half open with the fold across and both
 * halves have room; null otherwise.
 */
fun tabletopHalves(layout: WindowLayout, topDp: Float = 0f, bottomDp: Float = layout.heightDp): Pair<Float, Float>? {
    val fold = layout.fold ?: return null
    if (!fold.halfOpened || fold.vertical) return null
    val band = forbiddenBand(layout)!!
    val top = band.startDp - topDp
    val bottom = bottomDp - band.endDp
    return if (top < MIN_TABLETOP_HALF_DP || bottom < MIN_TABLETOP_HALF_DP) null else top to bottom
}

/** Half open with the fold across: content on the top half, actions on the bottom one. */
val WindowLayout.tabletop get() = tabletopHalves(this) != null

/** A list | detail split: the list's width and whether its divider sits on the fold. */
data class BookSplit(val listDp: Float, val onFold: Boolean)

/** Narrower than this, a list pane is cramped; the split keeps its fixed widths. */
const val MIN_BOOK_LIST_DP = 280f

/** Narrower than this, a detail pane is cramped; the split keeps its fixed widths. */
const val MIN_BOOK_DETAIL_DP = 320f

/**
 * Where a list | detail split puts its divider: on a vertical fold (flat or
 * half open) the list ends at the fold's centre, so each pane has a half;
 * without one, or when a pane would be cramped, the list keeps [fallbackListDp].
 */
fun bookSplit(layout: WindowLayout, railDp: Float, fallbackListDp: Float): BookSplit {
    val band = forbiddenBand(layout)?.takeIf { it.vertical } ?: return BookSplit(fallbackListDp, onFold = false)
    val list = band.centerDp - railDp
    val detail = layout.widthDp - band.centerDp
    return if (list < MIN_BOOK_LIST_DP || detail < MIN_BOOK_DETAIL_DP) BookSplit(fallbackListDp, onFold = false)
    else BookSplit(list, onFold = true)
}

/** A grid's cards: [min] wide or more, never wider than [max], [gap] apart, at least [floor] columns. */
data class GridSpec(val min: Float, val max: Float, val gap: Float, val floor: Int) {
    companion object {
        val Carts = GridSpec(min = 150f, max = 220f, gap = 16f, floor = 2)
        val Covers = GridSpec(min = 110f, max = 170f, gap = 12f, floor = 3)
        val List = GridSpec(min = 0f, max = Float.POSITIVE_INFINITY, gap = 16f, floor = 1)
    }
}

/** How many columns of at least [minDp] fit in [availableDp]; never fewer than [floor]. */
fun gridColumns(availableDp: Float, minDp: Float, gapDp: Float, floor: Int): Int =
    if (availableDp <= 0f || minDp <= 0f) floor
    else maxOf(floor, ((availableDp + gapDp) / (minDp + gapDp)).toInt())

/** [count] cells sharing [available] pixels with [spacing] between them; the leftover pixels go to the first cells. */
fun cellSizes(available: Int, spacing: Int, count: Int): IntArray {
    val room = (available - spacing * (count - 1)).coerceAtLeast(0)
    val slot = room / count
    val extra = room % count
    return IntArray(count) { slot + if (it < extra) 1 else 0 }
}

/** Where the main tabs go: under everything, in the phone's side rail, or in the tablet's rail with the logo. */
enum class NavKind { BottomBar, PhoneRail, TabletRail }

/** Short wins over wide: a phone on its side is 640-930dp wide and still keeps its own rail. */
val WindowLayout.nav: NavKind
    get() = when {
        short -> NavKind.PhoneRail
        width != SizeClass.Compact -> NavKind.TabletRail
        else -> NavKind.BottomBar
    }

/** The width the tabs take on the left, none when they are a bottom bar. */
fun railWidthDp(layout: WindowLayout): Float = when (layout.nav) {
    NavKind.BottomBar -> 0f
    NavKind.PhoneRail -> 80f
    NavKind.TabletRail -> if (layout.width == SizeClass.Expanded) 88f else 80f
}

/** Space on each side of a page's content: the phone's 20dp, more on a tablet so the cards line up with the shelf. */
fun contentSideDp(layout: WindowLayout): Float = when {
    layout.nav != NavKind.TabletRail -> 20f
    layout.width == SizeClass.Expanded -> 40f
    else -> 32f
}

/**
 * The shelf's arrangement. [tablet] false keeps the phone's shelf as it is; a
 * tablet adds a row of [continueCount] games to continue, [maxColumns] columns of
 * [cardHeight] cards [gap] apart, and the recently added row when [recentlyAdded].
 */
data class ShelfSpec(
    val tablet: Boolean,
    val maxColumns: Int,
    val continueCount: Int,
    val cardHeight: Float,
    val gap: Float,
    val sidePadding: Float,
    val topPadding: Float,
    val recentlyAdded: Boolean,
)

fun shelfSpecOf(layout: WindowLayout): ShelfSpec = when {
    layout.nav != NavKind.TabletRail -> ShelfSpec(false, 2, 0, 108f, SHELF_GAP_DP, SHELF_SIDE_DP, 0f, false)
    layout.width == SizeClass.Expanded -> ShelfSpec(true, 3, 3, 168f, 16f, 40f, 32f, false)
    // A tall portrait tablet has room under the grid for what came in last.
    else -> ShelfSpec(true, 2, 2, 160f, 14f, 32f, 40f, layout.height == SizeClass.Expanded)
}

/** The setup side by side (explanation, then progress) on a wide tablet; stacked everywhere else. */
fun setupColumns(layout: WindowLayout): Int = if (!layout.short && layout.width == SizeClass.Expanded) 2 else 1
