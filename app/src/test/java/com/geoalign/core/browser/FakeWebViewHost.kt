package com.geoalign.core.browser

import com.geoalign.core.device.DeviceProfile

/** A parked page, as far as a JVM test is concerned. */
data class FakeSnapshot(val label: String) : PageSnapshot

/** A document-start script handle that records whether it was actually removed. */
class FakeScript(val name: String) : RemovableScript {
    var removed = false
        private set

    override fun remove() {
        removed = true
    }
}

/**
 * Stands in for a real `android.webkit.WebView` so `BrowserSessionController` can be driven from a
 * plain JUnit test. This repo has no Robolectric and no Mockito, so this fake is the whole reason
 * the session logic is testable at all.
 *
 * [calls] records the ordered call log, which is how teardown ordering is asserted.
 */
class FakeWebViewHost(
    val scripts: MutableList<FakeScript> = mutableListOf(),
) : WebViewHost {

    val calls = mutableListOf<String>()

    var loadedUrls = mutableListOf<String>()
    var restored = mutableListOf<PageSnapshot>()
    var destroyed = false
    var clearedBrowsingData = false
    var appliedDevices = mutableListOf<DeviceProfile>()

    /** What [saveState] hands back. Null models a WebView with nothing worth parking. */
    var nextSnapshot: PageSnapshot? = FakeSnapshot("state")

    /** Whether [restoreState] succeeds. False models a snapshot the platform rejected. */
    var restoreSucceeds = true

    var backAvailable = false
    var forwardAvailable = false

    override val installedScripts: List<RemovableScript>
        get() = scripts.toList()

    override fun loadUrl(url: String) {
        calls += "loadUrl:$url"
        loadedUrls += url
    }

    override fun stopLoading() {
        calls += "stopLoading"
    }

    override fun reload() {
        calls += "reload"
    }

    override fun goBack() {
        calls += "goBack"
    }

    override fun goForward() {
        calls += "goForward"
    }

    override fun canGoBack(): Boolean = backAvailable

    override fun canGoForward(): Boolean = forwardAvailable

    override fun saveState(): PageSnapshot? {
        calls += "saveState"
        return nextSnapshot
    }

    override fun restoreState(snapshot: PageSnapshot): Boolean {
        calls += "restoreState"
        return if (restoreSucceeds) {
            restored += snapshot
            true
        } else {
            false
        }
    }

    override fun applyDevice(device: DeviceProfile) {
        calls += "applyDevice:${device.id}"
        appliedDevices += device
    }

    override fun clearBrowsingData() {
        calls += "clearBrowsingData"
        clearedBrowsingData = true
    }

    override fun destroy() {
        calls += "destroy"
        destroyed = true
    }
}
