package com.openemux.mobile.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class NsdTxtTest {
    private val id = "abcdef".padEnd(32, '0')
    private val fp = "1".repeat(64)
    private val announcement = Announcement(id, "Den PC", "phone", VersionRange(1, 1), 53319, fp)

    private fun txt(vararg overrides: Pair<String, String?>): Map<String, ByteArray?> =
        (NsdTxt.attributes(announcement) + overrides).mapValues { it.value?.toByteArray() }

    @Test
    fun `attributes round-trip`() {
        val attributes = NsdTxt.attributes(announcement)
        assertEquals(setOf("id", "name", "pf", "v", "vmin", "fp"), attributes.keys)
        assertEquals("1", attributes["v"])
        assertEquals(announcement, NsdTxt.parse(txt(), 53319))
    }

    @Test
    fun `records without a valid id, fp or port are ignored`() {
        assertNull(NsdTxt.parse(txt("id" to "nope"), 53319))
        assertNull(NsdTxt.parse(txt("fp" to null), 53319))
        assertNull(NsdTxt.parse(txt(), 0))
        assertNull(NsdTxt.parse(txt("id" to null), 53319))
        assertNull(NsdTxt.parse(txt() + ("fp" to byteArrayOf(0xff.toByte())), 53319))
    }

    @Test
    fun `missing or odd versions are listed as incompatible`() {
        val noVersion = NsdTxt.parse(txt("v" to null, "extra" to "x"), 53319)!!
        assertEquals(VersionRange(0, 0), noVersion.versions)
        assertFalse(noVersion.compatible)
        assertEquals(VersionRange(2, 2), NsdTxt.parse(txt("v" to "2", "vmin" to "9"), 53319)!!.versions)
    }

    @Test
    fun `the instance name is cut to 63 UTF-8 bytes on a character boundary`() {
        assertEquals("Den PC (abcdef)", NsdTxt.instanceName("Den PC", id))
        val long = NsdTxt.instanceName("ü".repeat(40), id)
        assertEquals(62, long.toByteArray().size)
        assertEquals("ü".repeat(31), long)
        val emoji = NsdTxt.instanceName("a" + "😀".repeat(20), id)
        assertEquals(61, emoji.toByteArray().size)
    }
}
