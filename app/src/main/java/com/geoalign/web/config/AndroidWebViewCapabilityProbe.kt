package com.geoalign.web.config

import android.content.Context
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * The one place in the browser that asks the platform what the installed WebView supports.
 *
 * Keeping `WebViewFeature.isFeatureSupported` and `WebViewCompat.getCurrentWebViewPackage` behind
 * this single call is what lets every other surface — the configurator, the capability gate,
 * diagnostics — agree on one set of answers instead of each re-deriving its own, and is why those
 * surfaces stay unit-testable.
 */
class AndroidWebViewCapabilityProbe(private val context: Context) : WebViewCapabilityProbe {

    override fun probe(): WebViewCapabilities {
        // Null when no WebView implementation can be resolved — a bare emulator image, or the
        // system package disabled. The rest of the app must survive that, so it stays nullable
        // rather than being defaulted to a fictional "unknown" build.
        val installed = runCatching { WebViewCompat.getCurrentWebViewPackage(context) }.getOrNull()
        return WebViewCapabilities(
            documentStartScript = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT),
            userAgentMetadata = WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA),
            safeBrowsing = WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE),
            serviceWorkerControl = WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE),
            errorDescription = WebViewFeature.isFeatureSupported(
                WebViewFeature.WEB_RESOURCE_ERROR_GET_DESCRIPTION,
            ),
            packageName = installed?.packageName,
            packageVersion = installed?.versionName,
        )
    }
}
