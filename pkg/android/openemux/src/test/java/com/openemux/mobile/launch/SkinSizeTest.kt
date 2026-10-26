package com.openemux.mobile.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkinSizeTest {

    @Test
    fun noSkinYetIsAlwaysAChange() {
        assertTrue(SkinSize(1080, 2400, 80).differs(null))
    }

    @Test
    fun insetJitterWithinTheToleranceIsTheSameWindow() {
        val drawn = SkinSize(1080, 2400, 80)
        assertFalse(SkinSize(1088, 2392, 72).differs(drawn))
        assertTrue(SkinSize(1089, 2400, 80).differs(drawn))
        assertTrue(SkinSize(1080, 2391, 80).differs(drawn))
        assertTrue(SkinSize(1080, 2400, 89).differs(drawn))
    }

    @Test
    fun unfoldingIsAChange() {
        assertTrue(SkinSize(1840, 2208, 0).differs(SkinSize(1080, 2092, 0)))
    }

    @Test
    fun theLaunchExtraRoundTrips() {
        val size = SkinSize(1080, 2400, 80)
        assertEquals("1080x2400x80", size.toString())
        assertEquals(size, SkinSize.parse(size.toString()))
    }

    @Test
    fun aMissingOrBrokenExtraParsesToNothing() {
        assertNull(SkinSize.parse(null))
        assertNull(SkinSize.parse(""))
        assertNull(SkinSize.parse("1080x2400"))
        assertNull(SkinSize.parse("1080xwidex80"))
        assertNull(SkinSize.parse("1080xwidex2400x80"))
        assertNull(SkinSize.parse("1080x2400x80x"))
    }
}
