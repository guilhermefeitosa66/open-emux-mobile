package com.openemux.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MetaTest {
    private val meta = Meta(
        roms = mapOf("FC/a.nes" to RomMeta(favorite = true), "FC/b.nes" to RomMeta(lastPlayed = 5), "SFC/c.sfc" to RomMeta()),
        consoles = mapOf("FC" to ConsoleSettings("fceumm", "none")),
        consoleOrder = listOf("SFC", "FC"),
    )

    @Test
    fun withoutRomsDropsTheListedGames() {
        assertEquals(setOf("FC/b.nes"), meta.withoutRoms(setOf("FC/a.nes", "SFC/c.sfc")).roms.keys)
    }

    @Test
    fun withoutRomsKeepsConsoleSettingsAndOrder() {
        val m = meta.withoutRoms(setOf("FC/a.nes"))
        assertEquals(meta.consoles, m.consoles)
        assertEquals(meta.consoleOrder, m.consoleOrder)
        assertEquals(RomMeta(lastPlayed = 5), m.roms["FC/b.nes"])
    }

    @Test
    fun withoutRomsIgnoresUnknownKeys() {
        assertEquals(meta, meta.withoutRoms(setOf("GBA/x.gba")))
        assertEquals(meta, meta.withoutRoms(emptySet()))
    }
}
