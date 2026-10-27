package com.openemux.mobile.share

import org.json.JSONArray
import org.json.JSONObject

/**
 * What an offer would send (spec §10), checked for shape and limits only.
 * What the receiver does with each item (placement, conflicts, writing) is
 * #595's, which extends these types instead of parsing again. Unknown fields
 * stay in [json]; a name is never turned into a path here.
 */
class Manifest(val games: List<Game>, val totalSize: Long, val fileCount: Long, val json: JSONObject) {
    val items: List<Item> get() = games.flatMap { it.items }
}

class Game(val id: String, val console: String, val title: String, val items: List<Item>, val metadata: JSONObject?, val json: JSONObject)

class Item(
    val id: String,
    val kind: String,
    val name: String,
    val size: Long,
    val mtime: Double,
    val sha256: String?,
    val json: JSONObject,
) {
    /** An unknown kind must be answered `skip`. */
    val knownKind: Boolean get() = kind in KINDS

    /** Its role in a multi-file ROM unit (cue, bin, m3u...); null for a single-file ROM or any other kind. */
    val partRole: String? get() = json.optJSONObject("part")?.optString("role")

    /** The ROM a game is launched from: a single-file ROM, or a unit's cue, m3u or main file. */
    val isMainRom: Boolean get() = kind == ROM && (partRole ?: Units.ROLE_MAIN) in Units.MAIN_ROLES

    companion object {
        const val ROM = "rom"
        const val BATTERY_SAVE = "battery_save"
        const val STATE = "state"
        const val COVER = "cover"
        const val LABEL = "label"
        val KINDS = setOf(ROM, BATTERY_SAVE, STATE, COVER, LABEL)
    }
}

object ManifestParser {
    /** The manifest in [json]; `bad_request` when its shape or a limit is off. */
    fun parse(json: Any?): Manifest {
        if (json !is JSONObject) throw bad("manifest must be an object")
        val rawGames = json.opt("games") as? JSONArray ?: throw bad("manifest.games must be a list")
        val gameIds = mutableSetOf<String>()
        val itemIds = mutableSetOf<String>()
        var count = 0
        val games = (0 until rawGames.length()).map { index ->
            val game = rawGames.opt(index) as? JSONObject ?: throw bad("game must be an object")
            val id = string(game, "id", "game")
            val console = string(game, "console", "game")
            val title = string(game, "title", "game")
            if (!gameIds.add(id)) throw bad("duplicate game id")
            val rawItems = game.opt("items") as? JSONArray ?: throw bad("game.items must be a list")
            val metadata = game.opt("metadata").takeUnless { it == JSONObject.NULL }
            if (metadata != null && metadata !is JSONObject) throw bad("game.metadata must be an object")
            val items = (0 until rawItems.length()).map {
                count++
                if (count > ShareProtocol.MAX_ITEMS) throw bad("more than ${ShareProtocol.MAX_ITEMS} items")
                item(rawItems.opt(it)).also { item -> if (!itemIds.add(item.id)) throw bad("duplicate item id") }
            }
            Game(id, console, title, items, metadata as JSONObject?, game)
        }
        val totalSize = nonNegative(json.opt("total_size")) ?: throw bad("manifest.total_size must be a non-negative integer")
        val fileCount = nonNegative(json.opt("file_count")) ?: throw bad("manifest.file_count must be a non-negative integer")
        return Manifest(games, totalSize, fileCount, json)
    }

    /** A bare file name: never a path, never empty, at most 255 bytes of UTF-8. */
    fun isSafeFileName(name: Any?): Boolean {
        if (name !is String || name.isEmpty() || name == "." || name == "..") return false
        if ('/' in name || '\\' in name || '\u0000' in name || ".." in name) return false
        return name.toByteArray(Charsets.UTF_8).size <= ShareProtocol.MAX_FILE_NAME_BYTES
    }

    private fun item(raw: Any?): Item {
        val item = raw as? JSONObject ?: throw bad("item must be an object")
        val id = string(item, "id", "item")
        val kind = string(item, "kind", "item")
        val name = item.opt("name")
        if (!isSafeFileName(name)) throw bad("item.name must be a bare file name")
        val size = nonNegative(item.opt("size")) ?: throw bad("item.size must be a non-negative integer")
        val mtime = when (val value = item.opt("mtime")) {
            is Int -> value.toDouble()
            is Long -> value.toDouble()
            is Double -> value.takeIf { it.isFinite() }
            is Number -> value.toDouble().takeIf { it.isFinite() }
            else -> null
        } ?: throw bad("item.mtime must be a number")
        val sha256 = item.opt("sha256").takeUnless { it == JSONObject.NULL }
        if (sha256 != null && !isHex((sha256 as? String)?.lowercase(), 64)) throw bad("item.sha256 must be 64 hex characters")
        return Item(id, kind, name as String, size, mtime, (sha256 as String?)?.lowercase(), item)
    }

    private fun string(json: JSONObject, key: String, what: String): String =
        json.opt(key) as? String ?: throw bad("$what.$key must be a string")

    private fun nonNegative(value: Any?): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }?.takeIf { it >= 0 }

    private fun bad(message: String) = ShareError.badRequest(message)
}
