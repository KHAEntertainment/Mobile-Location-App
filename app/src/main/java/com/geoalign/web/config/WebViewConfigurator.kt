package com.geoalign.web.config

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import com.geoalign.core.device.DeviceProfile
import com.geoalign.core.model.LocationProfile
import com.geoalign.web.environment.DeviceBundleCompiler
import com.geoalign.web.environment.EnvBundleCompiler
import com.geoalign.web.environment.NativeUaMetadata

/**
 * Turns a freshly constructed [WebView] into *the* GeoAlign browser WebView: the hardened settings
 * matrix, the device's user-agent and its matching client hints, safe browsing, and the two
 * document-start bundles (spec §10, §11, §14, §21).
 *
 * This used to live inline in `BrowserScreen`'s `AndroidView` factory, where nothing could reach it
 * — not a test, and not the other surfaces that need the same configuration. Everything here is a
 * decision about the WebView; the composable keeps only what closes over UI state (the clients, the
 * download listener, navigation).
 *
 * [capabilities] are probed once by the caller and never re-queried here, so what this configurator
 * did is exactly what a capability gate can report. [realUserAgent] is the untouched WebView UA
 * from `WebSettings.getDefaultUserAgent` — the input native mode reduces, and the base the client
 * hints are corrected from.
 */
class WebViewConfigurator(
    private val capabilities: WebViewCapabilities,
    private val realUserAgent: String,
) {

    /** Handles to the document-start scripts installed on a WebView, null where unsupported. */
    data class InstalledScripts(
        val environment: ScriptHandler?,
        val device: ScriptHandler?,
    )

    /**
     * First-time configuration of [webView] for [profile] and [device]. Returns the installed
     * script handles; the device one has to be retained so [applyDevice] can replace it later.
     *
     * Call this before the WebView loads anything: the document-start bundles only work if they are
     * registered before the first navigation.
     */
    fun configure(
        webView: WebView,
        profile: LocationProfile,
        device: DeviceProfile,
    ): InstalledScripts {
        enableRemoteDebuggingOnDebuggableBuilds(webView)
        applySettings(webView.settings, BrowserSettingsSpec.hardened(device, realUserAgent))
        // Native mode's client hints (and Sec-CH-UA headers) come from here.
        NativeUaMetadata.applyOrRestore(webView.settings, realUserAgent, device.native, capabilities)
        if (capabilities.safeBrowsing) {
            WebSettingsCompat.setSafeBrowsingEnabled(webView.settings, true)
        }

        // Document-start hooks (order: location environment, then device signals). Both are
        // WebView-wide, so they apply to every tab loaded here.
        if (!capabilities.documentStartScript) return InstalledScripts(null, null)
        val ctx = webView.context
        return InstalledScripts(
            environment = addDocumentStartScript(webView, EnvBundleCompiler.compileFromAssets(ctx, profile)),
            device = addDocumentStartScript(webView, DeviceBundleCompiler.compileFromAssets(ctx, device)),
        )
    }

    /**
     * Switch the emulated device on an already-configured [webView]: UA string, client hints, and a
     * replacement device bundle. [previous] is the handle returned last time; it is removed first,
     * because the bundles stack rather than supersede one another — leaving the old one installed
     * would have two device identities racing to define the same globals.
     *
     * Returns the new handle. The caller still has to reload for the swap to reach the current page.
     */
    fun applyDevice(webView: WebView, device: DeviceProfile, previous: ScriptHandler?): ScriptHandler? {
        webView.settings.userAgentString = BrowserSettingsSpec.userAgentFor(device, realUserAgent)
        // Client hints must follow the UA string, or the two contradict each other.
        NativeUaMetadata.applyOrRestore(webView.settings, realUserAgent, device.native, capabilities)
        if (!capabilities.documentStartScript) return null
        previous?.remove()
        return addDocumentStartScript(
            webView,
            DeviceBundleCompiler.compileFromAssets(webView.context, device),
        )
    }

    /** Copy a [BrowserSettingsSpec] onto a real `WebSettings`. The only place the matrix is applied. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun applySettings(settings: WebSettings, spec: BrowserSettingsSpec) {
        settings.javaScriptEnabled = spec.javaScriptEnabled
        settings.domStorageEnabled = spec.domStorageEnabled
        settings.allowFileAccess = spec.allowFileAccess
        settings.allowContentAccess = spec.allowContentAccess
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = spec.allowFileAccessFromFileURLs
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = spec.allowUniversalAccessFromFileURLs
        // There is deliberately no "allow mixed content" branch: the only value this browser ever
        // sets is MIXED_CONTENT_NEVER_ALLOW, and a false flag leaves the platform default (also
        // never-allow at this targetSdk) rather than opening it up.
        if (spec.blockMixedContent) settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.useWideViewPort = spec.useWideViewPort
        settings.loadWithOverviewMode = spec.loadWithOverviewMode
        settings.userAgentString = spec.userAgentString
    }

    private fun addDocumentStartScript(webView: WebView, script: String): ScriptHandler =
        WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))

    /**
     * On debuggable (sideload) builds, allow chrome://inspect so WebView-hostile sites
     * can be diagnosed live. No-op on release.
     */
    private fun enableRemoteDebuggingOnDebuggableBuilds(webView: WebView) {
        if (webView.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }
}
