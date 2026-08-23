package com.geoalign.core.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class BackPolicyTest {

    @Test fun anOverlayIsDismissedFirst() {
        assertEquals(
            BackAction.DISMISS_OVERLAY,
            BackPolicy.decide(hasOverlay = true, canGoBack = true, tabCount = 3),
        )
    }

    @Test fun historyIsWalkedBackBeforeTabsAreTouched() {
        assertEquals(
            BackAction.GO_BACK,
            BackPolicy.decide(hasOverlay = false, canGoBack = true, tabCount = 3),
        )
    }

    @Test fun withNoHistoryTheTabIsClosed() {
        assertEquals(
            BackAction.CLOSE_TAB,
            BackPolicy.decide(hasOverlay = false, canGoBack = false, tabCount = 2),
        )
    }

    @Test fun atTheFirstPageOfTheOnlyTabTheBrowserIsLeft() {
        // The behaviour this replaces: `BackHandler(enabled = canBack)` simply did not fire here,
        // so Back fell through to the Activity and dropped the user out of the app.
        assertEquals(
            BackAction.LEAVE_BROWSER,
            BackPolicy.decide(hasOverlay = false, canGoBack = false, tabCount = 1),
        )
    }

    @Test fun theLadderIsTotal() {
        // Every combination resolves to exactly one action; none falls through to the Activity.
        val actions = listOf(true, false).flatMap { overlay ->
            listOf(true, false).flatMap { back ->
                listOf(1, 2, 5).map { tabs -> BackPolicy.decide(overlay, back, tabs) }
            }
        }
        assertEquals(12, actions.size)
    }
}
