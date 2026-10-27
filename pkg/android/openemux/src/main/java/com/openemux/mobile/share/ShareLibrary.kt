package com.openemux.mobile.share

import com.openemux.mobile.data.Console
import com.openemux.mobile.data.CoreDef
import com.openemux.mobile.data.Covers
import com.openemux.mobile.data.Library
import com.openemux.mobile.data.Paths
import com.openemux.mobile.data.Rom
import com.openemux.mobile.data.RomMeta
import com.openemux.mobile.data.SaveState
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * The library as Share nearby needs it. [Library] needs a `Context`; this
 * port lets the manifest, placement and transfer code run in JVM tests over a
 * real folder layout.
 */
interface ShareLibrary {
    val paths: Paths
    fun romMeta(key: String): RomMeta
    fun effectiveCore(rom: Rom): CoreDef
    fun states(rom: Rom): List<SaveState>
    fun playtimeSeconds(rom: Rom): Long
    fun updateRom(key: String, change: (RomMeta) -> RomMeta)
    /** A transfer placed [placed]: rescan, so the games show. Called off the main thread. */
    fun onReceived(placed: List<File>)
}

/** The library key of a ROM file of [console], as [Library.scan] builds it. */
fun romKey(console: Console, file: File) = "${console.id}/${file.name}"

/** [ShareLibrary] over the app's [Library]. */
class LibraryShareAdapter(private val library: Library, private val covers: Covers) : ShareLibrary {
    override val paths: Paths get() = library.paths
    override fun romMeta(key: String) = library.romMeta(key)
    override fun effectiveCore(rom: Rom) = library.effectiveCore(rom)
    override fun states(rom: Rom) = library.states(rom)
    override fun playtimeSeconds(rom: Rom) = library.playtimeSeconds(rom)
    override fun updateRom(key: String, change: (RomMeta) -> RomMeta) = library.updateRom(key, change)

    override fun onReceived(placed: List<File>) {
        covers.forget(Console.entries.flatMap { console ->
            placed.filter { it.parentFile == paths.roms(console) }.map { romKey(console, it) }
        })
        runBlocking { library.scan() }
    }
}
