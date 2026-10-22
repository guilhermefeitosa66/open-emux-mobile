package com.openemux.mobile.data

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.retroarch.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.zip.ZipInputStream

/**
 * The first-run download, as on the desktop: the cores for this device's ABI,
 * every core's .info and the system files PPSSPP and Dolphin need. What is
 * already installed is skipped, so a retry picks up where it stopped.
 */
class Setup(private val paths: Paths) {
    enum class Step(@StringRes val label: Int) {
        CORES(R.string.data_setup_step_cores),
        INFO(R.string.data_setup_step_info),
        SYSTEM(R.string.data_setup_step_system),
    }

    sealed interface State {
        data object Idle : State
        /** [item] is null for an archive named after its step. */
        data class Running(val step: Step, val done: Int, val total: Int, val item: String?, val bytes: Long) : State {
            @Composable
            fun itemName(): String = item ?: stringResource(step.label)
        }
        /** [reason] takes the item's name as %1$s and [detail] as %2$s. */
        data class Failed(
            @StringRes val reason: Int,
            val step: Step,
            val done: Int,
            val total: Int,
            val item: String? = null,
            val detail: String = "",
        ) : State {
            @Composable
            fun message(): String = stringResource(reason, item ?: stringResource(step.label), detail)
        }
        data object Done : State
    }

    /** [name] is null for an archive named after its step. */
    private class Item(val step: Step, val name: String?, val url: String, val installed: () -> Boolean, val install: (File) -> Unit)

    private val abi = Build.SUPPORTED_ABIS.firstOrNull { it in ABIS }
    private val stamp = File(paths.internal, "setup.done")
    private val marks = File(paths.internal, "setup")

    private val _state = MutableStateFlow<State>(if (needed()) State.Idle else State.Done)
    val state: StateFlow<State> = _state.asStateFlow()

    private fun needed() = !stamp.exists() || stamp.readText() != "$VERSION/$abi"

    private fun items(abi: String): List<Item> {
        val cores = Console.entries.flatMap { it.cores }.distinctBy { it.id }.map { core ->
            val target = File(paths.cores, core.fileName)
            Item(Step.CORES, core.name, "$BUILDBOT/nightly/android/latest/$abi/${core.fileName}.zip", { target.length() > 0 }) { zip ->
                unzip(zip, paths.cores) { entry -> if (entry == core.fileName) entry else null }
            }
        }
        val info = marked("info", Step.INFO, null, "$BUILDBOT/assets/frontend/info.zip") { zip ->
            unzip(zip, paths.info) { entry -> entry.takeIf { it.endsWith(".info") && '/' !in it } }
        }
        val system = SYSTEM_PACKS.map { (name, file) ->
            marked("system-$file", Step.SYSTEM, name, "$BUILDBOT/assets/system/$file.zip") { zip -> unzip(zip, paths.system) { it } }
        }
        return cores + info + system
    }

    /** An archive that leaves no single file to check for: a marker records it. */
    private fun marked(id: String, step: Step, name: String?, url: String, install: (File) -> Unit): Item {
        val mark = File(marks, "$id.ok")
        return Item(step, name, url, { mark.exists() }) { zip -> install(zip); mark.parentFile?.mkdirs(); mark.writeText("") }
    }

    suspend fun run() = withContext(Dispatchers.IO) {
        val abi = abi ?: run {
            _state.value = State.Failed(R.string.data_setup_unsupported, Step.CORES, 0, 0, detail = Build.SUPPORTED_ABIS.joinToString())
            return@withContext
        }
        val items = items(abi)
        val tmp = File(marks, "download.zip")
        items.forEachIndexed { i, item ->
            if (item.installed()) return@forEachIndexed
            _state.value = State.Running(item.step, i, items.size, item.name, 0)
            try {
                download(item.url, tmp) { bytes -> _state.value = State.Running(item.step, i, items.size, item.name, bytes) }
                item.install(tmp)
            } catch (e: IOException) {
                _state.value = failure(e, item, i, items.size)
                return@withContext
            } finally {
                tmp.delete()
            }
        }
        stamp.writeText("$VERSION/$abi")
        _state.value = State.Done
    }

    private fun download(url: String, target: File, onBytes: (Long) -> Unit) {
        target.parentFile?.mkdirs()
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        try {
            if (conn.responseCode != 200) throw HttpError(conn.responseCode)
            var read = 0L
            var shown = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (read - shown > 256 * 1024) { shown = read; onBytes(read) }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Extracts the entries [rename] keeps, under the name it gives, never outside [dir]. */
    private fun unzip(zip: File, dir: File, rename: (String) -> String?) {
        dir.mkdirs()
        val root = dir.canonicalPath + File.separator
        var extracted = 0
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val name = rename(entry.name) ?: continue
                val target = File(dir, name)
                if (!target.canonicalPath.startsWith(root)) continue
                if (entry.isDirectory) { target.mkdirs(); continue }
                target.parentFile?.mkdirs()
                // Written aside first, so a cut download never leaves a half core behind.
                val part = File(target.path + ".part")
                part.outputStream().use { input.copyTo(it) }
                if (!part.renameTo(target)) throw IOException("could not write ${target.name}")
                extracted++
            }
        }
        if (extracted == 0) throw IOException("empty archive")
    }

    private class HttpError(val code: Int) : IOException("HTTP $code")

    private fun failure(e: IOException, item: Item, done: Int, total: Int): State.Failed {
        val (reason, detail) = when (e) {
            is UnknownHostException, is java.net.ConnectException, is java.net.NoRouteToHostException -> R.string.data_setup_no_internet to ""
            is SocketTimeoutException -> R.string.data_setup_timeout to ""
            is HttpError -> R.string.data_setup_http to e.code.toString()
            else -> R.string.data_setup_failed to (e.message ?: e.javaClass.simpleName)
        }
        return State.Failed(reason, item.step, done, total, item.name, detail)
    }

    private companion object {
        const val VERSION = 1
        const val BUILDBOT = "https://buildbot.libretro.com"
        val ABIS = setOf("arm64-v8a", "x86_64")
        val SYSTEM_PACKS = listOf("PPSSPP" to "PPSSPP", "Dolphin" to "Dolphin")
    }
}
