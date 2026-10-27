package com.openemux.mobile.share

import com.openemux.mobile.data.Console
import com.openemux.mobile.data.RomMeta
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Two Android share layers over TLS on 127.0.0.1, each with its own library
 * in a temp folder: a game goes from one to the other and lands in the right
 * folders, byte for byte, with its mtimes.
 */
class ShareTransferLoopbackTest {
    private val loopback = InetSocketAddress(InetAddress.getLoopbackAddress(), 0)
    private val senderLib = FakeLibrary()
    private val receiverLib = FakeLibrary()
    private lateinit var sender: ShareService
    private lateinit var receiver: ShareService

    private object NoLock : MulticastLockHandle {
        override val isHeld = false
        override fun acquire() = Unit
        override fun release() = Unit
    }

    private fun service(name: String) = ShareService(
        34,
        { IdentityStore(Files.createTempDirectory("share-id").toFile(), FakeKeyBackend()) { IdentityStore.Defaults(name, "phone") } },
        { ownId, onPeers -> Discovery(NoLock, emptyList(), PeerList(ownId, onChange = onPeers)) },
        { ShareServer(it, preferredPort = 0, bindAddress = loopback) },
    )

    @Before
    fun setUp() {
        sender = service("Phone")
        receiver = service("Tablet")
        receiver.start()
        for (lib in listOf(senderLib, receiverLib)) {
            lib.installCore(Console.SFC.core("snes9x"), "Snes9x", "1.62.3")
            lib.installCore(Console.PS.core("mednafen_psx"), "Beetle PSX", "0.9.44.1")
        }
        senderLib.installCore(Console.SFC.core("bsnes"), "bsnes", "115")
        receiverLib.installCore(Console.SFC.core("bsnes"), "bsnes", "116")
    }

    @After
    fun tearDown() {
        receiver.stop()
    }

    private fun client() = ShareClient("127.0.0.1", receiver.port!!, receiver.identity.value!!.fingerprint)

    private fun awaitOffer(): IncomingOffer {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            receiver.incoming.value?.let { return it }
            Thread.sleep(10)
        }
        throw AssertionError("no offer arrived")
    }

    private class Run(val sent: SendResult, val received: ReceiveResult)

    /** Offers [built]; the receiver accepts its default plan. */
    private fun transfer(built: BuiltManifest): Run {
        val sendDone = CountDownLatch(1)
        val receiveDone = CountDownLatch(1)
        val sent = AtomicReference<SendResult>()
        val received = AtomicReference<ReceiveResult>()
        sender.send(client(), built, onDone = { sent.set(it); sendDone.countDown() })
        val offer = awaitOffer()
        val plan = receiver.prepareReceive(offer, receiverLib)
        receiver.accept(offer.offerId, plan, receiverLib, onDone = { received.set(it); receiveDone.countDown() })
        assertTrue(sendDone.await(20, TimeUnit.SECONDS))
        assertTrue(receiveDone.await(20, TimeUnit.SECONDS))
        return Run(sent.get(), received.get())
    }

    private fun same(from: File, to: File) {
        assertTrue("$to is missing", to.isFile)
        assertArrayEquals(from.name, from.readBytes(), to.readBytes())
        assertEquals(from.name, from.lastModified() / 1000 * 1000, to.lastModified())
    }

    @Test
    fun `a game with its save, states, art and a disc unit goes from one device to the other`() {
        val s = senderLib.paths
        val rom = senderLib.rom(Console.SFC, "Game (USA).sfc", ByteArray(700 * 1024) { (it % 13).toByte() }, mtime = 1_700_000_001)
        val save = File(s.saves(Console.SFC), "Game (USA).srm").put("save v1", mtime = 1_700_000_002)
        val state = File(s.states(Console.SFC), "Game (USA).state1").put("state", mtime = 1_700_000_003)
        val label = File(s.labels(Console.SFC), "Game (USA).png").put(PNG, mtime = 1_700_000_004)
        val cover = File(s.covers(Console.SFC), "Game (USA).png").put(PNG, mtime = 1_700_000_005)
        senderLib.meta[rom.key] = RomMeta(favorite = true, shader = "zfast")
        // Made with bsnes 115; the receiver has 116: never requested.
        val other = senderLib.rom(Console.SFC, "Other.sfc")
        senderLib.meta[other.key] = RomMeta(core = "bsnes")
        File(s.states(Console.SFC), "Other.state").put("bsnes state")
        val track1 = senderLib.rom(Console.PS, "Disc (Track 1).bin", ByteArray(5000) { 7 }, mtime = 1_700_000_010)
        val track2 = senderLib.rom(Console.PS, "Disc (Track 2).bin", ByteArray(3000) { 8 }, mtime = 1_700_000_011)
        val cue = senderLib.rom(Console.PS, "Disc.cue", "FILE \"Disc (Track 1).bin\" BINARY\nFILE \"Disc (Track 2).bin\" BINARY\n".toByteArray())

        val built = sender.buildManifest(senderLib, listOf(rom, other, cue))
        // What the builder makes is what the session layer parses.
        ManifestParser.parse(built.json)
        val run = transfer(built)

        assertEquals(SendResult.DONE, run.sent.status)
        assertEquals(TransferStatus.DONE, run.received.status)
        val r = receiverLib.paths
        same(rom.file, File(r.roms(Console.SFC), "Game (USA).sfc"))
        same(save, File(r.saves(Console.SFC), "Game (USA).srm"))
        same(state, File(r.states(Console.SFC), "Game (USA).state1"))
        same(label, File(r.labels(Console.SFC), "Game (USA).png"))
        same(cover, File(r.covers(Console.SFC), "Game (USA).png"))
        same(other.file, File(r.roms(Console.SFC), "Other.sfc"))
        for (part in listOf(cue.file, track1.file, track2.file)) same(part, File(r.roms(Console.PS), part.name))
        assertFalse(File(r.states(Console.SFC), "Other.state").exists())

        val incompatible = built.json.getJSONArray("games").getJSONObject(1).getJSONArray("items").getJSONObject(1).getString("id")
        assertTrue(incompatible !in run.sent.sent)
        assertTrue(incompatible to "incompatible_state" in run.sent.skipped)
        assertTrue(incompatible to "incompatible_state" in run.received.skipped)
        assertEquals(run.sent.sent.size, run.received.placed.size)
        assertEquals(RomMeta(favorite = true, shader = "zfast"), receiverLib.meta["SFC/Game (USA).sfc"])
        assertEquals(RomMeta(core = "bsnes"), receiverLib.meta["SFC/Other.sfc"])
        assertEquals(listOf(run.received.placed), receiverLib.received)

        // Again: the ROMs are there, the save is the same age; then a newer save replaces it.
        val again = transfer(sender.buildManifest(senderLib, listOf(rom)))
        assertEquals(TransferStatus.DONE, again.received.status)
        assertTrue(again.sent.sent.isEmpty())
        assertTrue(again.sent.skipped.all { it.second == "exists_skip" })
        save.put("save v2", mtime = 1_700_000_900)
        val newer = transfer(sender.buildManifest(senderLib, listOf(rom)))
        assertEquals(listOf(File(r.saves(Console.SFC), "Game (USA).srm")), newer.received.replaced)
        same(save, File(r.saves(Console.SFC), "Game (USA).srm"))
    }

    @Test
    fun `accepting without room refuses before anything is answered`() {
        val rom = senderLib.rom(Console.SFC, "Big.sfc", ByteArray(10))
        val built = sender.buildManifest(senderLib, listOf(rom))
        val client = client()
        val offerId = client.offer(OfferSender("a".repeat(32), "Phone", "phone", "b".repeat(64)), built.json).offerId
        val offer = awaitOffer()
        val plan = planReceive(offer.manifest, receiverLib.paths, probe = FakeSpace(default = 5))
        val error = assertThrows(ShareError::class.java) { receiver.accept(offerId, plan, receiverLib) }
        assertEquals(ErrorCode.TOO_LARGE, error.code)
        val store = (error.details["stores"] as JSONArray).getJSONObject(0)
        assertEquals(10, store.getLong("needed"))
        assertEquals(5, store.getLong("free"))
        assertEquals("pending", client.poll(offerId).getString("status"))
        assertNull(receiver.transfer(offerId))
        assertFalse(File(receiverLib.paths.roms(Console.SFC), "Big.sfc").exists())

        val unknown = planReceive(offer.manifest, receiverLib.paths, probe = FakeSpace())
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(ShareError::class.java) { receiver.accept("nope", unknown, receiverLib) }.code)
        assertNull(receiver.transfer("nope"))
    }

    @Test
    fun `an upload with no transfer behind it is not implemented`() {
        val rom = senderLib.rom(Console.SFC, "G.sfc", "rom".toByteArray())
        val built = sender.buildManifest(senderLib, listOf(rom))
        val client = client()
        val offerId = client.offer(OfferSender("a".repeat(32), "Phone", "phone", "b".repeat(64)), built.json).offerId
        awaitOffer()
        receiver.accept(offerId, listOf(ItemAnswer.send("g1i1")))
        val error = assertThrows(ShareError::class.java) { client.putFile(offerId, "g1i1", rom.file, 3) {} }
        assertEquals(ErrorCode.NOT_IMPLEMENTED, error.code)
    }

    @Test
    fun `a cancel from either side, or stopping, ends the receive and leaves nothing`() {
        val rom = senderLib.rom(Console.SFC, "G.sfc", "rom".toByteArray())
        File(senderLib.paths.saves(Console.SFC), "G.srm").put("save")
        val built = sender.buildManifest(senderLib, listOf(rom))
        for (how in listOf("sender", "receiver", "stop")) {
            if (!receiver.running.value) receiver.start()
            val client = client()
            val offerId = client.offer(OfferSender("a".repeat(32), "Phone", "phone", "b".repeat(64)), built.json).offerId
            val offer = awaitOffer()
            val transfer = receiver.accept(offerId, receiver.prepareReceive(offer, receiverLib), receiverLib)
            client.putFile(offerId, "g1i1", rom.file, 3) {}
            when (how) {
                "sender" -> client.cancel(offerId)
                "receiver" -> receiver.cancel(offerId)
                else -> receiver.stop()
            }
            assertEquals(how, TransferStatus.CANCELLED, transfer.status.value)
            assertNull(receiver.transfer(offerId))
            assertFalse(how, File(receiverLib.paths.roms(Console.SFC), "G.sfc").exists())
            assertTrue(how, receiverLib.paths.base.walk().none { it.name.endsWith(".tmp") })
        }
    }

    @Test
    fun `a receive that fails cancels the offer for the sender`() {
        val rom = senderLib.rom(Console.SFC, "G.sfc", "rom".toByteArray())
        val built = sender.buildManifest(senderLib, listOf(rom))
        val client = client()
        val offerId = client.offer(OfferSender("a".repeat(32), "Phone", "phone", "b".repeat(64)), built.json).offerId
        val offer = awaitOffer()
        val transfer = receiver.accept(offerId, receiver.prepareReceive(offer, receiverLib), receiverLib)
        rom.file.put("bad")
        // The bad hash is answered on the upload itself, then the offer is over.
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(ShareError::class.java) { client.putFile(offerId, "g1i1", rom.file, 3) {} }.code)
        assertEquals(TransferStatus.FAILED, transfer.status.value)
        assertEquals("cancelled", client.poll(offerId).getString("status"))
    }

    @Test
    fun `a file shorter than offered is source_changed on the sending side`() {
        val rom = senderLib.rom(Console.SFC, "G.sfc", "rom".toByteArray())
        val built = sender.buildManifest(senderLib, listOf(rom))
        val client = client()
        val offerId = client.offer(OfferSender("a".repeat(32), "Phone", "phone", "b".repeat(64)), built.json).offerId
        val offer = awaitOffer()
        receiver.accept(offerId, receiver.prepareReceive(offer, receiverLib), receiverLib)
        assertEquals(ErrorCode.SOURCE_CHANGED, assertThrows(ShareError::class.java) { client.putFile(offerId, "g1i1", rom.file, 10) {} }.code)
        rom.file.delete()
        assertEquals(ErrorCode.SOURCE_CHANGED, assertThrows(ShareError::class.java) { client.putFile(offerId, "g1i1", rom.file, 3) {} }.code)
    }
}
