package com.openemux.mobile.game

/**
 * Coming back to a game after the device was closed (or the game left the
 * screen): the engine keeps the content paused, and the pause sheet is
 * shown over it unless it is already there or the game is closing.
 */
object ReturnPause {
    fun shouldOpenSheet(sheetShowing: Boolean, finishing: Boolean): Boolean = !sheetShowing && !finishing
}
