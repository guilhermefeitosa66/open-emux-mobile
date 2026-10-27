package com.openemux.mobile.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeersTest {
    private val own = "0".repeat(32)
    private val id = "a".repeat(32)
    private val fp = "1".repeat(64)
    private val clock = FakeClock()
    private var changes = 0
    private val list = PeerList(own, clock) { changes++ }

    private fun announcement(id: String = this.id, fp: String = this.fp, name: String = "Den PC", v: Int = 1) =
        Announcement(id, name, "desktop", VersionRange(v, v), 53319, fp)

    @Test
    fun `mdns only`() {
        list.seen(PeerPath.MDNS, announcement(), "192.168.0.5")
        val peer = list.peers().single()
        assertEquals(setOf(PeerPath.MDNS), peer.paths)
        assertEquals(listOf("192.168.0.5"), peer.addresses)
        list.gone(PeerPath.MDNS, id)
        assertTrue(list.peers().isEmpty())
    }

    @Test
    fun `beacon only`() {
        list.seen(PeerPath.BEACON, announcement(), "192.168.0.5")
        assertEquals(setOf(PeerPath.BEACON), list.peers().single().paths)
    }

    @Test
    fun `both paths merge into one entry`() {
        list.seen(PeerPath.MDNS, announcement(), "fe80::1%wlan0")
        list.seen(PeerPath.BEACON, announcement(), "192.168.0.5")
        val peer = list.peers().single()
        assertEquals(setOf(PeerPath.MDNS, PeerPath.BEACON), peer.paths)
        assertEquals(listOf("192.168.0.5", "fe80::1%wlan0"), peer.addresses)
    }

    @Test
    fun `the beacon path expires after 7 s while mdns keeps the entry`() {
        list.seen(PeerPath.MDNS, announcement())
        list.seen(PeerPath.BEACON, announcement(), "192.168.0.5")
        clock.now += 7_000
        list.expire()
        assertEquals(2, list.peers().single().paths.size)
        clock.now += 1
        list.expire()
        val peer = list.peers().single()
        assertEquals(setOf(PeerPath.MDNS), peer.paths)
        assertTrue(peer.addresses.isEmpty())
    }

    @Test
    fun `a beacon-only peer disappears when it expires`() {
        list.seen(PeerPath.BEACON, announcement(), "192.168.0.5")
        clock.now += 4_000
        list.seen(PeerPath.BEACON, announcement(), "192.168.0.5")
        clock.now += 6_000
        list.expire()
        assertEquals(1, list.peers().size)
        list.expire(clock.now + 2_000)
        assertTrue(list.peers().isEmpty())
    }

    @Test
    fun `bye removes the beacon path at once`() {
        list.seen(PeerPath.BEACON, announcement(), "192.168.0.5")
        list.gone(PeerPath.BEACON, id, fp)
        assertTrue(list.peers().isEmpty())
    }

    @Test
    fun `the same id with another fingerprint is a second entry`() {
        list.seen(PeerPath.MDNS, announcement())
        list.seen(PeerPath.BEACON, announcement(fp = "2".repeat(64), name = "Impostor"), "192.168.0.66")
        val peers = list.peers()
        assertEquals(2, peers.size)
        assertEquals(setOf(fp, "2".repeat(64)), peers.map { it.fp }.toSet())
        list.gone(PeerPath.BEACON, id, "2".repeat(64))
        assertEquals(fp, list.peers().single().fp)
    }

    @Test
    fun `own id is ignored`() {
        list.seen(PeerPath.BEACON, announcement(id = own), "192.168.0.2")
        assertTrue(list.peers().isEmpty())
        assertEquals(0, changes)
    }

    @Test
    fun `a manual entry stays until removed`() {
        list.seen(PeerPath.MANUAL, announcement(), "10.0.0.9")
        clock.now += 60_000
        list.expire()
        list.clearDiscovered()
        assertEquals(setOf(PeerPath.MANUAL), list.peers().single().paths)
        list.gone(PeerPath.MANUAL, id, fp)
        assertTrue(list.peers().isEmpty())
    }

    @Test
    fun `an incompatible version is listed and flagged`() {
        list.seen(PeerPath.BEACON, announcement(v = 0), "192.168.0.5")
        assertFalse(list.peers().single().compatible)
    }

    @Test
    fun `changes are reported only when something changed`() {
        list.seen(PeerPath.BEACON, announcement(), "192.168.0.5")
        list.seen(PeerPath.BEACON, announcement(), "192.168.0.5")
        assertEquals(1, changes)
        list.seen(PeerPath.BEACON, announcement(name = "Renamed"), "192.168.0.5")
        assertEquals(2, changes)
        list.gone(PeerPath.MDNS, id)
        list.expire()
        assertEquals(2, changes)
        list.clearDiscovered()
        assertEquals(3, changes)
    }

    @Test
    fun `peers are sorted by name`() {
        list.seen(PeerPath.BEACON, announcement(name = "zed"), "1.1.1.1")
        list.seen(PeerPath.BEACON, announcement(id = "b".repeat(32), name = "Alpha"), "1.1.1.2")
        assertEquals(listOf("Alpha", "zed"), list.peers().map { it.name })
    }
}
