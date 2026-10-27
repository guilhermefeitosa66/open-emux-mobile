package com.openemux.mobile.share

import com.openemux.mobile.data.Console
import com.openemux.mobile.data.CoreDef
import com.openemux.mobile.data.Paths
import java.io.File
import java.io.IOException

/**
 * Who made a save state: the core's `corename` and `display_version` from its
 * libretro `.info`. A state loads only in that exact core, so both must be
 * known and match; an empty value never matches.
 */
data class CoreIdentity(val corename: String, val version: String) {
    val known: Boolean get() = corename.isNotEmpty() && version.isNotEmpty()

    companion object {
        val UNKNOWN = CoreIdentity("", "")
    }
}

private val INFO_LINE = Regex("""^\s*([A-Za-z0-9_]+)\s*=\s*(.*?)\s*$""")

/** `corename` and `display_version` of a `.info` text; a missing key is "". */
fun parseCoreInfo(text: String): CoreIdentity {
    val values = mutableMapOf<String, String>()
    for (line in text.lineSequence()) {
        val match = INFO_LINE.matchEntire(line) ?: continue
        var value = match.groupValues[2]
        if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) value = value.substring(1, value.length - 1)
        values.putIfAbsent(match.groupValues[1], value.trim())
    }
    return CoreIdentity(values["corename"].orEmpty(), values["display_version"].orEmpty())
}

/**
 * The cores of this install, as Share nearby sees them: one instance per
 * manifest or offer, its `.info` reads cached.
 */
class CoreIdentities(private val paths: Paths) {
    private val identities = mutableMapOf<String, CoreIdentity>()

    fun identityOf(core: CoreDef): CoreIdentity = identities.getOrPut(core.id) {
        val info = File(paths.info, "${core.id}_libretro.info")
        try {
            parseCoreInfo(info.readText())
        } catch (_: IOException) {
            CoreIdentity.UNKNOWN
        }
    }

    /** The console's cores whose library is downloaded (the check `Setup` makes). */
    fun installed(console: Console): List<CoreDef> =
        console.cores.filter { File(paths.cores, it.fileName).length() > 0 }

    /** The installed core that made a state of [corename] [version], exactly; null otherwise. */
    fun findCompatible(console: Console, corename: String, version: String): CoreDef? {
        val wanted = CoreIdentity(corename, version)
        if (!wanted.known) return null
        return installed(console).firstOrNull { identityOf(it) == wanted }
    }

    /** The console's core called [corename] (a game's preferred core), installed or not. */
    fun findByName(console: Console, corename: String): CoreDef? =
        if (corename.isEmpty()) null else console.cores.firstOrNull { identityOf(it).corename == corename }
}
