package com.openemux.mobile.share

import com.openemux.mobile.data.Console
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UnitsTest {
    private val dir = Files.createTempDirectory("units").toFile()

    private fun roles(unit: Units.UnitFiles?) = unit!!.parts.map { it.first.name to it.second }

    @Test
    fun `a single file is its own unit`() {
        val rom = File(dir, "Game.sfc").put("x")
        val unit = Units.collectUnit(rom)!!
        assertEquals(listOf("Game.sfc" to "main"), roles(unit))
        assertFalse(unit.multi)
        assertNull(Units.collectUnit(File(dir, "missing.sfc")))
    }

    @Test
    fun `a cue brings its tracks once each`() {
        File(dir, "Disc (Track 1).bin").put("1")
        File(dir, "Disc (Track 2).bin").put("2")
        val cue = File(dir, "Disc.cue").put(
            "FILE \"Disc (Track 1).bin\" BINARY\n  TRACK 01 MODE1/2352\nFILE \"Disc (Track 2).bin\" BINARY\nfile \"Disc (Track 1).bin\" BINARY\n",
        )
        val unit = Units.collectUnit(cue)!!
        assertTrue(unit.multi)
        assertEquals(listOf("Disc.cue" to "cue", "Disc (Track 1).bin" to "bin", "Disc (Track 2).bin" to "bin"), roles(unit))
    }

    @Test
    fun `a cue with a missing track or a path in a reference is incomplete`() {
        assertNull(Units.collectUnit(File(dir, "A.cue").put("FILE \"gone.bin\" BINARY\n")))
        File(dir, "sub").mkdirs()
        File(dir, "sub/t.bin").put("1")
        assertNull(Units.collectUnit(File(dir, "B.cue").put("FILE \"sub/t.bin\" BINARY\n")))
        File(dir, "C.cue").put("FILE \"..\" BINARY\n")
        assertNull(Units.collectUnit(File(dir, "C.cue")))
    }

    @Test
    fun `an m3u brings its discs and their tracks`() {
        File(dir, "D1 (Track 1).bin").put("1")
        File(dir, "D1.cue").put("FILE \"D1 (Track 1).bin\" BINARY\n")
        File(dir, "D2.chd").put("2")
        val m3u = File(dir, "Game.m3u").put("# list\nD1.cue\n\nD2.chd\n")
        assertEquals(
            listOf("Game.m3u" to "m3u", "D1.cue" to "disc", "D2.chd" to "disc", "D1 (Track 1).bin" to "bin"),
            roles(Units.collectUnit(m3u)),
        )
        assertNull(Units.collectUnit(File(dir, "Bad.m3u").put("D9.chd\n")))
        File(dir, "D3.cue").put("FILE \"none.bin\" BINARY\n")
        assertNull(Units.collectUnit(File(dir, "Bad2.m3u").put("D3.cue\n")))
    }

    @Test
    fun `an unreadable sheet is incomplete`() {
        val cue = File(dir, "Dir.cue").apply { mkdirs() }
        assertNull(Units.collectUnit(cue))
        val sheet = File(dir, "Locked.m3u").put("x")
        sheet.setReadable(false)
        if (!sheet.canRead()) assertNull(Units.collectUnit(sheet))
        val lockedCue = File(dir, "Locked.cue").put("x")
        lockedCue.setReadable(false)
        if (!lockedCue.canRead()) assertNull(Units.collectUnit(lockedCue))
    }

    @Test
    fun `battery saves start with the stem and a dot and are neither ROM, art nor sheet`() {
        assertTrue(Units.isBatterySave("Game.srm", "Game", Console.SFC))
        assertTrue(Units.isBatterySave("Game.v1.2.srm", "Game.v1.2", Console.SFC))
        assertTrue(Units.isBatterySave("Game.srm.shared-20231114-221320", "Game", Console.SFC))
        assertFalse(Units.isBatterySave("Game (2).srm", "Game", Console.SFC))
        assertFalse(Units.isBatterySave("Gamer.srm", "Game", Console.SFC))
        assertFalse(Units.isBatterySave("Game.smc", "Game", Console.SFC))
        assertFalse(Units.isBatterySave("Game.PNG", "Game", Console.SFC))
        assertFalse(Units.isBatterySave("Game.cue", "Game", Console.PS))
        assertFalse(Units.isBatterySave("Game.", "Game", Console.SFC))
        assertFalse(Units.isBatterySave(".Game.srm.ab12cd34.tmp", ".Game", Console.SFC))
    }

    @Test
    fun `receivable names are bare and never dotfiles`() {
        assertTrue(Units.isReceivableName("Game (USA).sfc"))
        for (bad in listOf("", ".", "..", ".hidden.sfc", "a/b.sfc", "a\\b.sfc", "../evil.sfc", "x..y", "nul\u0000.sfc", "é".repeat(128))) {
            assertFalse(bad, Units.isReceivableName(bad))
        }
    }

    @Test
    fun `state files are named the way RetroArch names them`() {
        assertEquals("G.state", Units.stateFileName("G", 0))
        assertEquals("G.state7", Units.stateFileName("G", 7))
        assertEquals("G.state.auto", Units.stateFileName("G", Units.AUTO_SLOT))
    }

    @Test
    fun `images are told by their first bytes`() {
        assertEquals("png", Units.imageKind(PNG))
        assertEquals("jpeg", Units.imageKind(JPEG))
        assertEquals("webp", Units.imageKind(WEBP))
        assertNull(Units.imageKind("RIFF0000WAVE".toByteArray()))
        assertNull(Units.imageKind(byteArrayOf(0x89.toByte())))
        assertNull(Units.imageKind("GIF89a".toByteArray()))
        assertEquals("png", Units.imageKind(File(dir, "a.png").put(PNG)))
        assertEquals(null, Units.imageKind(File(dir, "short").put(byteArrayOf(1))))
        assertNull(Units.imageKind(File(dir, "missing")))
    }
}
