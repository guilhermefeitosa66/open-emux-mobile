package com.openemux.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NdsLayoutsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun layout(orientation: String, id: String) = NDS_LAYOUTS.getValue(orientation).first { it.id == id }

    @Test fun nothingSavedOpensEachOrientationOnItsDefault() {
        val dir = tmp.newFolder()
        assertEquals("stacked" to "side", NdsLayouts.load(dir).let { (p, l) -> p.id to l.id })
        assertEquals("halves", NdsLayouts.loadTabletop(dir).id)
    }

    @Test fun aFileFromBeforeTabletopOpensTabletopOnOnePerHalf() {
        val dir = tmp.newFolder()
        File(dir, "nds-layout").writeText("portrait=top\nlandscape=bottom\n")
        assertEquals("top" to "bottom", NdsLayouts.load(dir).let { (p, l) -> p.id to l.id })
        assertEquals("halves", NdsLayouts.loadTabletop(dir).id)
    }

    @Test fun eachOrientationIsSavedWithoutTouchingTheOthers() {
        val dir = tmp.newFolder()
        NdsLayouts.save(dir, "portrait-tabletop", layout("portrait-tabletop", "bottom"))
        NdsLayouts.save(dir, "portrait", layout("portrait", "top"))
        assertEquals("bottom", NdsLayouts.loadTabletop(dir).id)
        assertEquals("top" to "side", NdsLayouts.load(dir).let { (p, l) -> p.id to l.id })
        NdsLayouts.save(dir, "landscape", layout("landscape", "top"))
        assertEquals("bottom", NdsLayouts.loadTabletop(dir).id)
        assertEquals("portrait=top\nlandscape=top\nportrait-tabletop=bottom\n", File(dir, "nds-layout").readText())
    }

    @Test fun aLayoutTheOrientationNoLongerHasFallsBackToItsDefault() {
        val dir = tmp.newFolder()
        File(dir, "nds-layout").writeText("portrait-tabletop=side\nnot a line\n")
        assertEquals("halves", NdsLayouts.loadTabletop(dir).id)
    }

    @Test fun theTabletopCycleIsTopBottomOnePerHalf() {
        assertEquals("top", NdsLayouts.next("portrait-tabletop", "halves").id)
        assertEquals("bottom", NdsLayouts.next("portrait-tabletop", "top").id)
        assertEquals("halves", NdsLayouts.next("portrait-tabletop", "bottom").id)
    }
}
