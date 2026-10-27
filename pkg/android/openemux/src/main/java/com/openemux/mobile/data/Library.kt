package com.openemux.mobile.data

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Every directory the frontend and the RetroArch engine share. [base] is the
 * app's external files folder, [internal] its private one; the plain
 * constructor lets the JVM tests build the same layout in a temp folder.
 */
class Paths(val base: File, val internal: File) {
    constructor(context: Context) : this(context.getExternalFilesDir(null) ?: context.filesDir, context.filesDir)

    fun roms(console: Console) = File(base, "roms/${console.id}")
    fun states(console: Console) = File(base, "states/${console.id}")
    fun saves(console: Console) = File(base, "saves/${console.id}")
    val system = File(base, "system")
    val screenshots = File(base, "screenshots")
    val playtime = File(base, "playtime")
    val cores = File(internal, "cores")
    val info = File(internal, "info")
    val shaders = File(internal, "shaders")
    val launch = File(internal, "launch")
    val meta = File(internal, "meta.json")
    val index = File(internal, "library.index")
    val carts = File(internal, "carts")
    val labels = File(internal, "labels")

    fun labels(console: Console) = File(labels, console.id)

    /** Box art kept on the device (a game received through Share nearby), preferred over the mirror. */
    fun covers(console: Console) = File(internal, "covers/${console.id}")

    fun playtime(console: Console, stem: String) = File(playtime, "${console.id}_$stem.txt")
}

data class Rom(
    val key: String,
    val console: Console,
    val file: File,
    val title: String,
) {
    val baseName: String get() = file.nameWithoutExtension
}

data class RomMeta(
    val favorite: Boolean = false,
    val color: String? = null,
    val core: String? = null,
    val shader: String? = null,
    val lastPlayed: Long = 0L,
)

data class ConsoleSettings(
    val core: String,
    val shader: String,
    val color: String = "default",
)

/** Slot the pause sheet copies the auto state into: the engine loads slots only. */
const val SCRATCH_SLOT = 999

data class SaveState(val slot: Int, val file: File, val thumbnail: File?, val modified: Long) {
    val isAuto: Boolean get() = slot < 0
}

data class Meta(
    val roms: Map<String, RomMeta> = emptyMap(),
    val consoles: Map<String, ConsoleSettings> = emptyMap(),
    /** The user's console order, as on the desktop (issue #386): ids, the rest follow in Console order. */
    val consoleOrder: List<String> = emptyList(),
)

/** The metadata without the games [keys] names; console settings and order stay. */
fun Meta.withoutRoms(keys: Set<String>): Meta = copy(roms = roms - keys)

/** A cue sheet's FILE line: quoted or bare name. */
internal val CUE_FILE = Regex("""(?im)^\s*FILE\s+(?:"([^"]+)"|(\S+))""")

/** Clean title from a No-Intro style file name: tags and extension go. */
fun titleFromFileName(name: String): String {
    val stem = name.substringBeforeLast('.')
    val cleaned = stem.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), "").trim()
    return cleaned.ifEmpty { stem }
}

private const val TAG = "OpenEmuxLibrary"

/**
 * [romsReady] says whether the ROM folders under [Paths.base] can be listed right
 * now; while they cannot, a scan would read as "every game is gone" (issue #608).
 */
class Library(
    val paths: Paths,
    private val romsReady: (File) -> Boolean,
    private val storageRetryMs: Long = STORAGE_RETRY_MS,
) {
    // Shared storage that is not mounted yet (right after a boot) or the internal fallback holds none of the user's ROMs.
    constructor(context: Context) : this(Paths(context), { base ->
        base != context.filesDir && Environment.getExternalStorageState(base) == Environment.MEDIA_MOUNTED
    })

    private val _roms = MutableStateFlow<List<Rom>>(emptyList())
    val roms: StateFlow<List<Rom>> = _roms.asStateFlow()

    /** False until the ROM list is known: restored from the index or scanned once. */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** The keys the index on disk holds, so an unchanged scan does not rewrite it. */
    private var indexed: List<String>? = null

    private val _meta = MutableStateFlow(Meta())
    val meta: StateFlow<Meta> = _meta.asStateFlow()

    /** Bumped when files change under the frontend (states, playtime). */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    init {
        _meta.value = loadMeta()
        loadIndex()
    }

    /**
     * Lists the ROM folders and publishes what is there. While the shared storage is not
     * ready it waits a little, then gives up and leaves the library as it was restored:
     * the next scan (the next resume) decides, so an unmounted folder never empties the
     * shelf nor the index the next cold start opens on.
     */
    suspend fun scan() = withContext(Dispatchers.IO) {
        if (!awaitRoms()) {
            Log.w(TAG, "ROM storage not ready, scan skipped: ${paths.base}")
            return@withContext
        }
        var listed = true
        try {
            listed = scanRoms()
        } finally {
            if (listed) _loaded.value = true
        }
        if (!listed) Log.w(TAG, "a ROM folder could not be listed, scan discarded: ${paths.base}")
    }

    private suspend fun awaitRoms(): Boolean {
        repeat(STORAGE_TRIES - 1) {
            if (romsReady(paths.base)) return true
            delay(storageRetryMs)
        }
        return romsReady(paths.base)
    }

    /**
     * False, with nothing published, when a folder cannot be listed: a null listing is an
     * I/O error (storage going away mid-scan, a folder not reachable yet), not an empty folder.
     */
    private fun scanRoms(): Boolean {
        val found = Console.entries.flatMap { console ->
            val dir = paths.roms(console)
            dir.mkdirs()
            val files = (dir.listFiles() ?: return false).filter { it.isFile }
            // A disc's track files belong to its .cue, as in the desktop scanner.
            val tracks = files.filter { it.extension.equals("cue", ignoreCase = true) }.flatMap { cue ->
                CUE_FILE.findAll(cue.readText()).map { it.groupValues[1].ifEmpty { it.groupValues[2] }.trim() }.toList()
            }.toSet()
            files
                .filter { it.extension.lowercase() in console.extensions && it.name !in tracks }
                .map { Rom("${console.id}/${it.name}", console, it, titleFromFileName(it.name)) }
        }.sortedWith(ROM_ORDER)
        _roms.value = found
        saveIndex(found)
        _revision.value++
        return true
    }

    fun touch() {
        _revision.value++
    }

    fun romMeta(key: String): RomMeta = _meta.value.roms[key] ?: RomMeta()

    fun consoleSettings(console: Console): ConsoleSettings = _meta.value.settingsFor(console)

    private fun Meta.settingsFor(console: Console): ConsoleSettings =
        consoles[console.id] ?: ConsoleSettings(core = console.cores[0].id, shader = console.defaultShader)

    fun effectiveCore(rom: Rom): CoreDef =
        rom.console.core(romMeta(rom.key).core ?: consoleSettings(rom.console).core)

    fun effectiveShader(rom: Rom): ShaderDef =
        shaderById(romMeta(rom.key).shader ?: consoleSettings(rom.console).shader)

    fun effectiveColor(rom: Rom): String =
        romMeta(rom.key).color ?: consoleSettings(rom.console).color

    // Share nearby writes settings from its connection threads while the UI
    // writes from the main one: every read-modify-write of the meta is serialised.
    @Synchronized
    fun updateRom(key: String, change: (RomMeta) -> RomMeta) {
        _meta.update { it.copy(roms = it.roms + (key to change(it.roms[key] ?: RomMeta()))) }
        saveMeta()
    }

    @Synchronized
    fun updateConsole(console: Console, change: (ConsoleSettings) -> ConsoleSettings) {
        _meta.update { it.copy(consoles = it.consoles + (console.id to change(it.settingsFor(console)))) }
        saveMeta()
    }

    /**
     * Every console in the user's order: the ids the stored order names first,
     * then the others in Console order. One order feeds the shelf, the chips,
     * L1/R1 and Ajustes. Unknown or repeated ids are dropped.
     */
    fun orderedConsoles(order: List<String> = _meta.value.consoleOrder): List<Console> {
        val named = order.mapNotNull { Console.byId(it) }.distinct()
        return named + Console.entries.filter { it !in named }
    }

    /** Moves [console] one place among [present] (the consoles on screen); false if it cannot go further. */
    fun moveConsole(console: Console, present: List<Console>, step: Int): Boolean {
        val shown = orderedConsoles().filter { it in present }
        val target = shown.getOrNull(shown.indexOf(console) + step) ?: return false
        val all = orderedConsoles().toMutableList()
        val i = all.indexOf(console)
        val j = all.indexOf(target)
        all[i] = target
        all[j] = console
        setConsoleOrder(all)
        return true
    }

    @Synchronized
    fun setConsoleOrder(order: List<Console>) {
        _meta.update { it.copy(consoleOrder = order.map { o -> o.id }) }
        saveMeta()
    }

    @Synchronized
    fun resetConsoleOrder() {
        _meta.update { it.copy(consoleOrder = emptyList()) }
        saveMeta()
    }

    /** ROMs whose core does not follow the console default. */
    fun romsWithOwnCore(console: Console): List<Rom> =
        _roms.value.filter { it.console == console && romMeta(it.key).core != null }

    fun states(rom: Rom): List<SaveState> {
        val dir = paths.states(rom.console)
        val prefix = "${rom.baseName}.state"
        return dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith(prefix) && !it.name.endsWith(".png") }
            .mapNotNull { file ->
                val suffix = file.name.removePrefix(prefix)
                val slot = when {
                    suffix.isEmpty() -> 0
                    suffix == ".auto" -> -1
                    suffix.all { it.isDigit() } && suffix.toInt() != SCRATCH_SLOT -> suffix.toInt()
                    else -> return@mapNotNull null
                }
                val thumb = File(file.path + ".png").takeIf { it.exists() }
                SaveState(slot, file, thumb, file.lastModified())
            }
            .sortedByDescending { it.modified }
    }

    fun autoState(rom: Rom): SaveState? = states(rom).firstOrNull { it.isAuto }

    fun playtimeFile(rom: Rom) = paths.playtime(rom.console, rom.baseName)

    fun playtimeSeconds(rom: Rom): Long =
        runCatching { playtimeFile(rom).readText().trim().toLong() }.getOrDefault(0L)

    @Synchronized
    fun delete(rom: Rom) {
        deleteAll(listOf(rom))
    }

    /**
     * Deletes the games' files and their metadata, saving it once. A game
     * whose file could not be deleted keeps its place and its metadata.
     * Returns how many were removed.
     */
    fun deleteAll(roms: Collection<Rom>): Int {
        val gone = roms.filter { it.file.delete() || !it.file.exists() }.map { it.key }.toSet()
        if (gone.isEmpty()) return 0
        // Runs off the main thread: update atomically so a favorite toggled or a scan finished meanwhile is kept.
        _roms.update { list -> list.filterNot { it.key in gone } }
        saveIndex(_roms.value)
        _meta.update { it.withoutRoms(gone) }
        saveMeta()
        return gone.size
    }

    private fun loadIndex() {
        val file = paths.index
        if (!file.exists()) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        val roms = runCatching { decodeLibraryIndex(text, paths::roms) }.getOrNull() ?: return
        // An index whose every key was dropped (a console or extension this build no longer
        // knows) proves nothing about the files: let the scan decide, not the empty state.
        if (roms.isEmpty() && text.lines().drop(1).any { it.isNotBlank() }) return
        indexed = roms.map { it.key }
        _roms.value = roms
        _loaded.value = true
    }

    /**
     * A failed write only costs the fast start next time. Synchronized: a resume scan, an
     * import's scan and [delete] can overlap, and they share the tmp file and [indexed].
     */
    @Synchronized
    private fun saveIndex(roms: List<Rom>) {
        val keys = roms.map { it.key }
        if (keys == indexed) return
        runCatching {
            paths.index.parentFile?.mkdirs()
            val tmp = File(paths.index.path + ".tmp")
            tmp.writeText(encodeLibraryIndex(roms))
            if (!tmp.renameTo(paths.index)) error("rename failed")
            indexed = keys
        }.onFailure { Log.w(TAG, "library index not written", it) }
    }

    companion object {
        /** About ten seconds of waiting for the shared storage after a boot. */
        internal const val STORAGE_TRIES = 20
        private const val STORAGE_RETRY_MS = 500L
    }

    private fun loadMeta(): Meta {
        val file = paths.meta
        if (!file.exists()) return Meta()
        return runCatching {
            val json = JSONObject(file.readText())
            val roms = json.optJSONObject("roms")?.let { obj ->
                obj.keys().asSequence().associateWith { k ->
                    val r = obj.getJSONObject(k)
                    RomMeta(
                        favorite = r.optBoolean("fav"),
                        color = r.optString("color").ifEmpty { null },
                        core = r.optString("core").ifEmpty { null },
                        shader = r.optString("shader").ifEmpty { null },
                        lastPlayed = r.optLong("lastPlayed"),
                    )
                }
            }.orEmpty()
            val consoles = json.optJSONObject("consoles")?.let { obj ->
                obj.keys().asSequence().associateWith { k ->
                    val c = obj.getJSONObject(k)
                    ConsoleSettings(c.getString("core"), c.getString("shader"), c.optString("color", "default"))
                }
            }.orEmpty()
            val order = json.optJSONArray("consoleOrder")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }.orEmpty()
            Meta(roms, consoles, order.filter { Console.byId(it) != null }.distinct())
        }.getOrDefault(Meta())
    }

    // Synchronized: the bulk delete saves from an IO thread while the UI saves from the main one, both through one .tmp file.
    @Synchronized
    private fun saveMeta() {
        val m = _meta.value
        val json = JSONObject()
        json.put("roms", JSONObject().apply {
            m.roms.forEach { (k, r) ->
                put(k, JSONObject().apply {
                    put("fav", r.favorite)
                    r.color?.let { put("color", it) }
                    r.core?.let { put("core", it) }
                    r.shader?.let { put("shader", it) }
                    put("lastPlayed", r.lastPlayed)
                })
            }
        })
        json.put("consoles", JSONObject().apply {
            m.consoles.forEach { (k, c) ->
                put(k, JSONObject().apply {
                    put("core", c.core)
                    put("shader", c.shader)
                    put("color", c.color)
                })
            }
        })
        if (m.consoleOrder.isNotEmpty()) json.put("consoleOrder", org.json.JSONArray(m.consoleOrder))
        paths.meta.parentFile?.mkdirs()
        val tmp = File(paths.meta.path + ".tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(paths.meta)
    }
}
