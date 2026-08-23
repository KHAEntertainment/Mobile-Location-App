package com.geoalign.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.geoalign.core.browser.AlignmentRecoveryAction
import com.geoalign.core.browser.BackAction
import com.geoalign.core.browser.BrowserAlignmentGuard
import com.geoalign.core.browser.BrowserSessionController
import com.geoalign.core.browser.BrowserSessionState
import com.geoalign.core.browser.DownloadCoordinator
import com.geoalign.core.browser.DownloadEnqueuer
import com.geoalign.core.browser.DownloadRequest
import com.geoalign.core.browser.WebViewHost
import com.geoalign.core.device.DeviceProfile
import com.geoalign.core.device.DeviceProfiles
import com.geoalign.core.model.LocationProfile
import com.geoalign.core.monitor.AlignmentMonitorState
import com.geoalign.core.monitor.MonitorReason
import com.geoalign.core.monitor.MonitorStatus
import com.geoalign.data.monitor.AlignmentMonitor
import com.geoalign.data.profiles.ProfileFactory
import com.geoalign.data.profiles.ProfileStore
import com.geoalign.data.readiness.ReadinessService
import com.geoalign.ui.state.BrowserAlignmentPresenter
import com.geoalign.ui.state.BrowserAlignmentUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

/** The profile/device half of the browser's state — what the WebView is pretending to be. */
data class BrowserProfileState(
    /** False until the profile store has answered; the screen shows a spinner meanwhile. */
    val loaded: Boolean = false,
    val profile: LocationProfile? = null,
    val device: DeviceProfile? = null,
)

/**
 * Owns everything `BrowserScreen` used to keep in `remember` slots: the tab list, the attached tab
 * and its parked pages, the address, load progress, navigation enablement, the active location
 * profile and the emulated device.
 *
 * Two things follow from moving it here. The parked-page map now survives configuration change — as
 * a `remember { mutableMapOf<Long, Bundle>() }` it did not, so a rotation silently dropped every
 * background tab's page. And the transitions between those fields became methods on
 * [BrowserSessionController], which a JVM test can drive; as closures inside a `@Composable` they
 * were unreachable (PROJECT_CONTEXT: business logic never lives inside a `@Composable`).
 *
 * The WebView itself is not owned here. The composable constructs it — only it has a `Context` and
 * the right moment — and hands it over through [attachWebView] as a [WebViewHost].
 */
class BrowserViewModel(
    private val store: ProfileStore,
    downloadEnqueuer: DownloadEnqueuer,
    homeUrl: String,
    private val monitor: AlignmentMonitor,
    private val readiness: ReadinessService,
) : ViewModel() {

    private val controller = BrowserSessionController(homeUrl)
    private val downloads = DownloadCoordinator(downloadEnqueuer)

    val session: StateFlow<BrowserSessionState> = controller.state

    private val _profileState = MutableStateFlow(BrowserProfileState())
    val profileState: StateFlow<BrowserProfileState> = _profileState.asStateFlow()

    /**
     * The problem the user chose to browse past, if any. Held as the *status* accepted rather than
     * as a boolean, so a different problem arriving later re-prompts instead of inheriting consent
     * — `BrowserAlignmentGuard.nextAcknowledgement` is what enforces that, and it is unit-tested.
     */
    private var acknowledged: MonitorStatus? = null

    /** "Re-match" is work the monitor knows nothing about, so its progress is tracked here. */
    private var rematching: Boolean = false
    private var rematchError: String? = null

    private val _alignment = MutableStateFlow(
        BrowserAlignmentPresenter.present(monitor.snapshots.value.monitor),
    )
    val alignment: StateFlow<BrowserAlignmentUiState> = _alignment.asStateFlow()

    init {
        viewModelScope.launch {
            val profile = store.list().firstOrNull()
            _profileState.value = BrowserProfileState(
                loaded = true,
                profile = profile,
                device = profile?.let { DeviceProfiles.forProfile(it) },
            )
        }
        // Idempotent — `AppGraph` already started the singleton. Called anyway so the browser does
        // not depend on some other screen having been visited first.
        monitor.start()
        viewModelScope.launch {
            monitor.snapshots.collect { snapshot -> onMonitorState(snapshot.monitor) }
        }
        // The queued-navigation notice is derived from the session, not pushed from each call site.
        // Every path that can queue one — address bar, home, reload, retry, a link tap, a fresh
        // tab — would otherwise have to remember to refresh it, and the one that forgot would show
        // a paused tap as nothing happening at all.
        viewModelScope.launch {
            controller.state
                .map { it.heldNavigation }
                .distinctUntilChanged()
                .collect { refreshAlignment() }
        }
    }

    // ---------------------------------------------------------------- alignment

    /**
     * The single fold from an observed monitor reading to everything the browser does about it.
     * Every judgement in here belongs to pure code: `BrowserAlignmentGuard` decides whether to
     * pause, `BrowserAlignmentPresenter` decides what it looks like. This method only moves values.
     */
    private fun onMonitorState(state: AlignmentMonitorState) {
        acknowledged = BrowserAlignmentGuard.nextAcknowledgement(acknowledged, state)
        val decision = BrowserAlignmentGuard.decide(state, acknowledged)
        // The pause predicate is `isActionable`, applied inside the guard — never `!isAligned`,
        // which would fire on every RECHECKING transition and interrupt the user on each transient
        // connectivity callback.
        controller.setNavigationHeld(decision.navigationHeld)
        monitor.setUserAcceptedNoVpn(decision.userAcceptedNoVpn)
        _alignment.value = BrowserAlignmentPresenter.present(
            state = state,
            decision = decision,
            heldNavigationUrl = controller.state.value.heldNavigation,
            rematching = rematching,
            rematchError = rematchError,
        )
    }

    /** Re-run the fold against the last observed reading, after something local changed. */
    private fun refreshAlignment() = onMonitorState(monitor.snapshots.value.monitor)

    /** "Check again": ask the monitor to look, without touching the page on screen. */
    fun recheckAlignment() = monitor.refreshNow(MonitorReason.MANUAL_REFRESH)

    /**
     * "Continue anyway": record consent for *this* problem and let queued navigation through.
     *
     * Consent to a dropped tunnel is also the no-VPN opt-in, which is pushed back to the monitor so
     * the readiness screen and the browser do not disagree about whether to warn. It is never a
     * claim that anything is aligned — `BrowserAlignmentUiState.rendersVerified` stays false.
     */
    fun continueWithWarning() {
        acknowledged = BrowserAlignmentGuard.acknowledge(monitor.snapshots.value.monitor)
        refreshAlignment()
    }

    /**
     * "Re-match profile": rebuild the saved profile from wherever this connection is exiting now.
     *
     * The estimate is fetched here rather than read from the monitor's last snapshot, for the same
     * reason `ReadinessScreen.matchToVpn` does it: a cached reading would save the *previous* exit
     * and then re-render with the new one.
     */
    fun rematchProfile() {
        if (rematching) return
        rematching = true
        rematchError = null
        refreshAlignment()
        viewModelScope.launch {
            val outcome = runCatching {
                val fresh = readiness.evaluate(profileSelected = true, userAcceptedNoVpn = false)
                val geo = fresh.geolocation
                    ?: error("no location estimate for the current connection")
                val built = ProfileFactory.fromGeolocation(
                    geo = geo,
                    contentLanguage = Locale.getDefault().language.ifBlank { "en" },
                    id = UUID.randomUUID().toString(),
                    nowMillis = System.currentTimeMillis(),
                ) ?: error("estimate lacked coordinates")
                store.clear()
                store.upsert(built)
                built
            }
            rematching = false
            outcome.onSuccess { built ->
                rematchError = null
                _profileState.value = _profileState.value.copy(
                    profile = built,
                    device = DeviceProfiles.forProfile(built),
                )
                // The environment is a document-start script, so the running page already read the
                // old one. Rebuilding is the only way the new profile actually reaches a page.
                controller.rebuildWebView()
                refreshAlignment()
                monitor.refreshNow(MonitorReason.MANUAL_REFRESH)
            }.onFailure { error ->
                rematchError = error.message ?: "could not re-match this connection"
                refreshAlignment()
            }
        }
    }

    /**
     * Perform a recovery choice. Returns true when the screen still has work to do —
     * [AlignmentRecoveryAction.LEAVE_BROWSER] is the caller's, because only it knows what leaving
     * means, exactly as [BackAction.LEAVE_BROWSER] is.
     */
    fun onRecoveryAction(action: AlignmentRecoveryAction): Boolean = when (action) {
        AlignmentRecoveryAction.RECHECK -> { recheckAlignment(); false }
        AlignmentRecoveryAction.REMATCH_PROFILE -> { rematchProfile(); false }
        AlignmentRecoveryAction.CONTINUE_WITH_WARNING -> { continueWithWarning(); false }
        AlignmentRecoveryAction.LEAVE_BROWSER -> true
    }

    /**
     * A link tap, offered to the navigation hold before the WebView follows it. True means it was
     * queued and the `WebViewClient` should consume it.
     */
    fun holdLinkNavigation(url: String): Boolean = controller.holdLinkNavigation(url)

    // ---------------------------------------------------------------- WebView lifecycle

    fun attachWebView(host: WebViewHost) = controller.attach(host)

    fun releaseWebView(host: WebViewHost) = controller.release(host)

    /**
     * Leaving the browser: destroy the WebView and remove the document-start scripts it registered.
     * Also runs from [onCleared], so the teardown happens whether the screen was navigated away
     * from or the whole ViewModel went.
     */
    fun disposeWebView() = controller.dispose()

    override fun onCleared() {
        controller.dispose()
    }

    // ---------------------------------------------------------------- tabs & navigation

    fun switchTo(id: Long) = controller.switchTo(id)

    fun openNewTab() = controller.openNewTab()

    fun closeTab(id: Long) = controller.closeTab(id)

    fun editAddress(text: String) = controller.editAddress(text)

    fun load(text: String) = controller.load(text)

    fun goHome() = controller.goHome()

    fun goBack() = controller.goBack()

    fun goForward() = controller.goForward()

    fun reloadOrStop() = controller.reloadOrStop()

    fun persistAttached() = controller.persistAttached()

    fun clearSession() = controller.clearSession()

    /** Returns the action taken; [BackAction.LEAVE_BROWSER] is the screen's to act on. */
    fun onBack(): BackAction = controller.onBack()

    // ---------------------------------------------------------------- WebView callbacks

    fun onNavigationStateChanged(url: String?, canGoBack: Boolean, canGoForward: Boolean, loading: Boolean) =
        controller.onNavigationStateChanged(url, canGoBack, canGoForward, loading)

    fun onProgress(progress: Int) = controller.onProgress(progress)

    fun onTitle(title: String?) = controller.onTitle(title)

    fun onSslError(hostName: String) = controller.onSslError(hostName)

    fun dismissSslWarning() = controller.dismissSslWarning()

    fun onLoadError(isForMainFrame: Boolean, url: String, description: String?) =
        controller.onLoadError(isForMainFrame, url, description)

    fun onHttpError(isForMainFrame: Boolean, url: String, statusCode: Int, reason: String?) =
        controller.onHttpError(isForMainFrame, url, statusCode, reason)

    fun retry() = controller.retry()

    fun dismissPageError() = controller.dismissPageError()

    /** Always returns true — see [BrowserSessionController.onRenderProcessGone]. */
    fun onRenderProcessGone(didCrash: Boolean): Boolean = controller.onRenderProcessGone(didCrash)

    fun recoverFromRendererCrash() = controller.recoverFromRendererCrash()

    fun onDownloadRequested(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ): Boolean = downloads.onDownloadRequested(
        DownloadRequest(url, userAgent, contentDisposition, mimeType),
    )

    // ---------------------------------------------------------------- device

    /**
     * Switch the emulated device: the configurator (inside the host) swaps the UA string, the client
     * hints and the injected device bundle, the page reloads so it sees them, and the choice is
     * persisted back to the active profile so it sticks next session.
     */
    fun selectDevice(device: DeviceProfile) {
        val current = _profileState.value
        if (current.device?.id == device.id) return
        _profileState.value = current.copy(device = device)
        controller.applyDevice(device)
        val profile = current.profile ?: return
        viewModelScope.launch {
            runCatching { store.upsert(profile.copy(userAgentProfileId = device.id)) }
        }
    }

    /**
     * Manual wiring, matching the project's no-DI-framework convention. Deliberately does not reach
     * into `di/AppGraph`: the screen already builds the store there and passes it in.
     */
    class Factory(
        private val store: ProfileStore,
        private val downloadEnqueuer: DownloadEnqueuer,
        private val homeUrl: String,
        private val monitor: AlignmentMonitor,
        private val readiness: ReadinessService,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            BrowserViewModel(store, downloadEnqueuer, homeUrl, monitor, readiness) as T
    }
}
