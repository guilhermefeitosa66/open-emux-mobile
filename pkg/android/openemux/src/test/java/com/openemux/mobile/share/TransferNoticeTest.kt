package com.openemux.mobile.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferNoticeTest {
    private fun progress(done: Long, total: Long) = TransferProgress("g1i1", "Game.sfc", Item.ROM, done, total, done, total, 0, 3)

    @Test
    fun `waiting shows the peer and an indeterminate bar`() {
        val notice = TransferNotice.of(TransferState.Waiting("Desk", null))!!
        assertTrue(notice.outgoing)
        assertTrue(notice.waiting)
        assertEquals("Desk", notice.peer)
        assertNull(notice.percent)
    }

    @Test
    fun `running shows the direction, the games and the bytes`() {
        val sending = TransferNotice.of(TransferState.Running(true, "Tab", 2, progress(250, 1000)))!!
        assertEquals(TransferNotice(true, "Tab", false, 2, 250, 1000), sending)
        assertEquals(25, sending.percent)
        val receiving = TransferNotice.of(TransferState.Running(false, "Phone", 1, null))!!
        assertFalse(receiving.outgoing)
        assertEquals(0L, receiving.done)
        assertNull(receiving.percent)
    }

    @Test
    fun `there is nothing to show when idle or finished`() {
        assertNull(TransferNotice.of(TransferState.Idle))
        assertNull(TransferNotice.of(TransferState.Finished(ShareResult(ResultKind.SENT, true, "Desk"))))
    }

    @Test
    fun `the percent never leaves 0 to 100`() {
        assertEquals(100, TransferNotice(true, "x", false, 1, 2000, 1000).percent)
    }

    @Test
    fun `updates go at most twice a second, the forced ones always`() {
        val throttle = UpdateThrottle()
        assertTrue(throttle.allow(0))
        assertFalse(throttle.allow(100))
        assertFalse(throttle.allow(499))
        assertTrue(throttle.allow(500))
        assertTrue(throttle.allow(600, force = true))
        assertFalse(throttle.allow(700))
    }
}
