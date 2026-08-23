package com.geoalign.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.geoalign.core.browser.BackAction
import com.geoalign.core.browser.BrowserSessionController
import com.geoalign.core.browser.BrowserSessionState
import com.geoalign.core.browser.DownloadCoordinator
import com.geoalign.core.browser.DownloadEnqueuer
import com.geoalign.core.browser.DownloadRequest
import com.geoalign.core.browser.WebViewHost
import com.geoalign.core.device.DeviceProfile
import com.geoalign.core.device.DeviceProfiles
import com.geoalign.core.model.LocationProfile
import com.geoalign.data.profiles.ProfileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
) : ViewModel() {

    private val controller = BrowserSessionController(homeUrl)
    private val downloads = DownloadCoordinator(downloadEnqueuer)

    val session: StateFlow<BrowserSessionState> = controller.state

    private val _profileState = MutableStateFlow(BrowserProfileState())
    val profileState: StateFlow<BrowserProfileState> = _profileState.asStateFlow()

    init {
        viewModelScope.launch {
            val profile = store.list().firstOrNull()
            _profileState.value = BrowserProfileState(
                loaded = true,
                profile = profile,
                device = profile?.let { DeviceProfiles.forProfile(it) },
            )
        }
    }

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
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            BrowserViewModel(store, downloadEnqueuer, homeUrl) as T
    }
}
