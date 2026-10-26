package com.openemux.mobile.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkinGeometryTest {

    private val gba = 3f / 2f
    private val ndsSide = 512f / 192f
    private val ndsStacked = 256f / 384f

    /** A D-pad of 150 on the centre row (most consoles), and the Virtual Boy's A / B on the lower row. */
    private val padDepth = 75f
    private val vbDepth = SkinGeometry.LOWER_ROW + 70f

    /** A 10" tablet (1280x800dp) at 2x, an 8" one (1133x744dp) at 2x, in design units under the cap. */
    private fun units(dp: Float) = dp / SkinGeometry.TABLET_CAP

    @Test
    fun theTabletSkinStartsAtASmallestWidthOf600dp() {
        assertFalse(SkinGeometry.tablet(1198, 2400, 2f))
        assertTrue(SkinGeometry.tablet(1200, 2400, 2f))
        // Whichever way it is held.
        assertFalse(SkinGeometry.tablet(2400, 1198, 2f))
        assertTrue(SkinGeometry.tablet(2560, 1600, 2f))
        // A phone on its side is wide, not a tablet.
        assertFalse(SkinGeometry.tablet(2400, 1080, 2.625f))
        assertTrue(SkinGeometry.tablet(1600, 2560, 1f))
    }

    @Test
    fun phonesKeepTheirUnit() {
        assertEquals(1080f / 390f, SkinGeometry.unit(1080, 2.75f, tablet = false), 0f)
        assertEquals(1440f / 390f, SkinGeometry.unit(1440, 3.5f, tablet = false), 0f)
    }

    @Test
    fun theTabletUnitIsCappedSoButtonsStopGrowing() {
        // 10" and 13": far over the cap uncapped.
        assertEquals(2.8f, SkinGeometry.unit(1600, 2f, tablet = true), 1e-5f)
        assertEquals(2.8f, SkinGeometry.unit(2048, 2f, tablet = true), 1e-5f)
        // Even the smallest tablet (600dp) is past it: 600 / 390 > 1.4.
        assertEquals(1.4f, SkinGeometry.unit(600, 1f, tablet = true), 1e-5f)
        // Under the cap the frame's own unit stands.
        assertEquals(500f / 390f, SkinGeometry.unit(500, 1f, tablet = true), 1e-5f)
    }

    private fun assertLandscapeFits(w: Float, h: Float, g: TabletFrame, depth: Float = padDepth) {
        val side = g.lx * 2f
        assertEquals(w - side / 2f, g.rx, 1e-3f)
        // The picture sits between the side columns, the strip right under it at its width.
        assertTrue(g.picture.x >= side - 1e-3f && g.picture.right <= w - side + 1e-3f)
        assertEquals(g.picture.bottom, g.strip.y, 1e-3f)
        assertEquals(g.picture.w, g.strip.w, 1e-3f)
        assertEquals(g.picture.x, g.strip.x, 1e-3f)
        assertTrue(g.strip.bottom <= h)
        val boxes = listOfNotNull(g.picture, g.strip, g.shouldersLeft, g.shouldersRight, g.menu, g.name, g.layoutKey)
        boxes.forEach { assertTrue("$it outside $w x $h", it.inside(w, h)) }
        // Nothing drawn over the picture: touch reaches the game (the NDS bottom screen).
        boxes.filter { it != g.picture }.forEach { assertFalse("$it over the picture", it.intersects(g.picture)) }
        assertFalse(g.shouldersLeft.intersects(g.menu))
        // The columns mirror each other.
        assertEquals(g.shouldersLeft.x, w - g.shouldersRight.right, 1e-3f)
        assertTrue(g.rows.upper < g.rows.center && g.rows.center < g.rows.lower && g.rows.lower < g.pillsTop)
        assertTrue(g.menu.bottom < g.rows.upper)
        assertTrue(g.pillsTop + 24f <= h)
        // The pills sit under the lowest cluster, clear of it.
        assertTrue(g.rows.center + depth * g.k + SkinGeometry.PILL_GAP <= g.pillsTop + 1e-3f)
    }

    @Test
    fun landscapeCentresThePictureBetweenTheSideColumns() {
        val w = units(1280f)
        val h = units(800f)
        // A small cluster still gets the narrowest column.
        val g = SkinGeometry.landscape(w, h, gba, footprint = 120f, depth = padDepth, layoutKey = false)
        assertLandscapeFits(w, h, g)
        assertEquals(SkinGeometry.SIDE, g.lx * 2f, 1e-3f)
        assertEquals(gba, g.picture.w / g.picture.h, 1e-4f)
        assertEquals(w / 2f, g.picture.cx, 1e-3f)
        assertEquals(1f, g.k, 0f)
        assertNull(g.layoutKey)
        // TabJogoGBA: the picture about 816 x 544 dp.
        assertEquals(816f, g.picture.w * SkinGeometry.TABLET_CAP, 4f)
    }

    @Test
    fun aWideClusterWidensTheColumnsNotTheButtons() {
        val w = units(1280f)
        val h = units(800f)
        val g = SkinGeometry.landscape(w, h, gba, footprint = 186f, depth = padDepth, layoutKey = false)
        assertLandscapeFits(w, h, g)
        assertEquals(210f, g.lx * 2f, 1e-3f)
        assertEquals(1f, g.k, 0f)
    }

    @Test
    fun aNarrowWindowShrinksTheControlsToTheirColumn() {
        // Square 600dp: the columns stop at 30% of the width, the clusters scale into them.
        val g = SkinGeometry.landscape(units(600f), units(600f), gba, footprint = 186f, depth = padDepth, layoutKey = false)
        assertEquals(units(600f) * 0.3f, g.lx * 2f, 1e-3f)
        assertTrue(g.k < 1f)
        assertEquals((g.lx * 2f - 24f) / 186f, g.k, 1e-4f)
        assertLandscapeFits(units(600f), units(600f), g)
    }

    @Test
    fun aShortWindowShrinksTheControlsToItsHeight() {
        val h = units(620f)
        val g = SkinGeometry.landscape(units(1280f), h, gba, footprint = 150f, depth = padDepth, layoutKey = false)
        assertEquals((h - 200f) / 260f, g.k, 1e-4f)
        assertLandscapeFits(units(1280f), h, g)
    }

    /** A box grown by the overlay's touch margin (OverlayWriter, grow = 1.12). */
    private fun touch(b: Box) = Box(b.cx - b.w * 0.56f, b.y + b.h / 2f - b.h * 0.56f, b.w * 1.12f, b.h * 1.12f)

    @Test
    fun aDeepRightClusterPushesThePillsBelowIt() {
        // Virtual Boy: the second D-pad over A / B on the right, Start under them (QA BUG-1: B covered Start).
        for ((w, h) in listOf(units(1280f) to units(800f), units(1133f) to units(744f))) {
            val g = SkinGeometry.landscape(w, h, gba, footprint = 150f, depth = vbDepth, layoutKey = false)
            assertLandscapeFits(w, h, g, vbDepth)
            assertEquals(1f, g.k, 0f)
            // Slots.TWO's lower button, B, and the Start pill under it.
            val b = Box(g.rx - 40f * g.k - 36f * g.k, g.rows.lower + 34f * g.k - 36f * g.k, 72f * g.k, 72f * g.k)
            val start = Box(g.rx - 30f, g.pillsTop, 60f, 24f)
            assertFalse("$b over $start", touch(b).intersects(touch(start)))
        }
        // A shallow cluster keeps the pills where they were.
        val g = SkinGeometry.landscape(units(1280f), units(800f), gba, footprint = 150f, depth = padDepth, layoutKey = false)
        assertEquals(g.rows.center + 130f, g.pillsTop, 1e-3f)
    }

    @Test
    fun aShortWindowShrinksADeepClusterToFitAboveThePills() {
        val h = units(620f)
        val depth = 200f
        val g = SkinGeometry.landscape(units(1280f), h, gba, footprint = 150f, depth = depth, layoutKey = false)
        assertEquals((h - 52f - SkinGeometry.PILL_GAP - g.rows.center) / depth, g.k, 1e-4f)
        assertTrue(g.k < (h - 200f) / 260f)
        assertEquals(h - 52f, g.pillsTop, 1e-3f)
        assertLandscapeFits(units(1280f), h, g, depth)
    }

    @Test
    fun ndsSideBySideKeepsItsAspectWithTheLayoutKeyAbove() {
        for ((w, h) in listOf(units(1280f) to units(800f), units(1133f) to units(744f))) {
            val g = SkinGeometry.landscape(w, h, ndsSide, footprint = 150f, depth = padDepth, layoutKey = true)
            assertLandscapeFits(w, h, g)
            assertEquals(ndsSide, g.picture.w / g.picture.h, 1e-4f)
            assertNotNull(g.layoutKey)
            val key = g.layoutKey!!
            assertTrue(key.bottom <= g.picture.y)
            assertEquals(g.picture.cx, key.cx, 1e-3f)
        }
    }

    private fun assertPortraitFits(w: Float, h: Float, g: TabletFrame) {
        assertEquals(0f, g.picture.y, 0f)
        assertEquals(w / 2f, g.picture.cx, 1e-3f)
        assertEquals(Box(0f, g.picture.bottom, w, SkinGeometry.STRIP), g.strip)
        val boxes = listOf(g.picture, g.strip, g.shouldersLeft, g.shouldersRight, g.menu, g.name)
        boxes.forEach { assertTrue("$it outside $w x $h", it.inside(w, h)) }
        boxes.filter { it != g.picture }.forEach { assertFalse("$it over the picture", it.intersects(g.picture)) }
        assertFalse(g.shouldersLeft.intersects(g.menu))
        assertFalse(g.shouldersRight.intersects(g.menu))
        assertTrue(g.shouldersLeft.y >= g.strip.bottom)
        assertEquals(g.shouldersLeft.x, w - g.shouldersRight.right, 1e-3f)
        assertEquals(w - g.lx, g.rx, 1e-3f)
        assertTrue(g.menu.bottom < g.rows.upper && g.rows.upper < g.rows.center && g.rows.center < g.rows.lower)
        assertTrue(g.rows.lower < g.pillsTop && g.pillsTop < g.name.y)
        assertNull(g.layoutKey)
    }

    @Test
    fun portraitPutsThePictureAcrossTheTop() {
        val w = units(800f)
        val h = units(1280f)
        val g = SkinGeometry.portrait(w, h, gba)
        assertPortraitFits(w, h, g)
        // TabJogoRetrato: full width, 800 x 533 dp for the GBA.
        assertEquals(w, g.picture.w, 1e-3f)
        assertEquals(533f, g.picture.h * SkinGeometry.TABLET_CAP, 1f)
        assertEquals(1f, g.k, 0f)
    }

    @Test
    fun aTallPictureNarrowsToLeaveTheControlsRoom() {
        for ((w, h) in listOf(units(800f) to units(1280f), units(744f) to units(1133f))) {
            val g = SkinGeometry.portrait(w, h, ndsStacked)
            assertPortraitFits(w, h, g)
            assertEquals(h - SkinGeometry.STRIP - SkinGeometry.PANEL, g.picture.h, 1e-3f)
            assertEquals(ndsStacked, g.picture.w / g.picture.h, 1e-4f)
            assertTrue(g.picture.w < w)
        }
    }

    @Test
    fun anEightInchPortraitScalesTheControlsToItsRoom() {
        val g = SkinGeometry.portrait(units(744f), units(1133f), gba)
        assertPortraitFits(units(744f), units(1133f), g)
        assertTrue(g.k < 1f)
    }

    @Test
    fun boxesKnowTheirEdges() {
        val b = Box(10f, 20f, 30f, 40f)
        assertEquals(40f, b.right, 0f)
        assertEquals(60f, b.bottom, 0f)
        assertEquals(25f, b.cx, 0f)
        assertTrue(b.intersects(Box(39f, 59f, 5f, 5f)))
        assertFalse(b.intersects(Box(40f, 20f, 5f, 5f)))
        assertFalse(b.intersects(Box(10f, 60f, 5f, 5f)))
        assertFalse(b.intersects(Box(0f, 0f, 10f, 100f)))
        assertFalse(b.intersects(Box(0f, 0f, 100f, 20f)))
        assertTrue(b.inside(40f, 60f))
        assertFalse(b.inside(39f, 60f))
        assertFalse(b.inside(40f, 59f))
        assertFalse(Box(-1f, 0f, 1f, 1f).inside(10f, 10f))
        assertFalse(Box(0f, -1f, 1f, 1f).inside(10f, 10f))
    }
}
