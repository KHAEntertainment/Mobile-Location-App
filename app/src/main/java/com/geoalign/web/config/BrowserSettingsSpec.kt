package com.geoalign.web.config

import com.geoalign.core.device.DeviceProfile
import com.geoalign.core.device.NativeIdentity

/**
 * The hardened WebView settings matrix, as data.
 *
 * `WebSettings` is a platform object that cannot be constructed or read off-device, so as long as
 * these choices only existed as a sequence of assignments they could not be asserted anywhere: a
 * silently flipped flag would have shipped. Stating them as a value first makes the matrix a thing
 * a JVM test can hold and compare, and [WebViewConfigurator] the single place that copies it onto a
 * real `WebSettings`.
 *
 * Every field here is a deliberate deviation from, or confirmation of, a WebView default — the
 * comments say which and why.
 */
data class BrowserSettingsSpec(
    /** Required: the injected environment and device bundles are JavaScript. */
    val javaScriptEnabled: Boolean = true,
    /** Ordinary sites break without localStorage/sessionStorage. */
    val domStorageEnabled: Boolean = true,
    /** No page may read the APK's assets or the app's private files. */
    val allowFileAccess: Boolean = false,
    /** No page may reach content:// providers. */
    val allowContentAccess: Boolean = false,
    /** A file:// document must not be able to read sibling files. */
    val allowFileAccessFromFileURLs: Boolean = false,
    /** A file:// document must not get universal (cross-origin) access. */
    val allowUniversalAccessFromFileURLs: Boolean = false,
    /**
     * Explicitly refuse mixed content (spec §21) — maps to `MIXED_CONTENT_NEVER_ALLOW`. Cleartext
     * is also blocked at the manifest level (usesCleartextTraffic=false); set here to remove the
     * discrepancy.
     */
    val blockMixedContent: Boolean = true,
    /**
     * Honor the page's <meta viewport> the way mobile Chrome does. Without this
     * (WebView default useWideViewPort=false), responsive sites are laid out at
     * the raw view width, so window.innerWidth/innerHeight and the
     * (orientation: landscape) media query can read as a wide/landscape desktop —
     * which is what makes Tinder show its "portrait view" gate. Enabling these
     * makes width=device-width resolve to the real CSS width, so orientation
     * reads portrait like it does in Chrome.
     */
    val useWideViewPort: Boolean = true,
    /** Second half of the viewport fix above; the two are only correct together. */
    val loadWithOverviewMode: Boolean = true,
    /** Present the active device's UA from the very first request. */
    val userAgentString: String,
) {
    companion object {

        /**
         * The UA a device should present: a preset's spoofed UA, or the cleaned real UA for
         * "This device". A native profile's own [DeviceProfile.userAgent] is empty by construction,
         * so this is the only path that produces one.
         */
        fun userAgentFor(device: DeviceProfile, realUserAgent: String): String =
            if (device.native) NativeIdentity.reduceUserAgent(realUserAgent) else device.userAgent

        /**
         * The matrix the browser always uses, differing only in which device is being presented.
         * [realUserAgent] is the untouched WebView UA captured from
         * `WebSettings.getDefaultUserAgent`; it is only consulted for native profiles.
         */
        fun hardened(device: DeviceProfile, realUserAgent: String): BrowserSettingsSpec =
            BrowserSettingsSpec(userAgentString = userAgentFor(device, realUserAgent))
    }
}
