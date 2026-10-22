package com.openemux.mobile.ui

import android.net.Uri
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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

    fun library(filter: String) = "library/$filter"
    fun rom(key: String) = "rom/${Uri.encode(key)}"
    fun console(id: String) = "settings/$id"
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
                    runCatching { app.launcher.launch(rom, resume, slot) }
                        .onFailure { launching = null }
                }
            }
        }
    }

    OpenEmuxTheme {
        CompositionLocalProvider(LocalActions provides actions) {
            val entry by nav.currentBackStackEntryAsState()
            val route = entry?.destination?.route
            val showTabs = route in setOf(Routes.SHELF, Routes.LIBRARY, Routes.RECENT, Routes.IMPORT, Routes.SETTINGS)
            Box(Modifier.fillMaxSize().background(Oe.Bg)) {
                NavHost(
                    nav,
                    startDestination = Routes.SHELF,
                    modifier = Modifier.fillMaxSize(),
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
                }
                if (showTabs) {
                    TabBar(route, Modifier.align(Alignment.BottomCenter))
                }
                launching?.let { LaunchingOverlay(it) }
            }
        }
    }
}

@Composable
private fun TabBar(route: String?, modifier: Modifier) {
    val nav = LocalActions.current.nav
    val tabs = listOf(
        Triple("Biblioteca", Icons.Cart, Routes.SHELF),
        Triple("Recentes", Icons.Clock, Routes.RECENT),
        Triple("Importar", Icons.Download, Routes.IMPORT),
        Triple("Ajustes", Icons.Sliders, Routes.SETTINGS),
    )
    Column(modifier.fillMaxWidth().background(Color(0xF0121216))) {
        HorizontalDivider(color = Color(0xFF24242C), thickness = 1.dp)
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            tabs.forEach { (label, icon, target) ->
                val active = when (target) {
                    Routes.SHELF -> route == Routes.SHELF || route == Routes.LIBRARY
                    else -> route == target
                }
                Column(
                    Modifier
                        .widthIn(min = 64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Tab) {
                            if (!active || target != route) {
                                nav.navigate(target) {
                                    popUpTo(Routes.SHELF) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val color = if (active) Oe.AccentText else Oe.Muted
                    Icon(icon, color, 24.dp)
                    Text(label, style = Type.Caption.copy(color = color, fontSize = 11.sp), modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun LaunchingOverlay(rom: Rom) {
    val app = LocalActions.current.app
    Box(
        Modifier.fillMaxSize().background(Color(0xE6121216)).clickable(enabled = true, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Cartridge(
                rom.console, app.covers.urlFor(rom), rom.title, app.library.effectiveColor(rom),
                Modifier.size(width = 220.dp, height = 140.dp),
            )
            Row(Modifier.padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Oe.AccentText, strokeWidth = 2.dp)
                Text("  Encaixando o cartucho...", style = Type.Small)
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

val TabBarHeight = 86.dp
val BottomSpace = Modifier.height(TabBarHeight + 24.dp)
