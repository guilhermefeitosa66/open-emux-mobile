package com.openemux.mobile.ui

import android.app.Activity
import android.net.Uri
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.material3.VerticalDivider
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.openemux.mobile.OpenEmuxApp
import com.openemux.mobile.data.Rom
import com.openemux.mobile.data.Setup
import com.retroarch.R
import kotlinx.coroutines.launch

object Routes {
    const val SHELF = "shelf"
    const val LIBRARY = "library/{filter}"
    const val ROM = "rom/{key}"
    const val RECENT = "recent"
    const val IMPORT = "import"
    const val SETTINGS = "settings"
    const val CONSOLE = "settings/{console}"
    const val BIOS = "bios"
    const val ACHIEVEMENTS = "achievements"
    const val ABOUT = "about"
    const val LICENSES = "about/licenses"
    const val LICENSE = "about/license/{id}"

    fun library(filter: String) = "library/$filter"
    fun rom(key: String) = "rom/${Uri.encode(key)}"
    fun console(id: String) = "settings/$id"
    fun license(id: String) = "about/license/$id"
}

/** What every screen needs: the app's data, navigation and the game launcher. */
class AppActions(
    val app: OpenEmuxApp,
    val nav: NavHostController,
    val play: (Rom, Boolean, Int?) -> Unit,
)

val LocalActions = staticCompositionLocalOf<AppActions> { error("no actions") }

@Composable
fun OpenEmuxRoot() {
    val context = LocalContext.current
    val app = context.applicationContext as OpenEmuxApp
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    var launching by remember { mutableStateOf<Rom?>(null) }

    LifecycleResumeEffect(Unit) {
        scope.launch { app.library.scan() }
        launching = null
        onPauseOrDispose { }
    }

    val actions = remember(nav) {
        AppActions(app, nav) { rom, resume, slot ->
            if (launching == null) {
                launching = rom
                scope.launch {
                    runCatching { app.launcher.launch(context as Activity, rom, resume, slot) }
                        .onFailure { launching = null }
                }
            }
        }
    }

    OpenEmuxTheme {
        // The app runs full screen: the bars, when a swipe brings them back, sit over it without moving
        // anything, so their insets are spent here; only the camera cutout keeps the content away.
        Box(Modifier.fillMaxSize().background(Oe.Bg).consumeWindowInsets(WindowInsets.systemBars).windowInsetsPadding(WindowInsets.displayCutout)) {
            val setup by app.setup.state.collectAsState()
            // Nothing opens until the cores are on the device.
            if (setup !is Setup.State.Done) SetupScreen(setup) { scope.launch { app.setup.run() } }
            else CompositionLocalProvider(LocalActions provides actions) {
                val entry by nav.currentBackStackEntryAsState()
                val route = entry?.destination?.route
                // App-wide buttons: Start opens Ajustes, L1/R1 walk the main tabs where a screen does not use them.
                fun tab(step: Int) {
                    val i = (MAIN_TABS.indexOf(if (route == Routes.LIBRARY) Routes.SHELF else route).coerceAtLeast(0) + step)
                        .coerceIn(0, MAIN_TABS.lastIndex)
                    nav.navigateTab(MAIN_TABS[i])
                }
                GamepadButtons(
                    l1 = { if (route in MAIN_TABS || route == Routes.LIBRARY) tab(-1) },
                    r1 = { if (route in MAIN_TABS || route == Routes.LIBRARY) tab(1) },
                    start = { if (route != Routes.SETTINGS) nav.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                )
                val showTabs = route in setOf(Routes.SHELF, Routes.LIBRARY, Routes.RECENT, Routes.IMPORT, Routes.SETTINGS)
                // A short window (a phone on its side, a split-screen half): the 86dp bar would eat the height, so the tabs go
                // to a rail; a tablet has the width for one, with the logo on top. The NavHost stays outside any branch on
                // this, so resizing between them keeps the screen.
                val navKind = if (showTabs) LocalWindowLayout.current.nav else NavKind.BottomBar
                val rail = navKind != NavKind.BottomBar
                val railWidth = RailWidth
                Box(Modifier.fillMaxSize().background(Oe.Bg)) {
                CompositionLocalProvider(LocalRail provides rail) {
                    NavHost(
                        nav,
                        startDestination = Routes.SHELF,
                        // Right from the rail comes back to the item the screen had focused.
                        modifier = Modifier.fillMaxSize().padding(start = if (rail) railWidth else 0.dp)
                            .focusProperties { onEnter = { if (requestedFocusDirection == FocusDirection.Right) InputMode.last?.requestFocus() } }
                            .focusGroup(),
                        enterTransition = { fadeIn() },
                        exitTransition = { fadeOut() },
                    ) {
                        composable(Routes.SHELF) { ShelfScreen() }
                        composable(Routes.LIBRARY) { LibraryScreen(it.arguments?.getString("filter") ?: "ALL") }
                        composable(Routes.ROM) { DetailScreen(Uri.decode(it.arguments?.getString("key") ?: "")) }
                        composable(Routes.RECENT) { RecentScreen() }
                        composable(Routes.IMPORT) { ImportScreen() }
                        composable(Routes.SETTINGS) { SettingsScreen() }
                        composable(Routes.CONSOLE) { ConsoleSettingsScreen(it.arguments?.getString("console") ?: "GBA") }
                        composable(Routes.BIOS) { BiosScreen() }
                        composable(Routes.ACHIEVEMENTS) { AchievementsScreen() }
                        composable(Routes.ABOUT) { AboutScreen() }
                        composable(Routes.LICENSES) { LicensesScreen() }
                        composable(Routes.LICENSE) { LicenseTextScreen(it.arguments?.getString("id").orEmpty()) }
                    }
                    if (rail) {
                        TabRail(route, navKind == NavKind.TabletRail, railWidth, Modifier.align(Alignment.CenterStart))
                        ArtworkDoneToast(Modifier.align(Alignment.BottomCenter).padding(start = railWidth, bottom = 12.dp))
                        HintBar(GamepadBus.hints, Modifier.align(Alignment.BottomCenter).padding(start = railWidth))
                    } else if (showTabs) {
                        TabBar(route, Modifier.align(Alignment.BottomCenter))
                        ArtworkDoneToast(Modifier.align(Alignment.BottomCenter).padding(bottom = TabBarHeight + 12.dp))
                        HintBar(GamepadBus.hints, Modifier.align(Alignment.BottomCenter).padding(bottom = TabBarHeight))
                    } else {
                        HintBar(GamepadBus.hints, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
                    }
                    launching?.let { LaunchingOverlay(it) }
                }
                }
            }
        }
    }
}

@Composable
private fun TabBar(route: String?, modifier: Modifier) {
    Column(modifier.fillMaxWidth().background(Oe.Bg.copy(alpha = 0.94f))) {
        HorizontalDivider(color = Oe.Surface2, thickness = 1.dp)
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            Tabs(route)
        }
    }
}

/** The tabs down the left edge: centred for a phone on its side; on a tablet, under the logo, with the active pill. */
@Composable
private fun TabRail(route: String?, tablet: Boolean, width: Dp, modifier: Modifier) {
    val label = stringResource(R.string.nav_rail_label)
    val activeTab = remember { FocusRequester() }
    Row(
        modifier.fillMaxHeight().background(Oe.Bg.copy(alpha = 0.94f)).semantics { contentDescription = label }
            // Only Left from the content moves into the rail, onto the current tab; the first press of a gamepad
            // lands on the screen's own item.
            .focusProperties {
                onEnter = { if (requestedFocusDirection != FocusDirection.Left || !activeTab.requestFocus()) cancelFocusChange() }
            }
            .focusGroup(),
    ) {
        if (tablet) {
            val expanded = width > 80.dp
            Column(
                Modifier.width(width - 1.dp).fillMaxHeight().padding(top = if (expanded) 32.dp else 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(painterResource(R.drawable.oe_logo), contentDescription = "OpenEmux", modifier = Modifier.height(if (expanded) 36.dp else 34.dp))
                Column(
                    Modifier.padding(top = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Tabs(route, pill = true, activeTab)
                }
            }
        } else {
            Column(
                Modifier.width(width).fillMaxHeight().padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Tabs(route, activeTab = activeTab)
            }
        }
        VerticalDivider(color = Oe.Surface2, thickness = 1.dp)
    }
}

@Composable
private fun Tabs(route: String?, pill: Boolean = false, activeTab: FocusRequester? = null) {
    val nav = LocalActions.current.nav
    val tabs = listOf(
        Triple(R.string.settings_tab_library, Icons.Cart, Routes.SHELF),
        Triple(R.string.settings_tab_recent, Icons.Clock, Routes.RECENT),
        Triple(R.string.settings_tab_import, Icons.Download, Routes.IMPORT),
        Triple(R.string.settings_tab_settings, Icons.Sliders, Routes.SETTINGS),
    )
    tabs.forEach { (label, icon, target) ->
        val active = when (target) {
            Routes.SHELF -> route == Routes.SHELF || route == Routes.LIBRARY
            else -> route == target
        }
        Column(
            Modifier
                .then(if (pill) Modifier.width(76.dp).heightIn(min = 56.dp) else Modifier.widthIn(min = 64.dp))
                .then(if (active && activeTab != null) Modifier.focusRequester(activeTab) else Modifier)
                .gamepadFocus(corner = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Tab) { if (target == Routes.SHELF || target != route) nav.navigateTab(target) }
                .semantics { selected = active }
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val color = if (active) Oe.AccentText else Oe.Muted
            if (pill) {
                // The tablet rail: the active tab sits in a filled pill, its label in the text colour.
                Box(
                    Modifier.size(width = 56.dp, height = 32.dp).clip(RoundedCornerShape(16.dp)).background(if (active) Oe.AccentSoft else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, color, 22.dp) }
                Text(
                    stringResource(label),
                    style = Type.Caption.copy(color = if (active) Oe.Text else Oe.Muted, fontSize = 12.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium),
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                Icon(icon, color, 24.dp)
                Text(stringResource(label), style = Type.Caption.copy(color = color, fontSize = 11.sp), modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** Opens a main tab as its button does: the library tab always lands on the shelf, wherever the library was left. */
fun NavHostController.navigateTab(target: String) {
    if (target == Routes.SHELF) popBackStack(Routes.SHELF, inclusive = false)
    else navigate(target) {
        popUpTo(Routes.SHELF) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun LaunchingOverlay(rom: Rom) {
    val app = LocalActions.current.app
    Box(
        Modifier.fillMaxSize().background(Oe.Bg.copy(alpha = 0.9f)).clickable(enabled = true, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Cartridge(
                rom.console, labelFor(rom), rom.title, app.library.effectiveColor(rom),
                Modifier.size(width = 220.dp, height = 140.dp), cover = app.covers.urlFor(rom),
            )
            Row(Modifier.padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Oe.AccentText, strokeWidth = 2.dp)
                Text("  " + stringResource(R.string.settings_launching), style = Type.Small)
            }
        }
    }
}

/** Shared tile used by both the shelf and the settings to show a console. */
@Composable
fun ConsoleBadge(text: String) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, Oe.Border, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(text, style = Type.Tag.copy(color = Oe.Muted))
    }
}

/** True on the main tabs when they are a rail on the left (a phone on its side, a tablet), not a bar under everything. */
val LocalRail = compositionLocalOf { false }

/** The room the bottom tab bar takes, none when the tabs are the side rail. */
val TabBarHeight: Dp
    @Composable get() = if (LocalRail.current) 0.dp else 86.dp

/** The rail's width for this window: 80dp, 88dp on a wide tablet. */
val RailWidth: Dp
    @Composable get() = railWidthDp(LocalWindowLayout.current).let { if (it > 0f) it.dp else 80.dp }

private val MAIN_TABS = listOf(Routes.SHELF, Routes.RECENT, Routes.IMPORT, Routes.SETTINGS)
val BottomSpace: Modifier
    @Composable get() = Modifier.height(TabBarHeight + hintBarInset + 24.dp)
