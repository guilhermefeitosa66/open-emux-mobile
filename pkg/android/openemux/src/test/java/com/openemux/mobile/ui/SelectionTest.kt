package com.openemux.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionTest {
    private val visible = listOf("FC/a.nes", "FC/b.nes", "FC/c.nes")

    @Test
    fun startsInactive() {
        val s = RomSelection()
        assertFalse(s.active)
        assertEquals(0, s.count(visible))
    }

    @Test
    fun startEntersTheModeWithThatGame() {
        val s = RomSelection().start("FC/a.nes")
        assertTrue(s.active)
        assertTrue("FC/a.nes" in s)
        assertFalse("FC/b.nes" in s)
        assertEquals(1, s.count(visible))
    }

    @Test
    fun toggleAddsAndRemoves() {
        val s = RomSelection().start("FC/a.nes").toggle("FC/b.nes")
        assertEquals(setOf("FC/a.nes", "FC/b.nes"), s.keys)
        assertEquals(setOf("FC/a.nes"), s.toggle("FC/b.nes").keys)
    }

    @Test
    fun deselectingTheLastGameEndsTheMode() {
        assertFalse(RomSelection().start("FC/a.nes").toggle("FC/a.nes").active)
    }

    @Test
    fun selectAllTakesEveryVisibleGame() {
        val s = RomSelection().start("FC/a.nes").selectAll(visible)
        assertEquals(visible.toSet(), s.keys)
        assertTrue(s.allSelected(visible))
        assertEquals(3, s.count(visible))
    }

    @Test
    fun allSelectedIsFalseWhenOneIsMissingOrNothingIsVisible() {
        assertFalse(RomSelection().start("FC/a.nes").allSelected(visible))
        assertFalse(RomSelection().start("FC/a.nes").allSelected(emptyList()))
    }

    @Test
    fun countOnlyCountsVisibleGames() {
        val s = RomSelection(setOf("FC/a.nes", "SFC/z.sfc"))
        assertEquals(1, s.count(visible))
    }

    @Test
    fun retainKeepsTheVisibleIntersection() {
        val s = RomSelection(setOf("FC/a.nes", "SFC/z.sfc")).retain(visible)
        assertEquals(setOf("FC/a.nes"), s.keys)
        assertTrue(s.active)
    }

    @Test
    fun retainEndsTheModeWhenNothingSelectedIsVisible() {
        assertFalse(RomSelection(setOf("SFC/z.sfc")).retain(visible).active)
    }

    @Test
    fun clearEndsTheMode() {
        assertFalse(RomSelection().selectAll(visible).clear().active)
    }

    @Test
    fun focusStaysOnAGameThatWasKept() {
        assertEquals("FC/a.nes", focusAfterRemoval("FC/a.nes", visible))
    }

    @Test
    fun focusIsDroppedWhenTheFocusedGameWasRemoved() {
        assertEquals(null, focusAfterRemoval("FC/z.nes", visible))
        assertEquals(null, focusAfterRemoval(null, visible))
    }
}
