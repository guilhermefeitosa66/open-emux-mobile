package com.openemux.mobile.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

data class BiosDef(
    val console: Console,
    val fileName: String,
    val md5: Set<String>,
    val required: Boolean,
)

/** BIOS files the POC consoles can use, recognized by content, never by name. */
val KNOWN_BIOS = listOf(
    BiosDef(Console.GBA, "gba_bios.bin", setOf("a860e8c0b6d573d191e4ec7db1b1e4f6"), required = false),
)

data class ImportResult(val imported: Int, val skipped: Int, val names: List<String> = emptyList())

/** One entry the user picked: a document with a display name. */
private data class Picked(val uri: Uri, val name: String, val size: Long)

class Importer(private val context: Context, private val library: Library) {
    private val resolver get() = context.contentResolver

    suspend fun importRoms(uris: List<Uri>): ImportResult = withContext(Dispatchers.IO) {
        importPicked(uris.mapNotNull { describe(it) })
    }

    suspend fun importRomTree(tree: Uri): ImportResult = withContext(Dispatchers.IO) {
        importPicked(walkTree(tree))
    }

    suspend fun importBiosTree(tree: Uri): ImportResult = withContext(Dispatchers.IO) {
        val byHash = KNOWN_BIOS.flatMap { def -> def.md5.map { it to def } }.toMap()
        var imported = 0
        var skipped = 0
        val names = mutableListOf<String>()
        library.paths.system.mkdirs()
        for (file in walkTree(tree)) {
            // Every BIOS we know is far below this; anything larger is a game or a video.
            if (file.size > 32L * 1024 * 1024) { skipped++; continue }
            val bytes = resolver.openInputStream(file.uri)?.use { it.readBytes() }
            val def = bytes?.let { byHash[md5(it)] }
            if (def == null) { skipped++; continue }
            File(library.paths.system, def.fileName).writeBytes(bytes)
            names += def.fileName
            imported++
        }
        ImportResult(imported, skipped, names)
    }

    fun biosPresent(def: BiosDef): Boolean = File(library.paths.system, def.fileName).exists()

    private fun importPicked(files: List<Picked>): ImportResult {
        var imported = 0
        var skipped = 0
        for (picked in files) {
            val ext = picked.name.substringAfterLast('.', "").lowercase()
            val ok = if (ext == "zip") {
                resolver.openInputStream(picked.uri)?.use { extractZip(it) } ?: false
            } else {
                val console = Console.forExtension(ext)
                if (console == null) false
                else resolver.openInputStream(picked.uri)?.use { copyInto(console, picked.name, it); true } ?: false
            }
            if (ok) imported++ else skipped++
        }
        return ImportResult(imported, skipped)
    }

    private fun extractZip(input: InputStream): Boolean {
        var any = false
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.substringAfterLast('/')
                val console = Console.forExtension(name.substringAfterLast('.', "")) ?: continue
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
