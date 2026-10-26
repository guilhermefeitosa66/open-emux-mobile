package com.openemux.mobile.ui

/**
 * Where a scroll container puts the item that takes gamepad focus, as plain
 * math on its offset and size along the scroll axis (all in pixels, the
 * offset from the container's leading edge). The answer is how far to
 * scroll; the container stops at its ends, which is what rests the first and
 * last items against the content padding.
 */
object FocusGeometry {
    /** Centre lock: the item's middle goes to the container's middle. */
    fun centered(offset: Float, size: Float, containerSize: Float): Float =
        offset + size / 2f - containerSize / 2f

    /**
     * Keep-in-view with a margin band: nothing moves while the item sits
     * inside [leading, containerSize - trailing]; otherwise the least scroll
     * that brings it in. An item bigger than the band aligns to its start.
     */
    fun margin(offset: Float, size: Float, containerSize: Float, leading: Float, trailing: Float): Float {
        val end = containerSize - trailing
        return when {
            size > end - leading -> offset - leading
            offset < leading -> offset - leading
            offset + size > end -> offset + size - end
            else -> 0f
        }
    }
}
