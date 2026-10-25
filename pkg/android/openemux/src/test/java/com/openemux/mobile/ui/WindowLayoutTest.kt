package com.openemux.mobile.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowLayoutTest {

    @Test
    fun widthClassesFollowTheMaterialBreakpoints() {
        assertEquals(SizeClass.Compact, SizeClass.widthOf(0f))
        assertEquals(SizeClass.Compact, SizeClass.widthOf(599.9f))
        assertEquals(SizeClass.Medium, SizeClass.widthOf(600f))
        assertEquals(SizeClass.Medium, SizeClass.widthOf(839.9f))
        assertEquals(SizeClass.Expanded, SizeClass.widthOf(840f))
    }

    @Test
    fun heightClassesFollowTheMaterialBreakpoints() {
        assertEquals(SizeClass.Compact, SizeClass.heightOf(479.9f))
        assertEquals(SizeClass.Medium, SizeClass.heightOf(480f))
        assertEquals(SizeClass.Medium, SizeClass.heightOf(899.9f))
        assertEquals(SizeClass.Expanded, SizeClass.heightOf(900f))
    }

    @Test
    fun postureIsFlatUnlessHalfOpened() {
        assertEquals(Posture.Flat, postureOf(halfOpened = false, vertical = true, left = 1, top = 2, right = 3, bottom = 4))
        assertEquals(Posture.Flat, postureOf(halfOpened = false, vertical = false, left = 1, top = 2, right = 3, bottom = 4))
    }

    @Test
    fun aHalfOpenedHingeAcrossIsTabletopAndDownTheMiddleIsBook() {
        assertEquals(Posture.Tabletop(1100, 1140), postureOf(halfOpened = true, vertical = false, left = 0, top = 1100, right = 1800, bottom = 1140))
        assertEquals(Posture.Book(900, 940), postureOf(halfOpened = true, vertical = true, left = 900, top = 0, right = 940, bottom = 2200))
    }

    @Test
    fun ofDerivesTheClassesAndDefaultsToFlat() {
        val w = WindowLayout.of(700f, 1000f)
        assertEquals(SizeClass.Medium, w.width)
        assertEquals(SizeClass.Expanded, w.height)
        assertEquals(Posture.Flat, w.posture)
        assertEquals(Posture.Book(1, 2), WindowLayout.of(700f, 1000f, Posture.Book(1, 2)).posture)
    }

    private fun assertLayout(widthDp: Float, heightDp: Float, short: Boolean, wide: Boolean) {
        val w = WindowLayout.of(widthDp, heightDp)
        assertEquals("short at ${widthDp}x$heightDp", short, w.short)
        assertEquals("wide at ${widthDp}x$heightDp", wide, w.wide)
    }

    @Test
    fun phonesKeepTodaysLayouts() {
        assertLayout(411f, 914f, short = false, wide = false) // phone portrait
        assertLayout(320f, 569f, short = false, wide = false) // small phone portrait
        assertLayout(914f, 411f, short = true, wide = true)   // phone landscape: rail, two-column sheet
        assertLayout(569f, 320f, short = true, wide = false)  // small phone landscape: rail, one-column sheet
    }

    @Test
    fun windowsTakeTheLayoutOfTheirSize() {
        assertLayout(393f, 400f, short = true, wide = false)  // split-screen half
        assertLayout(600f, 900f, short = false, wide = false) // resized window
        assertLayout(960f, 552f, short = false, wide = true)  // 7" tablet landscape
    }

    @Test
    fun gridColumnsNeverDropBelowTheFloor() {
        // A 320dp phone leaves 280dp: today's 2 cartridges and 3 covers.
        assertEquals(2, gridColumns(280f, GridSpec.Carts.min, GridSpec.Carts.gap, GridSpec.Carts.floor))
        assertEquals(3, gridColumns(280f, GridSpec.Covers.min, GridSpec.Covers.gap, GridSpec.Covers.floor))
        // A 411dp phone: still 2 / 3.
        assertEquals(2, gridColumns(371f, GridSpec.Carts.min, GridSpec.Carts.gap, GridSpec.Carts.floor))
        assertEquals(3, gridColumns(371f, GridSpec.Covers.min, GridSpec.Covers.gap, GridSpec.Covers.floor))
    }

    @Test
    fun gridColumnsGrowWithTheWidth() {
        assertEquals(3, gridColumns(560f, GridSpec.Carts.min, GridSpec.Carts.gap, GridSpec.Carts.floor))
        assertEquals(4, gridColumns(560f, GridSpec.Covers.min, GridSpec.Covers.gap, GridSpec.Covers.floor))
        assertEquals(4, gridColumns(760f, GridSpec.Carts.min, GridSpec.Carts.gap, GridSpec.Carts.floor))
        assertEquals(6, gridColumns(760f, GridSpec.Covers.min, GridSpec.Covers.gap, GridSpec.Covers.floor))
        // Exactly three minimum cards and their two gaps.
        assertEquals(3, gridColumns(3 * 150f + 2 * 16f, 150f, 16f, 1))
        assertEquals(2, gridColumns(3 * 150f + 2 * 16f - 0.1f, 150f, 16f, 1))
    }

    @Test
    fun gridColumnsFallBackToTheFloor() {
        assertEquals(2, gridColumns(0f, 150f, 16f, 2))
        assertEquals(2, gridColumns(-10f, 150f, 16f, 2))
        assertEquals(1, gridColumns(800f, GridSpec.List.min, GridSpec.List.gap, GridSpec.List.floor))
    }

    @Test
    fun cellSizesShareTheWidthLeftByTheSpacing() {
        assertArrayEquals(intArrayOf(100, 100, 100), cellSizes(332, 16, 3))
        val uneven = cellSizes(337, 16, 3)
        assertArrayEquals(intArrayOf(102, 102, 101), uneven)
        assertEquals(337 - 2 * 16, uneven.sum())
        assertArrayEquals(intArrayOf(500), cellSizes(500, 16, 1))
        assertArrayEquals(intArrayOf(0, 0), cellSizes(10, 16, 2))
    }

    @Test
    fun cardsAreCappedButListRowsStretch() {
        assertTrue(GridSpec.Carts.max == 220f && GridSpec.Covers.max == 170f)
        assertFalse(GridSpec.List.max.isFinite())
    }
}
