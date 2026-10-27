package com.openemux.mobile.share

import com.openemux.mobile.data.Console
import com.openemux.mobile.data.Covers
import com.openemux.mobile.data.Paths
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** What to do when an incoming file meets one the library already has. */
enum class Policy {
    /** Replace only a strictly older file: a tie keeps the local one. */
    NEWEST,
    KEEP_BOTH,
    REPLACE,
    SKIP,
}

/**
 * The defaults, the desktop app's (#588): a ROM with other content is kept
 * beside the local one, saves and states keep the newest, and the user's own
 * art wins.
 */
val DEFAULT_POLICIES = mapOf(
    Item.ROM to Policy.KEEP_BOTH,
    Item.BATTERY_SAVE to Policy.NEWEST,
    Item.STATE to Policy.NEWEST,
    Item.COVER to Policy.SKIP,
    Item.LABEL to Policy.SKIP,
)

/** The order a commit places things in: a game's files after its ROM. */
val COMMIT_ORDER = listOf(Item.ROM, Item.BATTERY_SAVE, Item.STATE, Item.COVER, Item.LABEL)

/** One item's fate. [target] is computed here, never taken from the wire; [replaces] is moved aside on commit. */
data class Decision(
    val itemId: String,
    val kind: String,
    val send: Boolean,
    val reason: String? = null,
    val target: File? = null,
    val policy: Policy? = null,
    val replaces: File? = null,
    val size: Long = 0,
    val mtime: Double = 0.0,
    val sha256: String? = null,
    val name: String = "",
    val gameId: String = "",
) {
    fun answer(): ItemAnswer = if (send) ItemAnswer.send(itemId) else ItemAnswer.skip(itemId, reason!!)
}

enum class RomStatus {
    /** Placed under its own name. */
    NEW,
    /** Placed beside a different local copy, under a free name. */
    KEPT_BOTH,
    REPLACED,
    /** The library already has it (or keeps its own); its other files may still come. */
    EXISTING,
    /** Not coming and not here: the game's other files have nothing to belong to. */
    ABSENT;

    val placed: Boolean get() = this == NEW || this == KEPT_BOTH
}

class GamePlan(val gameId: String, val console: Console?, val title: String, val metadata: JSONObject?) {
    var romStatus = RomStatus.ABSENT
    /** The ROM in the library after the commit (placed, replaced or already there). */
    var romFile: File? = null
    var finalStem = ""
    /** Why the game's other files are skipped when its ROM is [RomStatus.ABSENT]. */
    var absentReason = SkipReason.USER_UNCHECKED
    val decisions = mutableListOf<Decision>()
}

/** One filesystem the transfer writes to: [needed] bytes against [free], the reserve kept. */
data class SpaceGroup(val root: File, val needed: Long, val free: Long, val ok: Boolean)

/** Which filesystem a folder is on, and how much of it is usable. */
interface SpaceProbe {
    fun store(dir: File): Pair<Any, Long>
}

object NioSpaceProbe : SpaceProbe {
    override fun store(dir: File): Pair<Any, Long> {
        val store = Files.getFileStore(dir.toPath())
        return store to store.usableSpace
    }
}

class ReceivePlan(val games: List<GamePlan>, private val probe: SpaceProbe = NioSpaceProbe, private val reserve: Long = SPACE_RESERVE) {
    val decisions: List<Decision> get() = games.flatMap { it.decisions }

    /** The `send` decisions in commit order: ROM parts, saves, states, art. */
    fun toSend(): List<Decision> = decisions.filter { it.send }.sortedBy { COMMIT_ORDER.indexOf(it.kind) }

    /** What [ShareServer.accept] takes. */
    fun answerItems(): List<ItemAnswer> = decisions.map { it.answer() }

    /** One entry per filesystem the files land on. */
    fun spaceCheck(): List<SpaceGroup> {
        val groups = linkedMapOf<Any, Triple<File, Long, Long>>()
        for (decision in toSend()) {
            var dir = decision.target!!.parentFile!!
            while (!dir.exists()) dir = dir.parentFile ?: break
            val (key, free) = try {
                probe.store(dir)
            } catch (_: IOException) {
                dir to 0L
            }
            val group = groups[key]
            groups[key] = Triple(group?.first ?: dir, (group?.second ?: 0L) + decision.size, free)
        }
        return groups.values.map { (root, needed, free) -> SpaceGroup(root, needed, free, needed + reserve <= free) }
    }

    fun okToAccept(checks: List<SpaceGroup> = spaceCheck()): Boolean = checks.all { it.ok }

    companion object {
        /** Kept free on every filesystem after the transfer. */
        const val SPACE_RESERVE = 64L * 1024 * 1024
    }
}

/**
 * The receiver's decisions for [manifest], before a byte is written: each item
 * mapped to a target in Android's layout (ROMs flat in `roms/<console>`,
 * `saves/`, `states/`, `covers/` and `labels/` per console), checked against
 * what the library has under the policy for its kind. [policies] replaces the
 * default of a kind, [overrides] one item's; [unchecked] holds the game and
 * item ids the user deselected. Nothing here writes.
 */
fun planReceive(
    manifest: Manifest,
    paths: Paths,
    cores: CoreIdentities = CoreIdentities(paths),
    policies: Map<String, Policy> = emptyMap(),
    overrides: Map<String, Policy> = emptyMap(),
    unchecked: Set<String> = emptySet(),
    probe: SpaceProbe = NioSpaceProbe,
    reserve: Long = ReceivePlan.SPACE_RESERVE,
): ReceivePlan {
    val planner = Planner(paths, cores, DEFAULT_POLICIES + policies, overrides, unchecked)
    return ReceivePlan(manifest.games.map(planner::plan), probe, reserve)
}

/** A state loads only in the installed core that made it: name and version, both known. */
fun stateCompatible(item: Item, console: Console, cores: CoreIdentities): Boolean {
    val state = item.json.optJSONObject("state") ?: return false
    return cores.findCompatible(console, state.optString("core"), state.optString("core_version")) != null
}

/** What a target name leaves free of 255 bytes: the stage's `.<name>.XXXXXXXX.tmp`, or `.<name>.share-old-<offer id>`. */
private const val NAME_HEADROOM = 34

private class Planner(
    private val paths: Paths,
    private val cores: CoreIdentities,
    private val policies: Map<String, Policy>,
    private val overrides: Map<String, Policy>,
    unchecked: Set<String>,
) {
    private val unchecked = unchecked.toMutableSet()
    /** Targets chosen earlier in this plan, so two incoming games never pick the same name. */
    private val taken = mutableSetOf<File>()

    private fun policy(item: Item) = overrides[item.id] ?: policies.getValue(item.kind)

    private fun skip(plan: GamePlan, item: Item, reason: String) {
        plan.decisions += Decision(item.id, item.kind, false, reason, name = item.name, gameId = plan.gameId)
    }

    private fun send(plan: GamePlan, item: Item, target: File, base: File, policy: Policy? = null, replaces: File? = null) {
        if (!fits(target.name) || !inside(target, base)) return skip(plan, item, SkipReason.INVALID_ITEM)
        taken += target.absoluteFile
        plan.decisions += Decision(
            item.id, item.kind, true, null, target, policy, replaces, item.size, item.mtime, item.sha256, item.name, plan.gameId,
        )
    }

    private fun isTaken(file: File) = file.absoluteFile in taken

    fun plan(game: Game): GamePlan {
        val console = Console.byId(game.console)
        val plan = GamePlan(game.id, console, game.title, game.metadata)
        val (items, rejected) = validate(game.items)
        rejected.forEach { skip(plan, it, SkipReason.INVALID_ITEM) }
        if (console == null) {
            items.forEach { skip(plan, it, SkipReason.UNSUPPORTED_CONSOLE) }
            return plan
        }
        if (items.isEmpty()) return plan
        if (game.id in unchecked) unchecked += items.map { it.id }
        val roms = items.filter { it.kind == Item.ROM }
        val main = roms.single(::isMain)
        if (!validUnit(console, main, roms)) {
            items.forEach { skip(plan, it, SkipReason.INVALID_ITEM) }
            return plan
        }
        val stem = main.name.substringBeforeLast('.')
        planRom(plan, console, main, roms, stem)
        for (item in items) {
            when {
                item.kind == Item.ROM -> Unit
                item.id in unchecked -> skip(plan, item, SkipReason.USER_UNCHECKED)
                plan.romStatus == RomStatus.ABSENT -> skip(plan, item, plan.absentReason)
                item.kind == Item.BATTERY_SAVE -> planSave(plan, console, item, stem)
                item.kind == Item.STATE -> planState(plan, console, item)
                else -> planArt(plan, console, item)
            }
        }
        return plan
    }

    // -- the receiver's look at each item ----------------------------------

    /** The usable items, and the others; a game without exactly one main ROM has no usable item. */
    private fun validate(items: List<Item>): Pair<List<Item>, List<Item>> {
        val (good, bad) = items.partition { problemFree(it) }
        if (good.count { it.kind == Item.ROM && isMain(it) } != 1) return emptyList<Item>() to items
        return good to bad
    }

    private fun problemFree(item: Item): Boolean {
        if (!item.knownKind || !Units.isReceivableName(item.name)) return false
        val part = item.json.opt("part").takeUnless { it == JSONObject.NULL }
        if (part != null) {
            if (item.kind != Item.ROM || part !is JSONObject) return false
            if (part.opt("unit") !is String || part.opt("role") !in Units.PART_ROLES) return false
        }
        return item.kind != Item.STATE || slotOf(item) != null && item.json.optJSONObject("state")!!.let {
            it.opt("core") is String && it.opt("core_version") is String
        }
    }

    private fun role(item: Item): String? = item.json.optJSONObject("part")?.optString("role")

    private fun isMain(item: Item) = item.kind == Item.ROM && (role(item) ?: Units.ROLE_MAIN) in Units.MAIN_ROLES

    private fun slotOf(item: Item): Int? {
        val slot = item.json.optJSONObject("state")?.opt("slot") ?: return null
        return when {
            slot == Units.AUTO_SLOT_WIRE -> Units.AUTO_SLOT
            slot is Int && slot in Units.STATE_SLOTS -> slot
            else -> null
        }
    }

    private fun validUnit(console: Console, main: Item, roms: List<Item>): Boolean {
        val suffix = main.name.substringAfterLast('.', "").lowercase()
        if (role(main) == Units.ROLE_M3U) return suffix == "m3u"
        if (suffix !in console.extensions) return false
        // A single-file game is its main file alone.
        return role(main) != null || roms.size == 1
    }

    // -- the ROM unit ------------------------------------------------------

    private fun planRom(plan: GamePlan, console: Console, main: Item, roms: List<Item>, stem: String) {
        val dir = paths.roms(console)
        val multi = role(main) != null
        val existing = File(dir, main.name).takeIf { it.isFile }
        fun finish(status: RomStatus, file: File?, finalStem: String = stem) {
            plan.romStatus = status
            plan.romFile = file
            plan.finalStem = finalStem
        }
        if (main.id in unchecked) {
            roms.forEach { skip(plan, it, SkipReason.USER_UNCHECKED) }
            return finish(if (existing != null) RomStatus.EXISTING else RomStatus.ABSENT, existing)
        }
        if (existing != null && roms.all { sameContent(File(dir, it.name), it) }) {
            roms.forEach { skip(plan, it, SkipReason.EXISTS_SKIP) }
            return finish(RomStatus.EXISTING, existing)
        }
        val free = roms.none { File(dir, it.name).exists() || isTaken(File(dir, it.name)) }
        if (free) {
            if (!sendUnit(plan, roms.map { it to File(dir, it.name) }, dir, policy(main), replacing = false)) return finish(RomStatus.ABSENT, null)
            return finish(RomStatus.NEW, File(dir, main.name))
        }
        val policy = policy(main)
        val replace = policy == Policy.REPLACE ||
            policy == Policy.NEWEST && existing != null && main.mtime * 1000 > existing.lastModified()
        if (replace) {
            if (!sendUnit(plan, roms.map { it to File(dir, it.name) }, dir, policy, replacing = true)) return finish(RomStatus.ABSENT, null)
            return finish(if (existing != null) RomStatus.REPLACED else RomStatus.NEW, File(dir, main.name))
        }
        if (policy == Policy.KEEP_BOTH && !multi) {
            // The folder is flat: a unit's tracks cannot be renamed without
            // rewriting its sheet, so only a single-file ROM gets a free name.
            val suffix = main.name.substring(stem.length)
            val finalStem = freeStem(stem) { listOf(File(dir, "$it$suffix")) }
            val target = File(dir, "$finalStem$suffix")
            if (!sendUnit(plan, listOf(main to target), dir, policy, replacing = false)) return finish(RomStatus.ABSENT, null)
            return finish(RomStatus.KEPT_BOTH, target, finalStem)
        }
        roms.forEach { skip(plan, it, SkipReason.EXISTS_SKIP) }
        if (existing != null) return finish(RomStatus.EXISTING, existing)
        plan.absentReason = SkipReason.EXISTS_SKIP
        finish(RomStatus.ABSENT, null)
    }

    /** Every part of a unit, or none: a cue without one of its tracks is a broken game. False when none. */
    private fun sendUnit(plan: GamePlan, pairs: List<Pair<Item, File>>, base: File, policy: Policy, replacing: Boolean): Boolean {
        if (!pairs.all { (_, target) -> fits(target.name) && inside(target, base) }) {
            pairs.forEach { (item, _) -> skip(plan, item, SkipReason.INVALID_ITEM) }
            plan.absentReason = SkipReason.INVALID_ITEM
            return false
        }
        for ((item, target) in pairs) send(plan, item, target, base, policy, target.takeIf { replacing && it.exists() })
        return true
    }

    /** [stem], or `stem (2)`, `stem (3)`... — the first whose files are all free. */
    private fun freeStem(stem: String, filesFor: (String) -> List<File>): String {
        var candidate = stem
        var n = 1
        while (filesFor(candidate).any { it.exists() || isTaken(it) }) {
            n++
            candidate = "$stem ($n)"
        }
        return candidate
    }

    // -- saves, states, art ------------------------------------------------

    /**
     * Send, skip or keep both for an item whose target may be taken. [existing]
     * is the local file it would replace; [keepBoth] says where the incoming
     * copy goes beside it, or null when it has nowhere to go.
     */
    private fun resolve(plan: GamePlan, item: Item, target: File, base: File, existing: File?, keepBoth: () -> File?) {
        if (existing == null) {
            return if (isTaken(target)) skip(plan, item, SkipReason.EXISTS_SKIP) else send(plan, item, target, base)
        }
        when (val policy = policy(item)) {
            Policy.KEEP_BOTH -> keepBoth()?.let { send(plan, item, it, base, policy) } ?: skip(plan, item, SkipReason.EXISTS_SKIP)
            Policy.SKIP -> skip(plan, item, SkipReason.EXISTS_SKIP)
            Policy.NEWEST -> if (item.mtime * 1000 > existing.lastModified()) {
                send(plan, item, target, base, policy, existing)
            } else {
                skip(plan, item, SkipReason.EXISTS_SKIP)
            }
            Policy.REPLACE -> send(plan, item, target, base, policy, existing)
        }
    }

    private fun planSave(plan: GamePlan, console: Console, item: Item, stem: String) {
        if (!Units.isBatterySave(item.name, stem, console)) return skip(plan, item, SkipReason.INVALID_ITEM)
        val dir = paths.saves(console)
        val target = File(dir, plan.finalStem + item.name.substring(stem.length))
        resolve(plan, item, target, dir, target.takeIf { it.exists() }) {
            // RetroArch only loads <stem>.srm: the incoming copy is kept beside
            // it for a manual restore, and the save the game loads stays.
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date((item.mtime * 1000).toLong()))
            File(dir, freeStem("${target.name}.shared-$stamp") { listOf(File(dir, it)) })
        }
    }

    private fun planState(plan: GamePlan, console: Console, item: Item) {
        if (!stateCompatible(item, console, cores)) return skip(plan, item, SkipReason.INCOMPATIBLE_STATE)
        val dir = paths.states(console)
        val target = File(dir, Units.stateFileName(plan.finalStem, slotOf(item)!!))
        resolve(plan, item, target, dir, target.takeIf { it.exists() }) {
            Units.STATE_SLOTS.drop(1).map { File(dir, Units.stateFileName(plan.finalStem, it)) }
                .firstOrNull { !it.exists() && !isTaken(it) }
        }
    }

    private fun planArt(plan: GamePlan, console: Console, item: Item) {
        val suffix = item.name.substringAfterLast('.', "").lowercase()
        val cover = item.kind == Item.COVER
        val allowed = if (cover) suffix in Units.ART_SUFFIXES else suffix == "png"
        if (!allowed || !Units.isReceivableName("${plan.finalStem}.$suffix")) return skip(plan, item, SkipReason.INVALID_ITEM)
        val dir = if (cover) paths.covers(console) else paths.labels(console)
        val target = File(dir, "${plan.finalStem}.$suffix")
        val existing = if (cover) Covers.localCover(paths, console, plan.finalStem) else target.takeIf { it.exists() }
        // Keeping both copies of a cover means nothing: the library shows one.
        resolve(plan, item, target, dir, existing) { null }
    }

    private fun fits(name: String) = name.toByteArray(Charsets.UTF_8).size + NAME_HEADROOM <= ShareProtocol.MAX_FILE_NAME_BYTES

    private fun inside(target: File, base: File): Boolean = try {
        target.canonicalFile.parentFile == base.canonicalFile
    } catch (_: IOException) {
        false
    }

    private fun sameContent(file: File, item: Item): Boolean = try {
        file.isFile && file.length() == item.size && (item.sha256 == null || sha256(file) == item.sha256)
    } catch (_: IOException) {
        false
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().toHex()
    }
}
