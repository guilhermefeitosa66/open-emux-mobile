package com.openemux.mobile.share

import com.openemux.mobile.data.CUE_FILE
import com.openemux.mobile.data.Console
import java.io.File
import java.io.IOException

/**
 * Rules both sides of a transfer share: what makes up a game's ROM unit, which
 * files are its battery saves, what a received name may be, what a state file
 * is called and whether received art is an image. Pure apart from reading the
 * files they describe; the desktop counterparts live in `core/share/manifest.py`.
 */
object Units {
    const val ROLE_CUE = "cue"
    const val ROLE_BIN = "bin"
    const val ROLE_M3U = "m3u"
    const val ROLE_DISC = "disc"
    const val ROLE_MAIN = "main"
    val PART_ROLES = setOf(ROLE_CUE, ROLE_BIN, ROLE_M3U, ROLE_DISC, ROLE_MAIN)
    /** The roles that carry the unit's name; the others are referenced by it. */
    val MAIN_ROLES = setOf(ROLE_CUE, ROLE_M3U, ROLE_MAIN)

    val ART_SUFFIXES = setOf("png", "jpg", "jpeg", "webp")
    private val UNIT_SUFFIXES = setOf("cue", "m3u")

    /** The slot of `<stem>.state.auto`, as [com.openemux.mobile.data.SaveState] numbers it. */
    const val AUTO_SLOT = -1
    const val AUTO_SLOT_WIRE = "auto"
    val STATE_SLOTS = 0..9

    /** A ROM's files, the main one first, each with its role. */
    class UnitFiles(val main: File, val parts: List<Pair<File, String>>) {
        val multi: Boolean get() = parts.size > 1
    }

    /**
     * The files [rom] is made of, or null when one is missing. A `.cue` brings
     * every file its `FILE` lines name (role `bin`); an `.m3u` brings its discs
     * (role `disc`) and each disc's own cue references. A reference that leaves
     * the folder cannot travel without breaking the sheet, so it makes the
     * unit incomplete too.
     */
    fun collectUnit(rom: File): UnitFiles? {
        if (!rom.isFile) return null
        val dir = rom.parentFile ?: return null
        val parts = mutableListOf(rom to ROLE_MAIN)
        when (rom.extension.lowercase()) {
            "cue" -> {
                parts[0] = rom to ROLE_CUE
                if (!referenced(dir, cueReferences(rom) ?: return null, ROLE_BIN, parts)) return null
            }
            "m3u" -> {
                parts[0] = rom to ROLE_M3U
                val discs = m3uEntries(rom) ?: return null
                if (!referenced(dir, discs, ROLE_DISC, parts)) return null
                for (disc in discs.filter { it.lowercase().endsWith(".cue") }) {
                    if (!referenced(dir, cueReferences(File(dir, disc)) ?: return null, ROLE_BIN, parts)) return null
                }
            }
        }
        return UnitFiles(rom, parts)
    }

    private fun cueReferences(cue: File): List<String>? = try {
        CUE_FILE.findAll(cue.readText()).map { it.groupValues[1].ifEmpty { it.groupValues[2] }.trim() }
            .filter { it.isNotEmpty() }.toList()
    } catch (_: IOException) {
        null
    }

    private fun m3uEntries(m3u: File): List<String>? = try {
        m3u.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    } catch (_: IOException) {
        null
    }

    private fun referenced(dir: File, names: List<String>, role: String, parts: MutableList<Pair<File, String>>): Boolean {
        for (name in names) {
            val file = File(dir, name)
            if ('/' in name || '\\' in name || name == "." || name == ".." || !file.isFile) return false
            if (parts.none { it.first == file }) parts += file to role
        }
        return true
    }

    /**
     * Is [name], in the console's saves folder, a core's save of the game whose
     * file stem is [stem]? It starts with the stem and a dot, has a suffix, and
     * that suffix is neither art, a unit sheet nor a ROM the console loads.
     * Dotfiles (temporary files among them) are never saves.
     */
    fun isBatterySave(name: String, stem: String, console: Console): Boolean {
        if (name.startsWith(".") || !name.startsWith("$stem.")) return false
        val suffix = name.substring(stem.length).substringAfterLast('.').lowercase()
        if (suffix.isEmpty()) return false
        return suffix !in ART_SUFFIXES && suffix !in UNIT_SUFFIXES && suffix !in console.extensions
    }

    /** A bare file name this side will write: never a path, never a dotfile. */
    fun isReceivableName(name: String): Boolean = ManifestParser.isSafeFileName(name) && !name.startsWith(".")

    /** `<stem>.state`, `<stem>.state<N>` or `<stem>.state.auto`, the names RetroArch uses. */
    fun stateFileName(stem: String, slot: Int): String = when (slot) {
        AUTO_SLOT -> "$stem.state.auto"
        0 -> "$stem.state"
        else -> "$stem.state$slot"
    }

    /** `png`, `jpeg` or `webp` from a file's first bytes; null for anything else. */
    fun imageKind(header: ByteArray): String? {
        fun at(index: Int, vararg bytes: Int) = header.size >= index + bytes.size &&
            bytes.withIndex().all { (i, b) -> header[index + i] == b.toByte() }
        return when {
            at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "png"
            at(0, 0xFF, 0xD8, 0xFF) -> "jpeg"
            at(0, 'R'.code, 'I'.code, 'F'.code, 'F'.code) && at(8, 'W'.code, 'E'.code, 'B'.code, 'P'.code) -> "webp"
            else -> null
        }
    }

    /** [imageKind] of a file's head; null when unreadable. */
    fun imageKind(file: File): String? = try {
        file.inputStream().use { input ->
            val head = ByteArray(12)
            var read = 0
            while (read < head.size) {
                val n = input.read(head, read, head.size - read)
                if (n < 0) break
                read += n
            }
            imageKind(head.copyOf(read))
        }
    } catch (_: IOException) {
        null
    }
}
