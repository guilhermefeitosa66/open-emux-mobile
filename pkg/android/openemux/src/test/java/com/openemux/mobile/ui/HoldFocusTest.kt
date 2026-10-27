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

    @Test
    fun aFenceStopsTheDpadWhileOn() {
        for (d in listOf(FocusDirection.Up, FocusDirection.Down, FocusDirection.Left, FocusDirection.Right, FocusDirection.Next, FocusDirection.Previous)) {
            assertTrue(blocksGamepadMove(true, d, gamepad = true))
        }
    }

    @Test
    fun aFenceLetsCodeTouchAndOffThrough() {
        // requestFocus arrives as Enter; clearing focus as Exit.
        assertFalse(blocksGamepadMove(true, FocusDirection.Enter, gamepad = true))
        assertFalse(blocksGamepadMove(true, FocusDirection.Exit, gamepad = true))
        assertFalse(blocksGamepadMove(true, FocusDirection.Right, gamepad = false))
        assertFalse(blocksGamepadMove(false, FocusDirection.Right, gamepad = true))
    }
}
