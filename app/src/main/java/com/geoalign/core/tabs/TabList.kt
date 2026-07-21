package com.geoalign.core.tabs

/**
 * Pure tab-list model (spec §11). The browser uses a single active WebView; this model owns only
 * the *identity and ordering* of tabs plus which one is active. It is dependency-free and
 * unit-tested. The Android layer keeps the live WebView and each tab's saved page state separately,
 * keyed by [Tab.id]; this model never touches WebView internals.
 *
 * Invariant: [TabsState] always holds at least one tab, so [activeTab] is non-null. Closing the
 * final tab yields a fresh home tab rather than an empty browser.
 */

data class Tab(
    val id: Long,
    val title: String,
    val url: String,
)

data class TabsState(
    val tabs: List<Tab>,
    val activeId: Long,
    /** Monotonic id source; never reused so saved-state maps can't collide across opens/closes. */
    val nextId: Long,
) {
    val activeTab: Tab
        get() = tabs.first { it.id == activeId }

    val activeIndex: Int
        get() = tabs.indexOfFirst { it.id == activeId }

    companion object {
        fun initial(homeUrl: String): TabsState =
            TabsState(tabs = listOf(Tab(id = 1L, title = "", url = homeUrl)), activeId = 1L, nextId = 2L)
    }
}

object TabListReducer {

    /** Open a new tab at the end and make it active. */
    fun openTab(state: TabsState, url: String, title: String = ""): TabsState {
        val tab = Tab(id = state.nextId, title = title, url = url)
        return state.copy(
            tabs = state.tabs + tab,
            activeId = tab.id,
            nextId = state.nextId + 1,
        )
    }

    /** Make [id] active if it exists; otherwise unchanged. */
    fun selectTab(state: TabsState, id: Long): TabsState =
        if (state.tabs.any { it.id == id }) state.copy(activeId = id) else state

    /**
     * Close [id]. If the active tab was closed, activation moves to the tab that slides into its
     * slot (the right neighbour), falling back to the new last tab when the rightmost was closed —
     * the behaviour users expect. Closing the only tab resets to a fresh home tab (ids keep
     * advancing so no saved state is mistaken for the new tab's).
     */
    fun closeTab(state: TabsState, id: Long, homeUrl: String): TabsState {
        val closedIndex = state.tabs.indexOfFirst { it.id == id }
        if (closedIndex < 0) return state

        val remaining = state.tabs.filterNot { it.id == id }
        if (remaining.isEmpty()) {
            return TabsState(
                tabs = listOf(Tab(id = state.nextId, title = "", url = homeUrl)),
                activeId = state.nextId,
                nextId = state.nextId + 1,
            )
        }

        val newActiveId = if (state.activeId != id) {
            state.activeId
        } else {
            val newIndex = closedIndex.coerceAtMost(remaining.lastIndex)
            remaining[newIndex].id
        }
        return state.copy(tabs = remaining, activeId = newActiveId)
    }

    /** Replace [id]'s title/url in place (used as pages load). No-op if the tab is gone. */
    fun updateTab(state: TabsState, id: Long, title: String? = null, url: String? = null): TabsState {
        if (state.tabs.none { it.id == id }) return state
        return state.copy(
            tabs = state.tabs.map { t ->
                if (t.id == id) t.copy(title = title ?: t.title, url = url ?: t.url) else t
            },
        )
    }
}
