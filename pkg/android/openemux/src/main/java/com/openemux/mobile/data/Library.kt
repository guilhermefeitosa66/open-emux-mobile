package com.openemux.mobile.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/** A cue sheet's FILE line: quoted or bare name. */
internal val CUE_FILE = Regex("""(?im)^\s*FILE\s+(?:"([^"]+)"|(\S+))""")

/** Clean title from a No-Intro style file name: tags and extension go. */
fun titleFromFileName(name: String): String {
    val stem = name.substringBeforeLast('.')
    val cleaned = stem.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), "").trim()
    return cleaned.ifEmpty { stem }
}

class Library(private val context: Context) {
    val paths = Paths(context)

    private val _roms = MutableStateFlow<List<Rom>>(emptyList())
    val roms: StateFlow<List<Rom>> = _roms.asStateFlow()

    private val _meta = MutableStateFlow(Meta())
    val meta: StateFlow<Meta> = _meta.asStateFlow()

    /** Bumped when files change under the frontend (states, playtime). */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    init {
        _meta.value = loadMeta()
    }

    suspend fun scan() = withContext(Dispatchers.IO) {
        val found = Console.entries.flatMap { console ->
            val dir = paths.roms(console)
            dir.mkdirs()
            val files = dir.listFiles().orEmpty().filter { it.isFile }
            // A disc's track files belong to its .cue, as in the desktop scanner.
            val tracks = files.filter { it.extension.equals("cue", ignoreCase = true) }.flatMap { cue ->
                CUE_FILE.findAll(cue.readText()).map { it.groupValues[1].ifEmpty { it.groupValues[2] }.trim() }.toList()
            }.toSet()
            files
                .filter { it.extension.lowercase() in console.extensions && it.name !in tracks }
                .map { Rom("${console.id}/${it.name}", console, it, titleFromFileName(it.name)) }
        }.sortedBy { it.title.lowercase() }
        _roms.value = found
        _revision.value++
    }

    fun touch() {
        _revision.value++
    }

    fun romMeta(key: String): RomMeta = _meta.value.roms[key] ?: RomMeta()

    fun consoleSettings(console: Console): ConsoleSettings =
        _meta.value.consoles[console.id]
            ?: ConsoleSettings(core = console.cores[0].id, shader = console.defaultShader)

    fun effectiveCore(rom: Rom): CoreDef =
        rom.console.core(romMeta(rom.key).core ?: consoleSettings(rom.console).core)

    fun effectiveShader(rom: Rom): ShaderDef =
        shaderById(romMeta(rom.key).shader ?: consoleSettings(rom.console).shader)

    fun effectiveColor(rom: Rom): String =
        romMeta(rom.key).color ?: consoleSettings(rom.console).color

    fun updateRom(key: String, change: (RomMeta) -> RomMeta) {
        val current = _meta.value
        _meta.value = current.copy(roms = current.roms + (key to change(romMeta(key))))
        saveMeta()
    }

    fun updateConsole(console: Console, change: (ConsoleSettings) -> ConsoleSettings) {
        val current = _meta.value
        _meta.value = current.copy(
            consoles = current.consoles + (console.id to change(consoleSettings(console)))
        )
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

    fun setConsoleOrder(order: List<Console>) {
        _meta.value = _meta.value.copy(consoleOrder = order.map { it.id })
        saveMeta()
    }

    fun resetConsoleOrder() {
        _meta.value = _meta.value.copy(consoleOrder = emptyList())
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

    fun delete(rom: Rom) {
        rom.file.delete()
        _roms.value = _roms.value.filterNot { it.key == rom.key }
        val current = _meta.value
        _meta.value = current.copy(roms = current.roms - rom.key)
        saveMeta()
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
