package com.openemux.mobile.data

import android.content.Context
import android.util.Log
import com.retroarch.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.CoroutineContext

/** Version strings compared numerically, the same rules as the desktop's update_checker.py. */
object UpdateVersion {
    private val NUMBER = Regex("""\d+(?:\.\d+)*""")

    /** The first dotted number in [value] ("v1.2.3", "OpenEmux 1.2"), or null when there is none. */
    fun parse(value: String?): List<Int>? =
        value?.let { NUMBER.find(it) }?.value?.split('.')?.map { it.toIntOrNull() ?: return null }

    /** True when [candidate] is a later version than [current]; "1.2" and "1.2.0" are equal. */
    fun isNewer(candidate: String?, current: String?): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}

/** The latest published release: its version and the page it is downloaded from. */
data class Release(val version: String, val url: String)

const val UPDATE_API_URL = "https://api.github.com/repos/guilhermefeitosa66/OpenEmux/releases/latest"
const val DEFAULT_RELEASE_URL = "https://github.com/guilhermefeitosa66/OpenEmux/releases/latest"

/** Reads a `releases/latest` reply; null for anything that does not name a version. */
fun parseRelease(json: String): Release? {
    val obj = try { JSONObject(json) } catch (_: JSONException) { return null }
    val tag = obj.optString("tag_name").ifBlank { obj.optString("name") }
    val version = UpdateVersion.parse(tag)?.joinToString(".") ?: return null
    // Only a GitHub page is opened; anything else falls back to the latest release.
    val page = obj.optString("html_url").takeIf { it.startsWith("https://github.com/") } ?: DEFAULT_RELEASE_URL
    return Release(version, page)
}

/** What the last check found; [Unknown] also covers "off", "still running" and "failed". */
sealed interface UpdateState {
    data object Unknown : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
}

/** Asks GitHub for the latest release. Never throws: every failure means "nothing to show". */
class UpdateChecker(private val current: String, private val fetch: suspend () -> String? = ::httpFetch) {
    suspend fun check(): UpdateState = try {
        when (val release = fetch()?.let(::parseRelease)) {
            null -> UpdateState.Unknown
            else -> if (UpdateVersion.isNewer(release.version, current)) UpdateState.Available(release) else UpdateState.UpToDate
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        UpdateState.Unknown
    }
}

/**
 * The check once per process, its switch and the banner's "Not now". [enabled] and [persist]
 * are the preference, kept outside so the logic runs without a Context.
 */
class UpdateStore(
    enabled: Boolean,
    private val persist: (Boolean) -> Unit,
    private val checker: UpdateChecker,
    private val dispatcher: CoroutineContext = Dispatchers.IO,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Unknown)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Memory only: the banner comes back on the next cold start. */
    private val _dismissed = MutableStateFlow(false)
    val dismissed: StateFlow<Boolean> = _dismissed.asStateFlow()

    private val _enabled = MutableStateFlow(enabled)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private var started = false
    private var job: Job? = null

    /** Starts the check unless it is off or already ran in this process; null when nothing started. */
    fun checkOnce(scope: CoroutineScope): Job? {
        if (!_enabled.value || started) return null
        started = true
        return scope.launch(dispatcher) {
            Log.i(TAG, "update: checking for a newer release")
            val found = checker.check()
            // Turned off while the request was out: show nothing.
            if (_enabled.value) _state.value = found
        }.also { job = it }
    }

    fun dismiss() {
        _dismissed.value = true
    }

    /** Off forgets what was found; on waits for the next start to check again. */
    fun setEnabled(on: Boolean) {
        _enabled.value = on
        persist(on)
        if (!on) {
            // A request still out must not land after an off-then-on.
            job?.cancel()
            _state.value = UpdateState.Unknown
        }
    }

    companion object {
        private const val PREF = "update_check"
        private const val TAG = "OpenEmuxUpdate"

        /** The store over the app's "openemux" preferences, checked against the installed version. */
        fun create(context: Context): UpdateStore {
            val prefs = context.getSharedPreferences("openemux", Context.MODE_PRIVATE)
            return UpdateStore(
                prefs.getBoolean(PREF, true),
                { prefs.edit().putBoolean(PREF, it).apply() },
                UpdateChecker(BuildConfig.VERSION_NAME),
            )
        }
    }
}

private const val TIMEOUT_MS = 8_000

/** The reply body, or null on any non-200 (a 403 is the rate limit) or network failure. */
private suspend fun httpFetch(): String? = withContext(Dispatchers.IO) {
    try {
        val connection = URL(UPDATE_API_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "OpenEmux/${BuildConfig.VERSION_NAME}")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) null
            else connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    } catch (e: IOException) {
        null
    }
}
