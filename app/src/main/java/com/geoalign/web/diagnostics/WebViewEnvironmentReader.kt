package com.geoalign.web.diagnostics

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import com.geoalign.core.diagnostics.ObservationOutcome
import com.geoalign.core.diagnostics.ObservedEnvironmentParser

/**
 * Reads back what a page sees inside an **already production-configured** WebView.
 *
 * This class deliberately configures nothing. It is handed a WebView that `WebViewConfigurator` has
 * already hardened, given a user-agent, given client hints and — crucially — already registered the
 * environment and device document-start bundles on. All it does is load a document into it and ask
 * the page what it can see. That division is the point of issue #8: the previous diagnostics screen
 * built its own WebView with its own settings and its own London bundle, so a regression in the real
 * browser could not have shown up there.
 *
 * The document is loaded with `loadDataWithBaseURL` rather than from `file:///android_asset/`,
 * because the production settings matrix sets `allowFileAccess = false` — the asset page the POC
 * used is unreachable from a correctly configured browser, which is itself worth knowing.
 */
class WebViewEnvironmentReader(
    private val collectorScript: String,
    private val timeoutMillis: Long = 10_000L,
    private val pollIntervalMillis: Long = 200L,
) {

    /**
     * Origin the diagnostics document is loaded under. A reserved-by-RFC `.invalid` host: it can
     * never resolve, so nothing here can reach the network, and the document is still a secure
     * context so the page behaves the way an https site would.
     */
    private val baseUrl = "https://diagnostics.geoalign.invalid/"

    private val document = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">" +
        "<title>GeoAlign diagnostics</title></head><body></body></html>"

    /** Stops a run in flight. Held by the caller so a destroyed WebView is never polled again. */
    fun interface Run {
        fun cancel()
    }

    /**
     * Load the diagnostics document into [webView] and report what the page saw. [onOutcome] is
     * called exactly once, always on the main thread, with [ObservationOutcome.Completed] or
     * [ObservationOutcome.Failed] — never left hanging, because a diagnostics screen that spins
     * forever tells the user less than one that says it could not read the browser.
     *
     * The returned [Run] must be cancelled before the WebView is destroyed: the polling loop is
     * posted to the main looper, and a poll that lands on a destroyed WebView is a crash on the
     * screen a user opens *because* something is already wrong.
     */
    fun read(webView: WebView, onOutcome: (ObservationOutcome) -> Unit): Run {
        val handler = Handler(Looper.getMainLooper())
        var finished = false

        fun stop() {
            finished = true
            handler.removeCallbacksAndMessages(null)
        }

        fun deliver(outcome: ObservationOutcome) {
            if (finished) return
            stop()
            onOutcome(outcome)
        }

        fun poll(attempt: Int) {
            if (finished) return
            webView.evaluateJavascript(READ_RESULT) { raw ->
                val environment = ObservedEnvironmentParser.parse(raw)
                when {
                    environment != null -> deliver(ObservationOutcome.Completed(environment))
                    attempt >= MAX_POLLS ->
                        deliver(ObservationOutcome.Failed("the page never reported its environment"))
                    else -> handler.postDelayed({ poll(attempt + 1) }, pollIntervalMillis)
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                if (finished) return
                // The collector runs as page script, i.e. after the document-start bundles.
                view.evaluateJavascript(collectorScript) { poll(attempt = 0) }
            }
        }

        // A hard stop, so a WebView that never finishes loading still produces a report.
        handler.postDelayed(
            { deliver(ObservationOutcome.Failed("no answer within ${timeoutMillis / 1000}s")) },
            timeoutMillis,
        )

        webView.loadDataWithBaseURL(baseUrl, document, "text/html", "utf-8", null)
        return Run { stop() }
    }

    private companion object {
        /** Evaluates to the collector's JSON string, or null while it is still waiting. */
        const val READ_RESULT = "window.__geoalignDiagnostics || null"
        const val MAX_POLLS = 40
    }
}

/** Loads the collector from assets, the way the environment and device bundles are loaded. */
object DiagnosticsCollectorScript {
    fun fromAssets(context: Context): String =
        context.assets.open("diagnostics_collect.js").bufferedReader().use { it.readText() }
}
