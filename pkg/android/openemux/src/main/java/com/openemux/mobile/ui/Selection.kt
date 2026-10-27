package com.openemux.mobile.ui

/**
 * The library's selection mode, by ROM key. Pure, so the rules are tested on
 * the JVM; the screen keeps one in a state and swaps it for each change.
 * Empty means the mode is off: deselecting the last game ends it.
 */
data class RomSelection(val keys: Set<String> = emptySet()) {
    val active: Boolean get() = keys.isNotEmpty()

    operator fun contains(key: String) = key in keys

    /** Enters the mode with [key] selected (a long press, or Start on a gamepad). */
    fun start(key: String) = RomSelection(keys + key)

    fun toggle(key: String) = RomSelection(if (key in keys) keys - key else keys + key)

    fun selectAll(visible: List<String>) = RomSelection(keys + visible)

    /** Keeps only the games still on screen, so a hidden game is never acted on. */
    fun retain(visible: List<String>) = RomSelection(keys intersect visible.toSet())

    fun clear() = RomSelection()

    fun count(visible: List<String>) = visible.count { it in keys }

    fun allSelected(visible: List<String>) = visible.isNotEmpty() && visible.all { it in keys }
}

/**
 * The library's focused game once a removal is done: kept if it is still
 * there, otherwise none, so the first tile takes the gamepad's focus.
 */
fun focusAfterRemoval(focused: String?, left: Collection<String>): String? = focused?.takeIf { it in left }
