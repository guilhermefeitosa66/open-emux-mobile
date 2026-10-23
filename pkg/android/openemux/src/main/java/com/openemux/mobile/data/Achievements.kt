package com.openemux.mobile.data

import android.content.Context
import android.util.Log
import com.retroarch.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** The RetroAchievements account and switches; the password is never part of it. */
data class AchievementsState(
    val username: String = "",
    val token: String = "",
    val enabled: Boolean = false,
    val hardcore: Boolean = false,
    /** Kept after signing out, to prefill the sign-in form. */
    val lastUsername: String = "",
) {
    val signedIn: Boolean get() = username.isNotEmpty() && token.isNotEmpty()
    val tracking: Boolean get() = enabled && signedIn
    val hardcoreActive: Boolean get() = tracking && hardcore
}

sealed interface LoginResult {
    data class Ok(val username: String, val token: String) : LoginResult
    /** The service said no; [message] is its own wording, when it sent one. */
    data class Refused(val message: String?) : LoginResult
    data object Unreachable : LoginResult
    data object Missing : LoginResult
}

/** One line of retroarch.cfg holds the value between quotes: nothing may break out of it. */
fun isSafeConfigValue(value: String): Boolean = value.none { it == '"' || it == '\n' || it == '\r' }

/** The engine's cheevos keys for a launch. Off is written out too: nothing else resets them. */
fun AchievementsState.configKeys(): Map<String, String> =
    if (tracking) linkedMapOf(
        "cheevos_enable" to "true",
        "cheevos_username" to username,
        "cheevos_token" to token,
        // The token signs in; a password in the config would be read first.
        "cheevos_password" to "",
        "cheevos_hardcore_mode_enable" to hardcore.toString(),
        // Top centre is clear of the skin in both orientations.
        "cheevos_appearance_anchor" to "1",
        "cheevos_appearance_padding_auto" to "true",
    ) else linkedMapOf(
        "cheevos_enable" to "false",
        "cheevos_username" to "",
        "cheevos_token" to "",
        "cheevos_password" to "",
        "cheevos_hardcore_mode_enable" to "false",
    )

/** Reads a `login2` reply; [username] stands in when the service does not echo it. */
fun parseLogin(body: String, username: String): LoginResult {
    val json = try { JSONObject(body) } catch (_: JSONException) { return LoginResult.Unreachable }
    val token = json.optString("Token")
    if (json.optBoolean("Success") && token.isNotEmpty()) {
        return LoginResult.Ok(json.optString("User").ifEmpty { username }, token)
    }
    return LoginResult.Refused(json.optString("Error").takeIf { it.isNotBlank() })
}

/** Exchanges the password for a token once, the same request the desktop makes. */
object RaLogin {
    private const val ENDPOINT = "https://retroachievements.org/dorequest.php"
    private const val TIMEOUT_MS = 15_000

    suspend fun login(username: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        val user = username.trim()
        if (user.isEmpty() || password.isEmpty()) return@withContext LoginResult.Missing
        val form = "r=login2&u=" + URLEncoder.encode(user, "UTF-8") + "&p=" + URLEncoder.encode(password, "UTF-8")
        try {
            val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.doOutput = true
                connection.setRequestProperty("User-Agent", "OpenEmux/${BuildConfig.VERSION_NAME}")
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.outputStream.use { it.write(form.toByteArray()) }
                // Wrong details come back as 401 with the reason in the body.
                val stream = if (connection.responseCode < 400) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                parseLogin(body, user).also {
                    if (it is LoginResult.Refused) Log.i(TAG, "cheevos: login refused for $user")
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            // Never the request or the reply: the password is in one of them.
            Log.i(TAG, "cheevos: login failed (${e.javaClass.simpleName})")
            LoginResult.Unreachable
        }
    }

    private const val TAG = "OpenEmux"
}

/** The account, in app-private preferences that are kept out of backups (see backup_rules.xml). */
class AchievementsStore(context: Context, private val launchConfig: File) {
    private val prefs = context.getSharedPreferences("cheevos", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AchievementsState> = _state.asStateFlow()

    private fun load() = AchievementsState(
        username = prefs.getString(USERNAME, "").orEmpty(),
        token = prefs.getString(TOKEN, "").orEmpty(),
        enabled = prefs.getBoolean(ENABLED, false),
        hardcore = prefs.getBoolean(HARDCORE, false),
        lastUsername = prefs.getString(LAST_USERNAME, "").orEmpty(),
    )

    /** Signing in is the intent to track: it turns tracking on. False if the account cannot go in a config line. */
    fun setAccount(username: String, token: String): Boolean {
        if (username.isEmpty() || token.isEmpty() || !isSafeConfigValue(username) || !isSafeConfigValue(token)) return false
        prefs.edit().putString(USERNAME, username).putString(TOKEN, token).putString(LAST_USERNAME, username)
            .putBoolean(ENABLED, true).commit()
        _state.value = load()
        return true
    }

    /** Forgets the token, here and in the last launch's config; the switches stay. */
    fun signOut() {
        prefs.edit().remove(USERNAME).remove(TOKEN).commit()
        launchConfig.delete()
        _state.value = load()
    }

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(ENABLED, on).apply()
        _state.value = _state.value.copy(enabled = on)
    }

    fun setHardcore(on: Boolean) {
        prefs.edit().putBoolean(HARDCORE, on).apply()
        _state.value = _state.value.copy(hardcore = on)
    }

    private companion object {
        const val USERNAME = "username"
        const val TOKEN = "token"
        const val ENABLED = "enabled"
        const val HARDCORE = "hardcore"
        const val LAST_USERNAME = "last_username"
    }
}
