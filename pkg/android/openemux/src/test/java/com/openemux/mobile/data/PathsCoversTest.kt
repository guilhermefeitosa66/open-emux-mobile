package com.openemux.mobile.data

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PathsCoversTest {
    private val root = Files.createTempDirectory("paths").toFile()
    private val paths = Paths(File(root, "ext"), File(root, "int"))

    @Test
    fun `the plain constructor lays out the same folders`() {
        assertEquals(File(root, "ext/roms/SFC"), paths.roms(Console.SFC))
        assertEquals(File(root, "ext/saves/SFC"), paths.saves(Console.SFC))
        assertEquals(File(root, "ext/states/SFC"), paths.states(Console.SFC))
        assertEquals(File(root, "int/labels/SFC"), paths.labels(Console.SFC))
        assertEquals(File(root, "int/covers/SFC"), paths.covers(Console.SFC))
        assertEquals(File(root, "ext/playtime/SFC_Game.txt"), paths.playtime(Console.SFC, "Game"))
    }

    @Test
    fun `a cover kept on the device wins and is looked for on every call`() {
        val covers = Covers(ContextWrapper(null), paths)
        val rom = Rom("SFC/Game (USA).sfc", Console.SFC, File(paths.roms(Console.SFC), "Game (USA).sfc"), "Game")
        val dir = paths.covers(Console.SFC).apply { mkdirs() }
        assertNull(covers.localCover(rom))
        File(dir, "Game (USA).webp").writeText("w")
        assertEquals(File(dir, "Game (USA).webp"), covers.localCover(rom))
        File(dir, "Game (USA).jpg").writeText("j")
        assertEquals(File(dir, "Game (USA).jpg"), covers.localCover(rom))
        assertEquals(File(dir, "Game (USA).jpg").toURI().toASCIIString(), covers.urlFor(rom))
        assertEquals(true, covers.urlFor(rom)!!.startsWith("file:/"))
        assertEquals(true, covers.urlFor(rom)!!.contains("Game%20(USA).jpg"))
        covers.forget(listOf(rom.key))
        File(dir, "Game (USA).png").writeText("p")
        assertEquals(File(dir, "Game (USA).png"), covers.localCover(rom))
    }
}
