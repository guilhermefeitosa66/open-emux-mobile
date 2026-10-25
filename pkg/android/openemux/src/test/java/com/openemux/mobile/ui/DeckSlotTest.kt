package com.openemux.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeckSlotTest {

    @Test
    fun startsEmpty() {
        assertNull(DeckSlot<String>().content)
    }

    @Test
    fun showsWhatTheScreenOnTopSet() {
        val slot = DeckSlot<String>()
        val library = Any()
        slot.set(library, "chips")
        assertEquals("chips", slot.content)
        slot.set(library, "chips and search")
        assertEquals("chips and search", slot.content)
        slot.clear(library)
        assertNull(slot.content)
    }

    @Test
    fun aScreenLeavingLateDoesNotClearTheNextOne() {
        // A cross-fade: the detail arrives and sets its deck before the library is disposed.
        val slot = DeckSlot<String>()
        val library = Any()
        val detail = Any()
        slot.set(library, "chips")
        slot.set(detail, "play")
        slot.clear(library)
        assertEquals("play", slot.content)
        slot.clear(detail)
        assertNull(slot.content)
    }
}
