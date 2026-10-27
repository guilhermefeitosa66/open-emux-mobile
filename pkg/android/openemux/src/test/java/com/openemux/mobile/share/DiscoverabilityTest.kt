package com.openemux.mobile.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverabilityTest {
    private val scheduler = FakeScheduler()
    private var starts = 0
    private var stops = 0
    private val rules = Discoverability(scheduler, { starts++ }, { stops++ })

    @Test
    fun `the first screen starts it and a second does not start it again`() {
        rules.open()
        rules.open()
        assertEquals(1, starts)
        assertTrue(rules.running)
    }

    @Test
    fun `it stops 30 s after the last screen closes`() {
        rules.open()
        rules.open()
        rules.close()
        scheduler.advance(60_000)
        assertEquals(0, stops)
        rules.close()
        scheduler.advance(29_999)
        assertEquals(0, stops)
        scheduler.advance(1)
        assertEquals(1, stops)
        assertFalse(rules.running)
    }

    @Test
    fun `reopening within the grace keeps it running`() {
        rules.open()
        rules.close()
        scheduler.advance(20_000)
        rules.open()
        scheduler.advance(60_000)
        assertEquals(1, starts)
        assertEquals(0, stops)
    }

    @Test
    fun `a running transfer holds it until 30 s after it ends`() {
        rules.open()
        rules.transferStarted()
        rules.close()
        scheduler.advance(120_000)
        assertEquals(0, stops)
        rules.transferEnded()
        scheduler.advance(29_000)
        assertEquals(0, stops)
        scheduler.advance(1_000)
        assertEquals(1, stops)
    }

    @Test
    fun `a transfer that starts in the grace cancels the stop`() {
        rules.open()
        rules.close()
        scheduler.advance(10_000)
        rules.transferStarted()
        scheduler.advance(60_000)
        assertEquals(0, stops)
    }

    @Test
    fun `an offer that arrives in the grace holds it until 30 s after it ends`() {
        rules.open()
        rules.close()
        scheduler.advance(15_000)
        rules.offerShown()
        scheduler.advance(60_000)
        assertEquals(0, stops)
        rules.offerEnded()
        scheduler.advance(29_999)
        assertEquals(0, stops)
        scheduler.advance(1)
        assertEquals(1, stops)
    }

    @Test
    fun `an offer accepted into a transfer stays held by the transfer`() {
        rules.open()
        rules.close()
        rules.offerShown()
        rules.transferStarted()
        rules.offerEnded()
        scheduler.advance(120_000)
        assertEquals(0, stops)
        rules.transferEnded()
        scheduler.advance(30_000)
        assertEquals(1, stops)
    }

    @Test
    fun `stray calls change nothing`() {
        rules.close()
        rules.transferEnded()
        rules.offerEnded()
        scheduler.advance(60_000)
        assertEquals(0, starts)
        assertEquals(0, stops)
        assertEquals(0, scheduler.pending)
    }

    @Test
    fun `after a failed start the next screen tries again`() {
        rules.open()
        rules.failed()
        assertFalse(rules.running)
        rules.close()
        scheduler.advance(60_000)
        assertEquals(0, stops)
        rules.open()
        assertEquals(2, starts)
    }

    @Test
    fun `a stop that falls due after the state changed does nothing`() {
        rules.open()
        rules.close()
        // The layer failed in the meantime: the grace's stop has nothing to stop.
        rules.failed()
        scheduler.advance(60_000)
        assertEquals(0, stops)
    }
}

class NothingFoundTest {
    private fun peer(compatible: Boolean) = Peer(
        "a".repeat(32), "b".repeat(64), "Desk", "desktop",
        if (compatible) VersionRange.OWN else VersionRange(9, 9), 53319, listOf("10.0.0.2"), setOf(PeerPath.MDNS),
    )

    @Test
    fun `15 s with nobody is nothing found`() {
        val rule = NothingFound()
        assertFalse(rule.isNothingFound(0))
        assertNull(rule.remaining(0))
        rule.started(1_000)
        assertEquals(15_000L, rule.remaining(1_000))
        assertFalse(rule.isNothingFound(15_999))
        assertTrue(rule.isNothingFound(16_000))
        assertEquals(0L, rule.remaining(20_000))
    }

    @Test
    fun `a compatible peer resets it, an incompatible one does not`() {
        val rule = NothingFound()
        rule.started(0)
        rule.peers(listOf(peer(false)), 5_000)
        assertTrue(rule.isNothingFound(15_000))
        rule.peers(listOf(peer(true)), 16_000)
        assertFalse(rule.isNothingFound(60_000))
        assertNull(rule.remaining(60_000))
        // The last one left: the count starts from then.
        rule.peers(emptyList(), 70_000)
        assertFalse(rule.isNothingFound(84_999))
        assertTrue(rule.isNothingFound(85_000))
        rule.peers(emptyList(), 90_000)
        assertTrue(rule.isNothingFound(90_000))
    }

    @Test
    fun `stopping forgets it`() {
        val rule = NothingFound()
        rule.started(0)
        rule.stopped()
        assertFalse(rule.isNothingFound(60_000))
    }
}
