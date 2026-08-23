package com.geoalign.core.browser

import com.geoalign.core.device.DeviceProfile
import com.geoalign.core.net.UrlNormalizer
import com.geoalign.core.tabs.TabListReducer
import com.geoalign.core.tabs.TabsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The renderer for the attached WebView died and the view is no longer usable. */
data class RendererGone(
    /** True for a renderer crash, false when Android reclaimed the renderer under memory pressure. */
    val didCrash: Boolean,
    /** The address that was showing when the renderer went away, for the recovery card. */
    val url: String,
) {
    val headline: String
        get() = if (didCrash) "This page stopped responding" else "This page was closed to save memory"

    val detail: String
        get() = if (didCrash) {
            "The page's renderer crashed. Your tabs are intact — reload to start it again."
        } else {
            "Android reclaimed this page while the app was in the background. Reload to bring it back."
        }
}

/**
 * Everything the browser chrome renders, as one value. All of it used to live in `remember` slots
 * inside `BrowserScreen`, where the transitions between these fields were closures that no test
 * could reach.
 */
data class BrowserSessionState(
    val tabs: TabsState,
    /** The tab whose page is loaded into the single WebView. Kept in step with `tabs.activeId`. */
    val attachedTabId: Long,
    val address: String,
    val progress: Int = 0,
    val loading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    /** Dismissible banner for a refused TLS connection. The page underneath is still usable. */
    val sslWarning: String? = null,
    val pageError: PageError? = null,
    val rendererGone: RendererGone? = null,
    /**
     * Bumped whenever the WebView has to be rebuilt from scratch. The composable keys its
     * `AndroidView` on this, so an increment releases the old WebView and constructs a new one.
     */
    val webViewGeneration: Int = 0,
    /**
     * New navigation is paused because the live alignment monitor is reporting a problem
     * (issue #6). **Only new navigation.** The attached WebView is not stopped, blanked or
     * destroyed while this is true — a user mid-form who loses their VPN must not also lose what
     * they typed.
     */
    val navigationHeld: Boolean = false,
    /**
     * The url the user asked for while [navigationHeld] was true, waiting to be issued once the
     * hold lifts. Holding onto it is the difference between "paused" and "swallowed".
     */
    val heldNavigation: String? = null,
) {
    /** Whether something is covering the page. Drives the first rung of the Back ladder. */
    val hasOverlay: Boolean
        get() = pageError != null || rendererGone != null
}

/**
 * Owns the tab/WebView binding for the browser (spec §10, §11).
 *
 * This is `BrowserScreen`'s former `persistAttached` / `bindTabToWebView` / `switchTo` /
 * `openNewTab` / `closeTab` / `load` / `clearSession` closures, plus the state they mutated, moved
 * somewhere a JVM test can reach them. It talks to the WebView only through [WebViewHost], so the
 * whole state machine runs against a fake in `BrowserSessionControllerTest` — no device, no
 * emulator, no Compose.
 *
 * The parked-page map lives here rather than in a `remember`, so it survives configuration change
 * along with the ViewModel that owns this controller. It is still in memory only: restoring tabs
 * after *process* death needs a serialised store and is tracked separately.
 */
class BrowserSessionController(private val homeUrl: String) {

    private val snapshots = mutableMapOf<Long, PageSnapshot>()
    private var host: WebViewHost? = null

    private val initialTabs = TabsState.initial(homeUrl)
    private val _state = MutableStateFlow(
        BrowserSessionState(
            tabs = initialTabs,
            attachedTabId = initialTabs.activeId,
            address = homeUrl,
        ),
    )
    val state: StateFlow<BrowserSessionState> = _state.asStateFlow()

    // ---------------------------------------------------------------- lifecycle

    /** Bind a freshly constructed WebView and load whatever the active tab should be showing. */
    fun attach(newHost: WebViewHost) {
        host = newHost
        bind(_state.value.tabs.activeId)
    }

    /**
     * The composable released [released] from the view hierarchy. Tears it down whether or not it
     * is still the attached host — after a renderer crash the controller has already let go of it,
     * but the object still has to be destroyed.
     */
    fun release(released: WebViewHost) {
        if (host === released) host = null
        teardown(released)
    }

    /** Leaving the browser for good: destroy the WebView and drop every parked page. */
    fun dispose() {
        host?.let { teardown(it) }
        host = null
        snapshots.clear()
    }

    /**
     * Removes the document-start scripts **before** destroying the WebView. After `destroy()` the
     * handles point at a dead WebView and removal can no longer do anything, so a teardown that
     * destroys first leaks every script it installed for the lifetime of the WebView's chromium
     * process. This ordering is asserted in `BrowserSessionControllerTest`.
     */
    private fun teardown(target: WebViewHost) {
        target.installedScripts.forEach { it.remove() }
        target.stopLoading()
        // Park on a blank document so nothing is still executing when the view goes away.
        target.loadUrl(BLANK_URL)
        target.destroy()
    }

    // ---------------------------------------------------------------- tabs

    /** Park the WebView's current page under the attached tab so it can be restored later. */
    fun persistAttached() {
        val h = host ?: return
        h.saveState()?.let { snapshots[_state.value.attachedTabId] = it }
    }

    fun switchTo(id: Long) {
        if (id == _state.value.tabs.activeId) return
        persistAttached()
        _state.value = _state.value.copy(tabs = TabListReducer.selectTab(_state.value.tabs, id))
        bind(_state.value.tabs.activeId)
    }

    fun openNewTab() {
        persistAttached()
        _state.value = _state.value.copy(tabs = TabListReducer.openTab(_state.value.tabs, homeUrl))
        // A brand-new tab has no parked page, so this loads the home url fresh.
        bind(_state.value.tabs.activeId)
    }

    fun closeTab(id: Long) {
        val wasActive = id == _state.value.tabs.activeId
        _state.value = _state.value.copy(tabs = TabListReducer.closeTab(_state.value.tabs, id, homeUrl))
        snapshots.remove(id)
        if (wasActive) bind(_state.value.tabs.activeId)
    }

    /** Load the given tab into the WebView: restore its parked page, or fetch its url fresh. */
    private fun bind(id: Long) {
        val tab = _state.value.tabs.tabs.firstOrNull { it.id == id } ?: return
        val h = host
        var deferred: String? = null
        if (h != null) {
            val snapshot = snapshots[id]
            // A parked page is a document this tab already had; putting it back is not a new
            // navigation and is never held. Only a fresh fetch is.
            val restored = snapshot != null && h.restoreState(snapshot)
            if (!restored) {
                if (_state.value.navigationHeld) deferred = tab.url else h.loadUrl(tab.url)
            }
        }
        _state.value = _state.value.copy(
            heldNavigation = deferred ?: _state.value.heldNavigation,
            attachedTabId = id,
            address = tab.url,
            // Reset the transient chrome; the callbacks repopulate it as the page settles.
            progress = 0,
            loading = false,
            canGoBack = h?.canGoBack() ?: false,
            canGoForward = h?.canGoForward() ?: false,
            sslWarning = null,
            pageError = null,
        )
    }

    // ---------------------------------------------------------------- navigation

    fun editAddress(text: String) {
        _state.value = _state.value.copy(address = text)
    }

    /**
     * Pause or resume *new* navigation (issue #6). Driven by the live alignment monitor through the
     * pure `BrowserAlignmentGuard`; this method carries no judgement about when to pause.
     *
     * Holding touches nothing that is already loaded: no `stopLoading`, no `about:blank`, no
     * `destroy`. The WebView keeps rendering, keeps its form state and keeps its history. Releasing
     * issues whatever navigation was queued in the meantime, so a request made during the hold is
     * deferred rather than discarded.
     */
    fun setNavigationHeld(held: Boolean) {
        val current = _state.value
        if (current.navigationHeld == held) return
        if (held) {
            _state.value = current.copy(navigationHeld = true)
            return
        }
        val pending = current.heldNavigation
        _state.value = current.copy(navigationHeld = false, heldNavigation = null)
        pending?.let { load(it) }
    }

    /**
     * A link tap inside the page, offered to the hold. Returns true when it was queued instead of
     * followed, which the `WebViewClient` reports back as "consumed" so the page does not navigate.
     */
    fun holdLinkNavigation(url: String): Boolean {
        if (!_state.value.navigationHeld) return false
        _state.value = _state.value.copy(heldNavigation = url)
        return true
    }

    /** Normalise address-bar input and navigate. No-op for input that normalises to nothing. */
    fun load(text: String) {
        val url = UrlNormalizer.normalize(text) ?: return
        if (_state.value.navigationHeld) {
            // The address bar shows what was asked for, the tab still points at the page actually
            // loaded, and the WebView is not touched. Updating the tab url here would rewrite the
            // identity of a page the user is still reading.
            _state.value = _state.value.copy(address = url, heldNavigation = url)
            return
        }
        _state.value = _state.value.copy(
            address = url,
            tabs = TabListReducer.updateTab(_state.value.tabs, _state.value.attachedTabId, url = url),
            pageError = null,
        )
        host?.loadUrl(url)
    }

    fun goHome() = load(homeUrl)

    fun goBack() {
        host?.goBack()
    }

    fun goForward() {
        host?.goForward()
    }

    fun reloadOrStop() {
        val h = host ?: return
        val current = _state.value
        if (current.loading) {
            // Stopping is always allowed — it is the one action that can only reduce what the
            // network sees, and refusing it while held would be perverse.
            h.stopLoading()
            return
        }
        // A reload re-fetches the current document over whatever connection exists now, which is
        // precisely the fetch the hold exists to prevent. Queued rather than refused, so the tap is
        // not simply swallowed.
        if (current.navigationHeld) {
            _state.value = current.copy(heldNavigation = current.address)
            return
        }
        h.reload()
    }

    /** Swap the emulated device on the live WebView and reload so the current page sees it. */
    fun applyDevice(device: DeviceProfile) {
        val h = host ?: return
        h.applyDevice(device)
        h.reload()
    }

    // ---------------------------------------------------------------- callbacks from the WebView

    fun onNavigationStateChanged(url: String?, canGoBack: Boolean, canGoForward: Boolean, loading: Boolean) {
        val current = _state.value
        _state.value = current.copy(
            address = url ?: current.address,
            tabs = if (url != null) {
                TabListReducer.updateTab(current.tabs, current.attachedTabId, url = url)
            } else {
                current.tabs
            },
            canGoBack = canGoBack,
            canGoForward = canGoForward,
            loading = loading,
            // A page starting to load supersedes whatever error the previous attempt left behind.
            // Only the start clears it: `onPageFinished` also fires for a failed load, and clearing
            // there would erase the error page a moment after showing it.
            pageError = if (loading) null else current.pageError,
        )
    }

    fun onProgress(progress: Int) {
        _state.value = _state.value.copy(progress = progress)
    }

    fun onTitle(title: String?) {
        if (title == null) return
        val current = _state.value
        _state.value = current.copy(
            tabs = TabListReducer.updateTab(current.tabs, current.attachedTabId, title = title),
        )
    }

    fun onSslError(hostName: String) {
        _state.value = _state.value.copy(
            sslWarning = "Refused an insecure connection to $hostName — the site's security " +
                "certificate could not be trusted.",
        )
    }

    fun dismissSslWarning() {
        _state.value = _state.value.copy(sslWarning = null)
    }

    /** A resource failed at the network level. Only a main-frame failure reaches the user. */
    fun onLoadError(isForMainFrame: Boolean, url: String, description: String? = null) {
        val error = LoadErrorPolicy.forNetworkFailure(isForMainFrame, url, description) ?: return
        _state.value = _state.value.copy(pageError = error, loading = false)
    }

    /** A resource came back 4xx/5xx. Only a main-frame failure reaches the user. */
    fun onHttpError(isForMainFrame: Boolean, url: String, statusCode: Int, description: String? = null) {
        val error = LoadErrorPolicy.forHttpStatus(isForMainFrame, url, statusCode, description) ?: return
        _state.value = _state.value.copy(pageError = error, loading = false)
    }

    /** "Retry" on the error page: load the same url again. Queued while navigation is held. */
    fun retry() {
        val error = _state.value.pageError ?: return
        if (_state.value.navigationHeld) {
            // The error card stays up: dismissing it would leave a blank view and imply the retry
            // happened. It did not — it is waiting.
            _state.value = _state.value.copy(heldNavigation = error.url)
            return
        }
        _state.value = _state.value.copy(pageError = null, address = error.url)
        host?.loadUrl(error.url)
    }

    fun dismissPageError() {
        _state.value = _state.value.copy(pageError = null)
    }

    /**
     * The WebView's renderer process went away. **Always returns true.**
     *
     * Returning false hands the dead renderer back to the framework, whose only remaining response
     * is to kill this app's process — the user loses every tab and the app disappears, which is the
     * exact failure this recovery exists to prevent. Returning true claims the event, at the cost of
     * having to treat the WebView as unusable from here on: the controller lets go of it (the
     * composable destroys the object when it leaves the hierarchy) and raises a recovery state.
     */
    fun onRenderProcessGone(didCrash: Boolean): Boolean {
        val current = _state.value
        // The page that just killed the renderer is not worth restoring: a parked snapshot of it
        // would feed the same content straight back into the replacement WebView.
        snapshots.remove(current.attachedTabId)
        host = null
        _state.value = current.copy(
            rendererGone = RendererGone(didCrash = didCrash, url = current.address),
            pageError = null,
            progress = 0,
            loading = false,
            canGoBack = false,
            canGoForward = false,
        )
        return true
    }

    /**
     * Rebuild the WebView around a changed location profile (issue #6, "Re-match profile").
     *
     * The virtual environment is installed as a document-start script, so a page that has already
     * loaded has already read the old one and cannot be un-told it. Re-matching therefore parks the
     * current page, bumps the generation so the composable constructs a WebView configured from the
     * new profile, and lets `attach` restore the parked page into it — which replays the load and
     * runs the new bundle at document start.
     *
     * This is the one place the page is deliberately re-loaded, and it is not the pause: it happens
     * only because the user explicitly asked to change what the browser is pretending to be. Doing
     * nothing instead would leave a page running under a profile the app has just declared wrong
     * while the indicator turned green over it.
     */
    fun rebuildWebView() {
        persistAttached()
        _state.value = _state.value.copy(webViewGeneration = _state.value.webViewGeneration + 1)
    }

    /** "Reload" on the recovery card: build a fresh WebView and re-bind the active tab. */
    fun recoverFromRendererCrash() {
        val current = _state.value
        if (current.rendererGone == null) return
        _state.value = current.copy(
            rendererGone = null,
            webViewGeneration = current.webViewGeneration + 1,
        )
    }

    // ---------------------------------------------------------------- session

    /**
     * Wipe browsing state and reset to one fresh home tab (spec §25). Does not touch the saved
     * location or device profile.
     */
    fun clearSession() {
        host?.clearBrowsingData()
        snapshots.clear()
        val fresh = TabsState.initial(homeUrl)
        val held = _state.value.navigationHeld
        _state.value = BrowserSessionState(
            tabs = fresh,
            attachedTabId = fresh.activeId,
            address = homeUrl,
            webViewGeneration = _state.value.webViewGeneration,
            // Wiping the session must not also wipe the reason new pages are paused. Rebuilding
            // this value from scratch used to reset every field, and a `navigationHeld` that
            // silently became false here would let the very next tap navigate unprotected.
            navigationHeld = held,
            heldNavigation = if (held) homeUrl else null,
        )
        if (!held) host?.loadUrl(homeUrl)
    }

    /**
     * Handle the Android Back gesture. Performs everything except [BackAction.LEAVE_BROWSER], which
     * is the caller's to act on because only it knows what leaving means.
     */
    fun onBack(): BackAction {
        val current = _state.value
        val action = BackPolicy.decide(
            hasOverlay = current.hasOverlay,
            canGoBack = current.canGoBack,
            tabCount = current.tabs.tabs.size,
        )
        when (action) {
            BackAction.DISMISS_OVERLAY ->
                // A renderer-recovery card can't just be hidden — that would leave a blank view
                // behind it — so dismissing it means rebuilding the WebView.
                if (current.rendererGone != null) recoverFromRendererCrash() else dismissPageError()
            BackAction.GO_BACK -> host?.goBack()
            BackAction.CLOSE_TAB -> closeTab(current.tabs.activeId)
            BackAction.LEAVE_BROWSER -> Unit
        }
        return action
    }

    private companion object {
        const val BLANK_URL = "about:blank"
    }
}
