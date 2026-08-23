package com.geoalign.core.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val HOME = "https://duckduckgo.com/"

/**
 * The navigation pause (issue #6), driven against [FakeWebViewHost] on the JVM.
 *
 * The acceptance criterion these exist for is the destructive one: pausing must hold *new*
 * navigation and nothing else. A user mid-form who loses their VPN keeps their page, their history
 * and their input — so every test here also asserts what the hold did **not** do.
 */
class BrowserSessionNavigationHoldTest {

    private fun held(): Pair<BrowserSessionController, FakeWebViewHost> {
        val host = FakeWebViewHost()
        val controller = BrowserSessionController(HOME).also { it.attach(host) }
        host.loadedUrls.clear()
        host.calls.clear()
        controller.setNavigationHeld(true)
        return controller to host
    }

    // ------------------------------------------------- the page in progress survives

    /** The whole point: holding touches the live WebView in no way at all. */
    @Test fun holdingDoesNotStopBlankOrDestroyTheLivePage() {
        val (_, host) = held()

        assertTrue("hold must issue no WebView calls whatsoever", host.calls.isEmpty())
        assertFalse(host.destroyed)
        assertTrue(host.loadedUrls.isEmpty())
    }

    @Test fun holdingDoesNotDisturbTheAddressOrTheTab() {
        val host = FakeWebViewHost()
        val controller = BrowserSessionController(HOME).also { it.attach(host) }

        controller.setNavigationHeld(true)

        assertEquals(HOME, controller.state.value.address)
        assertEquals(1, controller.state.value.tabs.tabs.size)
        assertNull(controller.state.value.heldNavigation)
    }

    // ------------------------------------------------- new navigation is queued, not swallowed

    @Test fun anAddressBarLoadIsQueuedInsteadOfIssued() {
        val (controller, host) = held()

        controller.load("https://example.com/form")

        assertTrue("no fetch may happen while held", host.loadedUrls.isEmpty())
        assertEquals("https://example.com/form", controller.state.value.heldNavigation)
        // The tab still points at the page actually loaded — rewriting it would change the identity
        // of a document the user is still reading.
        assertEquals(HOME, controller.state.value.tabs.tabs.first().url)
    }

    @Test fun releasingIssuesTheQueuedNavigation() {
        val (controller, host) = held()
        controller.load("https://example.com/next")

        controller.setNavigationHeld(false)

        assertEquals(listOf("https://example.com/next"), host.loadedUrls)
        assertNull(controller.state.value.heldNavigation)
        assertEquals("https://example.com/next", controller.state.value.tabs.tabs.first().url)
    }

    @Test fun releasingWithNothingQueuedNavigatesNowhere() {
        val (controller, host) = held()

        controller.setNavigationHeld(false)

        assertTrue(host.loadedUrls.isEmpty())
        assertFalse(controller.state.value.navigationHeld)
    }

    @Test fun anInPageLinkIsQueuedAndReportedAsConsumed() {
        val (controller, host) = held()

        val consumed = controller.holdLinkNavigation("https://example.com/link")

        assertTrue("the WebViewClient must consume it", consumed)
        assertEquals("https://example.com/link", controller.state.value.heldNavigation)
        assertTrue(host.loadedUrls.isEmpty())
    }

    @Test fun anInPageLinkIsFollowedNormallyWhenNothingIsHeld() {
        val host = FakeWebViewHost()
        val controller = BrowserSessionController(HOME).also { it.attach(host) }

        assertFalse(controller.holdLinkNavigation("https://example.com/link"))
        assertNull(controller.state.value.heldNavigation)
    }

    @Test fun homeIsQueuedLikeAnyOtherNewNavigation() {
        val (controller, host) = held()
        controller.load("https://example.com/")
        controller.setNavigationHeld(false)
        host.loadedUrls.clear()
        controller.setNavigationHeld(true)

        controller.goHome()

        assertTrue(host.loadedUrls.isEmpty())
        assertEquals(HOME, controller.state.value.heldNavigation)
    }

    /** A reload re-fetches the current document, which is exactly the fetch the hold prevents. */
    @Test fun reloadIsQueuedButStoppingIsAlwaysAllowed() {
        val (controller, host) = held()

        controller.reloadOrStop()

        assertFalse("reload must not reach the WebView", host.calls.contains("reload"))
        assertEquals(HOME, controller.state.value.heldNavigation)

        controller.onNavigationStateChanged(HOME, canGoBack = false, canGoForward = false, loading = true)
        controller.reloadOrStop()

        assertTrue("stop may only reduce what the network sees", host.calls.contains("stopLoading"))
    }

    @Test fun retryOnAnErrorPageIsQueuedAndTheErrorCardStays() {
        val host = FakeWebViewHost()
        val controller = BrowserSessionController(HOME).also { it.attach(host) }
        controller.onLoadError(isForMainFrame = true, url = "https://example.com/", description = "boom")
        controller.setNavigationHeld(true)
        host.loadedUrls.clear()

        controller.retry()

        assertTrue(host.loadedUrls.isEmpty())
        assertNotNull("dismissing the card would imply the retry happened", controller.state.value.pageError)
        assertEquals("https://example.com/", controller.state.value.heldNavigation)
    }

    // ------------------------------------------------- tabs

    @Test fun aFreshTabsFirstFetchIsHeldButTheTabStillOpens() {
        val (controller, host) = held()

        controller.openNewTab()

        assertEquals(2, controller.state.value.tabs.tabs.size)
        assertTrue("a new tab must not fetch while held", host.loadedUrls.isEmpty())
        assertEquals(HOME, controller.state.value.heldNavigation)
    }

    /**
     * A parked page is a document this tab already had. Putting it back is not a new navigation and
     * must not be held — otherwise switching tabs during a hold would leave the user staring at a
     * blank view, which is the destruction this issue forbids.
     */
    @Test fun returningToAParkedTabStillRestoresItWhileHeld() {
        val host = FakeWebViewHost()
        val controller = BrowserSessionController(HOME).also { it.attach(host) }
        val first = controller.state.value.tabs.activeId
        host.nextSnapshot = FakeSnapshot("tab-1")
        controller.openNewTab()
        controller.setNavigationHeld(true)
        host.loadedUrls.clear()
        host.restored.clear()

        controller.switchTo(first)

        assertEquals(listOf<PageSnapshot>(FakeSnapshot("tab-1")), host.restored)
        assertTrue(host.loadedUrls.isEmpty())
    }

    // ------------------------------------------------- session reset

    /**
     * `clearSession` rebuilt the whole state value, which silently reset every field on it. A
     * `navigationHeld` that became false here would let the very next tap navigate unprotected.
     */
    @Test fun clearingTheSessionDoesNotAlsoClearTheHold() {
        val (controller, host) = held()

        controller.clearSession()

        assertTrue("the hold must survive a session wipe", controller.state.value.navigationHeld)
        assertTrue(host.loadedUrls.isEmpty())
        assertEquals(HOME, controller.state.value.heldNavigation)
        assertTrue("but the wipe itself must still happen", host.clearedBrowsingData)
    }

    // ------------------------------------------------- ordinary browsing is untouched

    @Test fun withoutAHoldEverythingNavigatesAsBefore() {
        val host = FakeWebViewHost()
        val controller = BrowserSessionController(HOME).also { it.attach(host) }
        host.loadedUrls.clear()

        controller.load("https://example.com/")

        assertEquals(listOf("https://example.com/"), host.loadedUrls)
        assertFalse(controller.state.value.navigationHeld)
        assertNull(controller.state.value.heldNavigation)
    }

    @Test fun settingTheSameHoldTwiceIsANoOp() {
        val (controller, host) = held()
        controller.load("https://example.com/")

        controller.setNavigationHeld(true)

        assertEquals("https://example.com/", controller.state.value.heldNavigation)
        assertTrue(host.loadedUrls.isEmpty())
    }

    @Test fun aSecondRequestReplacesTheQueuedOneRatherThanStackingUp() {
        val (controller, host) = held()
        controller.load("https://example.com/first")

        controller.holdLinkNavigation("https://example.com/second")
        controller.setNavigationHeld(false)

        assertEquals(listOf("https://example.com/second"), host.loadedUrls)
    }

    // ------------------------------------------------- re-match rebuild

    @Test fun rebuildingForANewProfileParksThePageAndBumpsTheGeneration() {
        val host = FakeWebViewHost()
        val controller = BrowserSessionController(HOME).also { it.attach(host) }
        val before = controller.state.value.webViewGeneration
        host.calls.clear()

        controller.rebuildWebView()

        assertTrue("the page must be parked so it comes back", host.calls.contains("saveState"))
        assertEquals(before + 1, controller.state.value.webViewGeneration)
        assertFalse("rebuilding is not a teardown of the session", host.destroyed)
    }
}
