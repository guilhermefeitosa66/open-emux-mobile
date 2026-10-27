package com.openemux.mobile.share

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareFormatTest {
    @Test
    fun `sizes are decimal, whole up to kB and one decimal from MB up`() {
        assertEquals(SizeText("0", SizeUnit.B), ShareFormat.formatSize(-5))
        assertEquals(SizeText("999", SizeUnit.B), ShareFormat.formatSize(999))
        assertEquals(SizeText("1", SizeUnit.KB), ShareFormat.formatSize(1_000))
        assertEquals(SizeText("1000", SizeUnit.KB), ShareFormat.formatSize(999_999))
        assertEquals(SizeText("1.5", SizeUnit.MB), ShareFormat.formatSize(1_500_000))
        assertEquals(SizeText("3.1", SizeUnit.MB), ShareFormat.formatSize(3_080_000))
        assertEquals(SizeText("2.3", SizeUnit.GB), ShareFormat.formatSize(2_300_000_000))
    }

    @Test
    fun `paths come in a fixed order whatever the set`() {
        assertEquals(listOf(PeerPath.MDNS, PeerPath.BEACON, PeerPath.MANUAL), ShareFormat.orderedPaths(setOf(PeerPath.MANUAL, PeerPath.BEACON, PeerPath.MDNS)))
        assertEquals(listOf(PeerPath.BEACON), ShareFormat.orderedPaths(setOf(PeerPath.BEACON)))
        assertEquals(emptyList<PeerPath>(), ShareFormat.orderedPaths(emptySet()))
    }

    @Test
    fun `an unknown platform is a desktop`() {
        assertEquals("phone", ShareFormat.platformOf("phone"))
        assertEquals("tablet", ShareFormat.platformOf("tablet"))
        assertEquals("desktop", ShareFormat.platformOf("toaster"))
        assertEquals("desktop", ShareFormat.platformOf(null))
    }

    @Test
    fun `every layer code has its message and the rest is generic`() {
        val expected = mapOf(
            ErrorCode.DECLINED to ShareMessage.DECLINED, ErrorCode.EXPIRED to ShareMessage.EXPIRED,
            ErrorCode.CANCELLED to ShareMessage.CANCELLED, ErrorCode.BUSY to ShareMessage.BUSY,
            ErrorCode.FINGERPRINT_MISMATCH to ShareMessage.FINGERPRINT_MISMATCH,
            ErrorCode.INCOMPATIBLE_VERSION to ShareMessage.INCOMPATIBLE_VERSION,
            ErrorCode.TOO_LARGE to ShareMessage.TOO_LARGE, ErrorCode.NO_SPACE to ShareMessage.NO_SPACE,
            ErrorCode.SOURCE_CHANGED to ShareMessage.SOURCE_CHANGED, ErrorCode.BAD_REQUEST to ShareMessage.BAD_REQUEST,
            ErrorCode.UNREACHABLE to ShareMessage.UNREACHABLE, ErrorCode.IDLE_TIMEOUT to ShareMessage.CONNECTION_LOST,
            "tls13" to ShareMessage.TLS13, "keystore" to ShareMessage.KEYSTORE,
            Discovery.WARNING_LOCK_REFUSED to ShareMessage.MULTICAST_LOCK_REFUSED,
            ErrorCode.INTERNAL to ShareMessage.GENERIC, "something new" to ShareMessage.GENERIC,
        )
        expected.forEach { (code, message) -> assertEquals(code, message, ShareFormat.errorKey(code)) }
        assertEquals(ShareMessage.GENERIC, ShareFormat.errorKey(null))
    }

    @Test
    fun `every skip reason has its note and the rest is generic`() {
        val expected = mapOf(
            SkipReason.EXISTS_SKIP to SkipNote.EXISTS_SKIP, SkipReason.INCOMPATIBLE_STATE to SkipNote.INCOMPATIBLE_STATE,
            SkipReason.UNSUPPORTED_CONSOLE to SkipNote.UNSUPPORTED_CONSOLE, SkipReason.USER_UNCHECKED to SkipNote.USER_UNCHECKED,
            SkipReason.INVALID_ITEM to SkipNote.INVALID_ITEM, ErrorCode.NO_SPACE to SkipNote.NO_SPACE,
            SkipReason.UNIT_INCOMPLETE to SkipNote.UNIT_INCOMPLETE, SkipReason.TOO_MANY_ITEMS to SkipNote.TOO_MANY_ITEMS,
            "whatever" to SkipNote.GENERIC,
        )
        expected.forEach { (reason, note) -> assertEquals(reason, note, ShareFormat.reasonKey(reason)) }
        assertEquals(SkipNote.GENERIC, ShareFormat.reasonKey(null))
    }

    @Test
    fun `codes and fingerprints are grouped for reading`() {
        assertEquals("482 913", ShareFormat.formatCode("482913"))
        assertEquals("4 8 2 9 1 3", ShareFormat.spokenCode("482 913"))
        val fp = "0123456789abcdef".repeat(4)
        assertEquals(listOf("0123 4567 89ab cdef", "0123 4567 89ab cdef"), ShareFormat.fingerprintLines(fp))
        assertEquals(listOf("abcd ef"), ShareFormat.fingerprintLines("abcdef"))
    }
}
