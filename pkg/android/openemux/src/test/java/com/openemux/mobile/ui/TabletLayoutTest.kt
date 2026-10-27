package com.openemux.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabletLayoutTest {

    private fun w(width: Float, height: Float) = WindowLayout.of(width, height)

    @Test
    fun navigationFollowsTheWindow() {
        // Phone portrait and landscape keep what #535 gave them.
        assertEquals(NavKind.BottomBar, w(411f, 914f).nav)
        assertEquals(NavKind.PhoneRail, w(914f, 411f).nav)
        assertEquals(NavKind.BottomBar, w(320f, 569f).nav)
        assertEquals(NavKind.PhoneRail, w(569f, 320f).nav)
        // Tablets, both ways up, and a freeform window.
        assertEquals(NavKind.TabletRail, w(744f, 1133f).nav)
        assertEquals(NavKind.TabletRail, w(800f, 1280f).nav)
        assertEquals(NavKind.TabletRail, w(1280f, 800f).nav)
        assertEquals(NavKind.TabletRail, w(960f, 552f).nav)
        assertEquals(NavKind.TabletRail, w(600f, 900f).nav)
    }

    @Test
    fun navigationBoundaries() {
        assertEquals(NavKind.BottomBar, w(599f, 900f).nav)
        assertEquals(NavKind.TabletRail, w(600f, 480f).nav)
        // Short wins over wide.
        assertEquals(NavKind.PhoneRail, w(1280f, 479f).nav)
    }

    @Test
    fun railWidthByKindAndClass() {
        assertEquals(0f, railWidthDp(w(411f, 914f)))
        assertEquals(80f, railWidthDp(w(914f, 411f)))
        assertEquals(80f, railWidthDp(w(800f, 1280f)))
        assertEquals(88f, railWidthDp(w(1280f, 800f)))
        assertEquals(88f, railWidthDp(w(840f, 600f)))
    }

    @Test
    fun contentSideGrowsOnTablets() {
        assertEquals(20f, contentSideDp(w(411f, 914f)))
        assertEquals(20f, contentSideDp(w(914f, 411f)))
        assertEquals(32f, contentSideDp(w(800f, 1280f)))
        assertEquals(40f, contentSideDp(w(1280f, 800f)))
    }

    @Test
    fun phonesKeepTheirShelf() {
        val portrait = shelfSpecOf(w(411f, 914f))
        assertFalse(portrait.tablet)
        assertEquals(0, portrait.continueCount)
        assertEquals(SHELF_SIDE_DP, portrait.sidePadding)
        assertEquals(SHELF_GAP_DP, portrait.gap)
        assertEquals(104f, portrait.cardHeight)
        assertFalse(portrait.recentlyAdded)
        // A phone on its side is Expanded wide but short: still the phone's shelf.
        assertEquals(portrait, shelfSpecOf(w(914f, 411f)))
    }

    @Test
    fun expandedShelf() {
        val s = shelfSpecOf(w(1280f, 800f))
        assertEquals(ShelfSpec(true, 3, 3, 148f, 16f, 40f, 32f, false), s)
        // A tall Expanded window has no recently added row either: the boards do not draw one there.
        assertFalse(shelfSpecOf(w(1280f, 1000f)).recentlyAdded)
    }

    @Test
    fun mediumShelfAddsRecentlyAddedOnlyWhenTall() {
        val tall = shelfSpecOf(w(800f, 1280f))
        assertEquals(ShelfSpec(true, 2, 2, 148f, 14f, 32f, 40f, true), tall)
        assertTrue(shelfSpecOf(w(744f, 1133f)).recentlyAdded)
        assertTrue(shelfSpecOf(w(744f, 900f)).recentlyAdded)
        assertFalse(shelfSpecOf(w(744f, 899f)).recentlyAdded)
        assertFalse(shelfSpecOf(w(600f, 600f)).recentlyAdded)
    }

    @Test
    fun shelfColumnsOnTablets() {
        // 1280dp less the 88dp rail.
        assertEquals(3, shelfColumns(1192f, 3, 40f, 16f))
        // 800dp less the 80dp rail.
        assertEquals(2, shelfColumns(720f, 2, 32f, 14f))
        // 744dp less the rail still fits two.
        assertEquals(2, shelfColumns(664f, 2, 32f, 14f))
        // A narrow freeform window falls back to one.
        assertEquals(1, shelfColumns(520f, 2, 32f, 14f))
    }

    @Test
    fun setupSideBySideOnlyOnWideTablets() {
        assertEquals(2, setupColumns(w(1280f, 800f)))
        assertEquals(1, setupColumns(w(800f, 1280f)))
        assertEquals(1, setupColumns(w(411f, 914f)))
        assertEquals(1, setupColumns(w(914f, 411f)))
    }

    private data class Game(val name: String, val played: Long, val added: Long)
    private val games = listOf(Game("a", 0, 30), Game("b", 20, 10), Game("c", 50, 40), Game("d", 10, 20), Game("e", 40, 0))

    @Test
    fun continueListIsTheLastPlayedFirst() {
        assertEquals(listOf("c", "e", "b"), continueList(games, { it.played }, 3).map { it.name })
        assertEquals(listOf("c", "e", "b", "d"), continueList(games, { it.played }, 10).map { it.name })
        assertTrue(continueList(games, { it.played }, 0).isEmpty())
        assertTrue(continueList(games, { it.played }, -1).isEmpty())
        // Nothing played yet: the row has nothing to show.
        assertTrue(continueList(games.map { it.copy(played = 0) }, { it.played }, 3).isEmpty())
        assertTrue(continueList(emptyList<Game>(), { it.played }, 3).isEmpty())
    }

    @Test
    fun recentlyAddedIsTheNewestFilesFirst() {
        assertEquals(listOf("c", "a", "d", "b"), recentlyAdded(games, { it.added }, 4).map { it.name })
        assertEquals(listOf("c"), recentlyAdded(games, { it.added }, 1).map { it.name })
        assertTrue(recentlyAdded(games, { it.added }, -2).isEmpty())
        assertTrue(recentlyAdded(emptyList<Game>(), { it.added }, 4).isEmpty())
    }
}
