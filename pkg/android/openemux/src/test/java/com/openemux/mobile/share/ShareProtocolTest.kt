package com.openemux.mobile.share

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareProtocolTest {
    @Test
    fun `versions are read as the spec says`() {
        assertEquals(VersionRange(0, 0), VersionRange.parse(null, 1))
        assertEquals(VersionRange(0, 0), VersionRange.parse("one", null))
        assertEquals(VersionRange(3, 3), VersionRange.parse(3, null))
        assertEquals(VersionRange(3, 3), VersionRange.parse("3", "5"))
        assertEquals(VersionRange(1, 2), VersionRange.parse(2, "1"))
        assertEquals(VersionRange(0, 0), VersionRange.parse(true, 1))
        assertEquals(VersionRange(0, 0), VersionRange.parse(1.5, 1))
    }

    @Test
    fun `negotiation picks the highest shared version`() {
        assertEquals(1, VersionRange(1, 5).negotiate())
        assertNull(VersionRange(2, 2).negotiate())
        assertNull(VersionRange(0, 0).negotiate())
        assertNull(VersionRange(2, 1).negotiate())
        assertTrue(VersionRange.OWN.compatible)
        assertFalse(VersionRange(0, 0).compatible)
    }

    @Test
    fun `ids and fingerprints are lowercase hex of their length`() {
        assertTrue(isDeviceId("0123456789abcdef".repeat(2)))
        assertFalse(isDeviceId("0123456789ABCDEF".repeat(2)))
        assertFalse(isDeviceId("abc"))
        assertFalse(isDeviceId(42))
        assertTrue(isFingerprint("f".repeat(64)))
        assertFalse(isFingerprint("g".repeat(64)))
    }

    @Test
    fun `names lose control characters and are cut to 64`() {
        assertEquals("Den PC", sanitizeName("  Den\u0007 PC​ "))
        assertEquals(64, sanitizeName("x".repeat(100)).length)
        assertEquals(64, sanitizeName("😀".repeat(70)).codePointCount(0, 128))
        assertEquals("", sanitizeName(12))
        assertEquals("abcdef", displayName("\u0000 ", "abcdef0123"))
        assertEquals("desktop", sanitizePlatform("fridge"))
        assertEquals("tablet", sanitizePlatform("tablet"))
        assertEquals("abcd ef01", formatFingerprint("abcdef01"))
    }

    @Test
    fun `decimals are ASCII digits only`() {
        assertEquals(53319, parseDecimal("53319"))
        assertNull(parseDecimal("²"))
        assertNull(parseDecimal("-1"))
        assertNull(parseDecimal(""))
        assertNull(parseDecimal("12345678901"))
        assertNull(toPort(0))
        assertEquals(65535, toPort(65535L))
        assertNull(toInt(Long.MAX_VALUE))
    }

    @Test
    fun `json parsing refuses what is not one object`() {
        assertEquals(1, parseJsonObject("""{"a":1}""".toByteArray())!!.getInt("a"))
        assertNull(parseJsonObject("[1]".toByteArray()))
        assertNull(parseJsonObject("""{"a":1} x""".toByteArray()))
        assertNull(parseJsonObject(byteArrayOf(0xff.toByte(), 0xfe.toByte())))
        assertNull(parseJsonObject("{".toByteArray()))
        assertNull(parseJsonObject(("[".repeat(200_000)).toByteArray()))
    }

    @Test
    fun `errors round-trip through their envelope`() {
        val error = ShareError.version(2, 3)
        assertEquals(400, error.httpStatus)
        val envelope = error.toEnvelope()
        assertEquals("incompatible_version", envelope.getString("error"))
        assertEquals("version 1 vs 3", envelope.getString("message"))
        assertEquals(1, envelope.getJSONObject("supported").getInt("max"))
        assertEquals(2, envelope.getJSONObject("peer").getInt("min"))
        val back = ShareError.fromEnvelope(400, envelope)
        assertEquals("incompatible_version", back.code)
        assertTrue(back.details.containsKey("peer"))
        assertTrue(ShareError.version(null, null).toEnvelope().getJSONObject("peer").isNull("max"))
        assertEquals(ErrorCode.INTERNAL, ShareError.fromEnvelope(502, null).code)
        assertEquals(500, ShareError(ErrorCode.FINGERPRINT_MISMATCH).httpStatus)
    }

    @Test
    fun `info answers are normalised`() {
        val id = "a".repeat(32)
        val info = PeerInfo.parse(JSONObject().put("id", id).put("name", "").put("pf", "tv").put("v", 1))
        assertEquals("aaaaaa", info.name)
        assertEquals("desktop", info.platform)
        assertTrue(info.compatible)
        assertThrows(ShareError::class.java) { PeerInfo.parse(JSONObject().put("id", "x")) }
        assertThrows(ShareError::class.java) { PeerInfo.parse(null) }
    }
}
