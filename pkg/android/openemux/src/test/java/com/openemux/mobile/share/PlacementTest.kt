package com.openemux.mobile.share

import com.openemux.mobile.data.Console
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class PlacementTest {
    private val lib = FakeLibrary()
    private val paths = lib.paths
    private val snes9x = Console.SFC.core("snes9x")
    private val sfcRoms get() = paths.roms(Console.SFC)
    private val sfcSaves get() = paths.saves(Console.SFC)
    private val sfcStates get() = paths.states(Console.SFC)

    private fun plan(
        manifest: Manifest,
        policies: Map<String, Policy> = emptyMap(),
        overrides: Map<String, Policy> = emptyMap(),
        unchecked: Set<String> = emptySet(),
        probe: SpaceProbe = FakeSpace(),
    ) = planReceive(manifest, paths, CoreIdentities(paths), policies, overrides, unchecked, probe)

    private fun ReceivePlan.of(id: String) = decisions.single { it.itemId == id }

    private fun ReceivePlan.reasons() = decisions.associate { it.itemId to (if (it.send) "send" else it.reason) }

    /** One SFC game: ROM, save, state (snes9x 1.62.3, slot 1), cover and label. */
    private fun sfcGame(name: String = "Game", rom: ByteArray = "rom".toByteArray(), mtime: Long = 1_700_000_100L) = manifest {
        game("g1", "SFC", name) {
            item("rom", "$name.sfc", rom, mtime)
            item("battery_save", "$name.srm", "save".toByteArray(), mtime)
            item("state", "$name.state1", "state".toByteArray(), mtime, state = stateInfo("Snes9x", "1.62.3", 1))
            item("cover", "$name.png", PNG, mtime)
            item("label", "$name.png", PNG, mtime)
        }
    }

    @Test
    fun `a new game goes to Android's folders under its own names`() {
        lib.installCore(snes9x, "Snes9x", "1.62.3")
        val plan = plan(sfcGame())
        assertTrue(plan.decisions.all { it.send })
        assertEquals(File(sfcRoms, "Game.sfc"), plan.of("g1i1").target)
        assertEquals(File(sfcSaves, "Game.srm"), plan.of("g1i2").target)
        assertEquals(File(sfcStates, "Game.state1"), plan.of("g1i3").target)
        assertEquals(File(paths.covers(Console.SFC), "Game.png"), plan.of("g1i4").target)
        assertEquals(File(paths.labels(Console.SFC), "Game.png"), plan.of("g1i5").target)
        val game = plan.games.single()
        assertEquals(RomStatus.NEW, game.romStatus)
        assertEquals("Game", game.finalStem)
        assertEquals(listOf("rom", "battery_save", "state", "cover", "label"), plan.toSend().map { it.kind })
        assertEquals(plan.decisions.map { ItemAnswer.send(it.itemId) }, plan.answerItems())
    }

    @Test
    fun `the same ROM, by hash or by size, is exists_skip and its files still come`() {
        lib.installCore(snes9x, "Snes9x", "1.62.3")
        File(sfcRoms, "Game.sfc").put("rom")
        val plan = plan(sfcGame())
        assertEquals("exists_skip", plan.of("g1i1").reason)
        assertEquals(RomStatus.EXISTING, plan.games.single().romStatus)
        assertTrue(plan.of("g1i2").send)
        val bySize = manifest { game("g1", "SFC") { item("rom", "Game.sfc", "xyz".toByteArray(), hash = false) } }
        assertEquals("exists_skip", plan(bySize).of("g1i1").reason)
    }

    @Test
    fun `a different ROM under the same name is kept beside it, and its files follow the new name`() {
        lib.installCore(snes9x, "Snes9x", "1.62.3")
        File(sfcRoms, "Game.sfc").put("local")
        File(sfcRoms, "Game (2).sfc").put("also local")
        File(sfcSaves, "Game.srm").put("local save")
        val plan = plan(sfcGame())
        val game = plan.games.single()
        assertEquals(RomStatus.KEPT_BOTH, game.romStatus)
        assertEquals("Game (3)", game.finalStem)
        assertEquals(File(sfcRoms, "Game (3).sfc"), plan.of("g1i1").target)
        assertEquals(Policy.KEEP_BOTH, plan.of("g1i1").policy)
        assertEquals(File(sfcSaves, "Game (3).srm"), plan.of("g1i2").target)
        assertEquals(File(sfcStates, "Game (3).state1"), plan.of("g1i3").target)
        assertEquals(File(paths.covers(Console.SFC), "Game (3).png"), plan.of("g1i4").target)
        assertEquals(File(paths.labels(Console.SFC), "Game (3).png"), plan.of("g1i5").target)
    }

    @Test
    fun `two incoming games never pick the same name`() {
        val manifest = manifest {
            game("g1", "SFC") { item("rom", "Game.sfc", "a".toByteArray()) }
            game("g2", "SFC") { item("rom", "Game.sfc", "b".toByteArray()) }
            game("g3", "PS") {
                item("rom", "D.cue", "c".toByteArray(), role = "cue")
                item("rom", "T.bin", "t".toByteArray(), role = "bin")
            }
            game("g4", "PS") {
                item("rom", "E.cue", "e".toByteArray(), role = "cue")
                item("rom", "T.bin", "u".toByteArray(), role = "bin")
            }
        }
        val plan = plan(manifest)
        assertEquals(File(sfcRoms, "Game.sfc"), plan.of("g1i1").target)
        assertEquals(File(sfcRoms, "Game (2).sfc"), plan.of("g2i1").target)
        assertTrue(plan.of("g3i2").send)
        assertEquals("exists_skip", plan.of("g4i1").reason)
        assertEquals("exists_skip", plan.of("g4i2").reason)
        assertEquals(RomStatus.ABSENT, plan.games[3].romStatus)
    }

    @Test
    fun `the ROM policy can be overridden`() {
        File(sfcRoms, "Game.sfc").put("local", mtime = 1_700_000_000)
        val older = manifest { game("g1", "SFC") { item("rom", "Game.sfc", "new".toByteArray(), mtime = 1_600_000_000) } }
        val newer = manifest { game("g1", "SFC") { item("rom", "Game.sfc", "new".toByteArray(), mtime = 1_800_000_000) } }
        assertEquals("exists_skip", plan(older, policies = mapOf("rom" to Policy.SKIP)).of("g1i1").reason)
        assertEquals("exists_skip", plan(older, overrides = mapOf("g1i1" to Policy.NEWEST)).of("g1i1").reason)
        val replaced = plan(newer, overrides = mapOf("g1i1" to Policy.NEWEST))
        assertEquals(File(sfcRoms, "Game.sfc"), replaced.of("g1i1").replaces)
        assertEquals(RomStatus.REPLACED, replaced.games.single().romStatus)
        val forced = plan(older, policies = mapOf("rom" to Policy.REPLACE))
        assertEquals(File(sfcRoms, "Game.sfc"), forced.of("g1i1").replaces)
    }

    @Test
    fun `a disc unit lands flat, all of it or none of it`() {
        val unit = manifest {
            game("g1", "PS", "Disc", JSONObject().put("favorite", true)) {
                item("rom", "Disc.cue", "cue".toByteArray(), role = "cue")
                item("rom", "Disc (Track 1).bin", "t1".toByteArray(), role = "bin")
                item("rom", "Disc (Track 2).bin", "t2".toByteArray(), role = "bin")
                item("battery_save", "Disc.srm", "save".toByteArray())
            }
        }
        val ps = paths.roms(Console.PS)
        val fresh = plan(unit)
        assertEquals(listOf(File(ps, "Disc.cue"), File(ps, "Disc (Track 1).bin"), File(ps, "Disc (Track 2).bin")), fresh.toSend().take(3).map { it.target })
        assertEquals(RomStatus.NEW, fresh.games.single().romStatus)

        // All parts already there, same content.
        File(ps, "Disc.cue").put("cue")
        File(ps, "Disc (Track 1).bin").put("t1")
        File(ps, "Disc (Track 2).bin").put("t2")
        val same = plan(unit)
        assertEquals(listOf("exists_skip", "exists_skip", "exists_skip", "send"), same.decisions.map { if (it.send) "send" else it.reason })
        assertEquals(RomStatus.EXISTING, same.games.single().romStatus)

        // One part different: no keep-both for a unit in a flat folder.
        File(ps, "Disc (Track 2).bin").put("other")
        val differs = plan(unit)
        assertEquals(listOf("exists_skip", "exists_skip", "exists_skip"), differs.decisions.take(3).map { it.reason })
        assertEquals(RomStatus.EXISTING, differs.games.single().romStatus)

        // Replace replaces every part.
        val replace = plan(unit, policies = mapOf("rom" to Policy.REPLACE))
        assertEquals(3, replace.toSend().count { it.kind == "rom" && it.replaces == it.target })
        assertEquals(RomStatus.REPLACED, replace.games.single().romStatus)
    }

    @Test
    fun `a track another game owns keeps the whole unit out`() {
        File(paths.roms(Console.PS), "Track 01.bin").put("someone else's")
        val unit = manifest {
            game("g1", "PS") {
                item("rom", "New.cue", "cue".toByteArray(), role = "cue")
                item("rom", "Track 01.bin", "t1".toByteArray(), role = "bin")
                item("battery_save", "New.srm", "s".toByteArray())
            }
        }
        val plan = plan(unit)
        assertEquals(mapOf("g1i1" to "exists_skip", "g1i2" to "exists_skip", "g1i3" to "exists_skip"), plan.reasons())
        assertEquals(RomStatus.ABSENT, plan.games.single().romStatus)
        // Replacing on purpose takes the track; the cue was not there, so the game is new.
        val replaced = plan(unit, overrides = mapOf("g1i1" to Policy.REPLACE))
        assertEquals(RomStatus.NEW, replaced.games.single().romStatus)
        assertEquals(File(paths.roms(Console.PS), "Track 01.bin"), replaced.of("g1i2").replaces)
        assertNull(replaced.of("g1i1").replaces)
        // Newest needs a local main file to compare with.
        assertEquals("exists_skip", plan(unit, overrides = mapOf("g1i1" to Policy.NEWEST)).of("g1i1").reason)
    }

    @Test
    fun `an m3u unit is accepted as one`() {
        val unit = manifest {
            game("g1", "PS") {
                item("rom", "Game.m3u", "D1.chd".toByteArray(), role = "m3u")
                item("rom", "D1.chd", "d".toByteArray(), role = "disc")
            }
        }
        assertTrue(plan(unit).decisions.all { it.send })
        val wrong = manifest { game("g1", "PS") { item("rom", "Game.cue", "x".toByteArray(), role = "m3u") } }
        assertEquals("invalid_item", plan(wrong).of("g1i1").reason)
    }

    @Test
    fun `saves and states keep the newest, strictly`() {
        lib.installCore(snes9x, "Snes9x", "1.62.3")
        File(sfcRoms, "Game.sfc").put("rom")
        File(sfcSaves, "Game.srm").put("local", mtime = 1_700_000_100)
        File(sfcStates, "Game.state1").put("local", mtime = 1_700_000_100)
        val older = plan(sfcGame(mtime = 1_700_000_099))
        val equal = plan(sfcGame(mtime = 1_700_000_100))
        val newer = plan(sfcGame(mtime = 1_700_000_101))
        for (kept in listOf(older, equal)) {
            assertEquals("exists_skip", kept.of("g1i2").reason)
            assertEquals("exists_skip", kept.of("g1i3").reason)
        }
        assertEquals(File(sfcSaves, "Game.srm"), newer.of("g1i2").replaces)
        assertEquals(Policy.NEWEST, newer.of("g1i2").policy)
        assertEquals(File(sfcStates, "Game.state1"), newer.of("g1i3").replaces)
    }

    @Test
    fun `skip, replace and keep both for saves and states`() {
        lib.installCore(snes9x, "Snes9x", "1.62.3")
        File(sfcRoms, "Game.sfc").put("rom")
        File(sfcSaves, "Game.srm").put("local", mtime = 1_800_000_000)
        File(sfcStates, "Game.state1").put("local")
        File(sfcStates, "Game.state2").put("local")
        assertEquals("exists_skip", plan(sfcGame(mtime = 1_900_000_000), policies = mapOf("battery_save" to Policy.SKIP)).of("g1i2").reason)
        assertEquals(File(sfcSaves, "Game.srm"), plan(sfcGame(), overrides = mapOf("g1i2" to Policy.REPLACE)).of("g1i2").replaces)

        val both = plan(sfcGame(mtime = 1_700_000_000), policies = mapOf("battery_save" to Policy.KEEP_BOTH, "state" to Policy.KEEP_BOTH))
        assertEquals(File(sfcSaves, "Game.srm.shared-20231114-221320"), both.of("g1i2").target)
        assertNull(both.of("g1i2").replaces)
        assertEquals(File(sfcStates, "Game.state3"), both.of("g1i3").target)

        for (slot in 3..9) File(sfcStates, "Game.state$slot").put("full")
        val full = plan(sfcGame(), policies = mapOf("state" to Policy.KEEP_BOTH))
        assertEquals("exists_skip", full.of("g1i3").reason)
    }

    @Test
    fun `a state needs the exact installed core`() {
        val game = { core: String, version: String ->
            manifest {
                game("g1", "SFC") {
                    item("rom", "G.sfc")
                    item("state", "G.state", "s".toByteArray(), state = stateInfo(core, version, 0))
                }
            }
        }
        assertEquals("incompatible_state", plan(game("Snes9x", "1.62.3")).of("g1i2").reason)
        lib.installCore(snes9x, "Snes9x", "1.62.3")
        assertTrue(plan(game("Snes9x", "1.62.3")).of("g1i2").send)
        assertEquals(File(sfcStates, "G.state"), plan(game("Snes9x", "1.62.3")).of("g1i2").target)
        assertEquals("incompatible_state", plan(game("Snes9x", "1.63")).of("g1i2").reason)
        assertEquals("incompatible_state", plan(game("bsnes", "1.62.3")).of("g1i2").reason)
        assertEquals("incompatible_state", plan(game("", "")).of("g1i2").reason)
        File(paths.cores, snes9x.fileName).delete()
        assertEquals("incompatible_state", plan(game("Snes9x", "1.62.3")).of("g1i2").reason)
    }

    @Test
    fun `an auto state goes to its own name`() {
        lib.installCore(snes9x, "Snes9x", "1.62.3")
        val auto = manifest {
            game("g1", "SFC") {
                item("rom", "G.sfc")
                item("state", "G.state.auto", "s".toByteArray(), state = stateInfo("Snes9x", "1.62.3", "auto"))
            }
        }
        assertEquals(File(sfcStates, "G.state.auto"), plan(auto).of("g1i2").target)
    }

    @Test
    fun `art the library has wins, whatever its suffix`() {
        File(sfcRoms, "Game.sfc").put("rom")
        File(paths.covers(Console.SFC), "Game.webp").put(WEBP)
        File(paths.labels(Console.SFC), "Game.png").put(PNG)
        val plan = plan(sfcGame())
        assertEquals("exists_skip", plan.of("g1i4").reason)
        assertEquals("exists_skip", plan.of("g1i5").reason)
        val replace = plan(sfcGame(), policies = mapOf("cover" to Policy.REPLACE))
        assertEquals(File(paths.covers(Console.SFC), "Game.webp"), replace.of("g1i4").replaces)
        assertEquals(File(paths.covers(Console.SFC), "Game.png"), replace.of("g1i4").target)
        assertEquals("exists_skip", plan(sfcGame(), policies = mapOf("label" to Policy.KEEP_BOTH)).of("g1i5").reason)
    }

    @Test
    fun `hostile and unusable names are invalid_item, never a path outside`() {
        val names = listOf("../evil.sfc", "a/b.sfc", ".hidden.sfc", "x.exe")
        for (name in names) {
            val item = Item("i1", "rom", name, 1, 1.0, null, JSONObject().put("id", "i1").put("kind", "rom").put("name", name))
            val manifest = Manifest(listOf(Game("g1", "SFC", "x", listOf(item), null, JSONObject())), 1, 1, JSONObject())
            val plan = plan(manifest)
            assertEquals(name, "invalid_item", plan.of("i1").reason)
            assertTrue(plan.toSend().isEmpty())
        }
    }

    @Test
    fun `items that break the per-kind rules are invalid_item`() {
        lib.installCore(snes9x, "Snes9x", "1.62.3")
        val plan = plan(manifest {
            game("g1", "SFC") {
                item("rom", "Game.sfc")
                item("battery_save", "Other.srm")
                item("battery_save", "Game.smc")
                item("label", "Game.jpg", JPEG)
                item("cover", "Game.gif", "GIF89a".toByteArray())
                item("state", "Game.state", state = stateInfo("Snes9x", "1.62.3", 10))
                item("state", "Game.state", state = JSONObject().put("core", 1).put("core_version", "1").put("slot", 0), id = "bad-core")
                item("state", "Game.state", id = "no-state")
                item("battery_save", "Game.srm", role = "bin", id = "part-on-save")
                item("mystery", "Game.xyz", id = "unknown-kind")
            }
        })
        assertTrue(plan.of("g1i1").send)
        for (id in listOf("g1i2", "g1i3", "g1i4", "g1i5", "g1i6", "bad-core", "no-state", "part-on-save", "unknown-kind")) {
            assertEquals(id, "invalid_item", plan.of(id).reason)
        }
    }

    @Test
    fun `a bad part makes the item invalid`() {
        val raw = JSONObject().put("id", "i1").put("kind", "rom").put("name", "G.sfc").put("part", JSONObject().put("unit", 1).put("role", "main"))
        val notObject = JSONObject().put("id", "i2").put("kind", "rom").put("name", "H.sfc").put("part", "main")
        val items = listOf(Item("i1", "rom", "G.sfc", 1, 1.0, null, raw), Item("i2", "rom", "H.sfc", 1, 1.0, null, notObject))
        val plan = plan(Manifest(listOf(Game("g1", "SFC", "x", items, null, JSONObject())), 2, 2, JSONObject()))
        assertEquals(listOf("invalid_item", "invalid_item"), plan.decisions.map { it.reason })
    }

    @Test
    fun `a game needs exactly one main ROM with a suffix its console loads`() {
        val none = plan(manifest { game("g1", "SFC") { item("battery_save", "G.srm") } })
        assertEquals("invalid_item", none.of("g1i1").reason)
        val two = plan(manifest { game("g1", "SFC") { item("rom", "A.sfc"); item("rom", "B.sfc") } })
        assertEquals(listOf("invalid_item", "invalid_item"), two.decisions.map { it.reason })
        val wrongSuffix = plan(manifest { game("g1", "SFC") { item("rom", "A.gba"); item("battery_save", "A.srm") } })
        assertEquals(listOf("invalid_item", "invalid_item"), wrongSuffix.decisions.map { it.reason })
        val loosePart = plan(manifest {
            game("g1", "SFC") {
                item("rom", "A.sfc")
                item("rom", "A.bin", role = "bin")
            }
        })
        assertEquals(listOf("invalid_item", "invalid_item"), loosePart.decisions.map { it.reason })
        val empty = plan(manifest { game("g1", "SFC") {} })
        assertTrue(empty.decisions.isEmpty())
    }

    @Test
    fun `a console this app does not know is unsupported`() {
        val plan = plan(manifest { game("g1", "DREAMCAST") { item("rom", "A.cdi"); item("battery_save", ".x") } })
        assertEquals("unsupported_console", plan.of("g1i1").reason)
        assertEquals("invalid_item", plan.of("g1i2").reason)
    }

    @Test
    fun `unchecked items and games are user_unchecked`() {
        lib.installCore(snes9x, "Snes9x", "1.62.3")
        val one = plan(sfcGame(), unchecked = setOf("g1i3"))
        assertEquals("user_unchecked", one.of("g1i3").reason)
        assertTrue(one.of("g1i2").send)
        val whole = plan(sfcGame(), unchecked = setOf("g1"))
        assertTrue(whole.decisions.all { it.reason == "user_unchecked" })
        assertEquals(RomStatus.ABSENT, whole.games.single().romStatus)
        // The ROM unchecked but already here: its files can still come.
        File(sfcRoms, "Game.sfc").put("other")
        val romOnly = plan(sfcGame(), unchecked = setOf("g1i1"))
        assertEquals("user_unchecked", romOnly.of("g1i1").reason)
        assertEquals(RomStatus.EXISTING, romOnly.games.single().romStatus)
        assertTrue(romOnly.of("g1i2").send)
    }

    @Test
    fun `a name too long for the stage's temporary name is invalid`() {
        val stem = "x".repeat(220)
        val plan = plan(manifest { game("g1", "SFC") { item("rom", "$stem.sfc"); item("battery_save", "$stem.srm") } })
        assertEquals("invalid_item", plan.of("g1i1").reason)
        assertEquals("invalid_item", plan.of("g1i2").reason)
        assertEquals(RomStatus.ABSENT, plan.games.single().romStatus)
        assertEquals(SkipReason.INVALID_ITEM, plan.games.single().absentReason)
    }

    @Test
    fun `a name that only becomes too long after keep both is invalid`() {
        val stem = "y".repeat(200)
        File(sfcRoms, "$stem.sfc").put("local")
        File(sfcRoms, "$stem.srm").put("x")
        val plan = plan(manifest {
            game("g1", "SFC") {
                item("rom", "$stem.sfc", "incoming".toByteArray())
                item("battery_save", "$stem.srm", "s".toByteArray(), mtime = 1_800_000_000)
            }
        })
        assertEquals(RomStatus.KEPT_BOTH, plan.games.single().romStatus)
        assertTrue(plan.of("g1i2").send)
        val longer = "z".repeat(217)
        File(sfcRoms, "$longer.sfc").put("local")
        val tooLong = plan(manifest { game("g1", "SFC") { item("rom", "$longer.sfc", "incoming".toByteArray()) } })
        assertEquals("invalid_item", tooLong.of("g1i1").reason)
        assertEquals(RomStatus.ABSENT, tooLong.games.single().romStatus)
    }

    @Test
    fun `a target that resolves outside its folder is refused`() {
        // A symlink planted where the ROM would go points outside the library.
        val outside = File(paths.base.parentFile, "outside").apply { mkdirs() }
        sfcRoms.mkdirs()
        java.nio.file.Files.createSymbolicLink(File(sfcRoms, "Link.sfc").toPath(), File(outside, "x.sfc").toPath())
        val plan = plan(manifest { game("g1", "SFC") { item("rom", "Link.sfc"); item("battery_save", "Link.srm") } })
        assertEquals(listOf("invalid_item", "invalid_item"), plan.decisions.map { it.reason })
        val single = plan(manifest { game("g1", "SFC") { item("rom", "Link.sfc") } }, policies = mapOf("rom" to Policy.REPLACE))
        assertEquals("invalid_item", single.of("g1i1").reason)
    }

    @Test
    fun `the space check groups by filesystem and keeps the reserve`() {
        val big = ByteArray(1000)
        val manifest = manifest {
            game("g1", "SFC") { item("rom", "A.sfc", big) }
            game("g2", "SFC") { item("rom", "B.sfc", big); item("cover", "B.png", PNG) }
        }
        val external = paths.base.path
        val internal = paths.internal.path
        val enough = plan(manifest, probe = FakeSpace(mapOf(external to 2000 + ReceivePlan.SPACE_RESERVE, internal to Long.MAX_VALUE / 2)))
        val checks = enough.spaceCheck()
        assertEquals(2, checks.size)
        assertEquals(2000L, checks.first { it.root.path.startsWith(external) }.needed)
        assertTrue(enough.okToAccept())

        val short = plan(manifest, probe = FakeSpace(mapOf(external to 1999 + ReceivePlan.SPACE_RESERVE)))
        val failed = short.spaceCheck().filter { !it.ok }
        assertEquals(1, failed.size)
        assertEquals(2000L, failed.single().needed)
        assertFalse(short.okToAccept())
    }

    @Test
    fun `a filesystem that cannot be measured has no room`() {
        val manifest = manifest { game("g1", "SFC") { item("rom", "A.sfc") } }
        val broken = object : SpaceProbe {
            override fun store(dir: File): Pair<Any, Long> = throw IOException("no store")
        }
        assertFalse(plan(manifest, probe = broken).okToAccept())
        // The real probe measures the temp folder.
        assertTrue(planReceive(manifest, paths).spaceCheck().single().free > 0)
    }

    @Test
    fun `desktop's manifest sample is decided the way the spec says`() {
        // OpenEmux tests/data/share_vectors.json, copied from commit c46bec0 (issue 588's merge).
        val sample = JSONObject(javaClass.getResource("/share_vectors.json")!!.readText()).getJSONObject("manifest_sample")
        val manifest = ManifestParser.parse(sample)
        val psx = Console.PS.core("mednafen_psx")
        lib.installCore(psx, "Beetle PSX", "0.9.44.1")
        val plan = plan(manifest)
        assertTrue(plan.decisions.all { it.send })
        val ps = paths.roms(Console.PS)
        assertEquals(
            listOf(
                File(ps, "Example Quest.cue"), File(ps, "Example Quest (Track 1).bin"), File(ps, "Example Quest (Track 2).bin"),
                File(paths.saves(Console.PS), "Example Quest.srm"), File(paths.states(Console.PS), "Example Quest.state1"),
                File(paths.covers(Console.PS), "Example Quest.png"),
            ),
            plan.toSend().map { it.target },
        )
        assertEquals(true, plan.games.single().metadata!!.getBoolean("favorite"))
        lib.installCore(psx, "Beetle PSX", "0.9.45")
        assertEquals("incompatible_state", plan(manifest).of("g1i5").reason)
    }
}
