package com.openemux.mobile.share

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files

class ShareServiceTest {
    private lateinit var dir: File
    private val backend = FakeKeyBackend()
    private var storesMade = 0
    private var discoveries = 0
    private val loopback = InetSocketAddress(InetAddress.getLoopbackAddress(), 0)

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("share-service").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun service(sdk: Int, probe: SpaceProbe = NioSpaceProbe) = ShareService(
        sdk,
        { storesMade++; IdentityStore(dir, backend) { IdentityStore.Defaults("Pixel", "phone") } },
        { ownId, onPeers -> discoveries++; Discovery(NoLock, emptyList(), PeerList(ownId, onChange = onPeers)) },
        { ShareServer(it, preferredPort = 0, bindAddress = loopback) },
        probe,
    )

    private object NoLock : MulticastLockHandle {
        override val isHeld = false
        override fun acquire() = Unit
        override fun release() = Unit
    }

    @Test
    fun `Android 9 is unavailable and touches nothing`() {
        val service = service(28)
        assertEquals(ShareAvailability.Unavailable("tls13"), service.availability())
        val error = assertThrows(ShareError::class.java) { service.start() }
        assertEquals(ErrorCode.UNAVAILABLE, error.code)
        assertEquals("tls13", error.details["reason"])
        assertEquals(0, storesMade)
        assertEquals(0, backend.generated)
        assertEquals(0, discoveries)
        assertFalse(service.running.value)
    }

    @Test
    fun `Android 10 is available`() {
        val service = service(29)
        assertEquals(ShareAvailability.Available, service.availability())
        assertTrue(isDeviceId(service.identity.value!!.id))
    }

    @Test
    fun `a keystore that cannot make a key is unavailable keystore`() {
        backend.failGenerate = true
        val service = service(34)
        assertEquals(ShareAvailability.Unavailable("keystore"), service.availability())
        assertEquals("keystore", assertThrows(ShareError::class.java) { service.start() }.details["reason"])
    }

    @Test
    fun `start and stop run the server and discovery together`() {
        val service = service(34)
        assertThrows(ShareError::class.java) { service.accept("x", emptyList()) }
        service.start()
        service.start()
        assertEquals(1, discoveries)
        assertTrue(service.running.value)
        assertEquals(DiscoveryState.Running(), service.discoveryState.value)
        service.stop()
        assertFalse(service.running.value)
        assertEquals(DiscoveryState.Idle, service.discoveryState.value)
        service.stop()
    }

    @Test
    fun `the receiver's plan measures space with the service's probe`() {
        val lib = FakeLibrary()
        val offer = IncomingOffer(
            "offer1", OfferSender("c".repeat(32), "Phone", "phone", "d".repeat(64)),
            manifest { game("g1", "SFC") { item("rom", "Game.sfc", "rom".toByteArray()) } }, "482913",
        )
        val service = service(34, FakeSpace(mapOf(lib.paths.base.path to 7L)))
        val check = service.prepareReceive(offer, lib).spaceCheck().single()
        assertEquals(7L, check.free)
        assertFalse(check.ok)
        lib.paths.base.parentFile!!.deleteRecursively()
    }

    @Test
    fun `an incoming offer is published and cleared by the answer`() {
        val receiver = service(34)
        receiver.start()
        val identity = receiver.identity.value!!
        val sender = TestCerts.identity()
        val asSender = OfferSender(sender.id, "Phone", "phone", sender.fingerprint)
        val manifest = org.json.JSONObject("""{"games":[],"total_size":0,"file_count":0}""")
        val client = receiver.client(ShareAddress("127.0.0.1", receiver.port!!, identity.fingerprint))
        val outgoing = client.offer(asSender, manifest)
        assertEquals(outgoing.code, receiver.incoming.value!!.code)
        receiver.accept(outgoing.offerId, emptyList())
        assertNull(receiver.incoming.value)
        assertEquals("accepted", client.poll(outgoing.offerId).getString("status"))

        receiver.decline(client.offer(asSender, manifest).offerId)
        receiver.cancel(client.offer(asSender, manifest).offerId)
        assertNull(receiver.incoming.value)
        val fourth = client.offer(asSender, manifest)
        assertEquals(fourth.offerId, receiver.incoming.value!!.offerId)
        client.cancel(fourth.offerId)
        assertNull(receiver.incoming.value)
        receiver.stop()
        assertNull(receiver.port)
    }

    @Test
    fun `a manual peer is filled from its info, pinned or trusted on first use`() {
        val other = TestCerts.identity(name = "Den PC", platform = "desktop")
        val otherServer = ShareServer(other, preferredPort = 0, bindAddress = loopback)
        val otherPort = otherServer.start()
        val service = service(34)
        assertThrows(ShareError::class.java) { service.addManualPeer(ShareAddress("127.0.0.1", otherPort)) }
        service.start()
        try {
            val peer = service.addManualPeer(ShareAddress("127.0.0.1", otherPort))
            assertEquals(other.fingerprint, peer.fp)
            assertEquals("Den PC", peer.name)
            assertEquals(listOf("127.0.0.1"), peer.addresses)
            assertEquals(setOf(PeerPath.MANUAL), peer.paths)
            assertEquals(listOf(peer), service.peers.value)
            assertEquals(other.id, service.client(peer).info().id)
            assertEquals(peer, service.addManualPeer(ShareAddress("127.0.0.1", otherPort, other.fingerprint)))
            val wrong = assertThrows(ShareError::class.java) { service.addManualPeer(ShareAddress("127.0.0.1", otherPort, "0".repeat(64))) }
            assertEquals(ErrorCode.FINGERPRINT_MISMATCH, wrong.code)
            service.removeManualPeer(peer)
            assertTrue(service.peers.value.isEmpty())
            val self = assertThrows(ShareError::class.java) { service.addManualPeer(ShareAddress("127.0.0.1", service.port!!)) }
            assertEquals(ErrorCode.BAD_REQUEST, self.code)
        } finally {
            service.stop()
            otherServer.stop()
        }
    }

    @Test
    fun `renaming persists and a peer without address is unreachable`() {
        val service = service(34)
        assertEquals("Living room", service.setName("Living room").name)
        assertEquals("Living room", service.identity.value!!.name)
        val lonely = Peer("a".repeat(32), "1".repeat(64), "x", "desktop", VersionRange.OWN, 1, emptyList(), setOf(PeerPath.MDNS))
        assertEquals(ErrorCode.UNREACHABLE, assertThrows(ShareError::class.java) { service.client(lonely) }.code)
    }
}
