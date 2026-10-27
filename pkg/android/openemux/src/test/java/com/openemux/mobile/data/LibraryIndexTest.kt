package com.openemux.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class LibraryIndexTest {

    private val base = File("/roms")
    private val dir: (Console) -> File = { File(base, it.id) }

    /** A ROM the way Library.scan builds it. */
    private fun rom(console: Console, name: String) =
        Rom("${console.id}/$name", console, File(dir(console), name), titleFromFileName(name))

    private fun scanned(vararg roms: Rom) = roms.sortedWith(ROM_ORDER)

    @Test
    fun aDecodedIndexEqualsTheScanItCameFrom() {
        val roms = scanned(
            rom(Console.GBA, "Zelda (USA).gba"),
            rom(Console.SFC, "Aero (Europe).sfc"),
            rom(Console.GBA, "metroid.GBA"),
        )
        assertEquals(roms, decodeLibraryIndex(encodeLibraryIndex(roms), dir))
    }

    @Test
    fun anEmptyLibraryIsKnownEmpty() {
        assertEquals(emptyList<Rom>(), decodeLibraryIndex(encodeLibraryIndex(emptyList()), dir))
        assertEquals(emptyList<Rom>(), decodeLibraryIndex("v1", dir))
    }

    @Test
    fun textThisVersionDidNotWriteIsNoIndex() {
        assertNull(decodeLibraryIndex("", dir))
        assertNull(decodeLibraryIndex("v2\nGBA/a.gba\n", dir))
        assertNull(decodeLibraryIndex("GBA/a.gba\n", dir))
    }

    @Test
    fun linesThatAreNotRomsAreDropped() {
        val text = listOf(
            "v1",
            "",
            "   ",
            "GBA/a.gba",
            "GBA/a.gba",
            "XYZ/b.gba",
            "GBA/c.sfc",
            "GBA/noextension",
            "no-slash.gba",
        ).joinToString("\n")
        assertEquals(listOf(rom(Console.GBA, "a.gba")), decodeLibraryIndex(text, dir))
    }

    @Test
    fun theConsoleLookupCanBeInjected() {
        assertEquals(emptyList<Rom>(), decodeLibraryIndex("v1\nGBA/a.gba", dir) { null })
    }

    @Test
    fun aKeyWithALineBreakIsNotWritten() {
        val broken = Rom("GBA/a\nb.gba", Console.GBA, File("a\nb.gba"), "a")
        val carriage = Rom("GBA/a\rb.gba", Console.GBA, File("a\rb.gba"), "a")
        val good = rom(Console.GBA, "c.gba")
        assertEquals("v1\nGBA/c.gba\n", encodeLibraryIndex(listOf(broken, carriage, good)))
    }

    @Test
    fun theShelfWaitsUntilTheLibraryIsKnown() {
        val some = listOf(rom(Console.GBA, "a.gba"))
        assertEquals(ShelfContent.Loading, shelfContent(loaded = false, roms = emptyList()))
        assertEquals(ShelfContent.Empty, shelfContent(loaded = true, roms = emptyList()))
        assertEquals(ShelfContent.Shelves, shelfContent(loaded = false, roms = some))
        assertEquals(ShelfContent.Shelves, shelfContent(loaded = true, roms = some))
    }

    @Test
    fun matchingTitlesDecodeInOneOrderWhateverOrderTheyWereListedIn() {
        val listed = listOf(
            rom(Console.SFC, "Mario (USA).sfc"),
            rom(Console.FC, "Mario (USA).nes"),
            rom(Console.GBA, "mario.gba"),
            rom(Console.FC, "Mario (Europe).nes"),
        )
        val expected = listOf("FC/Mario (Europe).nes", "FC/Mario (USA).nes", "GBA/mario.gba", "SFC/Mario (USA).sfc")
        assertEquals(expected, listed.sortedWith(ROM_ORDER).map { it.key })
        assertEquals(expected, decodeLibraryIndex(encodeLibraryIndex(listed), dir)!!.map { it.key })
        assertEquals(expected, decodeLibraryIndex(encodeLibraryIndex(listed.reversed()), dir)!!.map { it.key })
    }
}
