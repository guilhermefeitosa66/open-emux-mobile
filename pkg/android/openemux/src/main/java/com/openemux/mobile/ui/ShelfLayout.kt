package com.openemux.mobile.ui

import kotlin.math.floor

// The shelf's geometry, in dp, kept free of Compose so the JVM tests reach it.

/** Padding on each side of the shelf grid and the gap between its cards. */
const val SHELF_SIDE_DP = 20f
const val SHELF_GAP_DP = 12f

/** Below this a card cannot hold the icon, the name and the fan side by side. */
const val SHELF_MIN_CARD_DP = 250f

/** Below this the card uses the compact layout: the icon goes inline with the tag. */
const val COMPACT_CARD_DP = 300f

/** The widest the fan of cartridges gets, which is how today's placements were drawn. */
const val FAN_MAX_DP = 210f
const val FAN_MIN_DP = 110f

/** How many cards fit side by side in [widthDp], each at least [SHELF_MIN_CARD_DP] wide. */
fun shelfColumns(widthDp: Float, maxColumns: Int = 2, side: Float = SHELF_SIDE_DP, gap: Float = SHELF_GAP_DP): Int {
    if (widthDp <= 0f) return 1
    val fit = floor((widthDp - 2 * side + gap) / (SHELF_MIN_CARD_DP + gap)).toInt()
    return fit.coerceIn(1, maxOf(1, maxColumns))
}

/** The width of each card when [widthDp] holds [columns] of them. */
fun cardWidthDp(widthDp: Float, columns: Int, side: Float = SHELF_SIDE_DP, gap: Float = SHELF_GAP_DP): Float =
    (widthDp - 2 * side - gap * (columns - 1)) / columns

/** The fan grows with the card, so a wide card has no empty middle. */
fun fanWidthDp(cardWidthDp: Float): Float = (cardWidthDp * 0.42f).coerceIn(FAN_MIN_DP, FAN_MAX_DP)

fun isCompactCard(cardWidthDp: Float): Boolean = cardWidthDp < COMPACT_CARD_DP

/** A card on screen, in any unit, as the grid laid it out. */
data class Slot<K>(val key: K, val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * The card a dragged card would swap with: the one under the dragged card's
 * centre ([cx], [cy]). Never the dragged card itself, nor one that cannot move.
 */
fun <K> reorderTarget(cx: Float, cy: Float, dragged: K, slots: List<Slot<K>>, movable: (K) -> Boolean): K? =
    slots.firstOrNull { s ->
        s.key != dragged && movable(s.key) &&
            cx >= s.x && cx < s.x + s.width && cy >= s.y && cy < s.y + s.height
    }?.key

/** The games to pick up again: the [n] last played, newest first; never-played ones never show. */
fun <T> continueList(items: List<T>, lastPlayed: (T) -> Long, n: Int): List<T> =
    items.filter { lastPlayed(it) > 0 }.sortedByDescending(lastPlayed).take(n.coerceAtLeast(0))

/** The [n] games that came in last, by when their file was copied in, newest first. */
fun <T> recentlyAdded(items: List<T>, modified: (T) -> Long, n: Int): List<T> =
    items.sortedByDescending(modified).take(n.coerceAtLeast(0))
