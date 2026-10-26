package com.openemux.mobile.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The backup archive (issue #545): a plain ZIP whose first entry is a manifest,
 * then files under a fixed set of roots. Only what is named here is written or
 * read back: an allowlist, so a private file added later stays out by default.
 * Nothing in this file touches a Context, so it runs on the JVM in the tests.
 */
enum class BackupGroup(val id: String) { ROMS("roms"), SAVES("saves"), SETTINGS("settings") }

data class BackupSize(val files: Int = 0, val bytes: Long = 0) {
    operator fun plus(other: BackupSize) = BackupSize(files + other.files, bytes + other.bytes)
}

/** What went wrong; every one of them leaves the app's data as it was. */
sealed class BackupError(message: String? = null) : Exception(message) {
    class NotABackup(detail: String? = null) : BackupError(detail)
    class TooNew : BackupError()
    class NoSpace(val needed: Long, val free: Long) : BackupError()
    class Io(detail: String?) : BackupError(detail)
    class Cancelled : BackupError()
}

/** One place the archive covers: a directory or a single file, under external/ or internal/. */
class BackupRoot(val name: String, val group: BackupGroup, val file: File, val isDir: Boolean)

/** The device side of the archive: where each root lives, and the staging next to it. */
class BackupRoots(val external: File, val internal: File) {
    val all = listOf(
        BackupRoot("external/roms", BackupGroup.ROMS, File(external, "roms"), true),
        BackupRoot("external/saves", BackupGroup.SAVES, File(external, "saves"), true),
        BackupRoot("external/states", BackupGroup.SAVES, File(external, "states"), true),
        BackupRoot("external/playtime", BackupGroup.SAVES, File(external, "playtime"), true),
        BackupRoot("external/screenshots", BackupGroup.SAVES, File(external, "screenshots"), true),
        BackupRoot("external/system", BackupGroup.SETTINGS, File(external, "system"), true),
        BackupRoot("internal/meta.json", BackupGroup.SETTINGS, File(internal, "meta.json"), false),
        BackupRoot("internal/nds-layout", BackupGroup.SETTINGS, File(internal, "nds-layout"), false),
        BackupRoot("internal/labels", BackupGroup.SETTINGS, File(internal, "labels"), true),
    )

    fun byName(name: String) = all.firstOrNull { it.name == name }

    /** Staging lives on the same volume as each root, so moving it in is a rename. */
    fun staging(root: BackupRoot): File =
        File(if (root.name.startsWith("external/")) external else internal, "$STAGING/${root.name}")

    val stagingDirs get() = listOf(File(external, STAGING), File(internal, STAGING)).distinct()

    private val committed get() = File(internal, "$STAGING/committed")

    /** Roots the device did not have, written before each is moved in: no .old says to remove them. */
    private val added get() = File(internal, "$STAGING/added")

    /** The device copy a swap set aside: kept until every root is in place. */
    fun old(root: BackupRoot) = File(root.file.path + ".old")

    /** The roots of the groups to export; a single file only when there is one. */
    fun exported(includeRoms: Boolean) = all.filter {
        (includeRoms || it.group != BackupGroup.ROMS) && (it.isDir || it.file.isFile)
    }

    /** Every file under [root], by its archive name. Half-written files stay out. */
    fun files(root: BackupRoot): List<Pair<String, File>> {
        if (!root.isDir) return if (root.file.isFile) listOf(root.name to root.file) else emptyList()
        if (!root.file.isDirectory) return emptyList()
        return root.file.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".tmp") && !it.name.endsWith(".part") }
            .map { root.name + "/" + it.relativeTo(root.file).invariantSeparatorsPath to it }
            .sortedBy { it.first }
            .toList()
    }

    /** [roots] go in place of the device's; on any failure, every root moved so far goes back. */
    fun swapIn(roots: List<BackupRoot>) {
        val moved = mutableListOf<BackupRoot>()
        try {
            for (root in roots) {
                val old = old(root)
                old.deleteRecursively()
                if (root.file.exists()) {
                    if (!rename(root.file, old)) throw IOException("could not set aside ${root.name}")
                } else {
                    added.parentFile.mkdirs()
                    added.appendText(root.name + "\n")
                }
                moved += root
                root.file.parentFile.mkdirs()
                if (!rename(staging(root), root.file)) throw IOException("could not restore ${root.name}")
            }
        } catch (e: IOException) {
            // A root the device did not have goes again; one it had comes back.
            moved.asReversed().forEach { root ->
                root.file.deleteRecursively()
                old(root).renameTo(root.file)
            }
            throw BackupError.Io(e.message)
        }
        committed.parentFile.mkdirs()
        committed.writeText("")
        finish()
    }

    /**
     * After a restore the process was killed in: a swap that got to the end is
     * finished, one that did not is undone. Then the staging goes.
     */
    fun recover() {
        val done = committed.exists()
        for (root in all) {
            val old = old(root)
            if (!old.exists()) continue
            if (done) old.deleteRecursively()
            else { root.file.deleteRecursively(); old.renameTo(root.file) }
        }
        // A root the swap brought in where the device had none goes again, unless the swap finished.
        if (!done && added.isFile) {
            added.readLines().mapNotNull { byName(it) }.forEach { it.file.deleteRecursively() }
        }
        finish()
    }

    private fun finish() {
        all.forEach { old(it).deleteRecursively() }
        stagingDirs.forEach { it.deleteRecursively() }
    }

    /** A seam for the tests: a rename that fails partway through a swap. */
    internal var rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) }

    companion object {
        const val STAGING = ".restore-staging"
    }
}

data class BackupManifest(
    val app: String,
    val created: Long,
    val roots: List<String>,
    val groups: Map<BackupGroup, BackupSize>,
    val roms: Int,
    val saves: Int,
    val version: Int = BackupFormat.VERSION,
) {
    val total: BackupSize get() = groups.values.fold(BackupSize()) { a, b -> a + b }

    fun toJson(): String = JSONObject()
        .put("format", BackupFormat.FORMAT)
        .put("version", version)
        .put("app", app)
        .put("created", created)
        .put("roots", JSONArray(roots))
        .put("groups", JSONObject().apply {
            groups.forEach { (g, s) -> put(g.id, JSONObject().put("files", s.files).put("bytes", s.bytes)) }
        })
        .put("roms", roms)
        .put("saves", saves)
        .toString()

    companion object {
        fun parse(text: String): BackupManifest {
            val json = try { JSONObject(text) } catch (e: JSONException) { throw BackupError.NotABackup("manifest") }
            if (json.optString("format") != BackupFormat.FORMAT) throw BackupError.NotABackup("format")
            val version = json.optInt("version", 0)
            if (version < 1) throw BackupError.NotABackup("version")
            if (version > BackupFormat.VERSION) throw BackupError.TooNew()
            return try {
                val groupsJson = json.getJSONObject("groups")
                val groups = BackupGroup.entries.mapNotNull { g ->
                    groupsJson.optJSONObject(g.id)?.let { g to BackupSize(it.getInt("files"), it.getLong("bytes")) }
                }.toMap()
                if (groups.values.any { it.files < 0 || it.bytes < 0 }) throw BackupError.NotABackup("sizes")
                val roots = json.getJSONArray("roots").let { a -> (0 until a.length()).map { a.getString(it) } }
                BackupManifest(json.optString("app"), json.optLong("created"), roots, groups, json.optInt("roms"), json.optInt("saves"), version)
            } catch (e: JSONException) {
                throw BackupError.NotABackup("manifest")
            }
        }
    }
}

object BackupFormat {
    const val FORMAT = "openemux-mobile-backup"
    const val VERSION = 1
    const val MANIFEST = "manifest.json"
    const val PREFS = "prefs/openemux.json"
    /** No settings file comes anywhere near this; a larger one is not ours. */
    const val PREFS_LIMIT = 1L shl 20

    /** The settings as typed values, so a restore puts back an Int as an Int. */
    fun prefsToJson(prefs: Map<String, *>): JSONObject {
        val json = JSONObject()
        prefs.forEach { (key, value) ->
            val (type, v) = when (value) {
                is String -> "string" to value
                is Boolean -> "boolean" to value
                is Int -> "int" to value
                is Long -> "long" to value
                is Float -> "float" to value.toDouble()
                is Set<*> -> "set" to JSONArray(value.filterIsInstance<String>().sorted())
                else -> return@forEach
            }
            json.put(key, JSONObject().put("t", type).put("v", v))
        }
        return json
    }

    /** The other way round; an entry of a type we do not know is skipped. */
    fun prefsFromJson(json: JSONObject): Map<String, Any> = json.keys().asSequence().mapNotNull { key ->
        val entry = json.optJSONObject(key) ?: return@mapNotNull null
        val value: Any = when (entry.optString("t")) {
            "string" -> entry.optString("v")
            "boolean" -> entry.optBoolean("v")
            "int" -> entry.optInt("v")
            "long" -> entry.optLong("v")
            "float" -> entry.optDouble("v").toFloat()
            "set" -> entry.optJSONArray("v")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: return@mapNotNull null
            else -> return@mapNotNull null
        }
        key to value
    }.toMap()
}

/** Progress: the root being copied (its last part, e.g. "states") and bytes done of the total. */
typealias BackupProgress = (folder: String, done: Long, total: Long) -> Unit

private const val BUFFER = 64 * 1024

class BackupWriter(private val roots: BackupRoots) {
    /** Sizes per group; the settings group without the preferences, which are measured on export. */
    fun measure(includeRoms: Boolean): Map<BackupGroup, BackupSize> =
        roots.exported(includeRoms).groupBy { it.group }.mapValues { (_, list) ->
            list.flatMap { roots.files(it) }.fold(BackupSize()) { s, (_, f) -> s + BackupSize(1, f.length()) }
        }

    fun write(
        out: OutputStream,
        includeRoms: Boolean,
        prefs: JSONObject,
        appVersion: String,
        now: Long,
        romCount: Int,
        onProgress: BackupProgress = { _, _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): BackupManifest {
        val exported = roots.exported(includeRoms)
        // Lengths are taken once: the manifest declares them and the reader holds the archive to them.
        val files = exported.associateWith { root -> roots.files(root).map { (name, f) -> Triple(name, f, f.length()) } }
        val prefsBytes = prefs.toString().toByteArray()
        val groups = BackupGroup.entries.associateWith { g ->
            files.filterKeys { it.group == g }.values.flatten().fold(BackupSize()) { s, (_, _, len) -> s + BackupSize(1, len) }
        }.filterKeys { g -> g != BackupGroup.ROMS || includeRoms }.toMutableMap()
        groups[BackupGroup.SETTINGS] = groups.getValue(BackupGroup.SETTINGS) + BackupSize(1, prefsBytes.size.toLong())
        val saves = files.filterKeys { it.name == "external/saves" || it.name == "external/states" }.values.flatten()
            .count { !it.second.name.endsWith(".png") }
        val manifest = BackupManifest(appVersion, now, exported.map { it.name }, groups, if (includeRoms) romCount else 0, saves)
        val total = manifest.total.bytes
        var done = 0L

        ZipOutputStream(out.buffered(BUFFER)).use { zip ->
            zip.putNextEntry(ZipEntry(BackupFormat.MANIFEST).apply { time = now })
            zip.write(manifest.toJson().toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(BackupFormat.PREFS).apply { time = now })
            zip.write(prefsBytes)
            zip.closeEntry()
            done += prefsBytes.size
            val buf = ByteArray(BUFFER)
            for ((root, list) in files) {
                val folder = root.name.substringAfter('/')
                onProgress(folder, done, total)
                for ((name, file, length) in list) {
                    if (isCancelled()) throw BackupError.Cancelled()
                    zip.putNextEntry(ZipEntry(name).apply { time = file.lastModified() })
                    var copied = 0L
                    file.inputStream().use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            copied += n
                            // A file rewritten mid-export would leave an archive the import refuses.
                            if (copied > length) throw BackupError.Io("$name changed during the export")
                            zip.write(buf, 0, n)
                            done += n
                            // Per buffer, not per file: one disc image can take minutes.
                            onProgress(folder, done, total)
                            if (isCancelled()) throw BackupError.Cancelled()
                        }
                    }
                    if (copied != length) throw BackupError.Io("$name changed during the export")
                    zip.closeEntry()
                    onProgress(folder, done, total)
                }
            }
        }
        return manifest
    }
}

/** What an archive brought, staged next to the roots it replaces. */
class StagedBackup(val manifest: BackupManifest, val roots: List<BackupRoot>, val prefs: Map<String, Any>?)

class BackupReader(private val roots: BackupRoots) {
    /** The manifest alone, to ask before anything is written. */
    fun readManifest(input: InputStream): BackupManifest = guarded {
        ZipInputStream(input).use { zip -> manifestOf(zip) }
    }

    /**
     * Everything into the staging directories, checked as it comes: only the
     * manifest's roots, no path that climbs out, no entry twice, and never more
     * than the manifest declared. On any failure the staging is gone.
     */
    fun extract(input: InputStream, onProgress: BackupProgress = { _, _, _ -> }, isCancelled: () -> Boolean = { false }): StagedBackup {
        roots.stagingDirs.forEach { it.deleteRecursively() }
        try {
            return guarded { ZipInputStream(input).use { zip -> extractFrom(zip, onProgress, isCancelled) } }
        } catch (e: Throwable) {
            roots.stagingDirs.forEach { it.deleteRecursively() }
            throw e
        }
    }

    private fun extractFrom(zip: ZipInputStream, onProgress: BackupProgress, isCancelled: () -> Boolean): StagedBackup {
        val manifest = manifestOf(zip)
        if (manifest.roots.toSet().size != manifest.roots.size) throw BackupError.NotABackup("roots")
        val listed = manifest.roots.map { roots.byName(it) ?: throw BackupError.NotABackup("root $it") }
        listed.filter { it.isDir }.forEach { roots.staging(it).mkdirs() }
        val declared = manifest.total
        val seen = mutableSetOf<String>()
        var bytes = 0L
        var prefs: Map<String, Any>? = null
        val buf = ByteArray(BUFFER)
        while (true) {
            if (isCancelled()) throw BackupError.Cancelled()
            val entry = zip.nextEntry ?: break
            val name = entry.name
            if (entry.isDirectory) continue
            if (!seen.add(name)) throw BackupError.NotABackup("duplicate $name")
            if (seen.size > declared.files) throw BackupError.NotABackup("too many files")
            if (name == BackupFormat.PREFS) {
                val data = ByteArrayOutputStream()
                bytes += copy(zip, data, buf, minOf(BackupFormat.PREFS_LIMIT, declared.bytes - bytes))
                prefs = try { BackupFormat.prefsFromJson(JSONObject(data.toString(Charsets.UTF_8.name()))) } catch (e: JSONException) { throw BackupError.NotABackup("prefs") }
                continue
            }
            val root = rootOf(name, listed) ?: throw BackupError.NotABackup("entry $name")
            val target = if (root.isDir) File(roots.staging(root), name.removePrefix(root.name + "/")) else roots.staging(root)
            target.parentFile.mkdirs()
            val folder = root.name.substringAfter('/')
            onProgress(folder, bytes, declared.bytes)
            val before = bytes
            target.outputStream().use {
                bytes += copy(zip, it, buf, declared.bytes - bytes) { n ->
                    onProgress(folder, before + n, declared.bytes)
                    if (isCancelled()) throw BackupError.Cancelled()
                }
            }
            // States are listed newest first: they keep the time they were made. -1 is "unknown".
            target.setLastModified(entry.time.coerceAtLeast(0))
        }
        if (seen.size != declared.files || bytes != declared.bytes) throw BackupError.NotABackup("incomplete")
        // A listed single file that never came would fail the swap halfway instead.
        listed.firstOrNull { !it.isDir && it.name !in seen }?.let { throw BackupError.NotABackup("missing ${it.name}") }
        return StagedBackup(manifest, listed, prefs)
    }

    /**
     * Copies one entry, refusing to go past [limit] bytes: a zip bomb stops here.
     * [onChunk] hears the bytes copied so far after every buffer.
     */
    private fun copy(input: InputStream, out: OutputStream, buf: ByteArray, limit: Long, onChunk: (Long) -> Unit = {}): Long {
        var n = 0L
        while (true) {
            val read = input.read(buf)
            if (read < 0) return n
            n += read
            if (n > limit) throw BackupError.NotABackup("larger than declared")
            out.write(buf, 0, read)
            onChunk(n)
        }
    }

    private fun manifestOf(zip: ZipInputStream): BackupManifest {
        val first = zip.nextEntry ?: throw BackupError.NotABackup("empty")
        if (first.name != BackupFormat.MANIFEST) throw BackupError.NotABackup("no manifest")
        val data = ByteArrayOutputStream()
        copy(zip, data, ByteArray(BUFFER), 64L * 1024)
        return BackupManifest.parse(data.toString(Charsets.UTF_8.name()))
    }

    /** The listed root an entry name falls under, or null for anything else. */
    private fun rootOf(name: String, listed: List<BackupRoot>): BackupRoot? {
        if (name.startsWith("/") || '\\' in name) return null
        val parts = name.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        return listed.firstOrNull { if (it.isDir) name.startsWith(it.name + "/") else name == it.name }
    }

    /** A cut or garbled archive reads as not a backup; anything else is an I/O failure. */
    private fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: BackupError) {
        throw e
    } catch (e: ZipException) {
        throw BackupError.NotABackup(e.message)
    } catch (e: EOFException) {
        throw BackupError.NotABackup(e.message)
    } catch (e: IOException) {
        throw BackupError.Io(e.message)
    }
}
