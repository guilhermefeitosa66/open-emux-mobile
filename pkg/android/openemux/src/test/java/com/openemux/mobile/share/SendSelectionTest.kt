package com.openemux.mobile.share

import com.openemux.mobile.data.Console
import com.openemux.mobile.data.RomMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SendSelectionTest {
    private val lib = FakeLibrary()
    private val paths = lib.paths

    /** Two SFC games: the first with everything, the second a bare ROM; and a broken cue left out. */
    private fun built(): Pair<BuiltManifest, SendSelection> {
        val full = lib.rom(Console.SFC, "Full.sfc", ByteArray(1000))
        File(paths.saves(Console.SFC), "Full.srm").put(ByteArray(200))
        File(paths.states(Console.SFC), "Full.state1").put(ByteArray(30))
        File(paths.states(Console.SFC), "Full.state2").put(ByteArray(20))
        File(paths.covers(Console.SFC), "Full.png").put(PNG)
        File(paths.labels(Console.SFC), "Full.png").put(PNG)
        lib.installCore(Console.SFC.core("snes9x"), "Snes9x", "1.62.3")
        lib.meta[full.key] = RomMeta(favorite = true)
        val bare = lib.rom(Console.SFC, "Bare.sfc", ByteArray(500))
        val broken = lib.rom(Console.PS, "Broken.cue", "FILE \"Missing.bin\" BINARY".toByteArray())
        val roms = listOf(full, bare, broken)
        val manifest = ManifestBuilder(lib).build(roms)
        return manifest to SendSelection.from(manifest, roms)
    }

    @Test
    fun `everything the games have is checked, and only what they have is listed`() {
        val (_, s) = built()
        assertEquals(2, s.games.size)
        val full = s.games[0]
        assertEquals("Full", full.title)
        assertEquals("SFC", full.console)
        assertEquals(SendPart.entries, full.parts.map { it.part })
        assertEquals(2, full.parts.single { it.part == SendPart.STATES }.count)
        assertEquals(50L, full.parts.single { it.part == SendPart.STATES }.bytes)
        assertEquals(listOf(SendPart.GAME), s.games[1].parts.map { it.part })
        assertTrue(s.games.all { s.state(it.id) == Tri.CHECKED })
        assertEquals(2, s.gameCount)
        assertEquals(7, s.fileCount)
        assertEquals(1000L + 200 + 50 + PNG.size * 2 + 500, s.totalBytes)
        assertFalse(s.isEmpty)
        assertTrue(s.unchecked().isEmpty())
    }

    @Test
    fun `a game the builder left out is listed with its reason`() {
        val (_, s) = built()
        val ex = s.excluded.single()
        assertEquals("PS/Broken.cue", ex.romKey)
        assertEquals("PS", ex.console)
        assertEquals("Broken", ex.title)
        assertEquals(SkipReason.UNIT_INCOMPLETE, ex.reason)
    }

    @Test
    fun `an excluded game the roms do not name falls back to its key`() {
        val built = BuiltManifest(org.json.JSONObject().put("games", org.json.JSONArray()), emptyMap(), listOf(Excluded("GBA/Lost.gba", SkipReason.INVALID_ITEM)))
        val ex = SendSelection.from(built, emptyList()).excluded.single()
        assertEquals("GBA", ex.console)
        assertEquals("Lost.gba", ex.title)
    }

    @Test
    fun `unchecking an item makes the game mixed and drops its size`() {
        val (_, s) = built()
        val id = s.games[0].id
        val before = s.totalBytes
        val next = s.toggle(id, SendPart.COVER)
        assertEquals(Tri.MIXED, next.state(id))
        assertFalse(next.isChecked(id, SendPart.COVER))
        assertEquals(before - PNG.size, next.totalBytes)
        assertEquals(6, next.fileCount)
        assertEquals(Tri.CHECKED, next.toggle(id, SendPart.COVER).state(id))
    }

    @Test
    fun `the game's own box toggles all of it`() {
        val (_, s) = built()
        val id = s.games[0].id
        val off = s.toggleGame(id)
        assertEquals(Tri.UNCHECKED, off.state(id))
        assertEquals(1, off.gameCount)
        assertEquals(0L, off.gameBytes(id))
        // From mixed, the box checks everything.
        val mixed = s.toggle(id, SendPart.SAVE)
        assertEquals(Tri.CHECKED, mixed.toggleGame(id).state(id))
        assertEquals(Tri.CHECKED, off.toggleGame(id).state(id))
        assertSame(s, s.toggleGame("nope"))
        assertEquals(Tri.UNCHECKED, s.state("nope"))
        assertEquals(0L, s.gameBytes("nope"))
    }

    @Test
    fun `nothing checked is empty`() {
        val (_, s) = built()
        val none = s.games.fold(s) { acc, g -> acc.toggleGame(g.id) }
        assertTrue(none.isEmpty)
        assertEquals(0, none.gameCount)
        assertEquals(7, none.unchecked().size)
    }

    @Test
    fun `details only is not a file, so a game with only them checked sends nothing`() {
        val (_, s) = built()
        val id = s.games[0].id
        val detailsOnly = SendPart.entries.filter { it != SendPart.DETAILS }.fold(s) { acc, p -> acc.toggle(id, p) }
        assertEquals(Tri.MIXED, detailsOnly.state(id))
        assertEquals(1, detailsOnly.gameCount)
    }

    @Test
    fun `the filtered manifest leaves out what was unchecked and recounts`() {
        val (built, s) = built()
        val full = s.games[0].id
        val bare = s.games[1].id
        val next = s.toggle(full, SendPart.STATES).toggle(full, SendPart.DETAILS).toggleGame(bare)
        val out = next.filter(built)
        val games = out.json.getJSONArray("games")
        assertEquals(1, games.length())
        val game = games.getJSONObject(0)
        assertEquals(full, game.getString("id"))
        assertNull(game.optJSONObject("metadata"))
        val kinds = (0 until game.getJSONArray("items").length()).map { game.getJSONArray("items").getJSONObject(it).getString("kind") }
        assertEquals(listOf(Item.ROM, Item.BATTERY_SAVE, Item.COVER, Item.LABEL), kinds)
        assertEquals(4, out.json.getInt("file_count"))
        assertEquals(1000L + 200 + PNG.size * 2, out.json.getLong("total_size"))
        assertEquals(next.unchecked(), built.sources.keys - out.sources.keys)
        assertEquals(built.excluded, out.excluded)
        // The original is untouched.
        assertTrue(built.json.getJSONArray("games").getJSONObject(0).has("metadata"))
        // The result parses as the receiver would.
        assertEquals(4L, ManifestParser.parse(out.json).fileCount)
    }

    @Test
    fun `details kept keep the metadata`() {
        val (built, s) = built()
        assertTrue(s.filter(built).json.getJSONArray("games").getJSONObject(0).has("metadata"))
    }
}
