package com.openemux.mobile.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryTest {
    private val announcement = Announcement("0".repeat(32), "Me", "phone", VersionRange.OWN, 53319, "1".repeat(64))

    private class FakeLock(val refuse: Boolean = false) : MulticastLockHandle {
        override var isHeld = false
        var acquired = 0
        var released = 0

        override fun acquire() {
            if (refuse) throw SecurityException("no CHANGE_WIFI_MULTICAST_STATE")
            acquired++
            isHeld = true
        }

        override fun release() {
            released++
            isHeld = false
        }
    }

    private class FakeTransport(override val path: PeerPath, val fail: Boolean = false) : DiscoveryTransport {
        var running = false
        var stops = 0

        override fun start(announcement: Announcement, peers: PeerList) {
            if (fail) throw IllegalStateException("socket in use")
            running = true
        }

        override fun stop() {
            stops++
            running = false
        }
    }

    private fun discovery(lock: MulticastLockHandle, vararg transports: DiscoveryTransport) =
        Discovery(lock, transports.toList(), PeerList(announcement.id))

    @Test
    fun `the lock is taken on start and released on stop`() {
        val lock = FakeLock()
        val nsd = FakeTransport(PeerPath.MDNS)
        val beacon = FakeTransport(PeerPath.BEACON)
        val discovery = discovery(lock, nsd, beacon)
        discovery.start(announcement)
        assertTrue(lock.isHeld)
        assertTrue(nsd.running && beacon.running)
        assertEquals(DiscoveryState.Running(), discovery.state.value)
        discovery.start(announcement)
        assertEquals(1, lock.acquired)
        discovery.stop()
        assertFalse(lock.isHeld)
        assertFalse(nsd.running || beacon.running)
        assertEquals(DiscoveryState.Idle, discovery.state.value)
    }

    @Test
    fun `a refused lock is a warning and discovery carries on`() {
        val nsd = FakeTransport(PeerPath.MDNS)
        val discovery = discovery(FakeLock(refuse = true), nsd)
        discovery.start(announcement)
        assertEquals(DiscoveryState.Running(listOf("multicast_lock_refused")), discovery.state.value)
        assertTrue(nsd.running)
        discovery.stop()
    }

    @Test
    fun `a transport failing to start releases the lock and stops what started`() {
        val lock = FakeLock()
        val nsd = FakeTransport(PeerPath.MDNS)
        val beacon = FakeTransport(PeerPath.BEACON, fail = true)
        val discovery = discovery(lock, nsd, beacon)
        discovery.start(announcement)
        assertFalse(lock.isHeld)
        assertEquals(1, lock.released)
        assertFalse(nsd.running)
        assertEquals(1, nsd.stops)
        assertEquals(DiscoveryState.Failed(ErrorCode.INTERNAL), discovery.state.value)
    }

    @Test
    fun `stop twice is safe, and a failing stop still releases the lock`() {
        val lock = FakeLock()
        val broken = object : DiscoveryTransport {
            override val path = PeerPath.BEACON
            override fun start(announcement: Announcement, peers: PeerList) = Unit
            override fun stop() = throw IllegalStateException("already closed")
        }
        val discovery = discovery(lock, broken)
        discovery.start(announcement)
        discovery.stop()
        discovery.stop()
        assertFalse(lock.isHeld)
        assertEquals(DiscoveryState.Idle, discovery.state.value)
    }

    @Test
    fun `stop forgets discovered peers and keeps manual ones`() {
        val discovery = discovery(FakeLock(), FakeTransport(PeerPath.MDNS))
        discovery.start(announcement)
        val other = Announcement("a".repeat(32), "Den", "desktop", VersionRange.OWN, 53319, "2".repeat(64))
        discovery.peers.seen(PeerPath.BEACON, other, "1.2.3.4")
        discovery.peers.seen(PeerPath.MANUAL, other.copy(id = "b".repeat(32)), "1.2.3.5")
        discovery.stop()
        assertEquals(listOf(setOf(PeerPath.MANUAL)), discovery.peers.peers().map { it.paths })
    }

    @Test
    fun `the expiry ticker sweeps the list`() {
        val clock = FakeClock()
        val peers = PeerList(announcement.id, clock)
        val discovery = Discovery(FakeLock(), emptyList(), peers)
        discovery.start(announcement)
        peers.seen(PeerPath.BEACON, Announcement("a".repeat(32), "Den", "desktop", VersionRange.OWN, 1, "2".repeat(64)), "1.2.3.4")
        clock.now += 8_000
        val end = System.currentTimeMillis() + 3_000
        while (peers.peers().isNotEmpty() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue(peers.peers().isEmpty())
        discovery.stop()
    }
}
