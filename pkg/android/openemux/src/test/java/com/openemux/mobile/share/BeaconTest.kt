package com.openemux.mobile.share

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class BeaconTest {
    private val id = "a".repeat(32)
    private val fp = "1".repeat(64)
    private val announcement = Announcement(id, "Den PC", "desktop", VersionRange(1, 1), 53319, fp)

    private fun hello(edit: JSONObject.() -> Unit = {}) =
        JSONObject(String(BeaconCodec.encode(false, announcement))).apply(edit).toString().toByteArray()

    @Test
    fun `hello and bye round-trip`() {
        assertEquals(BeaconMessage(false, announcement), BeaconCodec.parse(BeaconCodec.encode(false, announcement)))
        assertEquals(BeaconMessage(true, announcement), BeaconCodec.parse(BeaconCodec.encode(true, announcement)))
        val json = JSONObject(String(BeaconCodec.encode(false, announcement)))
        assertEquals("openemux-share", json.getString("p"))
        assertEquals(1, json.getInt("v"))
    }

    @Test
    fun `every drop rule drops`() {
        val padded = hello { put("pad", "x".repeat(1100)) }
        assertNull(BeaconCodec.parse(padded))
        assertNull(BeaconCodec.parse("not json".toByteArray()))
        assertNull(BeaconCodec.parse("[1]".toByteArray()))
        assertNull(BeaconCodec.parse(hello { put("p", "localsend") }))
        assertNull(BeaconCodec.parse(hello { put("t", "ping") }))
        assertNull(BeaconCodec.parse(hello { put("id", "xyz") }))
        assertNull(BeaconCodec.parse(hello { remove("fp") }))
        assertNull(BeaconCodec.parse(hello { put("port", 0) }))
        assertNull(BeaconCodec.parse(hello { put("port", 70000) }))
        assertNull(BeaconCodec.parse(hello { put("port", "http") }))
    }

    @Test
    fun `unknown keys are ignored and versions read as in mdns`() {
        val message = BeaconCodec.parse(hello { put("extra", 1); remove("v"); put("name", "") })!!
        assertEquals(VersionRange(0, 0), message.announcement.versions)
        assertFalse(message.announcement.compatible)
        assertEquals("aaaaaa", message.announcement.name)
    }

    @Test
    fun `the size limit applies to the received length`() {
        val data = hello().copyOf(2048)
        assertTrue(BeaconCodec.parse(data, hello().size) != null)
        assertNull(BeaconCodec.parse(data))
    }

    /** A socket that records what is sent and hands out queued datagrams. */
    private class FakeSocket : BeaconSocket {
        val sent = LinkedBlockingQueue<ByteArray>()
        val inbox = LinkedBlockingQueue<Pair<ByteArray, String>>()
        @Volatile var closed = false

        override fun sendToAll(data: ByteArray) {
            sent += data
        }

        override fun receive(): Pair<ByteArray, String>? {
            if (closed) throw IOException("closed")
            return inbox.poll(20, TimeUnit.MILLISECONDS)
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `the transport says hello, feeds the list from the source address and says bye`() {
        val socket = FakeSocket()
        val transport = BeaconTransport({ socket }, intervalMs = 10)
        val peers = PeerList("0".repeat(32))
        transport.start(announcement.copy(id = "0".repeat(32)), peers)
        transport.start(announcement, peers)
        val first = BeaconCodec.parse(socket.sent.poll(2, TimeUnit.SECONDS)!!)!!
        assertFalse(first.bye)
        val other = Announcement("b".repeat(32), "Phone", "phone", VersionRange(1, 1), 4000, "2".repeat(64))
        socket.inbox += BeaconCodec.encode(false, other) to "192.168.0.7"
        socket.inbox += "garbage".toByteArray() to "192.168.0.8"
        waitFor { peers.peers().isNotEmpty() }
        assertEquals(listOf("192.168.0.7"), peers.peers().single().addresses)
        socket.inbox += BeaconCodec.encode(true, other) to "192.168.0.7"
        waitFor { peers.peers().isEmpty() }
        transport.stop()
        transport.stop()
        assertTrue(socket.closed)
        val last = socket.sent.toList().last()
        assertTrue(BeaconCodec.parse(last)!!.bye)
    }

    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 2_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(5)
        }
    }
}
