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

    private val conflictsList: List<Conflict> = probe.decisions.filter { it.replaces != null }.map { probed ->
        val art = probed.kind == Item.COVER || probed.kind == Item.LABEL
        Conflict(
            probed.itemId, probed.kind, titles[probed.gameId].orEmpty(),
            probed.replaces!!.lastModified(), (probed.mtime * 1000).toLong(),
            overrides[probed.itemId] ?: DEFAULT_POLICIES.getValue(probed.kind),
            if (art) ART_POLICIES else ALL_POLICIES,
        )
    }

    /** Every incoming item that would meet a local file, whatever is chosen for it. */
    fun conflicts(): List<Conflict> = conflictsList

    /** What the plan skips, the user's own choices and the conflicts aside. */
    val skipped: List<SkippedItem> = plan.decisions.filter { d -> !d.send && d.reason != SkipReason.USER_UNCHECKED && conflictsList.none { it.itemId == d.itemId } }
        .map { SkippedItem(it.itemId, it.kind, titles[it.gameId].orEmpty(), it.reason!!) }

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
