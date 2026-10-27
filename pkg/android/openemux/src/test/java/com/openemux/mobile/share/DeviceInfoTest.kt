package com.openemux.mobile.share

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceInfoTest {
    @Test
    fun `the name falls back from settings to model to Android`() {
        assertEquals("Ana's phone", DeviceInfo.defaultName(" Ana's phone ", "Pixel 8"))
        assertEquals("Pixel 8", DeviceInfo.defaultName("", "Pixel 8"))
        assertEquals("Pixel 8", DeviceInfo.defaultName(null, "Pixel 8"))
        assertEquals("Android", DeviceInfo.defaultName(null, null))
        assertEquals(64, DeviceInfo.defaultName("n".repeat(80), null).length)
    }

    @Test
    fun `600 dp of smallest width is a tablet`() {
        assertEquals("phone", DeviceInfo.platformFor(599))
        assertEquals("tablet", DeviceInfo.platformFor(600))
    }
}
