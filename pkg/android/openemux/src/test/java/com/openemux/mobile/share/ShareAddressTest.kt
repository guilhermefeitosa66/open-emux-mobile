package com.openemux.mobile.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ShareAddressTest {
    private val fp = "ab".repeat(32)

    @Test
    fun `every typed form parses`() {
        assertEquals(ShareAddress("192.168.0.5"), ShareAddress.parse("192.168.0.5"))
        assertEquals(ShareAddress("192.168.0.5", 1234), ShareAddress.parse(" 192.168.0.5:1234 "))
        assertEquals(ShareAddress("fe80::1", 53319), ShareAddress.parse("[fe80::1]:53319"))
        assertEquals(ShareAddress("fe80::1"), ShareAddress.parse("[fe80::1]"))
        assertEquals(ShareAddress("fe80::1"), ShareAddress.parse("fe80::1"))
        assertEquals(ShareAddress("den-pc.local", 53319, fp), ShareAddress.parse("OPENEMUX-SHARE://den-pc.local:53319/#$fp"))
    }

    @Test
    fun `the fingerprint ignores spaces, colons and case`() {
        val grouped = formatFingerprint(fp).uppercase().replaceFirst(" ", ":")
        assertEquals(fp, ShareAddress.parse("openemux-share://10.0.0.2#$grouped").fp)
        assertEquals(null, ShareAddress.parse("10.0.0.2#").fp)
    }

    @Test
    fun `invalid input is a bad request`() {
        for (text in listOf("", "10.0.0.2:0", "10.0.0.2:70000", "10.0.0.2:x", "[fe80::1", "[fe80::1]x", "a b", "user@host", "10.0.0.2#abc")) {
            val error = assertThrows(text, ShareError::class.java) { ShareAddress.parse(text) }
            assertEquals(ErrorCode.BAD_REQUEST, error.code)
        }
    }

    @Test
    fun `format always carries every part and round-trips`() {
        val v4 = ShareAddress("192.168.0.5", 53319, fp)
        assertEquals("openemux-share://192.168.0.5:53319#$fp", v4.format())
        val v6 = ShareAddress("fe80::1", 1234, fp)
        assertEquals("openemux-share://[fe80::1]:1234#$fp", v6.format())
        assertEquals(v6, ShareAddress.parse(v6.format()))
        assertEquals("openemux-share://h:53319", ShareAddress("h").format())
    }
}
