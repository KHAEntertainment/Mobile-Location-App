package com.geoalign.core.device

/**
 * Derives the browser-visible identity for "This device" ([DeviceProfile.native]) mode so that it
 * matches what **real Chrome on the same hardware** presents (spec §14).
 *
 * Why this exists: stripping the `; wv` marker off the WebView's own User-Agent is not enough.
 * Real Chrome has shipped a *reduced* UA since UA Reduction — it reports a frozen `Android 10; K`
 * and a `MAJOR.0.0.0` version, never the true OS version, model, or patch version. A UA carrying
 * the real `Android 16; SM-F956U1` and a full `151.0.7922.169` is therefore both more identifying
 * than real Chrome and shaped like no Chrome that currently exists.
 *
 * The matching Client-Hints half is applied by the Android layer via
 * `WebSettingsCompat.setUserAgentMetadata`, which drives both `navigator.userAgentData` and the
 * `Sec-CH-UA` request headers. The WebView's own hints cannot simply be passed through: they
 * announce the brand `Android WebView` outright.
 *
 * Honesty note (spec §1): this changes what pages *see*, not what the hardware *is*. It aligns the
 * embedded browser with the device's real Chrome; it is not an anonymity guarantee.
 */
object NativeIdentity {

    /** Values Chrome freezes into every reduced Android User-Agent. */
    const val FROZEN_ANDROID_VERSION = "10"
    const val FROZEN_MODEL = "K"

    const val WEBVIEW_BRAND = "Android WebView"
    const val CHROME_BRAND = "Google Chrome"
    const val CHROMIUM_BRAND = "Chromium"

    private val CHROME_VERSION = Regex("""Chrome/(\d+)(?:\.(\d+)\.(\d+)\.(\d+))?""")

    /** Major Chrome version from a WebView UA, or null if absent. */
    fun chromeMajor(realUa: String): String? =
        CHROME_VERSION.find(realUa)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }

    /** Full `major.minor.build.patch` Chrome version from a WebView UA, or null if not complete. */
    fun chromeFullVersion(realUa: String): String? {
        val g = CHROME_VERSION.find(realUa)?.groupValues ?: return null
        if (g.size < 5 || g[2].isEmpty() || g[3].isEmpty() || g[4].isEmpty()) return null
        return "${g[1]}.${g[2]}.${g[3]}.${g[4]}"
    }

    /**
     * Rewrite the WebView's real UA into Chrome's reduced form. The `Mobile` token is preserved
     * from the source UA, because Chrome emits it on phone-sized layouts and omits it on
     * tablet-sized ones (an unfolded foldable reads as the latter) — mirroring the WebView keeps
     * that decision consistent with the window we are actually rendering into.
     *
     * Falls back to [stripWebViewMarkers] if no Chrome version can be parsed, so an unrecognised
     * UA degrades to the previous behaviour rather than to a fabricated one.
     */
    fun reduceUserAgent(realUa: String): String {
        val major = chromeMajor(realUa) ?: return stripWebViewMarkers(realUa)
        val mobile = if (realUa.contains(" Mobile ")) "Mobile " else ""
        return "Mozilla/5.0 (Linux; Android $FROZEN_ANDROID_VERSION; $FROZEN_MODEL) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 ${mobile}Safari/537.36"
    }

    /** Legacy de-WebView-ification, kept as the fallback path for unparseable user agents. */
    fun stripWebViewMarkers(ua: String): String = ua
        .replace("; wv)", ")")
        .replace(Regex(" Build/[A-Za-z0-9._-]+"), "")
        .replace("Version/4.0 ", "")

    /**
     * UA-CH `platformVersion` for an Android release string. Chrome reports three components, so
     * `"16"` becomes `"16.0.0"` and `"16.1"` becomes `"16.1.0"`.
     */
    fun platformVersion(androidRelease: String): String {
        val parts = androidRelease.split(".").filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "0.0.0"
        return (parts + listOf("0", "0")).take(3).joinToString(".")
    }

    /** True for Chrome's randomised GREASE brand entries (anything that is not a real engine brand). */
    fun isGreaseBrand(brand: String): Boolean =
        brand != WEBVIEW_BRAND && brand != CHROME_BRAND && brand != CHROMIUM_BRAND

    /**
     * Turn the WebView's *own* brand list into the equivalent Chrome list: rename the
     * `Android WebView` entry to `Google Chrome` and attach full versions. Reusing the WebView's
     * list rather than hardcoding one keeps the GREASE entry and the major versions correct for
     * whatever WebView build is installed, instead of drifting as Chrome updates.
     */
    fun chromeBrands(defaults: List<Brand>, chromeFullVersion: String): List<Brand> =
        defaults.map { b ->
            val name = if (b.brand == WEBVIEW_BRAND) CHROME_BRAND else b.brand
            val full = if (isGreaseBrand(b.brand)) "${b.major}.0.0.0" else chromeFullVersion
            Brand(brand = name, major = b.major, full = full)
        }
}
