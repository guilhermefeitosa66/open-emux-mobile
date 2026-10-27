package com.openemux.mobile.ui

// The list + detail decisions as plain values, so they run as JVM unit tests:
// which settings item a pane shows, where it lives as a screen of its own,
// and what Back does while a detail is open beside its list.

/** One thing the settings list opens, in its pane or as its own screen. */
sealed interface SettingsItem {
    data class Console(val id: String) : SettingsItem
    data object Bios : SettingsItem
    data object Theme : SettingsItem
    data object Language : SettingsItem
    data object Achievements : SettingsItem
    data object Backup : SettingsItem
    data object About : SettingsItem
    data class License(val id: String) : SettingsItem

    /** The item as a string, for the saved state. */
    val key: String
        get() = when (this) {
            is Console -> "console:$id"
            Bios -> "bios"
            Theme -> "theme"
            Language -> "language"
            Achievements -> "achievements"
            Backup -> "backup"
            About -> "about"
            is License -> "license:$id"
        }

    /** The screen that shows this item on a phone; null for the ones that open a sheet there. */
    val route: String?
        get() = when (this) {
            is Console -> Routes.console(id)
            Bios -> Routes.BIOS
            Achievements -> Routes.ACHIEVEMENTS
            Backup -> Routes.BACKUP
            About -> Routes.ABOUT
            is License -> Routes.license(id)
            Theme, Language -> null
        }

    companion object {
        /** The reverse of [key]; null for anything else. */
        fun of(key: String?): SettingsItem? = when {
            key == null -> null
            key.startsWith("console:") -> key.removePrefix("console:").takeIf { it.isNotEmpty() }?.let { Console(it) }
            key.startsWith("license:") -> key.removePrefix("license:").takeIf { it.isNotEmpty() }?.let { License(it) }
            else -> when (key) {
                "bios" -> Bios
                "theme" -> Theme
                "language" -> Language
                "achievements" -> Achievements
                "backup" -> Backup
                "about" -> About
                else -> null
            }
        }
    }
}

/** The item a phone's open sheet becomes in the pane when a resize brings the split back; null with none open. */
fun sheetToPane(themeSheet: Boolean, languageSheet: Boolean): SettingsItem? = when {
    themeSheet -> SettingsItem.Theme
    languageSheet -> SettingsItem.Language
    else -> null
}

/** What Back does on a list + detail screen. */
enum class BackStep { FocusList, ClearSelection, Leave }

/**
 * Back on a list + detail screen: a gamepad focused in the detail goes back
 * to the list first, then a selection is cleared, and only then the screen is left.
 */
fun backStep(selected: Boolean, focusInDetail: Boolean, gamepad: Boolean): BackStep = when {
    gamepad && focusInDetail -> BackStep.FocusList
    selected -> BackStep.ClearSelection
    else -> BackStep.Leave
}

/** How many saved states the two-column detail shows in a row in [widthDp]: 3 to 4 cells of at least 120dp. */
fun detailStatesCount(widthDp: Float): Int = gridColumns(widthDp, 120f, 10f, 3).coerceAtMost(4)

/** Which way a resize crossed between a split screen and screens of their own; null for no crossing. */
enum class SplitChange { Joined, Parted }

/** The crossing from [before] to [now], so a screen reacts to the change and not to how it started. */
fun splitChange(before: Boolean, now: Boolean): SplitChange? = when {
    !before && now -> SplitChange.Joined
    before && !now -> SplitChange.Parted
    else -> null
}
