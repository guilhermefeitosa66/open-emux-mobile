package com.openemux.mobile.launch

import android.content.Context
import android.util.Log
import com.openemux.mobile.data.Console
import com.openemux.mobile.data.NdsLayouts
import com.openemux.mobile.data.ScreenLayout
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
     */
    fun render(
        context: Context, base: File, console: Console, size: SkinSize, outDir: File,
        density: Float, hardcore: Boolean, coreId: String?, first: String? = null,
    ): Drawn {
        val tablet = SkinGeometry.tablet(size.short, size.long, density)
        val packaged = ImportedSkinRenderer.find(context, base, console)?.let {
            runCatching {
                ImportedSkinRenderer().render(it, console, size.short, size.long, outDir, size.cutout, tablet, hardcore, first)
            }.onFailure { e -> Log.e("ImportedSkin", "falling back to the built-in skin", e) }.getOrNull()
        }
        if (packaged != null) return Drawn(packaged, true, null)
        // The NDS layouts belong to the drawn skin.
        val ndsLayouts = if (console == Console.NDS) NdsLayouts.load(context.filesDir) else null
        val file = SkinRenderer(context).render(console, size, outDir, ndsLayouts, hardcore, coreId, density, first)
        return Drawn(file, false, ndsLayouts)
    }
}
