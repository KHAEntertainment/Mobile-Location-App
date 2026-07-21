package com.geoalign.core.tabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val HOME = "https://duckduckgo.com/"

class TabListReducerTest {

    @Test fun initialHasSingleActiveHomeTab() {
        val s = TabsState.initial(HOME)
        assertEquals(1, s.tabs.size)
        assertEquals(HOME, s.activeTab.url)
        assertEquals(s.tabs.first().id, s.activeId)
    }

    @Test fun openTabAppendsAndActivates() {
        val s = TabListReducer.openTab(TabsState.initial(HOME), "https://example.com")
        assertEquals(2, s.tabs.size)
        assertEquals("https://example.com", s.activeTab.url)
        assertEquals(s.tabs.last().id, s.activeId)
    }

    @Test fun idsAreUniqueAndMonotonicAcrossOpens() {
        var s = TabsState.initial(HOME)
        s = TabListReducer.openTab(s, "https://a.com")
        s = TabListReducer.openTab(s, "https://b.com")
        assertEquals(listOf(1L, 2L, 3L), s.tabs.map { it.id })
    }

    @Test fun selectTabChangesActive() {
        var s = TabListReducer.openTab(TabsState.initial(HOME), "https://a.com")
        s = TabListReducer.selectTab(s, 1L)
        assertEquals(1L, s.activeId)
    }

    @Test fun selectMissingTabIsNoOp() {
        val s = TabsState.initial(HOME)
        assertEquals(s, TabListReducer.selectTab(s, 999L))
    }

    @Test fun closingInactiveTabKeepsActive() {
        var s = TabListReducer.openTab(TabsState.initial(HOME), "https://a.com") // active = 2
        s = TabListReducer.closeTab(s, 1L, HOME)
        assertEquals(2L, s.activeId)
        assertEquals(1, s.tabs.size)
    }

    @Test fun closingActiveActivatesRightNeighbor() {
        var s = TabsState.initial(HOME)          // 1
        s = TabListReducer.openTab(s, "https://a.com") // 2
        s = TabListReducer.openTab(s, "https://b.com") // 3, active = 3
        s = TabListReducer.selectTab(s, 2L)      // active = 2 (middle)
        s = TabListReducer.closeTab(s, 2L, HOME) // right neighbor is 3
        assertEquals(3L, s.activeId)
    }

    @Test fun closingRightmostActiveFallsBackToLeft() {
        var s = TabsState.initial(HOME)          // 1
        s = TabListReducer.openTab(s, "https://a.com") // 2, active = 2 (rightmost)
        s = TabListReducer.closeTab(s, 2L, HOME)
        assertEquals(1L, s.activeId)
    }

    @Test fun closingLastTabResetsToFreshHome() {
        val s0 = TabsState.initial(HOME)
        val s = TabListReducer.closeTab(s0, s0.activeId, HOME)
        assertEquals(1, s.tabs.size)
        assertEquals(HOME, s.activeTab.url)
        // New tab gets a fresh id so stale saved state can't attach to it.
        assertTrue(s.activeId >= s0.nextId)
    }

    @Test fun closingMissingTabIsNoOp() {
        val s = TabsState.initial(HOME)
        assertEquals(s, TabListReducer.closeTab(s, 999L, HOME))
    }

    @Test fun updateTabSetsTitleAndUrl() {
        var s = TabsState.initial(HOME)
        s = TabListReducer.updateTab(s, 1L, title = "DuckDuckGo", url = "https://duckduckgo.com/?q=x")
        assertEquals("DuckDuckGo", s.tabs.first().title)
        assertEquals("https://duckduckgo.com/?q=x", s.tabs.first().url)
    }

    @Test fun updateTabPartialKeepsOtherField() {
        var s = TabsState.initial(HOME)
        s = TabListReducer.updateTab(s, 1L, title = "Only Title")
        assertEquals("Only Title", s.tabs.first().title)
        assertEquals(HOME, s.tabs.first().url)
    }

    @Test fun updateMissingTabIsNoOp() {
        val s = TabsState.initial(HOME)
        assertEquals(s, TabListReducer.updateTab(s, 999L, title = "nope"))
    }

    @Test fun activeIndexTracksActive() {
        var s = TabsState.initial(HOME)
        s = TabListReducer.openTab(s, "https://a.com")
        assertEquals(1, s.activeIndex)
        s = TabListReducer.selectTab(s, 1L)
        assertEquals(0, s.activeIndex)
        assertFalse(s.activeIndex < 0)
    }
}
