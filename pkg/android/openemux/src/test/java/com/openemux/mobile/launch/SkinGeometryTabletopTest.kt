package com.openemux.mobile.launch

import com.openemux.mobile.ui.Fold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkinGeometryTabletopTest {
    private val d = 2.625f

    /** A Pixel Fold's inner screen held upright: 840 x 900dp, the hinge across at 450dp. */
    private val foldW = (840 * d).toInt()
    private val foldH = (900 * d).toInt()
    private val across = Fold(vertical = false, halfOpened = true, leftPx = 0, topPx = (450 * d).toInt(), rightPx = foldW, bottomPx = (450 * d).toInt())

    @Test fun noFoldHasNoBand() {
        assertNull(SkinGeometry.tabletopBandFor(null, foldW, foldH, d))
    }

    @Test fun aFoldAcrossAPortraitWindowGivesItsBandWithTheMargin() {
        val band = SkinGeometry.tabletopBandFor(across, foldW, foldH, d)!!
        assertEquals((450 - 12) * d, band.startPx, 2f)
        assertEquals((450 + 12) * d, band.endPx, 2f)
    }

    @Test fun aBookFoldInALandscapeWindowIsProjectedOnTheLongSide() {
        val book = Fold(vertical = true, halfOpened = false, leftPx = foldH / 2, topPx = 0, rightPx = foldH / 2, bottomPx = foldW)
        val band = SkinGeometry.tabletopBandFor(book, foldH, foldW, d)!!
        assertEquals(foldH / 2f - 12 * d, band.startPx, 1f)
        assertEquals(foldH / 2f + 12 * d, band.endPx, 1f)
    }

    @Test fun theCutoutShiftsTheBandUp() {
        val band = SkinGeometry.tabletopBandFor(across, foldW, foldH, d, cutoutPx = 100)!!
        assertEquals((450 - 12) * d - 100, band.startPx, 2f)
    }

    @Test fun aFoldAlongTheLongSideHasNoBand() {
        val down = Fold(vertical = true, halfOpened = true, leftPx = foldW / 2, topPx = 0, rightPx = foldW / 2, bottomPx = foldH)
        assertNull(SkinGeometry.tabletopBandFor(down, foldW, foldH, d))
        val along = Fold(vertical = false, halfOpened = true, leftPx = 0, topPx = foldW / 2, rightPx = foldH, bottomPx = foldW / 2)
        assertNull(SkinGeometry.tabletopBandFor(along, foldH, foldW, d))
    }

    @Test fun aHalfUnder200dpHasNoBand() {
        val low = across.copy(topPx = (150 * d).toInt(), bottomPx = (150 * d).toInt())
        assertNull(SkinGeometry.tabletopBandFor(low, foldW, foldH, d))
        val high = across.copy(topPx = (800 * d).toInt(), bottomPx = (800 * d).toInt())
        assertNull(SkinGeometry.tabletopBandFor(high, foldW, foldH, d))
    }

    @Test fun theBandIsClampedToTheWindow() {
        val wide = across.copy(topPx = -50, bottomPx = foldH + 50)
        assertNull(SkinGeometry.tabletopBandFor(wide, foldW, foldH, d))
    }

    private fun Box.inBand(start: Float, end: Float) = SkinGeometry.intersectsBand(this, start, end)

    private fun boxes(g: TabletFrame) = listOf(g.picture, g.strip, g.shouldersLeft, g.shouldersRight, g.menu, g.name)

    private fun checkHalves(g: TabletFrame, w: Float, h: Float, start: Float, end: Float, pictureOnTop: Boolean = true) {
        boxes(g).forEach { b ->
            assertFalse("$b on the fold", b.inBand(start, end))
            assertTrue("$b outside", b.inside(w, h))
        }
        assertTrue(if (pictureOnTop) g.picture.bottom <= start else g.picture.y >= end)
        listOf(g.strip, g.shouldersLeft, g.shouldersRight, g.menu).forEach { assertTrue(it.y >= end) }
        // The clusters' rows sit under the band, with room for a cluster around each.
        assertTrue(g.rows.upper - 50f * g.k >= end)
        assertTrue(g.rows.lower + 40f * g.k <= g.pillsTop)
    }

    @Test fun wideTabletopPutsThePictureOnTopAndTheControlsUnderTheBand() {
        val g = SkinGeometry.tabletop(600f, 643f, 313f, 330f, 1.5f, wide = true)
        checkHalves(g, 600f, 643f, 313f, 330f)
        // L | strip | R on one row, the menu key under the strip, the name at the foot.
        assertEquals(g.shouldersLeft.y, g.strip.y, 0.01f)
        assertTrue(g.shouldersLeft.right < g.strip.x && g.strip.right < g.shouldersRight.x)
        assertTrue(g.menu.y >= g.strip.bottom)
        assertTrue(g.name.y > g.pillsTop)
        assertEquals(1.5f, g.picture.w / g.picture.h, 0.01f)
    }

    @Test fun compactTabletopPutsTheStripAcrossAndTheNameUnderThePicture() {
        val g = SkinGeometry.tabletop(390f, 866f, 421f, 444f, 1.5f, wide = false)
        checkHalves(g, 390f, 866f, 421f, 444f)
        assertEquals(g.shouldersLeft.y, g.menu.y, 0.01f)
        assertTrue(g.shouldersLeft.y >= g.strip.bottom)
        assertTrue(g.name.y < 421f && g.name.y >= g.picture.bottom)
        assertEquals(1f, g.k, 0.001f)
    }

    @Test fun aShortBottomHalfShrinksTheControls() {
        val g = SkinGeometry.tabletop(600f, 560f, 270f, 287f, 1.5f, wide = true)
        assertTrue(g.k < 1f)
        assertTrue(g.k > 0f)
    }

    @Test fun ndsTabletopKeepsAColumnForTheBottomScreen() {
        val g = SkinGeometry.tabletopNds(600f, 643f, 330f, 150f)
        checkHalves(g, 600f, 643f, 313f, 330f, pictureOnTop = false)
        assertEquals(g.picture.bottom, g.strip.y, 0.01f)
        assertTrue(g.shouldersLeft.right < g.menu.x)
        assertTrue(g.picture.x >= g.menu.right && g.picture.right <= g.shouldersRight.x)
        assertTrue(g.name.y >= g.shouldersRight.bottom)
    }

    @Test fun ndsTabletopKeepsRsTouchAreaOffTheColumn() {
        fun grownLeft(b: Box) = b.cx - b.w / 2f * SkinGeometry.TOUCH_GROW
        // Narrow sides keep the usual 12-unit margin; a wide one, as on a Pixel Fold, moves R off the column.
        val narrow = SkinGeometry.tabletopNds(600f, 643f, 330f, 150f)
        assertEquals(narrow.picture.right + 12f, narrow.shouldersRight.x, 0.01f)
        val wide = SkinGeometry.tabletopNds(1000f, 643f, 330f, 260f)
        assertTrue(wide.shouldersRight.x > wide.picture.right + 12f)
        for (g in listOf(narrow, wide)) assertTrue(grownLeft(g.shouldersRight) >= g.picture.right - 0.01f)
        assertEquals(600f - 12f, narrow.shouldersRight.right, 0.01f)
        assertEquals(1000f - 12f, wide.shouldersRight.right, 0.01f)
    }

    @Test fun fitInCentresTheBiggestBoxOfTheAspect() {
        val b = SkinGeometry.fitIn(Box(0f, 0f, 400f, 200f), 4f / 3f, 10f)
        assertEquals(180f, b.h, 0.01f)
        assertEquals(240f, b.w, 0.01f)
        assertEquals(80f, b.x, 0.01f)
        assertEquals(10f, b.y, 0.01f)
        val tall = SkinGeometry.fitIn(Box(0f, 0f, 100f, 400f), 1f)
        assertEquals(100f, tall.w, 0.01f)
        assertEquals(150f, tall.y, 0.01f)
    }

    @Test fun ndsHalvesPicksTheSmallestGapThatCoversTheBand() {
        val column = Box(174f, 338f, 252f, 249f)
        val fit = SkinGeometry.ndsHalvesFit(313f, 330f, column, (0..100).toList())!!
        assertEquals(252f / 256f, fit.scale, 0.001f)
        assertTrue(fit.gap * fit.scale >= 17f)
        assertTrue((fit.gap - 1) * fit.scale < 17f)
        // The top screen ends at the band, the bottom one starts past it and fits the column.
        assertEquals(313f, fit.viewport.y + 192f * fit.scale, 0.01f)
        assertTrue(fit.viewport.y + (192f + fit.gap) * fit.scale >= 330f)
        assertTrue(fit.viewport.bottom <= column.bottom + 0.01f)
        assertEquals(column.cx, fit.viewport.cx, 0.01f)
    }

    @Test fun ndsHalvesStepsTheScaleDownWhenNoGapFits() {
        val column = Box(174f, 338f, 252f, 200f)
        val fit = SkinGeometry.ndsHalvesFit(313f, 330f, column, listOf(0, 5, 64))!!
        assertEquals(64, fit.gap)
        assertTrue(fit.scale < 252f / 256f)
        assertTrue(fit.viewport.bottom <= column.bottom + 0.01f)
    }

    @Test fun ndsHalvesWithoutGapsOrRoomIsNull() {
        val column = Box(174f, 338f, 252f, 249f)
        assertNull(SkinGeometry.ndsHalvesFit(313f, 330f, column, emptyList()))
        assertNull(SkinGeometry.ndsHalvesFit(313f, 330f, column, listOf(0, 1)))
        assertNull(SkinGeometry.ndsHalvesFit(313f, 330f, Box(0f, 0f, 0f, 0f), listOf(10)))
        assertNotNull(SkinGeometry.ndsHalvesFit(313f, 330f, column, listOf(100, 30)))
    }

    @Test fun intersectsBandIsAboutTheYRangeOnly() {
        assertTrue(Box(0f, 300f, 10f, 20f).inBand(313f, 330f))
        assertTrue(Box(0f, 329f, 10f, 20f).inBand(313f, 330f))
        assertFalse(Box(0f, 293f, 10f, 20f).inBand(313f, 330f))
        assertFalse(Box(0f, 330f, 10f, 20f).inBand(313f, 330f))
    }
}
