package com.openemux.mobile.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** What a cold start opens on, and what a scan may change of it (issues #574 and #608). */
class LibraryStartupTest {

    private lateinit var root: File
    private lateinit var paths: Paths
    private var ready = true
    private var asked = 0

    @Before
    fun setUp() {
        root = Files.createTempDirectory("library-startup").toFile()
        paths = Paths(File(root, "external"), File(root, "internal"))
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    /** A new process: the library as Application.onCreate builds it. */
    private fun start() = Library(paths, { asked++; ready }, storageRetryMs = 0)

    private fun put(console: Console, name: String) =
        File(paths.roms(console), name).apply { parentFile!!.mkdirs(); writeText("") }

    private fun keys(library: Library) = library.roms.value.map { it.key }

    @Test
    fun aScanWhileTheStorageIsNotReadyKeepsTheRestoredLibraryAndItsIndex() = runBlocking {
        put(Console.GBA, "Zelda (USA).gba")
        put(Console.SFC, "Aero (Europe).sfc")
        start().scan()
        val index = paths.index.readText()

        // After a boot the shared storage lists nothing for a moment. Before #608 that scan
        // published an empty list and wrote an empty index: the next cold start opened on
        // "Your shelf is empty" and the consoles popped in only when a later scan found them.
        val mounted = File(root, "mounted")
        check(paths.base.renameTo(mounted))
        ready = false
        asked = 0
        val booted = start()
        booted.scan()
        assertEquals(listOf("SFC/Aero (Europe).sfc", "GBA/Zelda (USA).gba"), keys(booted))
        assertTrue(booted.loaded.value)
        assertEquals(index, paths.index.readText())
        assertEquals(Library.STORAGE_TRIES, asked)

        // The storage comes up: the next cold start opens on the shelf, and its scan finds the same games.
        paths.base.deleteRecursively()
        check(mounted.renameTo(paths.base))
        ready = true
        val next = start()
        assertEquals(ShelfContent.Shelves, shelfContent(next.loaded.value, next.roms.value))
        val restored = next.roms.value
        next.scan()
        assertEquals(restored, next.roms.value)
    }

    @Test
    fun withNoIndexAnUnreadyStorageStaysLoadingRatherThanEmpty() = runBlocking {
        put(Console.GBA, "Zelda (USA).gba")
        ready = false
        val library = start()
        library.scan()
        assertEquals(ShelfContent.Loading, shelfContent(library.loaded.value, library.roms.value))
        assertFalse(paths.index.exists())

        ready = true
        library.scan()
        assertEquals(ShelfContent.Shelves, shelfContent(library.loaded.value, library.roms.value))
    }

    @Test
    fun storageThatBecomesReadyWhileWaitingIsScanned() = runBlocking {
        put(Console.GBA, "Zelda (USA).gba")
        var calls = 0
        val library = Library(paths, { ++calls >= 3 }, storageRetryMs = 0)
        library.scan()
        assertEquals(3, calls)
        assertEquals(listOf("GBA/Zelda (USA).gba"), keys(library))
    }

    @Test
    fun aColdStartOpensOnTheLastScanFromItsFirstFrame() = runBlocking {
        put(Console.GBA, "Zelda (USA).gba")
        start().scan()
        val next = start()
        assertTrue(next.loaded.value)
        assertEquals(listOf("GBA/Zelda (USA).gba"), keys(next))
    }

    @Test
    fun removingEveryGameWithTheStorageReadyEmptiesTheShelf() = runBlocking {
        val file = put(Console.GBA, "Zelda (USA).gba")
        start().scan()
        file.delete()
        start().scan()
        val next = start()
        assertEquals(ShelfContent.Empty, shelfContent(next.loaded.value, next.roms.value))
    }

    @Test
    fun aScanOfUnchangedFilesEqualsTheRestoredList() = runBlocking {
        // Same title on several consoles and in several regions: only the key tells them apart.
        put(Console.SFC, "Mario (USA).sfc")
        put(Console.FC, "Mario (Europe).nes")
        put(Console.FC, "Mario (USA).nes")
        put(Console.GBA, "mario.gba")
        start().scan()
        val restored = start()
        val before = restored.roms.value
        restored.scan()
        assertEquals(before, restored.roms.value)
        assertEquals(listOf("FC/Mario (Europe).nes", "FC/Mario (USA).nes", "GBA/mario.gba", "SFC/Mario (USA).sfc"), keys(restored))
    }

    @Test
    fun aFailedIndexWriteIsRetriedByTheNextScan() = runBlocking {
        put(Console.GBA, "Zelda (USA).gba")
        // The internal folder is a file: the index cannot be written.
        paths.internal.writeText("")
        val library = start()
        library.scan()
        assertFalse(paths.index.exists())

        paths.internal.delete()
        library.scan()
        assertEquals(listOf("GBA/Zelda (USA).gba"), keys(start()))
    }
}
