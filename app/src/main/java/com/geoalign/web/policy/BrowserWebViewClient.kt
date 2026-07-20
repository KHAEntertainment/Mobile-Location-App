package com.geoalign.web.policy

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewClientCompat
import com.geoalign.core.net.LocalNetworkPolicy
import java.io.ByteArrayInputStream

/**
 * Production browser WebViewClient: enforces the local-network policy (spec §16) AND reports
 * navigation state so the toolbar can drive back/forward/refresh and the address bar (spec §10).
 * Self-contained (does not subclass the POC interceptor) to keep the two harnesses independent.
 */
class BrowserWebViewClient(
    private val onNav: (url: String?, canGoBack: Boolean, canGoForward: Boolean, loading: Boolean) -> Unit,
    private val onBlocked: (url: String, reason: String) -> Unit = { _, _ -> },
) : WebViewClientCompat() {

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val result = LocalNetworkPolicy.classifyHost(request.url.host)
        if (result.isBlocked) {
            onBlocked(request.url.toString(), result.reason)
            val body = "Blocked by GeoAlign local-network policy: ${result.reason}".toByteArray()
            return WebResourceResponse(
                "text/plain", "utf-8", 403, "Blocked",
                mapOf("X-GeoAlign-Blocked" to result.reason),
                ByteArrayInputStream(body),
            )
        }
        return null
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val result = LocalNetworkPolicy.classifyHost(request.url.host)
        if (result.isBlocked) {
            onBlocked(request.url.toString(), result.reason)
            return true // consume — do not navigate to a private destination
        }
        return false // let WebView load http(s) itself
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        onNav(url, view.canGoBack(), view.canGoForward(), true)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        onNav(url, view.canGoBack(), view.canGoForward(), false)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        onNav(url, view.canGoBack(), view.canGoForward(), false)
    }
}
