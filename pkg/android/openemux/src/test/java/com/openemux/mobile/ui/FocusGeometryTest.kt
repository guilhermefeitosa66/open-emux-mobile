package com.openemux.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FocusGeometryTest {
    @Test
    fun centeredPutsTheItemsMiddleInTheContainersMiddle() {
        // A 200px item at 700 in a 1000px row: its middle (800) goes to 500.
        assertEquals(300f, FocusGeometry.centered(700f, 200f, 1000f), 0f)
        assertEquals(-400f, FocusGeometry.centered(0f, 200f, 1000f), 0f)
        assertEquals(0f, FocusGeometry.centered(400f, 200f, 1000f), 0f)
    }

    @Test
    fun marginLeavesAnItemInsideTheBandAlone() {
        assertEquals(0f, FocusGeometry.margin(100f, 200f, 1000f, 50f, 150f), 0f)
        // Touching both edges of the band still fits.
        assertEquals(0f, FocusGeometry.margin(50f, 800f, 1000f, 50f, 150f), 0f)
    }

    @Test
    fun marginScrollsTheLeastThatBringsTheItemIn() {
        // Before the band: back by what it lacks.
        assertEquals(-40f, FocusGeometry.margin(10f, 200f, 1000f, 50f, 150f), 0f)
        // Past the band's end (850): forward by the overflow.
        assertEquals(100f, FocusGeometry.margin(750f, 200f, 1000f, 50f, 150f), 0f)
    }

    @Test
    fun marginAlignsAnItemBiggerThanTheBandToItsStart() {
        assertEquals(250f, FocusGeometry.margin(300f, 900f, 1000f, 50f, 150f), 0f)
        // A container smaller than its margins still answers with the start.
        assertEquals(-50f, FocusGeometry.margin(0f, 10f, 100f, 50f, 150f), 0f)
    }
}
