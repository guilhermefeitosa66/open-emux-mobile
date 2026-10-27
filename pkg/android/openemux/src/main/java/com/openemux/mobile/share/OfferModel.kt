package com.openemux.mobile.share

/** One incoming game as the offer sheet lists it. */
data class OfferGame(
    val id: String,
    val title: String,
    val console: String,
    val hasRom: Boolean,
    val saves: Int,
    val states: Int,
    val cover: Boolean,
    val label: Boolean,
    val bytes: Long,
)

/** An incoming item that meets a file the library already has. */
data class Conflict(
    val itemId: String,
    val kind: String,
    val title: String,
    /** The local file's mtime and the incoming one's, in milliseconds. */
    val yours: Long,
    val incoming: Long,
    val policy: Policy,
    val allowed: List<Policy>,
    /** A multi-file game (cue, m3u): its parts follow this row, and it cannot be kept twice. */
    val unit: Boolean = false,
) {
    val incomingNewer: Boolean get() = incoming > yours
    val yoursNewer: Boolean get() = yours > incoming
}

/** An item the plan does not ask for, with the game it belongs to. */
data class SkippedItem(val itemId: String, val kind: String, val title: String, val reason: String)

/**
 * The receive side's view of an offer (spec 05/06): what comes, what meets a
 * local file, what is skipped, and whether it fits. [plan] is the live plan
 * under [overrides]; [probe] is the same offer planned with every item set to
 * replace, which is what exposes each file the library already has.
 */
class OfferModel(
    val offer: IncomingOffer,
    val plan: ReceivePlan,
    private val probe: ReceivePlan,
    val overrides: Map<String, Policy>,
    /** When the offer reached this side, in milliseconds. */
    val receivedAt: Long,
    val spaceChecks: List<SpaceGroup> = plan.spaceCheck(),
    /** Set when accepting failed; the sheet shows it and stays. */
    val error: ShareMessage? = null,
) {
    val sender: String get() = offer.sender.name
    val code: String get() = offer.code

    val games: List<OfferGame> = offer.manifest.games.map { game ->
        val kinds = game.items.groupingBy { it.kind }.eachCount()
        OfferGame(
            game.id, game.title, game.console, Item.ROM in kinds, kinds[Item.BATTERY_SAVE] ?: 0, kinds[Item.STATE] ?: 0,
            Item.COVER in kinds, Item.LABEL in kinds, game.items.sumOf { it.size },
        )
    }

    val totalBytes: Long get() = offer.manifest.totalSize

    private val titles: Map<String, String> = offer.manifest.games.associate { it.id to it.title }

    private val items: Map<String, Item> = offer.manifest.items.associateBy { it.id }

    /** The games whose multi-file ROM meets local files: one row each, on its main item. */
    private val unitConflicts = mutableSetOf<String>()

    private val conflictsList: List<Conflict> = probe.decisions.filter { it.replaces != null }.mapNotNull { probed ->
        val art = probed.kind == Item.COVER || probed.kind == Item.LABEL
        if (probed.kind == Item.ROM && items[probed.itemId]?.partRole != null) {
            // Every part that exists here is probed; the unit is replaced or skipped whole, so one row stands for it.
            if (!unitConflicts.add(probed.gameId)) return@mapNotNull null
            val main = offer.manifest.games.first { it.id == probed.gameId }.items.first { it.isMainRom }
            return@mapNotNull Conflict(
                main.id, Item.ROM, titles[probed.gameId].orEmpty(),
                probed.replaces!!.lastModified(), (main.mtime * 1000).toLong(),
                overrides[main.id] ?: Policy.SKIP, UNIT_POLICIES, unit = true,
            )
        }
        Conflict(
            probed.itemId, probed.kind, titles[probed.gameId].orEmpty(),
            probed.replaces!!.lastModified(), (probed.mtime * 1000).toLong(),
            overrides[probed.itemId] ?: DEFAULT_POLICIES.getValue(probed.kind),
            if (art) ART_POLICIES else ALL_POLICIES,
        )
    }

    /** Every incoming item that would meet a local file, whatever is chosen for it. */
    fun conflicts(): List<Conflict> = conflictsList

    /** What the plan skips, the user's own choices and the conflicts aside (a conflicting unit's parts included). */
    val skipped: List<SkippedItem> = plan.decisions.filter { d ->
        !d.send && d.reason != SkipReason.USER_UNCHECKED && conflictsList.none { it.itemId == d.itemId } &&
            !(d.kind == Item.ROM && d.gameId in unitConflicts)
    }.map { SkippedItem(it.itemId, it.kind, titles[it.gameId].orEmpty(), it.reason!!) }

    /** States that no installed core can load. */
    val incompatibleStates: Int get() = skipped.count { it.reason == SkipReason.INCOMPATIBLE_STATE }

    /** Games of a console this app does not run. */
    val unsupported: List<OfferGame> get() = games.filter { g -> plan.games.firstOrNull { it.gameId == g.id }?.console == null }

    val spaceOk: Boolean get() = spaceChecks.all { it.ok }

    /** The first filesystem short of room: what it needs, what it has, and what to free (the reserve counted). */
    val shortage: Triple<Long, Long, Long>? get() = spaceChecks.firstOrNull { !it.ok }?.let {
        Triple(it.needed, it.free, it.needed + ReceivePlan.SPACE_RESERVE - it.free)
    }

    /** Whole seconds left to answer, never negative. */
    fun secondsLeft(now: Long): Int = ((receivedAt + ShareProtocol.OFFER_TTL_MS - now + 999) / 1000).coerceAtLeast(0).toInt()

    fun withError(error: ShareMessage?) = OfferModel(offer, plan, probe, overrides, receivedAt, spaceChecks, error)

    /** The same offer planned under new [overrides]; the probe does not change with them. */
    fun replan(planner: (Map<String, Policy>) -> ReceivePlan, overrides: Map<String, Policy>) =
        OfferModel(offer, planner(overrides), probe, overrides, receivedAt)

    companion object {
        val ALL_POLICIES = listOf(Policy.NEWEST, Policy.KEEP_BOTH, Policy.REPLACE, Policy.SKIP)
        val ART_POLICIES = listOf(Policy.SKIP, Policy.REPLACE)
        /** A multi-file game cannot get a free name beside the local one: replaced or skipped whole. */
        val UNIT_POLICIES = listOf(Policy.REPLACE, Policy.SKIP)

        /**
         * The model for [offer]; [planner] plans it under a map of per-item
         * overrides (the layer's `prepareReceive`). Plans twice and checks the
         * space, so off the main thread.
         */
        fun build(offer: IncomingOffer, planner: (Map<String, Policy>) -> ReceivePlan, overrides: Map<String, Policy>, receivedAt: Long): OfferModel {
            val probe = planner(offer.manifest.items.associate { it.id to Policy.REPLACE })
            return OfferModel(offer, planner(overrides), probe, overrides, receivedAt)
        }
    }
}
