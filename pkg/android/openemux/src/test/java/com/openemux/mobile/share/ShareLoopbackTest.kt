package com.openemux.mobile.share

import com.openemux.mobile.share.http.HttpCodec
import com.openemux.mobile.share.http.HttpResponse
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/** A real Android-side server and client over TLS 1.3 on 127.0.0.1. */
class ShareLoopbackTest {
    private val loopback = InetSocketAddress(InetAddress.getLoopbackAddress(), 0)
    private val receiver = TestCerts.identity(name = "Den tablet", platform = "tablet")
    private val sender = TestCerts.identity(name = "Phone")
    private val asSender = OfferSender(sender.id, sender.name, sender.platform, sender.fingerprint)
    private lateinit var server: ShareServer
    private var port = 0

    private val manifest = JSONObject(
        """{"games":[{"id":"g1","console":"GBA","title":"Game","items":[
            {"id":"i1","kind":"rom","name":"Game.gba","size":4,"mtime":1},
            {"id":"i2","kind":"battery_save","name":"Game.srm","size":2,"mtime":1}]}],
            "total_size":6,"file_count":2}"""
    )

    @Before
    fun setUp() {
        server = ShareServer(receiver, preferredPort = 0, bindAddress = loopback)
        port = server.start()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private fun client(fp: String? = receiver.fingerprint) = ShareClient("127.0.0.1", port, fp)

    /** One raw request on a TLS 1.3 socket that trusts anything, for what the client never sends. */
    private fun raw(head: String, body: ByteArray = ByteArray(0), protocols: Array<String> = arrayOf("TLSv1.3")): HttpResponse {
        val socket = Tls.clientContext(PinningTrustManager(null)).socketFactory.createSocket("127.0.0.1", port) as SSLSocket
        socket.use {
            it.enabledProtocols = protocols
            it.soTimeout = 5_000
            it.startHandshake()
            it.outputStream.write(head.toByteArray(Charsets.ISO_8859_1))
            it.outputStream.write(body)
            it.outputStream.flush()
            return HttpCodec.readResponse(BufferedInputStream(it.inputStream))
        }
    }

    @Test
    fun `client and server agree on the code`() {
        val heard = AtomicReference<IncomingOffer>()
        server.listener = object : IncomingOfferListener {
            override fun onOffer(offer: IncomingOffer) = heard.set(offer)
        }
        val info = client().info()
        assertEquals(receiver.id, info.id)
        assertEquals("tablet", info.platform)
        val outgoing = client().offer(asSender, manifest)
        val incoming = heard.get()
        assertEquals(outgoing.offerId, incoming.offerId)
        assertEquals(outgoing.code, incoming.code)
        assertEquals(sender.fingerprint, incoming.sender.fp)
        assertEquals("pending", client().poll(outgoing.offerId).getString("status"))
        server.accept(outgoing.offerId, listOf(ItemAnswer.send("i1"), ItemAnswer.skip("i2", "exists_skip")))
        val poll = client().poll(outgoing.offerId)
        assertEquals("accepted", poll.getString("status"))
        assertEquals(2, poll.getJSONArray("items").length())
    }

    @Test
    fun `a wrong fingerprint is refused in the handshake before any HTTP byte`() {
        val error = assertThrows(ShareError::class.java) { client("0".repeat(64)).info() }
        assertEquals(ErrorCode.FINGERPRINT_MISMATCH, error.code)
        assertEquals(0, server.requestCount.get())
        client().info()
        assertEquals(1, server.requestCount.get())
    }

    @Test
    fun `trust on first use records the fingerprint it saw`() {
        val client = client(null)
        assertNull(client.peerFingerprint)
        client.info()
        assertEquals(receiver.fingerprint, client.peerFingerprint)
    }

    @Test
    fun `a request without a usable version is refused, but info always answers`() {
        val missing = raw("GET /offer/x HTTP/1.1\r\nConnection: close\r\n\r\n")
        assertEquals(400, missing.status)
        val body = missing.json()!!
        assertEquals("incompatible_version", body.getString("error"))
        assertEquals(1, body.getJSONObject("supported").getInt("min"))
        assertTrue(body.getJSONObject("peer").isNull("min"))
        val future = raw("GET /offer/x HTTP/1.1\r\nOpenEmux-Share-Version: 2\r\n\r\n").json()!!
        assertEquals(2, future.getJSONObject("peer").getInt("max"))
        val info = raw("GET /info HTTP/1.1\r\nOpenEmux-Share-Version: 9\r\n\r\n")
        assertEquals(200, info.status)
        assertEquals("1", info.header(ShareProtocol.VERSION_HEADER))
        assertEquals(receiver.id, info.json()!!.getString("id"))
    }

    @Test
    fun `a TLS 1_2 client is refused`() {
        assertThrows(SSLException::class.java) { raw("GET /info HTTP/1.1\r\n\r\n", protocols = arrayOf("TLSv1.2")) }
    }

    @Test
    fun `every other path is not found and oversized bodies are too large`() {
        assertEquals(404, raw("GET /nope HTTP/1.1\r\nOpenEmux-Share-Version: 1\r\n\r\n").status)
        val big = raw("POST /offer HTTP/1.1\r\nOpenEmux-Share-Version: 1\r\nContent-Length: 1048577\r\n\r\n", ByteArray(1048577))
        assertEquals(413, big.status)
        assertEquals("too_large", big.json()!!.getString("error"))
        assertEquals(400, raw("BROKEN\r\n\r\n").status)
    }

    @Test
    fun `server errors reach the client with their code`() {
        val outgoing = client().offer(asSender, manifest)
        val busy = assertThrows(ShareError::class.java) { client().offer(asSender, manifest) }
        assertEquals(ErrorCode.BUSY, busy.code)
        client().cancel(outgoing.offerId)
        assertEquals("cancelled", client().poll(outgoing.offerId).getString("status"))
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(ShareError::class.java) { client().cancel("x".repeat(22)) }.code)
    }

    @Test
    fun `put file answers 501 after its state checks`() {
        val outgoing = client().offer(asSender, manifest)
        val early = raw("PUT /file/${outgoing.offerId}/i1 HTTP/1.1\r\nOpenEmux-Share-Version: 1\r\nContent-Length: 4\r\n\r\n", byteArrayOf(1, 2, 3, 4))
        assertEquals(400, early.status)
        server.accept(outgoing.offerId, listOf(ItemAnswer.send("i1")))
        val put = raw("PUT /file/${outgoing.offerId}/i1 HTTP/1.1\r\nOpenEmux-Share-Version: 1\r\nContent-Length: 4\r\n\r\n", byteArrayOf(1, 2, 3, 4))
        assertEquals(501, put.status)
        assertEquals("not_implemented", put.json()!!.getString("error"))
        server.cancel(outgoing.offerId)
        val cancelled = raw("PUT /file/${outgoing.offerId}/i1 HTTP/1.1\r\nOpenEmux-Share-Version: 1\r\nContent-Length: 4\r\n\r\n", byteArrayOf(1, 2, 3, 4))
        assertEquals(410, cancelled.status)
        assertEquals("cancelled", cancelled.json()!!.getString("error"))
    }

    @Test
    fun `a file receiver gets the body once`() {
        val received = AtomicReference<ByteArray>()
        server.fileReceiver = FileReceiver { _, item, body ->
            assertEquals("i1", item.id)
            received.set(body.readBytes())
        }
        val outgoing = client().offer(asSender, manifest)
        server.accept(outgoing.offerId, listOf(ItemAnswer.send("i1")))
        val head = "PUT /file/${outgoing.offerId}/i1 HTTP/1.1\r\nOpenEmux-Share-Version: 1\r\nContent-Length: 4\r\n\r\n"
        assertEquals(204, raw(head, byteArrayOf(9, 8, 7, 6)).status)
        assertEquals(listOf<Byte>(9, 8, 7, 6), received.get().toList())
        assertEquals(400, raw(head, byteArrayOf(9, 8, 7, 6)).status)
    }

    @Test
    fun `a receiver cancel cuts an upload in progress`() {
        val started = CountDownLatch(1)
        server.fileReceiver = FileReceiver { _, _, body ->
            started.countDown()
            body.readBytes()
        }
        val outgoing = client().offer(asSender, manifest)
        server.accept(outgoing.offerId, listOf(ItemAnswer.send("i1")))
        val socket = Tls.clientContext(PinningTrustManager(null)).socketFactory.createSocket("127.0.0.1", port) as SSLSocket
        socket.use {
            it.soTimeout = 5_000
            it.outputStream.write("PUT /file/${outgoing.offerId}/i1 HTTP/1.1\r\nOpenEmux-Share-Version: 1\r\nContent-Length: 4\r\n\r\n\u0001".toByteArray())
            it.outputStream.flush()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            server.cancel(outgoing.offerId)
            val read = try { it.inputStream.read() } catch (_: java.io.IOException) { -1 }
            assertEquals(-1, read)
        }
    }

    @Test
    fun `a receiver that fails is an internal error and the server keeps serving`() {
        server.fileReceiver = FileReceiver { _, _, _ -> throw IllegalStateException("disk on fire") }
        val outgoing = client().offer(asSender, manifest)
        server.accept(outgoing.offerId, listOf(ItemAnswer.send("i1")))
        val put = raw("PUT /file/${outgoing.offerId}/i1 HTTP/1.1\r\nOpenEmux-Share-Version: 1\r\nContent-Length: 4\r\n\r\n", byteArrayOf(1, 2, 3, 4))
        assertEquals(500, put.status)
        assertEquals("internal", put.json()!!.getString("error"))
        client().info()
    }

    @Test
    fun `the sender's cancel reaches the listener`() {
        val cancelled = AtomicReference<String>()
        server.listener = object : IncomingOfferListener {
            override fun onOffer(offer: IncomingOffer) = Unit
            override fun onCancelled(offerId: String) = cancelled.set(offerId)
        }
        val outgoing = client().offer(asSender, manifest)
        client().cancel(outgoing.offerId)
        assertEquals(outgoing.offerId, cancelled.get())
    }

    @Test
    fun `nothing listening is unreachable`() {
        val free = ServerSocket(0).use { it.localPort }
        assertEquals(ErrorCode.UNREACHABLE, assertThrows(ShareError::class.java) { ShareClient("127.0.0.1", free, null).info() }.code)
    }

    @Test
    fun `the preferred port falls back to a free one when taken, and stop frees it`() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { taken ->
            val other = ShareServer(receiver, preferredPort = taken.localPort, bindAddress = loopback)
            val otherPort = other.start()
            assertTrue(otherPort != taken.localPort)
            assertEquals(otherPort, other.start())
            other.stop()
            other.stop()
            ServerSocket(otherPort, 50, InetAddress.getLoopbackAddress()).close()
        }
        repeat(3) {
            server.stop()
            port = server.start()
            client().info()
        }
    }
}
