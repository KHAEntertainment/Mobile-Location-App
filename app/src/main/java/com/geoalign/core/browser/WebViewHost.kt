package com.geoalign.core.browser

import com.geoalign.core.device.DeviceProfile

/**
 * The browser's view of a live WebView, as a port with no Android types (spec §10, §11).
 *
 * `BrowserSessionController` drives tab and session transitions entirely through this interface, so
 * every one of those transitions is reachable from a plain JVM unit test with a fake host — this
 * repo has no Robolectric and no Mockito, so anything that can only be exercised against a real
 * `android.webkit.WebView` is, by definition, untestable here.
 *
 * There is exactly one production implementation, `com.geoalign.web.session.AndroidWebViewHost`.
 */
interface WebViewHost {

    /**
     * The document-start scripts currently installed on this WebView, newest first.
     *
     * These have to be removed explicitly on teardown: they are registered with the WebView, and a
     * `destroy()` that leaves them registered has no one left to unregister them. The list is
     * recomputed on read, so it reflects device swaps.
     */
    val installedScripts: List<RemovableScript>

    fun loadUrl(url: String)

    fun stopLoading()

    fun reload()

    fun goBack()

    fun goForward()

    fun canGoBack(): Boolean

    fun canGoForward(): Boolean

    /** Park the current page so it can be restored later, or null if there is nothing to park. */
    fun saveState(): PageSnapshot?

    /** Restore a parked page. Returns false if the snapshot could not be applied. */
    fun restoreState(snapshot: PageSnapshot): Boolean

    /**
     * Swap the emulated device: user-agent string, client hints and the injected device bundle. The
     * implementation replaces the previous device script rather than stacking a second one.
     */
    fun applyDevice(device: DeviceProfile)

    /** Wipe cookies, web storage, cache, form data and history. Does not touch saved profiles. */
    fun clearBrowsingData()

    fun destroy()
}

/**
 * An opaque handle to a parked page. Android backs this with a `Bundle`; tests supply their own
 * value. Deliberately empty — nothing outside the Android layer may look inside a snapshot.
 */
interface PageSnapshot

/**
 * A document-start script that can be uninstalled, mirroring `androidx.webkit.ScriptHandler.remove`
 * without importing it. Exists so script removal on teardown can be asserted from a JVM test.
 */
fun interface RemovableScript {
    fun remove()
}
