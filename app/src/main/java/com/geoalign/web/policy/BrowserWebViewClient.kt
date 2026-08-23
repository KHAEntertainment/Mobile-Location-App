package com.geoalign.web.policy

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebViewClientCompat
import com.geoalign.core.net.ExternalSchemePolicy
import com.geoalign.core.net.LocalNetworkPolicy
import java.io.ByteArrayInputStream

/**
 * Production browser WebViewClient (spec §10, §16, §19). Enforces the local-network policy, routes
 * non-web schemes (safe ones to the system, dangerous ones blocked), reports navigation state for
 * the toolbar, refuses invalid TLS by default, surfaces main-frame load failures, and survives a
 * dead renderer. Self-contained (does not subclass the POC interceptor) to keep the two harnesses
 * independent.
 */
class BrowserWebViewClient(
    private val onNav: (url: String?, canGoBack: Boolean, canGoForward: Boolean, loading: Boolean) -> Unit,
    private val onBlocked: (url: String, reason: String) -> Unit = { _, _ -> },
    private val onExternal: (url: String) -> Unit = {},
    private val onSslError: (url: String) -> Unit = {},
    /** Network-level failure. `isForMainFrame` decides whether it reaches the user. */
    private val onLoadError: (isForMainFrame: Boolean, url: String, description: String?) -> Unit =
        { _, _, _ -> },
    /** 4xx/5xx response. `isForMainFrame` decides whether it reaches the user. */
    private val onHttpError: (isForMainFrame: Boolean, url: String, statusCode: Int, reason: String?) -> Unit =
        { _, _, _, _ -> },
    /** The renderer process died. Must produce a recovery experience, not a blank view. */
    private val onRendererGone: (didCrash: Boolean) -> Unit = {},
    /**
     * Offers an in-page link to the alignment navigation hold (issue #6). Returns true when it was
     * queued instead of followed, which is consumed here so the WebView does not navigate.
     *
     * Defaults to "never held" so a caller that does not wire it gets ordinary browsing rather than
     * a silently frozen one. The decision itself is `BrowserSessionController`'s; this only asks.
     */
    private val onHoldNavigation: (url: String) -> Boolean = { false },
    /**
     * `WebResourceErrorCompat.getDescription` is available on the installed WebView, from the one
     * capability probe. Defaults false so a caller that does not pass it gets the safe answer rather
     * than a description this WebView cannot produce.
     */
    private val canReadErrorDescription: Boolean = false,
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
        val uri = request.url
        return when (ExternalSchemePolicy.classify(uri.scheme)) {
            ExternalSchemePolicy.Action.LOAD_IN_WEBVIEW -> {
                val result = LocalNetworkPolicy.classifyHost(uri.host)
                if (result.isBlocked) {
                    onBlocked(uri.toString(), result.reason)
                    true // consume — do not navigate to a private destination
                } else {
                    // Asked *after* the local-network policy: a blocked private destination is
                    // refused outright and must never be queued for later.
                    onHoldNavigation(uri.toString()) // true consumes and queues; false loads now
                }
            }
            ExternalSchemePolicy.Action.OPEN_EXTERNALLY -> {
                onExternal(uri.toString())
                true // handed to the system; WebView should not try to load it
            }
            ExternalSchemePolicy.Action.BLOCK -> {
                onBlocked(uri.toString(), "blocked-scheme:${uri.scheme}")
                true
            }
        }
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        // Never proceed through a TLS failure. HTTPS-only is a core promise (spec §21); a bad
        // certificate could be interception, so cancel and surface it rather than offering "proceed".
        handler.cancel()
        onSslError(error.url ?: view.url ?: "this site")
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

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceErrorCompat,
    ) {
        // `isForMainFrame` is the whole filter: this fires once per failing resource, so a page that
        // renders perfectly well can emit a handful of subframe failures, and turning any of those
        // into an error page would blank out a working page.
        // getDescription throws on a WebView that does not implement it, so it is read only when the
        // single capability probe said it is there. This used to be a runCatching, which was the
        // last place in the browser that answered a capability question at the point of use.
        val description = if (canReadErrorDescription) error.description?.toString() else null
        onLoadError(request.isForMainFrame, request.url.toString(), description)
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        onHttpError(
            request.isForMainFrame,
            request.url.toString(),
            errorResponse.statusCode,
            errorResponse.reasonPhrase,
        )
    }

    /**
     * The renderer for this WebView died — crashed, or was reclaimed by Android under memory
     * pressure.
     *
     * **Always returns true.** Returning false hands the dead renderer back to the framework, whose
     * only remaining response is to kill this app's process: every tab gone, the app simply
     * disappears. Returning true claims the event and obliges the app to stop using this WebView,
     * which is what [onRendererGone] arranges.
     */
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onRendererGone(detail.didCrash())
        return true
    }
}
