package com.openemux.mobile.share

import com.openemux.mobile.data.Console
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OfferModelTest {
    private val lib = FakeLibrary()
    private val paths = lib.paths
    private val sender = OfferSender("a".repeat(32), "Desk", "desktop", "b".repeat(64))

    private fun sfcGame() = manifest {
        game("g1", "SFC", "Game") {
            item("rom", "Game.sfc", "rom".toByteArray(), 1_700_000_100)
            item("battery_save", "Game.srm", "save".toByteArray(), 1_700_000_100)
            item("state", "Game.state1", "s1".toByteArray(), 1_700_000_100, state = stateInfo("Snes9x", "1.62.3", 1))
            item("state", "Game.state2", "s2".toByteArray(), 1_700_000_100, state = stateInfo("Snes9x", "1.62.3", 2))
            item("cover", "Game.png", PNG, 1_700_000_100)
            item("label", "Game.png", PNG, 1_700_000_100)
        }
    }

    private fun model(manifest: Manifest, space: SpaceProbe = FakeSpace(), receivedAt: Long = 0): Pair<OfferModel, (Map<String, Policy>) -> ReceivePlan> {
        val planner = { overrides: Map<String, Policy> -> planReceive(manifest, paths, CoreIdentities(paths), overrides = overrides, probe = space) }
        return OfferModel.build(IncomingOffer("offer1", sender, manifest, "482913"), planner, emptyMap(), receivedAt) to planner
    }

    @Test
    fun `the sheet summary has the sender, the code and what each game brings`() {
        lib.installCore(Console.SFC.core("snes9x"), "Snes9x", "1.62.3")
        val (m, _) = model(sfcGame())
        assertEquals("Desk", m.sender)
        assertEquals("482913", m.code)
        assertEquals(OfferGame("g1", "Game", "SFC", true, 1, 2, true, true, 3L + 4 + 2 + 2 + PNG.size * 2), m.games.single())
        assertEquals(m.offer.manifest.totalSize, m.totalBytes)
        assertTrue(m.conflicts().isEmpty())
        assertTrue(m.skipped.isEmpty())
        assertTrue(m.spaceOk)
        assertNull(m.shortage)
        assertNull(m.error)
    }

    @Test
    fun `only items that meet a local file are conflicts, with both dates and the allowed choices`() {
        lib.installCore(Console.SFC.core("snes9x"), "Snes9x", "1.62.3")
        File(paths.saves(Console.SFC), "Game.srm").put("mine", mtime = 1_700_000_200)
        File(paths.covers(Console.SFC), "Game.jpg").put(JPEG, mtime = 1_600_000_000)
        val (m, _) = model(sfcGame())
        val conflicts = m.conflicts().associateBy { it.kind }
        assertEquals(setOf(Item.BATTERY_SAVE, Item.COVER), conflicts.keys)
        val save = conflicts.getValue(Item.BATTERY_SAVE)
        assertEquals("Game", save.title)
        assertEquals(1_700_000_200_000L, save.yours)
        assertEquals(1_700_000_100_000L, save.incoming)
        assertTrue(save.yoursNewer)
        assertFalse(save.incomingNewer)
        assertEquals(Policy.NEWEST, save.policy)
        assertEquals(OfferModel.ALL_POLICIES, save.allowed)
        val cover = conflicts.getValue(Item.COVER)
        assertTrue(cover.incomingNewer)
        assertEquals(Policy.SKIP, cover.policy)
        assertEquals(OfferModel.ART_POLICIES, cover.allowed)
        // Keep Newest keeps the newer local save; the conflicts are not listed again as skipped.
        assertFalse(m.plan.decisions.single { it.itemId == save.itemId }.send)
        assertTrue(m.skipped.isEmpty())
    }

    @Test
    fun `a choice replans, so replace sends the save and the probe stays`() {
        lib.installCore(Console.SFC.core("snes9x"), "Snes9x", "1.62.3")
        File(paths.saves(Console.SFC), "Game.srm").put("mine", mtime = 1_700_000_200)
        val (m, planner) = model(sfcGame(), receivedAt = 5)
        val save = m.conflicts().single()
        val next = m.replan(planner, mapOf(save.itemId to Policy.REPLACE))
        assertTrue(next.plan.decisions.single { it.itemId == save.itemId }.send)
        assertEquals(Policy.REPLACE, next.conflicts().single().policy)
        assertEquals(5L, next.receivedAt)
    }

    @Test
    fun `what is skipped is grouped by reason`() {
        File(paths.roms(Console.SFC), "Game.sfc").put("rom")
        val offer = manifest {
            game("g1", "SFC", "Game") {
                item("rom", "Game.sfc", "rom".toByteArray())
                item("state", "Game.state1", "s1".toByteArray(), state = stateInfo("Snes9x", "1.62.3", 1))
            }
            game("g2", "XYZ", "Alien") { item("rom", "Alien.xyz", "x".toByteArray()) }
        }
        val (m, _) = model(offer)
        assertEquals(1, m.incompatibleStates)
        assertEquals(listOf("Alien"), m.unsupported.map { it.title })
        val reasons = m.skipped.associate { it.itemId to it.reason }
        assertEquals(SkipReason.EXISTS_SKIP, reasons["g1i1"])
        assertEquals(SkipReason.INCOMPATIBLE_STATE, reasons["g1i2"])
        assertEquals(SkipReason.UNSUPPORTED_CONSOLE, reasons["g2i1"])
        assertEquals("Game", m.skipped.first { it.itemId == "g1i1" }.title)
        assertTrue(m.conflicts().isEmpty())
    }

    @Test
    fun `short of room it says what it needs, what is free and what to free, the reserve counted`() {
        val free = 1_000L
        val (m, _) = model(sfcGame(), FakeSpace(default = free))
        assertFalse(m.spaceOk)
        val (needed, has, missing) = m.shortage!!
        assertEquals(free, has)
        assertTrue(needed > 0)
        assertEquals(needed + ReceivePlan.SPACE_RESERVE - free, missing)
    }

    @Test
    fun `the countdown starts at 60, rounds up and never goes negative`() {
        val (m, _) = model(sfcGame(), receivedAt = 10_000)
        assertEquals(60, m.secondsLeft(10_000))
        assertEquals(60, m.secondsLeft(10_500))
        assertEquals(42, m.secondsLeft(28_000))
        assertEquals(1, m.secondsLeft(69_999))
        assertEquals(0, m.secondsLeft(70_000))
        assertEquals(0, m.secondsLeft(500_000))
    }

    @Test
    fun `an error keeps the rest of the model`() {
        val (m, _) = model(sfcGame())
        val failed = m.withError(ShareMessage.TOO_LARGE)
        assertEquals(ShareMessage.TOO_LARGE, failed.error)
        assertEquals(m.plan, failed.plan)
        assertEquals(m.conflicts(), failed.conflicts())
        assertNull(failed.withError(null).error)
    }
}
