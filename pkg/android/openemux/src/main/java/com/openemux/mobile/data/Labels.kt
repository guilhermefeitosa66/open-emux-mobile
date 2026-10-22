package com.openemux.mobile.data

import android.util.Log
import com.retroarch.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.zip.CRC32

enum class SyncPause { Offline, Quota }

data class SyncProgress(
    val running: Boolean = false,
    val total: Int = 0,
    val done: Int = 0,
    val current: String? = null,
    val paused: SyncPause? = null,
    /** Known to have no label on ScreenScraper. */
    val missing: Int = 0,
    val finishedAt: Long = 0L,
)

/**
 * Cartridge labels ("support-texture") from ScreenScraper, looked up by ROM
 * hash like the desktop app does. Box art is a different asset ([Covers]).
 * Labels are kept on disk; a ".none" file remembers a ROM that has none.
 */
class Labels(private val paths: Paths) {
    private val _found = MutableStateFlow<Map<String, File>>(emptyMap())
    val found: StateFlow<Map<String, File>> = _found.asStateFlow()

    private val _progress = MutableStateFlow(SyncProgress())
    val progress: StateFlow<SyncProgress> = _progress.asStateFlow()

    /** ROMs whose label is not known yet (neither found nor known to be missing). */
    private val _pending = MutableStateFlow<Set<String>>(emptySet())
    val pending: StateFlow<Set<String>> = _pending.asStateFlow()

    private val mutex = Mutex()
    private var lastRequest = 0L

    private val usable get() = BuildConfig.SS_DEVID.isNotEmpty() && BuildConfig.SS_DEVPASSWORD.isNotEmpty()

    private fun dir(console: Console) = File(paths.labels, console.id)
    private fun file(rom: Rom) = File(dir(rom.console), "${rom.baseName}.png")
    private fun none(rom: Rom) = File(dir(rom.console), "${rom.baseName}.none")

    /** Fetches what is missing, one ROM at a time; quota and network errors pause the run. */
    suspend fun sync(roms: List<Rom>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            _found.value = roms.mapNotNull { rom -> file(rom).takeIf { it.exists() }?.let { rom.key to it } }.toMap()
            val todo = roms.filter { !file(it).exists() && !none(it).exists() && it.file.exists() }
            _pending.value = todo.map { it.key }.toSet()
            if (!usable || todo.isEmpty()) {
                _progress.value = SyncProgress(total = roms.size, done = roms.size - todo.size)
                return@withContext
            }
            var done = roms.size - todo.size
            var failures = 0
            try {
                for (rom in todo) {
                    _progress.value = SyncProgress(running = true, total = roms.size, done = done, current = rom.title)
                    val result = fetch(rom)
                    when (result) {
                        is Result.Found -> _found.value = _found.value + (rom.key to result.file)
                        Result.Missing -> { dir(rom.console).mkdirs(); none(rom).createNewFile() }
                        Result.Quota -> { _progress.value = _progress.value.copy(running = false, current = null, paused = SyncPause.Quota); return@withContext }
                        Result.Offline -> if (++failures >= 3) {
                            _progress.value = _progress.value.copy(running = false, current = null, paused = SyncPause.Offline)
                            return@withContext
                        }
                        Result.Skip -> Unit
                    }
                    if (result !is Result.Offline) failures = 0
                    if (result is Result.Found || result == Result.Missing) {
                        done++
                        _pending.value = _pending.value - rom.key
                    }
                }
                _progress.value = SyncProgress(total = roms.size, done = done, missing = roms.count { none(it).exists() }, finishedAt = System.currentTimeMillis())
            } finally {
                if (_progress.value.running) _progress.value = _progress.value.copy(running = false, current = null)
            }
        }
    }

    private sealed interface Result {
        class Found(val file: File) : Result
        data object Missing : Result
        data object Skip : Result
        data object Offline : Result
        data object Quota : Result
    }

    private suspend fun fetch(rom: Rom): Result {
        val (crc, md5) = runCatching { digests(rom.file) }.getOrElse { return Result.Skip }
        val params = listOf(
            "devid" to BuildConfig.SS_DEVID,
            "devpassword" to BuildConfig.SS_DEVPASSWORD,
            "softname" to "OpenEmux",
            "output" to "json",
            "systemeid" to rom.console.screenScraperId.toString(),
            "romtype" to "rom",
            "romnom" to rom.file.name,
            "crc" to crc,
            "md5" to md5,
            "romtaille" to rom.file.length().toString(),
        )
        val query = params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val response = get("https://api.screenscraper.fr/api2/jeuInfos.php?$query")
        val body = when (response.code) {
            200 -> response.body?.toString(Charsets.UTF_8) ?: return Result.Skip
            404 -> return Result.Missing
            429, 430 -> { Log.w(TAG, "screenscraper quota: ${response.code}"); return Result.Quota }
            -1 -> return Result.Offline
            else -> return Result.Skip
        }
        val medias = runCatching {
            JSONObject(body).getJSONObject("response").getJSONObject("jeu").getJSONArray("medias")
        }.getOrElse { return Result.Skip }
        val regions = regionPriority(rom.file.name)
        val url = (0 until medias.length()).map { medias.getJSONObject(it) }
            .filter { it.optString("type") == "support-texture" }
            .minByOrNull { regions.indexOf(it.optString("region")).let { i -> if (i < 0) regions.size else i } }
            ?.optString("url")
            ?: return Result.Missing
        val image = get("$url&maxwidth=640")
        if (image.code == -1) return Result.Offline
        if (image.code != 200 || image.body == null) return Result.Skip
        val target = file(rom)
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".tmp")
        tmp.writeBytes(image.body)
        tmp.renameTo(target)
        return Result.Found(target)
    }

    private class Response(val code: Int, val body: ByteArray?)

    /** The API punishes bursts: one request per second, media included. */
    private suspend fun get(url: String): Response {
        val wait = lastRequest + 1000 - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        lastRequest = System.currentTimeMillis()
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            try {
                val code = conn.responseCode
                Response(code, if (code == 200) conn.inputStream.use { it.readBytes() } else null)
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            Log.w(TAG, "screenscraper unreachable: ${e.javaClass.simpleName}")
            Response(-1, null)
        }
    }

    private fun digests(file: File): Pair<String, String> {
        val crc = CRC32()
        val md5 = MessageDigest.getInstance("MD5")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                crc.update(buf, 0, n)
                md5.update(buf, 0, n)
            }
        }
        return "%08X".format(crc.value) to md5.digest().joinToString("") { "%02X".format(it) }
    }

    private fun regionPriority(fileName: String): List<String> {
        val tagged = listOfNotNull(
            "us".takeIf { "(USA" in fileName },
            "eu".takeIf { "Europe" in fileName },
            "jp".takeIf { "Japan" in fileName },
            "br".takeIf { "Brazil" in fileName },
        )
        return (tagged + listOf("wor", "us", "eu", "jp", "ss")).distinct()
    }

    private companion object {
        const val TAG = "OpenEmuxLabels"
    }
}
