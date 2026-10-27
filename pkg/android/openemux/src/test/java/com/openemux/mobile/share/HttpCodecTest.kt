package com.openemux.mobile.share

import com.openemux.mobile.share.http.BoundedInputStream
import com.openemux.mobile.share.http.HttpCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException

class HttpCodecTest {
    private fun input(text: String) = ByteArrayInputStream(text.toByteArray(Charsets.ISO_8859_1))

    @Test
    fun `a request round-trips and keep-alive reads the next one`() {
        val out = ByteArrayOutputStream()
        HttpCodec.writeRequest(out, "POST", "/offer", mapOf("OpenEmux-Share-Version" to "1"), """{"a":1}""".toByteArray())
        HttpCodec.writeRequest(out, "GET", "/info?x=1", emptyMap(), null)
        val stream = ByteArrayInputStream(out.toByteArray())
        val first = HttpCodec.readRequest(stream)!!
        assertEquals("POST", first.method)
        assertEquals("/offer", first.path)
        assertEquals("1", first.header("openemux-share-version"))
        assertEquals(1, first.readJson().getInt("a"))
        val second = HttpCodec.readRequest(stream)!!
        assertEquals("/info", second.path)
        assertEquals(0, second.contentLength)
        assertNull(HttpCodec.readRequest(stream))
    }

    @Test
    fun `a response round-trips`() {
        val out = ByteArrayOutputStream()
        HttpCodec.writeResponse(out, 201, mapOf("Content-Type" to "application/json"), """{"ok":true}""".toByteArray())
        HttpCodec.writeResponse(out, 299, emptyMap(), null)
        val stream = ByteArrayInputStream(out.toByteArray())
        val response = HttpCodec.readResponse(stream)
        assertEquals(201, response.status)
        assertEquals("application/json", response.header("Content-Type"))
        assertTrue(response.json()!!.getBoolean("ok"))
        val empty = HttpCodec.readResponse(stream)
        assertEquals(299, empty.status)
        assertNull(empty.json())
        assertTrue(String(out.toByteArray()).contains("HTTP/1.1 299 Unknown"))
    }

    @Test
    fun `headers are capped at 8 KiB`() {
        val big = "GET /info HTTP/1.1\r\nX: ${"a".repeat(9000)}\r\n\r\n"
        val error = assertThrows(ShareError::class.java) { HttpCodec.readRequest(input(big)) }
        assertEquals(ErrorCode.BAD_REQUEST, error.code)
    }

    @Test
    fun `malformed heads are refused`() {
        for (text in listOf(
            "GET /info\r\n\r\n",
            "GET /info SPDY/3\r\n\r\n",
            "GET /info HTTP/1.1\r\nno colon\r\n\r\n",
            "PUT /f HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n",
            "PUT /f HTTP/1.1\r\nContent-Length: -5\r\n\r\n",
            "PUT /f HTTP/1.1\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\n",
        )) {
            assertThrows(text, ShareError::class.java) { HttpCodec.readRequest(input(text)) }
        }
        assertThrows(EOFException::class.java) { HttpCodec.readRequest(input("GET /info HTTP/1.1\r\n")) }
        assertThrows(IOException::class.java) { HttpCodec.readResponse(input("SPDY 200\r\n\r\n")) }
        assertThrows(EOFException::class.java) { HttpCodec.readResponse(input("")) }
        assertThrows(EOFException::class.java) { HttpCodec.readResponse(input("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nab")) }
        assertThrows(IOException::class.java) { HttpCodec.readResponse(input("HTTP/1.1 200 OK\r\nContent-Length: 50\r\n\r\n"), maxBody = 10) }
    }

    @Test
    fun `a JSON body over 1 MiB is too large and never read`() {
        val stream = input("POST /offer HTTP/1.1\r\nContent-Length: 2000000\r\n\r\n{")
        val request = HttpCodec.readRequest(stream)!!
        val error = assertThrows(ShareError::class.java) { request.readJson() }
        assertEquals(ErrorCode.TOO_LARGE, error.code)
        assertEquals(1, stream.available())
        assertFalse(request.body.discard(1024))
    }

    @Test
    fun `a body that is not an object or is cut short fails`() {
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(ShareError::class.java) {
            HttpCodec.readRequest(input("POST /o HTTP/1.1\r\nContent-Length: 3\r\n\r\n[1]"))!!.readJson()
        }.code)
        assertThrows(EOFException::class.java) {
            HttpCodec.readRequest(input("POST /o HTTP/1.1\r\nContent-Length: 30\r\n\r\n{}"))!!.readJson()
        }
    }

    @Test
    fun `the bounded stream stops at its limit and discards the rest`() {
        val source = ByteArrayInputStream(ByteArray(100) { it.toByte() })
        val bounded = BoundedInputStream(source, 10)
        assertEquals(0, bounded.read())
        val buffer = ByteArray(50)
        assertEquals(9, bounded.read(buffer, 0, 50))
        assertEquals(-1, bounded.read())
        assertEquals(-1, bounded.read(buffer, 0, 50))
        assertEquals(90, source.available())
        val rest = BoundedInputStream(source, 20)
        assertEquals(20, rest.available())
        assertTrue(rest.discard(100))
        assertEquals(0, rest.remaining)
        assertEquals(70, source.available())
        val short = BoundedInputStream(ByteArrayInputStream(byteArrayOf(1)), 5)
        assertArrayEquals(byteArrayOf(1), short.readBytes())
        assertEquals(-1, BoundedInputStream(ByteArrayInputStream(ByteArray(0)), 5).read())
        short.close()
    }
}
