package com.openemux.mobile.share

import com.openemux.mobile.data.Rom
import org.json.JSONArray
import org.json.JSONObject

/** What can go along with a game, as "What to Send" lists it; [DETAILS] is the game's metadata, not a file. */
enum class SendPart { GAME, SAVE, STATES, COVER, LABEL, DETAILS }

/** One check of a game: its files of that kind (none for [SendPart.DETAILS]). */
data class SendPartInfo(val part: SendPart, val itemIds: List<String>, val bytes: Long) {
    val count: Int get() = itemIds.size
}

data class SendGame(val id: String, val console: String, val title: String, val parts: List<SendPartInfo>)

/** A game the manifest left out, with the [SkipReason] that kept it. */
data class ExcludedGame(val romKey: String, val console: String, val title: String, val reason: String)

enum class Tri { CHECKED, MIXED, UNCHECKED }

/**
 * The "What to Send" checklist (spec 02): every part of every game checked by
 * default; the user unchecks parts, or whole games. Immutable, like
 * [com.openemux.mobile.ui.RomSelection]: every change is a copy.
 */
data class SendSelection(
    val games: List<SendGame>,
    val excluded: List<ExcludedGame> = emptyList(),
    val unchecked: Set<Pair<String, SendPart>> = emptySet(),
) {
    fun isChecked(gameId: String, part: SendPart) = (gameId to part) !in unchecked

    fun toggle(gameId: String, part: SendPart): SendSelection {
        val key = gameId to part
        return copy(unchecked = if (key in unchecked) unchecked - key else unchecked + key)
    }

    /** All of a game's parts on, or (when they all are) all off. */
    fun toggleGame(gameId: String): SendSelection {
        val game = games.firstOrNull { it.id == gameId } ?: return this
        val keys = game.parts.map { gameId to it.part }
        return copy(unchecked = if (state(gameId) == Tri.CHECKED) unchecked + keys else unchecked - keys.toSet())
    }

    fun state(gameId: String): Tri {
        val parts = games.firstOrNull { it.id == gameId }?.parts.orEmpty()
        val on = parts.count { isChecked(gameId, it.part) }
        return when {
            parts.isEmpty() || on == 0 -> Tri.UNCHECKED
            on == parts.size -> Tri.CHECKED
            else -> Tri.MIXED
        }
    }

    private fun checkedParts(game: SendGame) = game.parts.filter { isChecked(game.id, it.part) }

    fun gameBytes(gameId: String): Long = games.firstOrNull { it.id == gameId }?.let { g -> checkedParts(g).sumOf { it.bytes } } ?: 0

    val totalBytes: Long get() = games.sumOf { gameBytes(it.id) }
    val fileCount: Int get() = games.sumOf { g -> checkedParts(g).sumOf { it.count } }

    /** Games with at least one file going. */
    val gameCount: Int get() = games.count { g -> checkedParts(g).any { it.count > 0 } }

    /** Nothing to send: no file is checked. */
    val isEmpty: Boolean get() = fileCount == 0

    /** The item ids the user left out (the receiver would answer them `user_unchecked`). */
    fun unchecked(): Set<String> = games.flatMap { g -> g.parts.filter { !isChecked(g.id, it.part) }.flatMap { it.itemIds } }.toSet()

    /**
     * [built] without what the user left out: the unchecked items, the
     * metadata when Library Details is off, and the games left with no file;
     * the totals recomputed. The receiver never hears of the rest.
     */
    fun filter(built: BuiltManifest): BuiltManifest {
        val drop = unchecked()
        val games = JSONArray()
        var total = 0L
        var count = 0
        val source = built.json.getJSONArray("games")
        for (g in 0 until source.length()) {
            val game = source.getJSONObject(g)
            val id = game.getString("id")
            val items = JSONArray()
            val list = game.getJSONArray("items")
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                if (item.getString("id") in drop) continue
                items.put(item)
                total += item.getLong("size")
                count++
            }
            if (items.length() == 0) continue
            val copy = JSONObject(game.toString()).put("items", items)
            if (!isChecked(id, SendPart.DETAILS)) copy.remove("metadata")
            games.put(copy)
        }
        val json = JSONObject().put("games", games).put("total_size", total).put("file_count", count)
        return BuiltManifest(json, built.sources.filterKeys { it !in drop }, built.excluded)
    }

    companion object {
        private val PART_OF = mapOf(
            Item.ROM to SendPart.GAME, Item.BATTERY_SAVE to SendPart.SAVE, Item.STATE to SendPart.STATES,
            Item.COVER to SendPart.COVER, Item.LABEL to SendPart.LABEL,
        )

        /** Everything checked, from what the manifest builder made of [roms]. */
        fun from(built: BuiltManifest, roms: List<Rom>): SendSelection {
            val source = built.json.getJSONArray("games")
            val games = (0 until source.length()).map { g ->
                val game = source.getJSONObject(g)
                val list = game.getJSONArray("items")
                val byPart = linkedMapOf<SendPart, MutableList<JSONObject>>()
                for (i in 0 until list.length()) {
                    val item = list.getJSONObject(i)
                    val part = PART_OF[item.getString("kind")] ?: continue
                    byPart.getOrPut(part) { mutableListOf() } += item
                }
                val parts = SendPart.entries.mapNotNull { part ->
                    if (part == SendPart.DETAILS) {
                        game.optJSONObject("metadata")?.let { SendPartInfo(part, emptyList(), 0) }
                    } else {
                        byPart[part]?.let { items -> SendPartInfo(part, items.map { it.getString("id") }, items.sumOf { it.getLong("size") }) }
                    }
                }
                SendGame(game.getString("id"), game.getString("console"), game.getString("title"), parts)
            }
            val excluded = built.excluded.map { ex ->
                val rom = roms.firstOrNull { it.key == ex.romKey }
                ExcludedGame(ex.romKey, rom?.console?.id ?: ex.romKey.substringBefore('/'), rom?.title ?: ex.romKey.substringAfter('/'), ex.reason)
            }
            return SendSelection(games, excluded)
        }
    }
}
