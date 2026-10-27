package com.openemux.mobile.share

import com.openemux.mobile.data.Console
import com.openemux.mobile.data.Rom
import com.openemux.mobile.data.RomMeta
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManifestBuilderTest {
    private val lib = FakeLibrary()
    private val paths = lib.paths

    private fun build(vararg roms: Rom, hashLimit: Long = ManifestBuilder.HASH_LIMIT) =
        ManifestBuilder(lib, hashLimit = hashLimit).build(roms.toList())

    private fun items(built: BuiltManifest, game: Int = 0) =
        built.json.getJSONArray("games").getJSONObject(game).getJSONArray("items").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    @Test
    fun `a single-file game with its save, states, cover, label and settings`() {
        val rom = lib.rom(Console.SFC, "Game (USA).sfc", "rom".toByteArray(), mtime = 1_700_000_001)
        File(paths.saves(Console.SFC), "Game (USA).srm").put("save", mtime = 1_700_000_002)
        File(paths.saves(Console.SFC), "Game (USA) (2).srm").put("other game")
        File(paths.saves(Console.SFC), ".Game (USA).srm.0a1b2c3d.tmp").put("temp")
        File(paths.states(Console.SFC), "Game (USA).state").put("s0")
        File(paths.states(Console.SFC), "Game (USA).state3").put("s3")
        File(paths.states(Console.SFC), "Game (USA).state.auto").put("sa")
        File(paths.states(Console.SFC), "Game (USA).state3.png").put(PNG)
        File(paths.states(Console.SFC), "Game (USA).state999").put("scratch")
        File(paths.covers(Console.SFC), "Game (USA).jpg").put(JPEG)
        File(paths.labels(Console.SFC), "Game (USA).png").put(PNG)
        lib.installCore(Console.SFC.core("snes9x"), "Snes9x", "1.62.3")
        lib.installCore(Console.SFC.core("bsnes"), "bsnes", "115")
        lib.meta[rom.key] = RomMeta(favorite = true, color = "red", core = "bsnes", shader = "zfast")
        lib.playtime[rom.key] = 5400

        val built = build(rom)
        val game = built.json.getJSONArray("games").getJSONObject(0)
        assertEquals("g1", game.getString("id"))
        assertEquals("SFC", game.getString("console"))
        assertEquals("Game", game.getString("title"))
        val meta = game.getJSONObject("metadata")
        assertEquals(true, meta.getBoolean("favorite"))
        assertEquals("red", meta.getString("cartridge_color"))
        assertEquals("bsnes", meta.getString("core"))
        assertEquals("zfast", meta.getString("shader"))
        assertEquals(5400, meta.getLong("playtime_seconds"))

        val items = items(built)
        assertEquals(
            listOf("rom", "battery_save", "state", "state", "state", "cover", "label"),
            items.map { it.getString("kind") },
        )
        assertEquals(
            listOf("Game (USA).sfc", "Game (USA).srm", "Game (USA).state", "Game (USA).state3", "Game (USA).state.auto", "Game (USA).jpg", "Game (USA).png"),
            items.map { it.getString("name") },
        )
        assertEquals((1..7).map { "g1i$it" }, items.map { it.getString("id") })
        assertFalse(items[0].has("part"))
        assertEquals(1_700_000_001L, items[0].getLong("mtime"))
        assertEquals(sha256("rom".toByteArray()), items[0].getString("sha256"))
        // The effective core is the game's own (bsnes), not the console's default.
        assertEquals(stateInfo("bsnes", "115", 0).toString(), items[2].getJSONObject("state").toString())
        assertEquals(3, items[3].getJSONObject("state").getInt("slot"))
        assertEquals("auto", items[4].getJSONObject("state").getString("slot"))
        assertEquals(7, built.json.getInt("file_count"))
        assertEquals(items.sumOf { it.getLong("size") }, built.json.getLong("total_size"))
        assertEquals(rom.file, built.sources["g1i1"])
        assertTrue(built.excluded.isEmpty())
        // It must parse as the receiver parses it.
        assertEquals(7, ManifestParser.parse(JSONObject(built.json.toString())).items.size)
    }

    @Test
    fun `a game with nothing of its own sends no metadata and no art`() {
        val rom = lib.rom(Console.GBA, "Plain.gba")
        lib.meta[rom.key] = RomMeta(color = "default", core = "unknown-core", shader = "no-such-shader")
        val built = build(rom)
        val game = built.json.getJSONArray("games").getJSONObject(0)
        assertFalse(game.has("metadata"))
        assertEquals(listOf("rom"), items(built).map { it.getString("kind") })
    }

    @Test
    fun `a core without info sends an empty version, and its name is left out of the metadata`() {
        val rom = lib.rom(Console.SFC, "G.sfc")
        File(paths.states(Console.SFC), "G.state1").put("s")
        lib.meta[rom.key] = RomMeta(core = "snes9x")
        val built = build(rom)
        assertEquals(stateInfo("", "", 1).toString(), items(built)[1].getJSONObject("state").toString())
        assertFalse(built.json.getJSONArray("games").getJSONObject(0).has("metadata"))
    }

    @Test
    fun `a cue unit travels as one group, the cover from the mirror never`() {
        lib.rom(Console.PS, "Disc (Track 1).bin", "t1".toByteArray())
        lib.rom(Console.PS, "Disc (Track 2).bin", "t2".toByteArray())
        val cue = lib.rom(Console.PS, "Disc.cue", "FILE \"Disc (Track 1).bin\" BINARY\nFILE \"Disc (Track 2).bin\" BINARY\n".toByteArray())
        val built = build(cue)
        val items = items(built)
        assertEquals(listOf("Disc.cue", "Disc (Track 1).bin", "Disc (Track 2).bin"), items.map { it.getString("name") })
        assertEquals(listOf("cue", "bin", "bin"), items.map { it.getJSONObject("part").getString("role") })
        assertTrue(items.all { it.getJSONObject("part").getString("unit") == "g1" })
    }

    @Test
    fun `an incomplete unit or a dotfile name is left out with its reason`() {
        val broken = lib.rom(Console.PS, "Broken.cue", "FILE \"gone.bin\" BINARY\n".toByteArray())
        val hidden = lib.rom(Console.SFC, ".hidden.sfc")
        val good = lib.rom(Console.SFC, "Good.sfc")
        val built = build(broken, hidden, good)
        assertEquals(listOf(Excluded(broken.key, "unit_incomplete"), Excluded(hidden.key, "invalid_item")), built.excluded)
        assertEquals(1, built.json.getJSONArray("games").length())
        assertEquals("g1", built.json.getJSONArray("games").getJSONObject(0).getString("id"))
    }

    @Test
    fun `games past 2000 items are left out as too many`() {
        val big = lib.rom(Console.SFC, "Big.sfc")
        for (slot in 0..9) File(paths.states(Console.SFC), Units.stateFileName("Big", slot)).put("s")
        val many = (1..181).map { lib.rom(Console.GBA, "G$it.gba") }
        val roms = many + big // 181 + 11 items: fits
        val built = ManifestBuilder(lib).build(roms)
        assertTrue(built.excluded.isEmpty())
        val tooMany = (182..2000).map { lib.rom(Console.GBA, "G$it.gba") }
        val second = ManifestBuilder(lib).build(tooMany + many + big)
        assertEquals(listOf(Excluded(big.key, "too_many_items")), second.excluded)
        assertEquals(2000, second.json.getInt("file_count"))
    }

    @Test
    fun `only items up to the limit are hashed, with progress`() {
        val small = lib.rom(Console.SFC, "S.sfc", ByteArray(10))
        val large = lib.rom(Console.SFC, "L.sfc", ByteArray(11))
        val seen = mutableListOf<Pair<Long, Long>>()
        val built = ManifestBuilder(lib, hashLimit = 10, onProgress = { d, t -> seen += d to t }).build(listOf(small, large))
        assertTrue(items(built, 0)[0].has("sha256"))
        assertFalse(items(built, 1)[0].has("sha256"))
        assertEquals(listOf(10L to 10L), seen)
    }

    @Test
    fun `cancel stops the hashing`() {
        val rom = lib.rom(Console.SFC, "S.sfc", ByteArray(3 * ManifestBuilder.HASH_CHUNK))
        var chunks = 0
        val builder = ManifestBuilder(lib, onProgress = { _, _ -> chunks++ }, cancelled = { chunks >= 1 })
        val error = assertThrows(ShareError::class.java) { builder.build(listOf(rom)) }
        assertEquals(ErrorCode.CANCELLED, error.code)
        assertEquals(1, chunks)
    }

    @Test
    fun `a file that cannot be read while hashing is source_changed`() {
        val rom = lib.rom(Console.SFC, "S.sfc")
        val cancel = { rom.file.delete(); false }
        val error = assertThrows(ShareError::class.java) { ManifestBuilder(lib, cancelled = cancel).build(listOf(rom)) }
        assertEquals(ErrorCode.SOURCE_CHANGED, error.code)
    }

    @Test
    fun `a missing ROM file is an incomplete unit`() {
        val rom = lib.rom(Console.SFC, "Gone.sfc")
        rom.file.delete()
        assertEquals("unit_incomplete", build(rom).excluded.single().reason)
        assertNull(build(rom).sources["g1i1"])
    }
}
