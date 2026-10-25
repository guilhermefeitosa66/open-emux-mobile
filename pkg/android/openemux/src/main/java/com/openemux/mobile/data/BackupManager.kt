package com.openemux.mobile.data

import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.openemux.mobile.AppLocale
import com.openemux.mobile.MainActivity
import com.retroarch.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/** Where the Back up and restore screen is; it lives here so a rotation does not lose a running export. */
sealed interface BackupState {
    data object Idle : BackupState
    data class Running(val export: Boolean, val folder: String, val done: Long, val total: Long) : BackupState
    /** A picked archive, read and waiting for the user to confirm the restore. */
    data class Confirm(val uri: Uri, val manifest: BackupManifest, val bytes: Long) : BackupState
    data class Exported(val name: String, val bytes: Long) : BackupState
    data class Restored(val games: Int, val saves: Int) : BackupState
    data class Failed(val export: Boolean, val error: BackupError) : BackupState
}

/** The Android side of the backup: preferences, the Storage Access Framework, free space and the restart. */
class BackupManager(private val context: Context, private val library: Library) {
    val roots = BackupRoots(library.paths.base, library.paths.internal)
    private val resolver get() = context.contentResolver
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<BackupState>(BackupState.Idle)
    val state: StateFlow<BackupState> = _state.asStateFlow()

    private val _sizes = MutableStateFlow<Map<BackupGroup, BackupSize>?>(null)
    /** Per group, ROMs included; null while measuring. */
    val sizes: StateFlow<Map<BackupGroup, BackupSize>?> = _sizes.asStateFlow()

    /** The app's, not the screen's: leaving the screen does not stop an export halfway. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    fun dismiss() {
        if (_state.value !is BackupState.Running) _state.value = BackupState.Idle
    }

    fun measure() = scope.launch {
        _sizes.value = null
        _sizes.value = BackupWriter(roots).measure(includeRoms = true)
    }

    /** The settings as they are stored, plus the language, which API 33 and newer keep in the system. */
    private fun prefsJson() = BackupFormat.prefsToJson(prefs.all + (LOCALE to AppLocale.current(context)))

    fun export(uri: Uri, includeRoms: Boolean) = scope.launch {
        cancelled = false
        _state.value = BackupState.Running(true, "", 0, 0)
        try {
            val out = resolver.openOutputStream(uri, "wt") ?: throw IOException("no output stream")
            val manifest = out.use {
                BackupWriter(roots).write(
                    it, includeRoms, prefsJson(), BuildConfig.VERSION_NAME, System.currentTimeMillis(), library.roms.value.size,
                    onProgress = { folder, done, total -> _state.value = BackupState.Running(true, folder, done, total) },
                    isCancelled = { cancelled },
                )
            }
            _state.value = BackupState.Exported(displayName(uri) ?: "", sizeOf(uri) ?: manifest.total.bytes)
        } catch (e: Exception) {
            // Export never touches the app's data; only the half-written file goes.
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            _state.value = if (e is BackupError.Cancelled) BackupState.Idle else BackupState.Failed(true, e.asBackupError())
        }
    }

    /** Reads the picked file's manifest and asks for confirmation. */
    fun inspect(uri: Uri) = scope.launch {
        _state.value = try {
            val input = resolver.openInputStream(uri) ?: throw IOException("no input stream")
            val manifest = input.use { BackupReader(roots).readManifest(it) }
            BackupState.Confirm(uri, manifest, sizeOf(uri) ?: manifest.total.bytes)
        } catch (e: Exception) {
            BackupState.Failed(false, e.asBackupError())
        }
    }

    fun restore(confirm: BackupState.Confirm) = scope.launch {
        cancelled = false
        val needed = confirm.manifest.total.bytes
        val free = minOf(StatFs(roots.external.path).availableBytes, StatFs(roots.internal.path).availableBytes)
        if (needed + SPACE_MARGIN > free) {
            _state.value = BackupState.Failed(false, BackupError.NoSpace(needed, free))
            return@launch
        }
        _state.value = BackupState.Running(false, "", 0, needed)
        val staged = try {
            val input = resolver.openInputStream(confirm.uri) ?: throw IOException("no input stream")
            input.use {
                BackupReader(roots).extract(
                    it,
                    onProgress = { folder, done, total -> _state.value = BackupState.Running(false, folder, done, total) },
                    isCancelled = { cancelled },
                )
            }
        } catch (e: Exception) {
            _state.value = if (e is BackupError.Cancelled) BackupState.Idle else BackupState.Failed(false, e.asBackupError())
            return@launch
        }
        try {
            roots.swapIn(staged.roots)
        } catch (e: BackupError) {
            roots.stagingDirs.forEach { it.deleteRecursively() }
            _state.value = BackupState.Failed(false, e)
            return@launch
        }
        staged.prefs?.let { restorePrefs(it) }
        // The cores are not in the archive: the first-run setup fetches whatever is missing.
        File(library.paths.internal, "setup.done").delete()
        File(library.paths.internal, "setup").deleteRecursively()
        _state.value = BackupState.Restored(staged.manifest.roms, staged.manifest.saves)
        delay(RESTART_DELAY)
        restart()
    }

    private fun restorePrefs(values: Map<String, Any>) {
        val edit = prefs.edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        edit.commit()
        val locale = values[LOCALE] as? String ?: return
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                if (locale.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(locale)
        }
    }

    /** Library, settings, theme and language are all read at start: a fresh process is the simplest reload. */
    private fun restart() {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }

    private fun displayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun sizeOf(uri: Uri): Long? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
    }.getOrNull()

    private fun Exception.asBackupError() = this as? BackupError ?: BackupError.Io(message ?: javaClass.simpleName)

    private companion object {
        const val PREFS = "openemux"
        const val LOCALE = "locale"
        const val SPACE_MARGIN = 100L * 1024 * 1024
        const val RESTART_DELAY = 1500L
    }
}
