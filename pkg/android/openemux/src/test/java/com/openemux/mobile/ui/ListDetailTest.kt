package com.openemux.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListDetailTest {

    private val items = listOf(
        SettingsItem.Console("GBA"), SettingsItem.Bios, SettingsItem.Theme, SettingsItem.Language,
        SettingsItem.Achievements, SettingsItem.Backup, SettingsItem.About, SettingsItem.License("gpl"),
    )

    // The windows of the plan's table: phone portrait and landscape, 8" and 10" portrait, 10" and 7" landscape.
    private val phone = WindowLayout.of(411f, 914f)
    private val phoneSideways = WindowLayout.of(914f, 411f)
    private val eightInch = WindowLayout.of(744f, 1133f)
    private val tenInchPortrait = WindowLayout.of(800f, 1280f)
    private val tenInchLandscape = WindowLayout.of(1280f, 800f)
    private val sevenInchLandscape = WindowLayout.of(960f, 552f)

    @Test
    fun phonesKeepAScreenPerItem() {
        for (w in listOf(phone, phoneSideways)) {
            assertFalse(w.splitSettings)
            assertFalse(w.libraryPane)
            assertFalse(w.twoColumnDetail)
            assertFalse(w.tableList)
        }
    }

    @Test
    fun portraitTabletsSplitSettingsButOpenGamesOnTheirOwnScreen() {
        for (w in listOf(eightInch, tenInchPortrait)) {
            assertTrue(w.splitSettings)
            assertFalse(w.libraryPane)
            assertTrue(w.twoColumnDetail)
            assertTrue(w.tableList)
            assertEquals(320f, w.settingsListDp)
        }
    }

    @Test
    fun landscapeTabletsShowTheGameBesideTheLibrary() {
        for (w in listOf(tenInchLandscape, sevenInchLandscape)) {
            assertTrue(w.splitSettings)
            assertTrue(w.libraryPane)
            assertTrue(w.twoColumnDetail)
            assertTrue(w.tableList)
            assertEquals(400f, w.settingsListDp)
        }
    }

    @Test
    fun theFlagsFlipAtTheBreakpoints() {
        assertFalse(WindowLayout.of(599f, 900f).splitSettings)
        assertTrue(WindowLayout.of(600f, 900f).splitSettings)
        assertFalse(WindowLayout.of(839f, 900f).libraryPane)
        assertTrue(WindowLayout.of(840f, 900f).libraryPane)
        // A short window never splits, however wide.
        assertFalse(WindowLayout.of(1280f, 479f).splitSettings)
        assertFalse(WindowLayout.of(1280f, 479f).libraryPane)
        assertFalse(WindowLayout.of(1280f, 479f).twoColumnDetail)
        assertTrue(WindowLayout.of(1280f, 480f).libraryPane)
        assertFalse(WindowLayout.of(599f, 900f).tableList)
        assertTrue(WindowLayout.of(600f, 480f).tableList)
        assertFalse(WindowLayout.of(1280f, 479f).tableList)
    }

    @Test
    fun everyItemSurvivesItsSavedKey() {
        for (item in items) assertEquals(item, SettingsItem.of(item.key))
    }

    @Test
    fun unknownKeysAreNoItem() {
        assertNull(SettingsItem.of(null))
        assertNull(SettingsItem.of(""))
        assertNull(SettingsItem.of("console:"))
        assertNull(SettingsItem.of("license:"))
        assertNull(SettingsItem.of("shaders"))
    }

    @Test
    fun itemsOpenTheirPhoneScreenOrASheet() {
        assertEquals("settings/GBA", SettingsItem.Console("GBA").route)
        assertEquals(Routes.BIOS, SettingsItem.Bios.route)
        assertEquals(Routes.ACHIEVEMENTS, SettingsItem.Achievements.route)
        assertEquals(Routes.BACKUP, SettingsItem.Backup.route)
        assertEquals(Routes.ABOUT, SettingsItem.About.route)
        assertEquals("about/license/gpl", SettingsItem.License("gpl").route)
        assertNull(SettingsItem.Theme.route)
        assertNull(SettingsItem.Language.route)
    }

    @Test
    fun backFocusesTheListThenClearsThenLeaves() {
        assertEquals(BackStep.FocusList, backStep(selected = true, focusInDetail = true, gamepad = true))
        assertEquals(BackStep.FocusList, backStep(selected = false, focusInDetail = true, gamepad = true))
        assertEquals(BackStep.ClearSelection, backStep(selected = true, focusInDetail = false, gamepad = true))
        assertEquals(BackStep.Leave, backStep(selected = false, focusInDetail = false, gamepad = true))
        // By touch there is no focus to move: Back clears, then leaves.
        assertEquals(BackStep.ClearSelection, backStep(selected = true, focusInDetail = true, gamepad = false))
        assertEquals(BackStep.ClearSelection, backStep(selected = true, focusInDetail = false, gamepad = false))
        assertEquals(BackStep.Leave, backStep(selected = false, focusInDetail = true, gamepad = false))
        assertEquals(BackStep.Leave, backStep(selected = false, focusInDetail = false, gamepad = false))
    }

    @Test
    fun theDetailShowsThreeToFourStates() {
        assertEquals(3, detailStatesCount(0f))
        assertEquals(3, detailStatesCount(509f))
        assertEquals(4, detailStatesCount(510f))
        assertEquals(4, detailStatesCount(2000f))
    }

    @Test
    fun onlyACrossingIsAChange() {
        assertEquals(SplitChange.Joined, splitChange(before = false, now = true))
        assertEquals(SplitChange.Parted, splitChange(before = true, now = false))
        assertNull(splitChange(before = true, now = true))
        assertNull(splitChange(before = false, now = false))
    }
}
