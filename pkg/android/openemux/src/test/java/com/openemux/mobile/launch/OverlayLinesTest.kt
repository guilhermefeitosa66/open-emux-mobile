package com.openemux.mobile.launch

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayLinesTest {

    @Test
    fun dpadWithTheDefaultBindsNamesNone() {
        val cfg = StringBuilder()
        OverlayLines.dpad(cfg, "overlay0_desc3", listOf("up", "down", "left", "right"), 1.41818f, 1.41818f, 1.5f, 1.5f)
        assertEquals(
            """
            overlay0_desc3_reach_x = 1.4182
            overlay0_desc3_reach_y = 1.5000
            overlay0_desc3_range_mod = 20.0
            overlay0_desc3_range_mod_exclusive = true
            overlay0_desc3_dead_zone = 0.25

            """.trimIndent(),
            cfg.toString(),
        )
    }

    @Test
    fun dpadNamesTheBindsThatDiffer() {
        val cfg = StringBuilder()
        OverlayLines.dpad(cfg, "k", listOf("l2", "l3", "left", "r3"), 1f, 1f, 1f, 1f)
        val named = cfg.lines().filter { it.startsWith("k_up") || it.startsWith("k_down") || it.startsWith("k_left") || it.startsWith("k_right") }
        assertEquals(listOf("k_up = \"l2\"", "k_down = \"l3\"", "k_right = \"r3\""), named)
    }

    @Test
    fun lopsidedReachNamesEachSide() {
        val cfg = StringBuilder()
        OverlayLines.dpad(cfg, "k", listOf("up", "down", "left", "right"), 1f, 2f, 1.5f, 1.5f)
        val reach = cfg.lines().filter { it.startsWith("k_reach") }
        assertEquals(listOf("k_reach_left = 1.0000", "k_reach_right = 2.0000", "k_reach_y = 1.5000"), reach)
    }

    @Test
    fun knobIsAMovableImage() {
        val cfg = StringBuilder()
        OverlayLines.knob(cfg, "overlay1_desc7", "landscape-knob7.png")
        assertEquals("overlay1_desc7_overlay = \"landscape-knob7.png\"\noverlay1_desc7_movable = true\n", cfg.toString())
    }

    @Test
    fun travelIsTheDrawnSkinsShare() {
        assertEquals(44f, OverlayLines.DPAD_TRAVEL * 60f, 1e-4f)
    }
}
