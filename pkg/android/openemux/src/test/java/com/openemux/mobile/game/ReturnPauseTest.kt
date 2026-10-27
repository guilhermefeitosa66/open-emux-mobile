package com.openemux.mobile.game

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReturnPauseTest {

    @Test
    fun theSheetOpensOverThePausedGame() {
        assertTrue(ReturnPause.shouldOpenSheet(sheetShowing = false, finishing = false))
    }

    @Test
    fun aSheetLeftOpenIsNotOpenedAgain() {
        assertFalse(ReturnPause.shouldOpenSheet(sheetShowing = true, finishing = false))
    }

    @Test
    fun nothingOpensWhileTheGameIsClosing() {
        assertFalse(ReturnPause.shouldOpenSheet(sheetShowing = false, finishing = true))
        assertFalse(ReturnPause.shouldOpenSheet(sheetShowing = true, finishing = true))
    }
}
