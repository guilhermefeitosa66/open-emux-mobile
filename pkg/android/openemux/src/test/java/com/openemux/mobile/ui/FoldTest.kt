package com.openemux.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldTest {

    private val delta = 0.01f

    // Pixel Fold, open: 2208 x 1840 px at 2.625 (841 x 701 dp), a zero-width fold down the middle.
    private fun foldOpen(halfOpened: Boolean = false) = WindowLayout.of(
        841f, 701f,
        fold = foldOf(halfOpened, vertical = true, left = 1104, top = 0, right = 1104, bottom = 1840),
        density = 2.625f,
    )

    // The same device turned so the fold is across, half open on a table: 701 x 841 dp.
    private fun tabletop(halfOpened: Boolean = true, top: Int = 1104, bottom: Int = 1104, heightDp: Float = 841f) = WindowLayout.of(
        701f, heightDp,
        fold = foldOf(halfOpened, vertical = false, left = 0, top = top, right = 1840, bottom = bottom),
        density = 2.625f,
    )

    @Test
    fun aFoldKeepsItsStateFlatIncluded() {
        assertEquals(Fold(vertical = true, halfOpened = false, leftPx = 1, topPx = 2, rightPx = 3, bottomPx = 4), foldOf(false, true, 1, 2, 3, 4))
        assertEquals(Fold(vertical = false, halfOpened = true, leftPx = 1, topPx = 2, rightPx = 3, bottomPx = 4), foldOf(true, false, 1, 2, 3, 4))
    }

    @Test
    fun aWindowWithoutAFoldHasNoBand() {
        val layout = WindowLayout.of(390f, 844f)
        assertNull(layout.fold)
        assertEquals(1f, layout.density, 0f)
        assertNull(forbiddenBand(layout))
    }

    @Test
    fun aZeroWidthFoldStillGetsATwentyFourDpBandCentredOnIt() {
        val band = forbiddenBand(foldOpen())!!
        assertTrue(band.vertical)
        assertEquals(1104 / 2.625f - 12f, band.startDp, delta)
        assertEquals(1104 / 2.625f + 12f, band.endDp, delta)
        assertEquals(24f, band.endDp - band.startDp, delta)
        assertEquals(1104 / 2.625f, band.centerDp, delta)
    }

    @Test
    fun aHingeAcrossGivesAYRangeInDp() {
        // A 30px hinge at 2.625: 11.43dp, plus 12dp each side.
        val band = forbiddenBand(tabletop(top = 1090, bottom = 1120))!!
        assertFalse(band.vertical)
        assertEquals(1090 / 2.625f - 12f, band.startDp, delta)
        assertEquals(1120 / 2.625f + 12f, band.endDp, delta)
    }

    @Test
    fun theBandStaysInsideTheWindow() {
        val atStart = WindowLayout.of(600f, 800f, fold = foldOf(false, true, 5, 0, 10, 800))
        assertEquals(0f, forbiddenBand(atStart)!!.startDp, 0f)
        assertEquals(22f, forbiddenBand(atStart)!!.endDp, 0f)
        val atEnd = WindowLayout.of(600f, 800f, fold = foldOf(true, false, 0, 795, 600, 800))
        assertEquals(783f, forbiddenBand(atEnd)!!.startDp, 0f)
        assertEquals(800f, forbiddenBand(atEnd)!!.endDp, 0f)
    }

    @Test
    fun theMarginCanBeChosen() {
        val band = forbiddenBand(WindowLayout.of(800f, 600f, fold = foldOf(false, true, 400, 0, 400, 600)), marginDp = 4f)!!
        assertEquals(396f, band.startDp, 0f)
        assertEquals(404f, band.endDp, 0f)
    }

    @Test
    fun tabletopIsHalfOpenWithTheFoldAcross() {
        assertTrue(tabletop().tabletop)
        assertFalse("flat across", tabletop(halfOpened = false).tabletop)
        assertFalse("half open as a book", foldOpen(halfOpened = true).tabletop)
        assertFalse("no fold", WindowLayout.of(390f, 844f).tabletop)
    }

    @Test
    fun tabletopNeedsRoomOnBothHalves() {
        // A fold 150dp from the top leaves a top half too small to hold a screen.
        assertFalse(tabletop(top = 394, bottom = 394).tabletop)
        // And one 150dp from the bottom, a bottom half too small.
        assertFalse(tabletop(top = 1814, bottom = 1814).tabletop)
    }

    @Test
    fun theHalvesAreMeasuredFromTheBand() {
        val (top, bottom) = tabletopHalves(tabletop())!!
        assertEquals(1104 / 2.625f - 12f, top, delta)
        assertEquals(841f - (1104 / 2.625f + 12f), bottom, delta)
    }

    @Test
    fun theHalvesFollowTheBoxTheyAreLaidOutIn() {
        // A box from 24dp (under a cutout) to 821dp of the window.
        val (top, bottom) = tabletopHalves(tabletop(), topDp = 24f, bottomDp = 821f)!!
        assertEquals(1104 / 2.625f - 12f - 24f, top, delta)
        assertEquals(821f - (1104 / 2.625f + 12f), bottom, delta)
    }

    @Test
    fun aFlipHalfOpenHasTwoHalves() {
        // Galaxy Z Flip, 1080 x 2640 px at 2.625 (411 x 1005 dp), fold across at 1320 px.
        val flip = WindowLayout.of(411f, 1005f, fold = foldOf(true, false, 0, 1320, 1080, 1320), density = 2.625f)
        val (top, bottom) = tabletopHalves(flip)!!
        assertEquals(1320 / 2.625f - 12f, top, delta)
        assertEquals(1005f - 1320 / 2.625f - 12f, bottom, delta)
        assertTrue(flip.tabletop)
    }

    @Test
    fun theNoHalvesCasesAreNull() {
        assertNull(tabletopHalves(WindowLayout.of(390f, 844f)))
        assertNull(tabletopHalves(tabletop(halfOpened = false)))
        assertNull(tabletopHalves(foldOpen(halfOpened = true)))
    }

    @Test
    fun anOpenFoldPutsTheSplitOnTheFold() {
        // DobFoldAberto: the fold at 420dp, an 80dp rail, a 340dp list.
        val layout = WindowLayout.of(840f, 700f, fold = foldOf(false, true, 420, 0, 420, 700))
        assertEquals(BookSplit(340f, onFold = true), bookSplit(layout, railDp = 80f, fallbackListDp = 440f))
        val half = WindowLayout.of(840f, 700f, fold = foldOf(true, true, 420, 0, 420, 700))
        assertEquals(BookSplit(340f, onFold = true), bookSplit(half, railDp = 80f, fallbackListDp = 440f))
    }

    @Test
    fun withoutAVerticalFoldTheSplitKeepsItsWidth() {
        assertEquals(BookSplit(440f, onFold = false), bookSplit(WindowLayout.of(1280f, 800f), 80f, 440f))
        assertEquals(BookSplit(440f, onFold = false), bookSplit(tabletop(), 80f, 440f))
    }

    @Test
    fun aCrampedPaneKeepsTheFixedWidths() {
        // List: 300 - 80 = 220dp, under 280.
        val narrowList = WindowLayout.of(900f, 700f, fold = foldOf(false, true, 300, 0, 300, 700))
        assertEquals(BookSplit(440f, onFold = false), bookSplit(narrowList, 80f, 440f))
        // Detail: 700 - 400 = 300dp, under 320.
        val narrowDetail = WindowLayout.of(700f, 700f, fold = foldOf(false, true, 400, 0, 400, 700))
        assertEquals(BookSplit(440f, onFold = false), bookSplit(narrowDetail, 80f, 440f))
        // Exactly at both minimums still splits on the fold.
        val exact = WindowLayout.of(680f, 700f, fold = foldOf(false, true, 360, 0, 360, 700))
        assertEquals(BookSplit(280f, onFold = true), bookSplit(exact, 80f, 440f))
    }
}
