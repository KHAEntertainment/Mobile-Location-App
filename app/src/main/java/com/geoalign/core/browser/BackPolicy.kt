package com.geoalign.core.browser

/** What the Android Back gesture should do, given the browser's current state. */
enum class BackAction {
    /** An error or renderer-recovery card is covering the page: take it away, stay where we are. */
    DISMISS_OVERLAY,

    /** The page has history: walk it back. */
    GO_BACK,

    /** No history left, but other tabs are open: close this one and land on a neighbour. */
    CLOSE_TAB,

    /** Nothing left to go back to: leave the browser for the dashboard. */
    LEAVE_BROWSER,
}

/**
 * The Back ladder (spec §10).
 *
 * Before this existed, `BackHandler` was simply `enabled = canGoBack`, so Back at the first page of
 * the first tab fell through to the Activity and dropped the user out of the app — from the middle
 * of a browsing session, with no warning. Every rung below is deterministic and ordered, so the
 * gesture always does the least destructive thing still available.
 */
object BackPolicy {

    fun decide(
        hasOverlay: Boolean,
        canGoBack: Boolean,
        tabCount: Int,
    ): BackAction = when {
        hasOverlay -> BackAction.DISMISS_OVERLAY
        canGoBack -> BackAction.GO_BACK
        tabCount > 1 -> BackAction.CLOSE_TAB
        else -> BackAction.LEAVE_BROWSER
    }
}
