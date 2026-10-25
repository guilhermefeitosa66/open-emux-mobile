package com.openemux.mobile.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowMetricsCalculator
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

/** The window this composition draws in. The default (a phone held upright) only serves previews. */
val LocalWindowLayout = staticCompositionLocalOf { WindowLayout.of(390f, 844f) }

/**
 * Measures the window and provides it as [LocalWindowLayout]. The only place
 * that reads the screen size: everything else asks the [WindowLayout].
 * Both activities handle size changes themselves, so a rotation, split
 * screen or a resized window recomposes this with the new size.
 */
@Composable
fun ProvideWindowLayout(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.activity() }
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current.density
    val size = remember(activity, configuration, density) {
        activity?.let { WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(it).bounds }
            ?.let { it.width() / density to it.height() / density }
            ?: (configuration.screenWidthDp.toFloat() to configuration.screenHeightDp.toFloat())
    }
    val folds = remember(activity) {
        // A device or a window the tracker cannot read has no fold.
        val info = activity?.let { runCatching { WindowInfoTracker.getOrCreate(it).windowLayoutInfo(it) }.getOrNull() }
        (info ?: emptyFlow()).map { layout ->
            layout.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()?.let { fold ->
                val b = fold.bounds
                foldOf(fold.state == FoldingFeature.State.HALF_OPENED, fold.orientation == FoldingFeature.Orientation.VERTICAL, b.left, b.top, b.right, b.bottom)
            }
        }.catch { emit(null) }
    }
    val fold by folds.collectAsStateWithLifecycle(initialValue = null)
    val posture = fold?.let { postureOf(it.halfOpened, it.vertical, it.leftPx, it.topPx, it.rightPx, it.bottomPx) } ?: Posture.Flat
    CompositionLocalProvider(LocalWindowLayout provides WindowLayout.of(size.first, size.second, posture, fold, density), content = content)
}

/** The activity behind a context, through any wrappers (a dialog's themed context included). */
private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
