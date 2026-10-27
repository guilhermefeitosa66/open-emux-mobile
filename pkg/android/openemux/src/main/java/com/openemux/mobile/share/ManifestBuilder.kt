package com.openemux.mobile.share

import android.util.Log
import com.openemux.mobile.data.CART_COLORS
import com.openemux.mobile.data.Covers
import com.openemux.mobile.data.Rom
import com.openemux.mobile.data.SHADERS
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** A game left out of a manifest, and why ([SkipReason]). */
data class Excluded(val romKey: String, val reason: String)

/** The manifest to offer, the file behind each item id, and the games left out. */
class BuiltManifest(val json: JSONObject, val sources: Map<String, File>, val excluded: List<Excluded>)

/**
 * The sending side: a selection of games becomes the manifest of spec §10 —
 * each game's ROM unit, its battery saves, its states tagged with the core
 * that made them, its local cover and label, and its own settings. Hashing
 * reads every file up to [hashLimit], so this runs off the main thread;
 * [cancelled] is checked between files and between chunks.
 */
class ManifestBuilder(
    private val library: ShareLibrary,
    private val cores: CoreIdentities = CoreIdentities(library.paths),
    private val hashLimit: Long = HASH_LIMIT,
    private val onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    private val cancelled: () -> Boolean = { false },
) {
    private class GameFile(val kind: String, val file: File, val role: String? = null, val state: JSONObject? = null)

    fun build(roms: List<Rom>): BuiltManifest {
        val games = JSONArray()
        val sources = linkedMapOf<String, File>()
        val excluded = mutableListOf<Excluded>()
        val toHash = mutableListOf<JSONObject>()
        var count = 0
        var totalSize = 0L
        for (rom in roms) {
            val unit = Units.collectUnit(rom.file)
            if (unit == null) {
                excluded += Excluded(rom.key, SkipReason.UNIT_INCOMPLETE)
                continue
            }
            val files = gameFiles(rom, unit)
            if (!files.all { Units.isReceivableName(it.file.name) }) {
                excluded += Excluded(rom.key, SkipReason.INVALID_ITEM)
                continue
            }
            if (count + files.size > ShareProtocol.MAX_ITEMS) {
                excluded += Excluded(rom.key, SkipReason.TOO_MANY_ITEMS)
                continue
            }
            count += files.size
            val gameId = "g${games.length() + 1}"
            val items = JSONArray()
            files.forEachIndexed { index, file ->
                val size = file.file.length()
                val item = JSONObject().put("id", "${gameId}i${index + 1}").put("kind", file.kind)
                    .put("name", file.file.name).put("size", size).put("mtime", file.file.lastModified() / 1000)
                if (file.role != null && unit.multi) item.put("part", JSONObject().put("unit", gameId).put("role", file.role))
                file.state?.let { item.put("state", it) }
                items.put(item)
                sources[item.getString("id")] = file.file
                totalSize += size
                if (size <= hashLimit) toHash += item
            }
            val game = JSONObject().put("id", gameId).put("console", rom.console.id).put("title", rom.title).put("items", items)
            metadata(rom)?.let { game.put("metadata", it) }
            games.put(game)
        }
        hash(toHash, sources)
        val json = JSONObject().put("games", games).put("total_size", totalSize).put("file_count", count)
        return BuiltManifest(json, sources, excluded)
    }

    private fun gameFiles(rom: Rom, unit: Units.UnitFiles): List<GameFile> {
        val files = unit.parts.map { (file, role) -> GameFile(Item.ROM, file, role = role) }.toMutableList()
        val stem = unit.main.nameWithoutExtension
        val paths = library.paths
        paths.saves(rom.console).listFiles().orEmpty().filter { it.isFile && Units.isBatterySave(it.name, stem, rom.console) }
            .sortedBy { it.name }.forEach { files += GameFile(Item.BATTERY_SAVE, it) }
        val identity = cores.identityOf(library.effectiveCore(rom))
        library.states(rom).sortedBy { if (it.isAuto) Int.MAX_VALUE else it.slot }.forEach { state ->
            val slot: Any = if (state.isAuto) Units.AUTO_SLOT_WIRE else state.slot
            files += GameFile(
                Item.STATE, state.file,
                state = JSONObject().put("core", identity.corename).put("core_version", identity.version).put("slot", slot),
            )
        }
        Covers.localCover(paths, rom.console, stem)?.let { files += GameFile(Item.COVER, it) }
        File(paths.labels(rom.console), "$stem.png").takeIf { it.isFile }?.let { files += GameFile(Item.LABEL, it) }
        return files
    }

    /** Only what this game sets for itself: the console's defaults belong to the receiver. */
    private fun metadata(rom: Rom): JSONObject? {
        val meta = library.romMeta(rom.key)
        val json = JSONObject()
        if (meta.favorite) json.put("favorite", true)
        meta.color?.takeIf { color -> color != "default" && CART_COLORS.any { it.id == color } }?.let { json.put("cartridge_color", it) }
        meta.core?.let { id -> rom.console.cores.firstOrNull { it.id == id } }
            ?.let { cores.identityOf(it).corename }?.takeIf { it.isNotEmpty() }?.let { json.put("core", it) }
        meta.shader?.takeIf { shader -> SHADERS.any { it.id == shader } }?.let { json.put("shader", it) }
        library.playtimeSeconds(rom).takeIf { it > 0 }?.let { json.put("playtime_seconds", it) }
        return json.takeIf { it.length() > 0 }
    }

    private fun hash(items: List<JSONObject>, sources: Map<String, File>) {
        val total = items.sumOf { it.getLong("size") }
        var done = 0L
        val buffer = ByteArray(HASH_CHUNK)
        for (item in items) {
            checkCancelled()
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                sources.getValue(item.getString("id")).inputStream().use { input ->
                    while (true) {
                        checkCancelled()
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "share manifest: ${item.getString("name")} could not be read", e)
                throw ShareError(ErrorCode.SOURCE_CHANGED, "${item.getString("name")} could not be read")
            }
            item.put("sha256", digest.digest().toHex())
        }
    }

    private fun checkCancelled() {
        if (cancelled()) throw ShareError(ErrorCode.CANCELLED, "cancelled while hashing")
    }

    companion object {
        /** Items up to this size carry a sha256; bigger ones rely on TLS and an exact length. */
        const val HASH_LIMIT = 64L * 1024 * 1024
        const val HASH_CHUNK = 1024 * 1024
    }
}
