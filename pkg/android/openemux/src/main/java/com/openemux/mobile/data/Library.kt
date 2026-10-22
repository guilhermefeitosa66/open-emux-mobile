package com.openemux.mobile.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Every directory the frontend and the RetroArch engine share. */
class Paths(context: Context) {
    val base: File = context.getExternalFilesDir(null) ?: context.filesDir
    val internal: File = context.filesDir

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
)

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
            dir.listFiles().orEmpty()
                .filter { it.isFile && it.extension.lowercase() in console.extensions }
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

    fun playtimeFile(rom: Rom) = File(paths.playtime, "${rom.console.id}_${rom.baseName}.txt")

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
            Meta(roms, consoles)
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
        paths.meta.parentFile?.mkdirs()
        val tmp = File(paths.meta.path + ".tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(paths.meta)
    }
}
