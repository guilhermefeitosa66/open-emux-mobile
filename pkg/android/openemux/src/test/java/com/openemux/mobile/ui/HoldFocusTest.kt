package com.openemux.mobile.ui

import androidx.compose.ui.focus.FocusDirection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldFocusTest {

    @Test
    fun flatListHoldsTheGamepadAtItsEnd() {
        assertTrue(holdsFocusAtEnd(FocusDirection.Down, FocusDirection.Down, gamepad = true, tabletop = false))
    }

    @Test
    fun tabletopLetsDownGoToTheBottomHalf() {
        // The deck and the tabs lie under the grid's last row.
        assertFalse(holdsFocusAtEnd(FocusDirection.Down, FocusDirection.Down, gamepad = true, tabletop = true))
    }

    @Test
    fun tabletopStillHoldsTheOtherEnds() {
        assertTrue(holdsFocusAtEnd(FocusDirection.Up, FocusDirection.Up, gamepad = true, tabletop = true))
    }

    @Test
    fun otherDirectionsAndTouchPass() {
        assertFalse(holdsFocusAtEnd(FocusDirection.Down, FocusDirection.Up, gamepad = true, tabletop = false))
        assertFalse(holdsFocusAtEnd(FocusDirection.Down, FocusDirection.Down, gamepad = false, tabletop = false))
    }
}
