package com.openemux.mobile.launch

import com.openemux.mobile.data.NDS_LAYOUTS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkinSpecsTest {
    private val stacked = NDS_LAYOUTS.getValue("portrait").last()
    private val side = NDS_LAYOUTS.getValue("landscape").last()
    private val top = NDS_LAYOUTS.getValue("portrait").first()

    private fun names(specs: List<OverlaySpec>) = specs.map { it.name }

    @Test fun withoutAFoldTheOverlaysAreTheOnesBefore() {
        assertEquals(listOf("portrait", "landscape"), names(overlaySpecs(1.5f, keypad = false, nds = null)))
        assertEquals(
            listOf("portrait", "landscape", "keypad-portrait", "keypad-landscape"),
            names(overlaySpecs(1.5f, keypad = true, nds = null)),
        )
        val nds = overlaySpecs(1.5f, keypad = false, nds = top to side)
        assertEquals("portrait@top", nds.first().name)
        assertEquals(6, nds.size)
        assertTrue(nds.none { it.tabletop || TABLETOP in it.name })
    }

    @Test fun aFoldAppendsTheTabletopOverlaysAfterTheRest() {
        val flat = overlaySpecs(1.5f, keypad = true, nds = null)
        val specs = overlaySpecs(1.5f, keypad = true, nds = null, tabletop = true)
        assertEquals(names(flat), names(specs).take(flat.size))
        assertEquals(listOf(TABLETOP, "keypad-$TABLETOP"), names(specs).drop(flat.size))
        val t = specs.first { it.name == TABLETOP }
        assertTrue(t.portrait && t.tabletop && !t.keypadOpen)
        assertEquals("landscape", t.rotate)
        val k = specs.first { it.name == "keypad-$TABLETOP" }
        assertTrue(k.keypadOpen)
        assertEquals("keypad-landscape", k.rotate)
        assertEquals(listOf(TABLETOP), names(overlaySpecs(1.5f, keypad = false, nds = null, tabletop = true)).drop(2))
    }

    @Test fun ndsGetsThreeTabletopLayoutsFromTheSavedOne() {
        val specs = overlaySpecs(1.5f, keypad = false, nds = stacked to side, tabletop = true, ndsTabletop = top)
        val tabletop = specs.filter { it.tabletop }
        assertEquals(listOf("$TABLETOP@top", "$TABLETOP@bottom", "$TABLETOP@halves"), names(tabletop))
        assertEquals("portrait@stacked", specs.first().name)
        assertEquals("$TABLETOP@bottom", tabletop[0].layoutNext)
        assertEquals("$TABLETOP@top", tabletop[2].layoutNext)
        tabletop.forEach { assertEquals("landscape@side", it.rotate) }
        // Nothing saved yet: one per half first.
        val fresh = overlaySpecs(1.5f, keypad = false, nds = stacked to side, tabletop = true)
        assertEquals("$TABLETOP@halves", fresh.first { it.tabletop }.name)
    }

    @Test fun noFlatOverlayLeadsIntoTabletopAndTabletopTurnsToLandscape() {
        for (specs in listOf(
            overlaySpecs(1.5f, keypad = true, nds = null, tabletop = true),
            overlaySpecs(1.5f, keypad = false, nds = stacked to side, tabletop = true),
        )) {
            specs.filter { !it.tabletop }.forEach { s ->
                assertFalse(s.name, TABLETOP in s.rotate)
                assertFalse(s.name, TABLETOP in s.layoutNext.orEmpty())
            }
            specs.filter { it.tabletop }.forEach { s ->
                assertTrue("portrait" in s.name)
                assertTrue("landscape" in s.rotate)
            }
        }
    }

    @Test fun aPackageSkinGetsTheDrawnTabletopOverlaysTurningToItsLandscape() {
        val plain = packageTabletopSpecs(1.5f, nds = false)
        assertEquals(listOf(TABLETOP), names(plain))
        assertTrue(plain[0].tabletop && plain[0].portrait && !plain[0].keypadOpen)
        assertEquals("landscape", plain[0].rotate)
        val nds = packageTabletopSpecs(1.5f, nds = true, ndsTabletop = top)
        assertEquals(listOf("$TABLETOP@top", "$TABLETOP@bottom", "$TABLETOP@halves"), names(nds))
        nds.forEach { assertTrue(it.tabletop && it.layout != null); assertEquals("landscape", it.rotate) }
        assertEquals("$TABLETOP@halves", packageTabletopSpecs(1.5f, nds = true).first().name)
    }

    @Test fun overlayNamesFollowTheIndexNotTheLineOrder() {
        val cfg = "overlays = 2\noverlay1_name = \"landscape\"\noverlay0_name = \"portrait\"\noverlay0_overlay = \"p.png\"\n"
        assertEquals(listOf("portrait", "landscape"), overlayNames(cfg))
        assertEquals(emptyList<String>(), overlayNames(""))
    }

    @Test fun withOverlaysCountsTheAddedOnesAndAppendsThem() {
        val cfg = "overlays = 2\noverlay0_name = \"portrait\"\noverlay1_name = \"landscape\"\n\n"
        val body = "overlay2_name = \"$TABLETOP\"\n"
        val merged = withOverlays(cfg, 1, body)
        assertEquals("overlays = 3\noverlay0_name = \"portrait\"\noverlay1_name = \"landscape\"\n$body", merged)
        assertEquals(listOf("portrait", "landscape", TABLETOP), overlayNames(merged))
        assertEquals(cfg.trimEnd('\n') + "\n", withOverlays(cfg, 0, ""))
        assertEquals("x\n$body", withOverlays("x", 1, body))
    }
}
