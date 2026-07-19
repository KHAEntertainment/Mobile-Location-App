package com.geoalign.web.policy

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewClientCompat
import com.geoalign.core.net.LocalNetworkPolicy
import java.io.ByteArrayInputStream

/**
 * POC 5 — live enforcement of [LocalNetworkPolicy] at the WebView network boundary.
 *
 * Every subresource and navigation request is classified by host; anything resolving to a
 * private / special-use destination is short-circuited with a synthetic 403 instead of being
 * dispatched. This is the WebView-interceptable portion of local-network isolation. Known gaps
 * (some WebSocket and pre-resolved connection paths, and public-hostname-resolves-to-private,
 * which needs resolved-address visibility we don't always get) are documented, not hidden.
 *
 * [onBlocked] lets the UI surface a count/log without the policy needing a UI dependency.
 */
class LocalNetworkInterceptor(
    private val onBlocked: (url: String, reason: String) -> Unit = { _, _ -> },
) : WebViewClientCompat() {

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val host = request.url.host
        val result = LocalNetworkPolicy.classifyHost(host)
        if (result.isBlocked) {
            onBlocked(request.url.toString(), result.reason)
            return blockedResponse(result.reason)
        }
        return null // allow — let WebView handle it normally
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val result = LocalNetworkPolicy.classifyHost(request.url.host)
        if (result.isBlocked) {
            onBlocked(request.url.toString(), result.reason)
            return true // consume — do not navigate to a private destination
        }
        return false
    }

    private fun blockedResponse(reason: String): WebResourceResponse {
        val body = "Blocked by GeoAlign local-network policy: $reason".toByteArray()
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            403,
            "Blocked",
            mapOf("X-GeoAlign-Blocked" to reason),
            ByteArrayInputStream(body),
        )
    }
}
