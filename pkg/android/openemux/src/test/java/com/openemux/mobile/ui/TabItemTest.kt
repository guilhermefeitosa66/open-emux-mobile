package com.openemux.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabItemTest {

    @Test
    fun theTabsComeInTheirOrder() {
        assertEquals(listOf(Routes.SHELF, Routes.RECENT, Routes.IMPORT, Routes.SETTINGS), TAB_ITEMS.map { it.route })
    }

    @Test
    fun theLibraryTabStaysLitInsideTheLibrary() {
        val library = TAB_ITEMS.first()
        assertTrue(library.isActive(Routes.SHELF))
        assertTrue(library.isActive(Routes.LIBRARY))
        assertFalse(library.isActive(Routes.RECENT))
        assertFalse(library.isActive(null))
    }

    @Test
    fun anyOtherTabIsLitOnlyOnItsRoute() {
        val settings = TAB_ITEMS.last()
        assertTrue(settings.isActive(Routes.SETTINGS))
        assertFalse(settings.isActive(Routes.CONSOLE))
        assertFalse(settings.isActive(Routes.LIBRARY))
    }
}
