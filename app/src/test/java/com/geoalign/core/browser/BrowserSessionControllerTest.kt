package com.geoalign.core.browser

import com.geoalign.core.device.DeviceProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val HOME = "https://duckduckgo.com/"

/**
 * The whole point of `BrowserSessionController`: these transitions used to be closures inside a
 * `@Composable`, where nothing could reach them. Everything here runs on the JVM against
 * [FakeWebViewHost] — no device, no emulator, no Compose.
 */
class BrowserSessionControllerTest {

    private fun controllerWith(host: FakeWebViewHost): BrowserSessionController =
        BrowserSessionController(HOME).also { it.attach(host) }

    // ---------------------------------------------------------------- attach & tabs

    @Test fun attachingLoadsTheActiveTabsUrl() {
        val host = FakeWebViewHost()
        val controller = controllerWith(host)
        assertEquals(listOf(HOME), host.loadedUrls)
        assertEquals(HOME, controller.state.value.address)
    }

    @Test fun openingATabParksTheOldPageAndLoadsHomeFresh() {
        val host = FakeWebViewHost()
        val controller = controllerWith(host)
        host.loadedUrls.clear()

        controller.openNewTab()

        assertEquals(2, controller.state.value.tabs.tabs.size)
        assertTrue(host.calls.contains("saveState"))
        // A brand-new tab has no parked page, so it loads rather than restores.
        assertEquals(listOf(HOME), host.loadedUrls)
        assertTrue(host.restored.isEmpty())
    }

    @Test fun switchingBackToATabRestoresItsParkedPageInsteadOfReloading() {
        val host = FakeWebViewHost()
        val controller = controllerWith(host)
        val firstTabId = controller.state.value.tabs.activeId
        host.nextSnapshot = FakeSnapshot("tab-1")

        controller.openNewTab()
        host.loadedUrls.clear()
        host.nextSnapshot = FakeSnapshot("tab-2")

        controller.switchTo(firstTabId)

        assertEquals(firstTabId, controller.state.value.attachedTabId)
        assertEquals(listOf<PageSnapshot>(FakeSnapshot("tab-1")), host.restored)
        assertTrue("restored tabs must not re-fetch", host.loadedUrls.isEmpty())
    }

    @Test fun aRejectedSnapshotFallsBackToLoadingTheTabsUrl() {
        val host = FakeWebViewHost()
        val controller = controllerWith(host)
        val firstTabId = controller.state.value.tabs.activeId
        controller.openNewTab()
        host.loadedUrls.clear()
        host.restoreSucceeds = false

        controller.switchTo(firstTabId)

        assertEquals(listOf(HOME), host.loadedUrls)
    }

    @Test fun switchingToTheAlreadyActiveTabIsANoOp() {
        val host = FakeWebViewHost()
        val controller = controllerWith(host)
        host.calls.clear()

        controller.switchTo(controller.state.value.tabs.activeId)

        assertTrue(host.calls.isEmpty())
    }

    @Test fun closingTheActiveTabBindsTheNeighbour() {
        val host = FakeWebViewHost()
        val controller = controllerWith(host)
        val firstTabId = controller.state.value.tabs.activeId
        controller.openNewTab()
        val secondTabId = controller.state.value.tabs.activeId
        host.loadedUrls.clear()
        host.restored.clear()

        controller.closeTab(secondTabId)

        assertEquals(firstTabId, controller.state.value.attachedTabId)
        assertEquals(1, controller.state.value.tabs.tabs.size)
    }

    @Test fun closingAnInactiveTabLeavesTheAttachedTabAlone() {
        val host = FakeWebViewHost()
        val controller = controllerWith(host)
        val firstTabId = controller.state.value.tabs.activeId
        controller.openNewTab()
        val secondTabId = controller.state.value.tabs.activeId
        host.calls.clear()

        controller.closeTab(firstTabId)

        assertEquals(secondTabId, controller.state.value.attachedTabId)
        assertTrue(host.calls.isEmpty())
    }

    @Test fun closingATabDropsItsParkedPage() {
        val host = FakeWebViewHost()
        val controller = controllerWith(host)
        val firstTabId = controller.state.value.tabs.activeId
        controller.openNewTab() // parks tab 1
        val secondTabId = controller.state.value.tabs.activeId

        controller.closeTab(firstTabId)
        host.loadedUrls.clear()
        host.restored.clear()
        // Closing the last tab yields a fresh home tab. Nothing parked may attach to it.
        controller.closeTab(secondTabId)

        assertEquals(listOf(HOME), host.loadedUrls)
        assertTrue(host.restored.isEmpty())
    }

    // ---------------------------------------------------------------- address & navigation

    @Test fun loadNormalisesInputAndUpdatesTheAttachedTab() {
        val host = FakeWebViewHost()
        val controller = controllerWith(host)
        host.loadedUrls.clear()

        controller.load("example.com")

        assertEquals("https://example.com", controller.state.value.address)
        assertEquals(listOf("https://example.com"), host.loadedUrls)
        assertEquals("https://example.com", controller.state.value.tabs.activeTab.url)
    }

    @Test fun loadIgnoresBlankInput() {
        val host = FakeWebViewHost()
        val controller = controllerWith(host)
        host.loadedUrls.clear()

        controller.load("   ")

        assertTrue(host.loadedUrls.isEmpty())
    }

    @Test fun editingTheAddressDoesNotNavigate() {
        val host = FakeWebViewHost()
        val controller = controllerWith(host)
        host.loadedUrls.clear()

        controller.editAddress("half-typed")

        assertEquals("half-typed", controller.state.value.address)
        assertTrue(host.loadedUrls.isEmpty())
    }

    @Test fun navigationCallbacksDriveTheToolbar() {
        val host = FakeWebViewHost()
        val controller = controllerWith(host)

        controller.onNavigationStateChanged("https://example.com/a", canGoBack = true, canGoForward = false, loading = true)

        val state = controller.state.value
        assertEquals("https://example.com/a", state.address)
        assertTrue(state.canGoBack)
        assertFalse(state.canGoForward)
        assertTrue(state.loading)
        assertEquals("https://example.com/a", state.tabs.activeTab.url)
    }

    @Test fun titleUpdatesTheAttachedTabOnly() {
        val host = FakeWebViewHost()
        val controller = controllerWith(host)
        controller.openNewTab()

        controller.onTitle("Second")

        val tabs = controller.state.value.tabs
        assertEquals("", tabs.tabs.first().title)
        assertEquals("Second", tabs.activeTab.title)
    }

    @Test fun reloadOrStopStopsWhileLoadingAndReloadsOtherwise() {
        val host = FakeWebViewHost()
        val controller = controllerWith(host)

        controller.reloadOrStop()
        assertTrue(host.calls.contains("reload"))

        controller.onNavigationStateChanged(null, canGoBack = false, canGoForward = false, loading = true)
        host.calls.clear()
        controller.reloadOrStop()
        assertEquals(listOf("stopLoading"), host.calls)
    }

    @Test fun applyingADeviceSwapsItAndReloadsSoTheCurrentPageSeesIt() {
        val host = FakeWebViewHost()
        val controller = controllerWith(host)
        val device = DeviceProfiles.ALL.last()
        host.calls.clear()

        controller.applyDevice(device)

        assertEquals(listOf("applyDevice:${device.id}", "reload"), host.calls)
    }

    // ---------------------------------------------------------------- ssl warning

    @Test fun sslErrorRaisesADismissibleWarningWithoutCoveringThePage() {
        val host = FakeWebViewHost()
        val controller = controllerWith(host)

        controller.onSslError("example.com")

        assertNotNull(controller.state.value.sslWarning)
        assertTrue(controller.state.value.sslWarning!!.contains("example.com"))
        // A TLS refusal is a banner, not an overlay: Back must not be consumed by it.
        assertFalse(controller.state.value.hasOverlay)

        controller.dismissSslWarning()
        assertNull(controller.state.value.sslWarning)
    }

    // ---------------------------------------------------------------- load errors

    @Test fun mainFrameFailureShowsAnErrorPageWithRetryAndOpenExternally() {
        val host = FakeWebViewHost()
        val controller = controllerWith(host)

        controller.onLoadError(isForMainFrame = true, url = "https://example.com/x", description = "net::ERR")

        val error = controller.state.value.pageError
        assertNotNull(error)
        assertEquals(PageError.Kind.NETWORK, error!!.kind)
        assertTrue("Open externally must be offered for http(s)", error.canOpenExternally)
        assertTrue(controller.state.value.hasOverlay)
    }

    @Test fun subframeFailureShowsNothing() {
        val host = FakeWebViewHost()
        val controller = controllerWith(host)

        controller.onLoadError(isForMainFrame = false, url = "https://tracker.example/pixel.gif")
        controller.onHttpError(isForMainFrame = false, url = "https://ads.example/iframe", statusCode = 404)

        assertNull(controller.state.value.pageError)
        assertFalse(controller.state.value.hasOverlay)
    }

    @Test fun mainFrameHttpErrorShowsTheStatus() {
        val host = FakeWebViewHost()
        val controller = controllerWith(host)

        controller.onHttpError(
            isForMainFrame = true,
            url = "https://example.com/gone",
            statusCode = 503,
            description = "Unavailable",
        )

        val error = controller.state.value.pageError
        assertNotNull(error)
        assertEquals(PageError.Kind.HTTP, error!!.kind)
        assertEquals(503, error.statusCode)
    }

    @Test fun retryReloadsTheFailedUrlAndClearsTheErrorPage() {
        val host = FakeWebViewHost()
        val controller = controllerWith(host)
        controller.onLoadError(isForMainFrame = true, url = "https://example.com/x")
        host.loadedUrls.clear()

        controller.retry()

        assertNull(controller.state.value.pageError)
        assertEquals(listOf("https://example.com/x"), host.loadedUrls)
    }

    @Test fun aNewPageStartingClearsTheErrorButFinishingDoesNot() {
        val host = FakeWebViewHost()
        val controller = controllerWith(host)
        controller.onLoadError(isForMainFrame = true, url = "https://example.com/x")

        // onPageFinished also fires for a failed load; clearing there would erase the error page
        // moments after showing it.
        controller.onNavigationStateChanged("https://example.com/x", false, false, loading = false)
        assertNotNull(controller.state.value.pageError)

        controller.onNavigationStateChanged("https://example.com/y", false, false, loading = true)
        assertNull(controller.state.value.pageError)
    }

    @Test fun switchingTabsClearsTheErrorPageFromTheTabWeLeft() {
        val host = FakeWebViewHost()
        val controller = controllerWith(host)
        val firstTabId = controller.state.value.tabs.activeId
        controller.onLoadError(isForMainFrame = true, url = "https://example.com/x")

        controller.openNewTab()

        assertNull(controller.state.value.pageError)
        assertEquals(firstTabId, controller.state.value.tabs.tabs.first().id)
    }

    // ---------------------------------------------------------------- renderer recovery

    @Test fun renderProcessGoneReturnsTrue() {
        val controller = controllerWith(FakeWebViewHost())

        // Returning false hands the dead renderer back to the framework, which kills the app
        // process. This must be true for every reason the renderer can go away.
        assertTrue(controller.onRenderProcessGone(didCrash = true))
        assertTrue(controller.onRenderProcessGone(didCrash = false))
    }

    @Test fun renderProcessGoneProducesARecoveryStateRatherThanABlankView() {
        val host = FakeWebViewHost()
        val controller = controllerWith(host)
        controller.onNavigationStateChanged("https://example.com/heavy", true, false, loading = false)

        controller.onRenderProcessGone(didCrash = true)

        val gone = controller.state.value.rendererGone
        assertNotNull(gone)
        assertTrue(gone!!.didCrash)
        assertEquals("https://example.com/heavy", gone.url)
        assertTrue(controller.state.value.hasOverlay)
        assertFalse(controller.state.value.canGoBack)
    }

    @Test fun recoveringRebuildsTheWebViewAndReloadsRatherThanRestoringTheCrashedPage() {
        val host = FakeWebViewHost()
        val controller = controllerWith(host)
        controller.persistAttached() // park a page for the attached tab
        val generationBefore = controller.state.value.webViewGeneration

        controller.onRenderProcessGone(didCrash = true)
        controller.recoverFromRendererCrash()

        assertNull(controller.state.value.rendererGone)
        assertEquals(generationBefore + 1, controller.state.value.webViewGeneration)

        // The composable builds a replacement WebView and hands it over.
        val replacement = FakeWebViewHost()
        controller.attach(replacement)
        assertTrue("the page that killed the renderer must not be restored", replacement.restored.isEmpty())
        assertEquals(listOf(HOME), replacement.loadedUrls)
    }

    @Test fun theDeadHostIsNotUsedAgainAfterTheRendererGoesAway() {
        val host = FakeWebViewHost()
        val controller = controllerWith(host)
        controller.onRenderProcessGone(didCrash = true)
        host.calls.clear()

        controller.load("https://example.com")
        controller.goBack()
        controller.reloadOrStop()

        assertTrue("nothing may be sent to a WebView with a dead renderer", host.calls.isEmpty())
    }

    @Test fun aReleasedDeadHostIsStillTornDown() {
        val script = FakeScript("device")
        val host = FakeWebViewHost(mutableListOf(script))
        val controller = controllerWith(host)
        controller.onRenderProcessGone(didCrash = true)

        controller.release(host)

        assertTrue(script.removed)
        assertTrue(host.destroyed)
    }

    // ---------------------------------------------------------------- teardown

    @Test fun disposeRemovesEveryScriptHandlerAndDestroysTheWebView() {
        val environment = FakeScript("environment")
        val device = FakeScript("device")
        val host = FakeWebViewHost(mutableListOf(device, environment))
        val controller = controllerWith(host)

        controller.dispose()

        assertTrue("environment script handler must be removed", environment.removed)
        assertTrue("device script handler must be removed", device.removed)
        assertTrue(host.destroyed)
    }

    @Test fun scriptHandlersAreRemovedBeforeTheWebViewIsDestroyed() {
        val removalOrder = mutableListOf<String>()
        val host = object : WebViewHost by FakeWebViewHost() {
            override val installedScripts: List<RemovableScript>
                get() = listOf(RemovableScript { removalOrder += "remove" })

            override fun destroy() {
                removalOrder += "destroy"
            }
        }
        val controller = BrowserSessionController(HOME).also { it.attach(host) }

        controller.dispose()

        // After destroy() the handles point at a dead WebView and removal can no longer do anything.
        assertEquals(listOf("remove", "destroy"), removalOrder)
    }

    @Test fun releaseTearsDownTheWebViewTheComposableHandedBack() {
        val script = FakeScript("device")
        val host = FakeWebViewHost(mutableListOf(script))
        val controller = controllerWith(host)

        controller.release(host)

        assertTrue(script.removed)
        assertTrue(host.destroyed)

        // Detached: later calls must not reach the destroyed view.
        host.calls.clear()
        controller.load("https://example.com")
        assertTrue(host.calls.isEmpty())
    }

    // ---------------------------------------------------------------- session

    @Test fun clearSessionWipesBrowsingDataAndResetsToOneHomeTab() {
        val host = FakeWebViewHost()
        val controller = controllerWith(host)
        controller.openNewTab()
        controller.load("https://example.com")
        host.loadedUrls.clear()

        controller.clearSession()

        val state = controller.state.value
        assertTrue(host.clearedBrowsingData)
        assertEquals(1, state.tabs.tabs.size)
        assertEquals(HOME, state.address)
        assertEquals(state.tabs.activeId, state.attachedTabId)
        assertEquals(listOf(HOME), host.loadedUrls)
    }

    @Test fun clearSessionDropsParkedPagesSoNothingSurvivesTheWipe() {
        val host = FakeWebViewHost()
        val controller = controllerWith(host)
        controller.openNewTab() // parks tab 1

        controller.clearSession()

        val replacement = FakeWebViewHost()
        controller.attach(replacement)
        assertTrue(replacement.restored.isEmpty())
        assertEquals(listOf(HOME), replacement.loadedUrls)
    }

    // ---------------------------------------------------------------- back

    @Test fun backGoesBackWhenThePageHasHistory() {
        val host = FakeWebViewHost()
        val controller = controllerWith(host)
        controller.onNavigationStateChanged(null, canGoBack = true, canGoForward = false, loading = false)
        host.calls.clear()

        assertEquals(BackAction.GO_BACK, controller.onBack())
        assertEquals(listOf("goBack"), host.calls)
    }

    @Test fun backClosesTheTabWhenThereIsNoHistoryButOtherTabsAreOpen() {
        val host = FakeWebViewHost()
        val controller = controllerWith(host)
        val firstTabId = controller.state.value.tabs.activeId
        controller.openNewTab()

        assertEquals(BackAction.CLOSE_TAB, controller.onBack())
        assertEquals(1, controller.state.value.tabs.tabs.size)
        assertEquals(firstTabId, controller.state.value.tabs.activeId)
    }

    @Test fun backAtTheFirstPageOfTheFirstTabLeavesTheBrowser() {
        val host = FakeWebViewHost()
        val controller = controllerWith(host)
        host.calls.clear()

        // One tab, no history: the ladder has run out. The screen turns this into `onExit()` —
        // back to the dashboard, never a silent drop out of the app.
        assertEquals(BackAction.LEAVE_BROWSER, controller.onBack())
        assertEquals(1, controller.state.value.tabs.tabs.size)
        assertTrue(host.calls.isEmpty())
    }

    @Test fun backDismissesAnErrorPageBeforeAnythingElse() {
        val host = FakeWebViewHost()
        val controller = controllerWith(host)
        controller.onNavigationStateChanged(null, canGoBack = true, canGoForward = false, loading = false)
        controller.onLoadError(isForMainFrame = true, url = "https://example.com/x")
        host.calls.clear()

        assertEquals(BackAction.DISMISS_OVERLAY, controller.onBack())
        assertNull(controller.state.value.pageError)
        assertTrue(host.calls.isEmpty())
    }

    @Test fun backOnTheRecoveryCardRebuildsTheWebViewRatherThanHidingIt() {
        val controller = controllerWith(FakeWebViewHost())
        controller.onRenderProcessGone(didCrash = true)
        val generation = controller.state.value.webViewGeneration

        assertEquals(BackAction.DISMISS_OVERLAY, controller.onBack())
        assertNull(controller.state.value.rendererGone)
        assertEquals(generation + 1, controller.state.value.webViewGeneration)
    }

    // ---------------------------------------------------------------- no host attached

    @Test fun everyTransitionSurvivesHavingNoWebViewAttached() {
        val controller = BrowserSessionController(HOME)

        controller.persistAttached()
        controller.openNewTab()
        controller.switchTo(controller.state.value.tabs.tabs.first().id)
        controller.closeTab(controller.state.value.tabs.activeId)
        controller.load("example.com")
        controller.goHome()
        controller.goBack()
        controller.reloadOrStop()
        controller.applyDevice(DeviceProfiles.ALL.first())
        controller.clearSession()
        controller.dispose()

        assertEquals(1, controller.state.value.tabs.tabs.size)
    }
}
