package com.geoalign.web.config

/**
 * What the *installed* WebView can actually do, as a plain value.
 *
 * The implementation behind `android.webkit.WebView` is a separately updatable system package, so
 * none of these are compile-time facts: the same APK runs against a five-year-old WebView on one
 * device and a current one on the next. Every branch that used to ask `WebViewFeature` at the point
 * of use now reads a field here instead, so the answers are gathered once, in one place
 * ([WebViewCapabilityProbe]), and the rest of the tree — including its unit tests — never touches
 * an Android API to find out.
 *
 * This type is deliberately Android-free: it is constructed in JVM tests to drive
 * [WebViewConfigurator]'s decisions without a device or an emulator.
 */
data class WebViewCapabilities(
    /**
     * `WebViewCompat.addDocumentStartJavaScript` is available. Without it the location and device
     * bundles cannot be installed *before* page script runs, which is the only ordering that makes
     * them effective — a late injection loses every race against the page's own bootstrap.
     */
    val documentStartScript: Boolean,
    /**
     * `WebSettingsCompat.setUserAgentMetadata` is available. This is the half of native mode that
     * JavaScript cannot fix, because it drives the `Sec-CH-UA` *request headers* as well as
     * `navigator.userAgentData`.
     */
    val userAgentMetadata: Boolean,
    /** `WebSettingsCompat.setSafeBrowsingEnabled` is available. */
    val safeBrowsing: Boolean,
    /**
     * `ServiceWorkerControllerCompat` is available. Carried as a fact only — nothing consumes it
     * yet. Service workers issue requests outside the `WebViewClient` callbacks, so any surface
     * that claims requests are being filtered has to know whether that path can be reached at all.
     */
    val serviceWorkerControl: Boolean,
    /** Package name of the WebView implementation in use, or null if none could be resolved. */
    val packageName: String?,
    /** Version of that package (e.g. "151.0.7922.169"), or null if none could be resolved. */
    val packageVersion: String?,
) {
    companion object {
        /**
         * The pessimistic baseline: nothing supported, nothing known. Used where a real probe has
         * not run — a configurator holding this still produces a hardened, functioning WebView, it
         * just installs no scripts and no client hints.
         */
        val NONE = WebViewCapabilities(
            documentStartScript = false,
            userAgentMetadata = false,
            safeBrowsing = false,
            serviceWorkerControl = false,
            packageName = null,
            packageVersion = null,
        )
    }
}

/**
 * Source of [WebViewCapabilities]. Exists so the one Android-touching implementation can be swapped
 * for a fixed value in tests; production has exactly one implementation,
 * [AndroidWebViewCapabilityProbe].
 */
fun interface WebViewCapabilityProbe {
    fun probe(): WebViewCapabilities
}
