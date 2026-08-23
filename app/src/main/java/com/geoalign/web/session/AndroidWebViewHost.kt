package com.geoalign.web.session

import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import com.geoalign.core.browser.PageSnapshot
import com.geoalign.core.browser.RemovableScript
import com.geoalign.core.browser.WebViewHost
import com.geoalign.core.device.DeviceProfile
import com.geoalign.web.config.WebViewConfigurator

/** A parked page, backed by the `Bundle` `WebView.saveState` writes into. */
class BundleSnapshot(internal val bundle: Bundle) : PageSnapshot

/**
 * The one production [WebViewHost]: a real [WebView] plus the [WebViewConfigurator] that dressed it
 * and the document-start script handles that configuration installed.
 *
 * Everything here is a call into `android.webkit`; every *decision* about when to make those calls
 * lives in `BrowserSessionController`. That split is what lets the session logic be unit-tested in a
 * repo with no instrumentation tests.
 *
 * Teardown is idempotent. Two paths can reach it — the composable releasing the view, and the
 * ViewModel being cleared — and after a renderer crash the WebView is already dead, so every call
 * here is guarded rather than assumed to succeed.
 */
class AndroidWebViewHost(
    private val webView: WebView,
    private val configurator: WebViewConfigurator,
    environmentScript: ScriptHandler?,
    deviceScript: ScriptHandler?,
) : WebViewHost {

    private val environmentHandle: ScriptHandler? = environmentScript
    private var deviceHandle: ScriptHandler? = deviceScript
    private var destroyed = false

    override val installedScripts: List<RemovableScript>
        get() = listOfNotNull(deviceHandle, environmentHandle).map { handle ->
            RemovableScript { runCatching { handle.remove() } }
        }

    override fun loadUrl(url: String) = ifAlive { webView.loadUrl(url) }

    override fun stopLoading() = ifAlive { webView.stopLoading() }

    override fun reload() = ifAlive { webView.reload() }

    override fun goBack() = ifAlive { webView.goBack() }

    override fun goForward() = ifAlive { webView.goForward() }

    override fun canGoBack(): Boolean = !destroyed && webView.canGoBack()

    override fun canGoForward(): Boolean = !destroyed && webView.canGoForward()

    override fun saveState(): PageSnapshot? {
        if (destroyed) return null
        val bundle = Bundle()
        return if (webView.saveState(bundle) != null) BundleSnapshot(bundle) else null
    }

    override fun restoreState(snapshot: PageSnapshot): Boolean {
        if (destroyed) return false
        val bundle = (snapshot as? BundleSnapshot)?.bundle ?: return false
        return webView.restoreState(bundle) != null
    }

    override fun applyDevice(device: DeviceProfile) {
        if (destroyed) return
        // The configurator removes the previous device bundle itself and hands back the new handle;
        // keeping the old one installed would leave two device identities defining the same globals.
        deviceHandle = configurator.applyDevice(webView, device, deviceHandle)
    }

    override fun clearBrowsingData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        ifAlive {
            webView.clearCache(true)
            webView.clearFormData()
            webView.clearHistory()
        }
    }

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        // Detach before destroying: a WebView destroyed while still parented keeps being measured
        // and drawn by its parent, against a native instance that is already gone.
        runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
        runCatching { webView.destroy() }
    }

    private inline fun ifAlive(block: () -> Unit) {
        if (destroyed) return
        runCatching { block() }
    }
}
