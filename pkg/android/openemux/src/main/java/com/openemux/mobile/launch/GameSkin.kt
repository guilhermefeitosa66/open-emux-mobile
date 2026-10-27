package com.openemux.mobile.launch

import android.content.Context
import android.util.Log
import com.openemux.mobile.data.Console
import com.openemux.mobile.data.NdsLayouts
import com.openemux.mobile.data.ScreenLayout
import com.openemux.mobile.ui.Fold
import java.io.File

/**
 * Picks and draws a game's skin, the same way at launch and when the game's
 * window changes size: a skin in the iOS emulators' format (the user's own
 * in files/skins/<console>, else the one the app ships for the console)
 * first, the drawn skin when there is none or it fails.
 */
object GameSkin {
    /**
     * [packaged]: a .deltaskin/.manicskin was used, which draws the two NDS
     * screens one over the other; [ndsLayouts] the drawn skin's NDS layouts.
     */
    class Drawn(val file: File, val packaged: Boolean, val ndsLayouts: Pair<ScreenLayout, ScreenLayout>?)

    /**
     * [density] makes one tablet decision for both skins, from the window they are drawn
     * for (a package's iPad art, the drawn tablet shape); [first] is the overlay RetroArch opens on.
     * [fold], the one the window has now, adds the drawn tabletop overlays when it runs across the long side.
     */
    fun render(
        context: Context, base: File, console: Console, size: SkinSize, outDir: File,
        density: Float, hardcore: Boolean, coreId: String?, first: String? = null, fold: Fold? = null,
    ): Drawn {
        val tablet = SkinGeometry.tablet(size.short, size.long, density)
        val ndsTabletop = if (console == Console.NDS) NdsLayouts.loadTabletop(context.filesDir) else null
        val packaged = ImportedSkinRenderer.find(context, base, console)?.let {
            runCatching {
                ImportedSkinRenderer().render(it, console, size.short, size.long, outDir, size.cutout, tablet, hardcore, first)
            }.onFailure { e -> Log.e("ImportedSkin", "falling back to the built-in skin", e) }.getOrNull()
        }
        if (packaged != null) {
            // No band to draw for (no fold, or one along the long side): the package skin as it is.
            if (SkinGeometry.tabletopBandFor(fold, size.short, size.long, density, size.cutout) == null) return Drawn(packaged, true, null)
            // A package skin has no tabletop art: the drawn skin's tabletop overlays go after its own.
            val file = runCatching {
                SkinRenderer(context).render(
                    console, size, outDir, hardcore = hardcore, coreId = coreId, density = density,
                    fold = fold, ndsTabletop = ndsTabletop, under = packaged,
                )
            }.onFailure { e -> Log.e("ImportedSkin", "no tabletop overlays", e) }.getOrDefault(packaged)
            return Drawn(file, true, null)
        }
        // The NDS layouts belong to the drawn skin.
        val ndsLayouts = if (console == Console.NDS) NdsLayouts.load(context.filesDir) else null
        val file = SkinRenderer(context).render(
            console, size, outDir, ndsLayouts, hardcore, coreId, density, first, fold, ndsTabletop.takeIf { ndsLayouts != null },
        )
        return Drawn(file, false, ndsLayouts)
    }
}
