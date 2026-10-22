package com.openemux.mobile.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.retroarch.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

data class BiosDef(
    val console: Console,
    /** Any one of these files satisfies the entry (the desktop's any_of). */
    val files: List<String>,
    val required: Boolean,
    val md5: Set<String> = emptySet(),
) {
    @Composable
    fun fileName(): String = files.joinToString(stringResource(R.string.data_bios_or))
}

private fun bios(console: Console, required: Boolean, vararg files: String) = BiosDef(console, files.toList(), required)

/**
 * The desktop's bios_catalog.py. A file is recognized by its MD5 when we know it,
 * otherwise by its name, as the desktop does. kronos (Saturn) has no Android core,
 * so its entry is left out.
 */
val KNOWN_BIOS = listOf(
    bios(Console.A5200, true, "5200.rom"),
    bios(Console.A5200, false, "ATARIXL.ROM"),
    bios(Console.A5200, false, "ATARIBAS.ROM"),
    bios(Console.A5200, false, "ATARIOSA.ROM"),
    bios(Console.A5200, false, "ATARIOSB.ROM"),
    bios(Console.LYNX, true, "lynxboot.img"),
    bios(Console.FDS, true, "disksys.rom"),
    bios(Console.FDS, false, "gamegenie.nes"),
    BiosDef(Console.GBA, listOf("gba_bios.bin"), false, setOf("a860e8c0b6d573d191e4ec7db1b1e4f6")),
    bios(Console.GG, false, "bios.gg"),
    bios(Console.INTV, true, "exec.bin"),
    bios(Console.INTV, true, "grom.bin"),
    bios(Console.MCD, true, "bios_CD_U.bin", "bios_CD_E.bin", "bios_CD_J.bin"),
    bios(Console.MCD, false, "bios_MD.bin"),
    bios(Console.NDS, false, "bios7.bin"),
    bios(Console.NDS, false, "bios9.bin"),
    bios(Console.NDS, false, "firmware.bin"),
    bios(Console.NDS, false, "dsi_bios7.bin"),
    bios(Console.NDS, false, "dsi_bios9.bin"),
    bios(Console.NDS, false, "dsi_firmware.bin"),
    bios(Console.NDS, false, "dsi_nand.bin"),
    bios(Console.O2, true, "o2rom.bin"),
    bios(Console.O2, false, "c52.bin"),
    bios(Console.O2, false, "g7400.bin"),
    bios(Console.O2, false, "jopac.bin"),
    bios(Console.PCECD, true, "syscard3.pce", "syscard2.pce", "syscard1.pce", "gexpress.pce"),
    bios(
        Console.PS, true, "scph5500.bin", "scph5501.bin", "scph5502.bin", "PSXONPSP660.bin",
        "ps1_rom.bin", "scph101.bin", "scph7001.bin", "scph1001.bin",
    ),
    bios(Console.SATURN, true, "sega_101.bin", "mpr-17933.bin"),
    bios(Console.SATURN, false, "mpr-18811-mx.ic1"),
    bios(Console.SATURN, false, "mpr-19367-mx.ic1"),
    bios(Console.SMS, false, "bios.sms"),
    bios(Console.GC, false, "IPL.bin"),
)

private val DISC_SHEETS = setOf("cue", "chd", "pbp", "iso", "cso", "rvz", "gcm", "ciso")

data class ImportResult(val imported: Int, val skipped: Int, val names: List<String> = emptyList())

/** One entry the user picked: a document with a display name. */
private data class Picked(val uri: Uri, val name: String, val size: Long)

class Importer(private val context: Context, private val library: Library) {
    private val resolver get() = context.contentResolver

    /**
     * Extensions in this batch that more than one console takes, with the
     * candidates, most likely first. The UI asks once per extension, like the
     * desktop's import (rom_importer.py).
     */
    suspend fun ambiguities(uris: List<Uri>): Map<String, List<Console>> = withContext(Dispatchers.IO) {
        ambiguitiesOf(uris.mapNotNull { describe(it) })
    }

    suspend fun ambiguitiesInTree(tree: Uri): Map<String, List<Console>> = withContext(Dispatchers.IO) {
        ambiguitiesOf(walkTree(tree))
    }

    suspend fun importRoms(uris: List<Uri>, choices: Map<String, Console> = emptyMap()): ImportResult = withContext(Dispatchers.IO) {
        importPicked(uris.mapNotNull { describe(it) }, choices)
    }

    suspend fun importRomTree(tree: Uri, choices: Map<String, Console> = emptyMap()): ImportResult = withContext(Dispatchers.IO) {
        importPicked(walkTree(tree), choices)
    }

    private fun ambiguitiesOf(files: List<Picked>): Map<String, List<Console>> {
        val followers = cueTracks(files)
        return files.filter { it.name !in followers }
            .mapNotNull { key(it)?.let { ext -> ext to Console.forExtension(ext) } }
            .filter { it.second.size > 1 }
            .toMap()
    }

    /** The extension that decides a file's console; for a zip, its main entry's. */
    private fun key(picked: Picked): String? {
        val ext = picked.name.substringAfterLast('.', "").lowercase()
        if (ext != "zip") return ext.takeIf { Console.forExtension(it).isNotEmpty() }
        val exts = resolver.openInputStream(picked.uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                generateSequence { zip.nextEntry }.filter { !it.isDirectory }
                    .map { it.name.substringAfterLast('.', "").lowercase() }
                    .filter { Console.forExtension(it).isNotEmpty() }.toList()
            }
        }.orEmpty()
        // A disc's sheet names the console better than its tracks do.
        return exts.firstOrNull { it in DISC_SHEETS } ?: exts.firstOrNull { it != "bin" } ?: exts.firstOrNull()
    }

    private fun consoleFor(ext: String, choices: Map<String, Console>): Console? {
        val candidates = Console.forExtension(ext)
        return if (candidates.size > 1) choices[ext] ?: candidates.first() else candidates.firstOrNull()
    }

    /** Track files named by a .cue in the batch: they go wherever their sheet goes. */
    private fun cueTracks(files: List<Picked>): Map<String, String> {
        val tracks = mutableMapOf<String, String>()
        for (cue in files.filter { it.name.endsWith(".cue", ignoreCase = true) }) {
            val text = resolver.openInputStream(cue.uri)?.use { it.readBytes().decodeToString() } ?: continue
            CUE_FILE.findAll(text).forEach { tracks[it.groupValues[1].ifEmpty { it.groupValues[2] }.trim()] = cue.name }
        }
        return tracks
    }

    suspend fun importBiosTree(tree: Uri): ImportResult = withContext(Dispatchers.IO) {
        val byHash = KNOWN_BIOS.flatMap { def -> def.md5.map { it to def.files.first() } }.toMap()
        val byName = KNOWN_BIOS.filter { it.md5.isEmpty() }.flatMap { it.files }.associateBy { it.lowercase() }
        var imported = 0
        var skipped = 0
        val names = mutableListOf<String>()
        library.paths.system.mkdirs()
        for (file in walkTree(tree)) {
            // Every BIOS we know is far below this; anything larger is a game or a video.
            if (file.size > 32L * 1024 * 1024) { skipped++; continue }
            val named = byName[file.name.lowercase()]
            val bytes = if (named != null || byHash.isNotEmpty()) resolver.openInputStream(file.uri)?.use { it.readBytes() } else null
            val target = named ?: bytes?.let { byHash[md5(it)] }
            if (target == null || bytes == null) { skipped++; continue }
            File(library.paths.system, target).writeBytes(bytes)
            names += target
            imported++
        }
        ImportResult(imported, skipped, names)
    }

    fun biosPresent(def: BiosDef): Boolean = def.files.any { File(library.paths.system, it).exists() }

    private fun importPicked(files: List<Picked>, choices: Map<String, Console>): ImportResult {
        var imported = 0
        var skipped = 0
        val followers = cueTracks(files)
        val cueConsole = mutableMapOf<String, Console?>()
        // Sheets first, so their tracks know where to go.
        for (picked in files.sortedBy { if (it.name.endsWith(".cue", ignoreCase = true)) 0 else 1 }) {
            val ext = picked.name.substringAfterLast('.', "").lowercase()
            val ok = if (ext == "zip") {
                val console = key(picked)?.let { consoleFor(it, choices) }
                console != null && (resolver.openInputStream(picked.uri)?.use { extractZip(it, console) } ?: false)
            } else {
                val console = followers[picked.name]?.let { cueConsole[it] } ?: consoleFor(ext, choices)
                if (ext == "cue") cueConsole[picked.name] = console
                if (console == null) false
                else resolver.openInputStream(picked.uri)?.use { copyInto(console, picked.name, it); true } ?: false
            }
            // A disc's track files are part of the game, not games of their own.
            if (ok && picked.name !in followers) imported++ else if (!ok) skipped++
        }
        return ImportResult(imported, skipped)
    }

    private fun extractZip(input: InputStream, console: Console): Boolean {
        var any = false
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.substringAfterLast('/')
                val ext = name.substringAfterLast('.', "").lowercase()
                // Disc tracks travel with their sheet even where .bin is not a game of its own.
                if (ext !in console.extensions && !(ext == "bin" && "cue" in console.extensions)) continue
                copyInto(console, name, zip)
                any = true
            }
        }
        return any
    }

    private fun copyInto(console: Console, name: String, input: InputStream) {
        val dir = library.paths.roms(console).apply { mkdirs() }
        val target = File(dir, name.replace('/', '_'))
        val tmp = File(dir, ".${target.name}.part")
        tmp.outputStream().use { input.copyTo(it) }
        tmp.renameTo(target)
    }

    private fun describe(uri: Uri): Picked? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst()) Picked(uri, c.getString(0) ?: return null, c.getLong(1)) else null }

    private fun walkTree(tree: Uri): List<Picked> {
        val out = mutableListOf<Picked>()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val pending = ArrayDeque(listOf(rootId))
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        var visited = 0
        while (pending.isNotEmpty() && visited < 5000) {
            val parent = pending.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            resolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    visited++
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) pending += id
                    else out += Picked(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, c.getLong(3))
                }
            }
        }
        return out
    }

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
}
