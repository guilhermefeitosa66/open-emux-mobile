package com.openemux.mobile.ui

import android.view.KeyEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openemux.mobile.MainActivity
import com.openemux.mobile.OpenEmuxApp
import com.retroarch.R
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The four main tabs answer a tap and the gamepad's Start, L1 and R1 on a device. */
@RunWith(AndroidJUnit4::class)
class MainTabsInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun open() {
        DoneSetup.install(context.applicationContext as OpenEmuxApp)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }

    @After
    fun close() = scenario.close()

    private fun label(id: Int) = context.getString(id)

    private val isTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    private fun tab(id: Int) = compose.onNode(isTab and hasText(label(id)), useUnmergedTree = false)

    private fun tap(id: Int) {
        tab(id).performClick()
        compose.waitForIdle()
    }

    private fun lit(id: Int) {
        compose.waitUntil(5_000) { runCatching { tab(id).assertIsSelected() }.isSuccess }
        tab(id).assertIsSelected()
    }

    private fun press(code: Int) {
        scenario.onActivity {
            it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        compose.waitForIdle()
    }

    @Test
    fun everyTabOpensFromTheOthers() {
        lit(R.string.settings_tab_library)
        for (id in listOf(R.string.settings_tab_recent, R.string.settings_tab_import, R.string.settings_tab_settings, R.string.settings_tab_library)) {
            tap(id)
            lit(id)
        }
        // Settings from each of the others, and back.
        for (from in listOf(R.string.settings_tab_library, R.string.settings_tab_recent, R.string.settings_tab_import)) {
            tap(from)
            lit(from)
            tap(R.string.settings_tab_settings)
            lit(R.string.settings_tab_settings)
        }
    }

    @Test
    fun settingsOpensFromInsideASubScreen() {
        tap(R.string.settings_tab_settings)
        lit(R.string.settings_tab_settings)
        compose.onNodeWithText(label(R.string.settings_about)).performClick()
        compose.waitForIdle()
        tap(R.string.settings_tab_recent)
        lit(R.string.settings_tab_recent)
        tap(R.string.settings_tab_settings)
        lit(R.string.settings_tab_settings)
    }

    @Test
    fun startAndTheShouldersWalkTheTabs() {
        lit(R.string.settings_tab_library)
        press(KeyEvent.KEYCODE_BUTTON_START)
        lit(R.string.settings_tab_settings)
        for (id in listOf(R.string.settings_tab_import, R.string.settings_tab_recent, R.string.settings_tab_library)) {
            press(KeyEvent.KEYCODE_BUTTON_L1)
            lit(id)
        }
        for (id in listOf(R.string.settings_tab_recent, R.string.settings_tab_import, R.string.settings_tab_settings)) {
            press(KeyEvent.KEYCODE_BUTTON_R1)
            lit(id)
        }
    }
}
