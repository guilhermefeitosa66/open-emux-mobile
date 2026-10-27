package com.openemux.mobile.game

import com.openemux.mobile.launch.TABLETOP
import com.openemux.mobile.ui.Fold
import com.openemux.mobile.ui.WindowLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabletopTest {
    private val skin = TabletopSkin(438f, 462f, listOf("portrait", "landscape", "keypad-portrait", "keypad-landscape", TABLETOP, "keypad-$TABLETOP"), emptyMap())
    private val nds = listOf("portrait@stacked", "portrait@top", "landscape@side", "$TABLETOP@halves", "$TABLETOP@top", "$TABLETOP@bottom")

    private fun fold(top: Float = 450f, halfOpened: Boolean = true, vertical: Boolean = false) =
        Fold(vertical, halfOpened, 0, (top * 2).toInt(), 1680, (top * 2).toInt())

    private fun window(w: Float = 840f, h: Float = 900f, fold: Fold? = fold()) = WindowLayout.of(w, h, fold = fold, density = 2f)

    @Test fun parseReadsTheBandTheOverlaysAndTheGaps() {
        val parsed = TabletopSkin.parse("band_start_dp=438.00\nband_end_dp=462.00\noverlays=portrait,$TABLETOP@halves\ngap.$TABLETOP@halves=18\ngap.bad=x\n\n")!!
        assertEquals(438f, parsed.bandStartDp, 0.001f)
        assertEquals(462f, parsed.bandEndDp, 0.001f)
        assertEquals(listOf("portrait", "$TABLETOP@halves"), parsed.overlays)
        assertEquals(mapOf("$TABLETOP@halves" to 18), parsed.gaps)
        assertNull(parsed.flatLayout)
    }

    @Test fun parseReadsThePackageSkinsFlatLayout() {
        val text = "band_start_dp=438\nband_end_dp=462\noverlays=portrait,landscape,$TABLETOP@halves\n"
        assertEquals("top/bottom", TabletopSkin.parse(text + "flat_layout=top/bottom\n")!!.flatLayout)
        assertNull(TabletopSkin.parse(text + "flat_layout=\n")!!.flatLayout)
    }

    @Test fun parseWithoutABandOrOverlaysIsNull() {
        assertNull(TabletopSkin.parse(""))
        assertNull(TabletopSkin.parse("band_start_dp=1\noverlays=a"))
        assertNull(TabletopSkin.parse("band_start_dp=1\nband_end_dp=x\noverlays=a"))
        assertNull(TabletopSkin.parse("band_start_dp=1\nband_end_dp=2\noverlays="))
        assertNull(TabletopSkin.parse("band_start_dp=1\nband_end_dp=2"))
    }

    @Test fun tabletopWantsAHalfOpenFoldAcrossAPortraitWindowWhereTheSkinPutsIt() {
        assertTrue(wantsTabletop(window(), skin))
        assertTrue(wantsTabletop(window(fold = fold(top = 457f)), skin))
        assertFalse(wantsTabletop(window(), null))
        assertFalse(wantsTabletop(window(fold = fold(halfOpened = false)), skin))
        assertFalse(wantsTabletop(window(fold = null), skin))
        assertFalse(wantsTabletop(window(w = 900f, h = 840f), skin))
        assertFalse(wantsTabletop(window(fold = Fold(true, true, 840, 0, 840, 1800)), skin))
        // Rotated the other way round an off-centre hinge: not where the skin drew the band.
        assertFalse(wantsTabletop(window(fold = fold(top = 470f)), skin))
        assertFalse(wantsTabletop(window(fold = fold(top = 440f)), skin))
    }

    @Test fun intoTabletopGoesToTheTwinOfThePortraitOverlay() {
        assertEquals(TABLETOP, tabletopTarget("portrait", true, skin.overlays, null, null))
        assertEquals("keypad-$TABLETOP", tabletopTarget("keypad-portrait", true, skin.overlays, null, null))
        assertEquals("$TABLETOP@top", tabletopTarget("portrait@stacked", true, nds, null, "top"))
    }

    @Test fun aPackageSkinsPlainPortraitGoesToTheSavedNdsTabletopLayoutAndBack() {
        val packaged = listOf("portrait", "landscape", "$TABLETOP@halves", "$TABLETOP@top", "$TABLETOP@bottom")
        assertEquals("$TABLETOP@halves", tabletopTarget("portrait", true, packaged, null, "halves"))
        assertEquals("portrait", tabletopTarget("$TABLETOP@halves", false, packaged, "portrait", "halves"))
        assertEquals(TABLETOP, tabletopTarget("portrait", true, listOf("portrait", "landscape", TABLETOP), null, null))
    }

    @Test fun outOfTabletopGoesBackToTheFlatOverlay() {
        assertEquals("portrait", tabletopTarget(TABLETOP, false, skin.overlays, null, null))
        assertEquals("keypad-portrait", tabletopTarget("keypad-$TABLETOP", false, skin.overlays, null, null))
        assertEquals("keypad-portrait", tabletopTarget(TABLETOP, false, skin.overlays, "keypad-portrait", null))
        assertEquals("portrait@top", tabletopTarget("$TABLETOP@halves", false, nds, "portrait@top", "halves"))
    }

    @Test fun nothingChangesWhenAlreadyThereOrInLandscapeOrUnknown() {
        assertNull(tabletopTarget(TABLETOP, true, skin.overlays, null, null))
        assertNull(tabletopTarget("portrait", false, skin.overlays, null, null))
        assertNull(tabletopTarget("landscape", true, skin.overlays, null, null))
        assertNull(tabletopTarget("portrait", true, listOf("portrait", "landscape"), null, null))
        assertNull(tabletopTarget("portrait@stacked", true, nds, null, "gone"))
        assertNull(tabletopTarget("$TABLETOP@halves", false, nds, null, "halves"))
    }
}
