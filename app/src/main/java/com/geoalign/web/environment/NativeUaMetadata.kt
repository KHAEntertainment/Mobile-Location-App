package com.geoalign.web.environment

import android.os.Build
import android.webkit.WebSettings
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.geoalign.core.device.Brand
import com.geoalign.core.device.NativeIdentity

/**
 * Applies "This device" mode's UA Client Hints to a WebView (spec §14).
 *
 * This is the half of native mode that JavaScript cannot fix: [WebSettingsCompat.setUserAgentMetadata]
 * feeds both `navigator.userAgentData` *and* the `Sec-CH-UA` request headers, so it closes the
 * long-standing gap where the client-side identity was corrected but the HTTP headers still carried
 * the WebView's own — which announces the brand `Android WebView` outright.
 *
 * The WebView's existing metadata is used as the base so the GREASE entry, major versions and the
 * mobile flag stay whatever the installed WebView build reports; only the brand name, full
 * versions, platform version and model are corrected. Pure derivation lives in [NativeIdentity].
 */
object NativeUaMetadata {

    /**
     * The WebView's untouched metadata, kept per-WebSettings so switching away from native mode can
     * restore it. Without this, a spoof preset selected after native mode would keep advertising the
     * real device's model and OS version in its request headers.
     */
    private val originals = java.util.WeakHashMap<WebSettings, UserAgentMetadata>()

    /**
     * Apply native-mode client hints when [native], otherwise restore whatever the WebView had
     * before native mode first touched it. Returns true if the settings were changed.
     */
    fun applyOrRestore(settings: WebSettings, realUa: String, native: Boolean): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return false
        if (!native) {
            val original = originals.remove(settings) ?: return false
            WebSettingsCompat.setUserAgentMetadata(settings, original)
            return true
        }
        return apply(settings, realUa)
    }

    /** Returns true if metadata was applied; false if unsupported or the UA had no Chrome version. */
    fun apply(settings: WebSettings, realUa: String): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return false
        val fullVersion = NativeIdentity.chromeFullVersion(realUa) ?: return false

        val defaults = WebSettingsCompat.getUserAgentMetadata(settings)
        originals.putIfAbsent(settings, defaults)
        val corrected = NativeIdentity.chromeBrands(
            defaults.brandVersionList.map { Brand(it.brand, it.majorVersion, it.fullVersion) },
            fullVersion,
        )

        val metadata = UserAgentMetadata.Builder(defaults)
            .setBrandVersionList(
                corrected.map {
                    UserAgentMetadata.BrandVersion.Builder()
                        .setBrand(it.brand)
                        .setMajorVersion(it.major)
                        .setFullVersion(it.full)
                        .build()
                },
            )
            .setFullVersion(fullVersion)
            .setPlatform("Android")
            .setPlatformVersion(NativeIdentity.platformVersion(Build.VERSION.RELEASE.orEmpty()))
            .setModel(Build.MODEL.orEmpty())
            .build()

        WebSettingsCompat.setUserAgentMetadata(settings, metadata)
        return true
    }
}
