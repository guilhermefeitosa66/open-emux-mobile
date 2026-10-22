package com.openemux.mobile

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList

/**
 * The app's language, as on the desktop: the system's when it is one of
 * these (English otherwise, through the English base resources), or the
 * one picked in Ajustes. From API 33 the system keeps the pick and applies
 * it to every process; below, it is stored here and each activity wraps
 * its context with it.
 */
object AppLocale {
    /** Same order, native names and flags as the desktop's LANGUAGE_META. */
    val LANGUAGES = listOf(
        Triple("en", "English", "🇺🇸"),
        Triple("de", "Deutsch", "🇩🇪"),
        Triple("ja", "日本語", "🇯🇵"),
        Triple("fr", "Français", "🇫🇷"),
        Triple("zh-CN", "简体中文", "🇨🇳"),
        Triple("pt-BR", "Português (Brasil)", "🇧🇷"),
        Triple("es", "Español", "🇪🇸"),
        Triple("ta", "தமிழ்", "🇮🇳"),
    )

    private fun prefs(context: Context) = context.getSharedPreferences("openemux", Context.MODE_PRIVATE)

    /** The picked language tag, or "" for the system's. */
    fun current(context: Context): String {
        val tag = if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales.get(0)?.toLanguageTag().orEmpty()
        } else {
            prefs(context).getString("locale", "").orEmpty()
        }
        if (tag.isEmpty()) return ""
        // A bare "pt" or "zh" from the system settings means the variant we ship, as on the desktop.
        return LANGUAGES.firstOrNull { it.first.equals(tag, ignoreCase = true) }?.first
            ?: LANGUAGES.firstOrNull { it.first.substringBefore('-').equals(tag.substringBefore('-'), ignoreCase = true) }?.first
            ?: ""
    }

    fun set(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            // The system recreates the activities in the new language.
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            prefs(activity).edit().putString("locale", tag).commit()
            activity.recreate()
        }
    }

    /** For attachBaseContext: below API 33 the activities apply the pick themselves. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = prefs(base).getString("locale", "").orEmpty()
        if (tag.isEmpty()) return base
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(tag))
        return base.createConfigurationContext(config)
    }
}
