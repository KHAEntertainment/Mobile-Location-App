package com.geoalign.core.browser

/** Everything the WebView's download listener knows about a download it is handing over. */
data class DownloadRequest(
    val url: String,
    val userAgent: String?,
    val contentDisposition: String?,
    val mimeType: String?,
)

/** Somewhere to hand an accepted download. Android backs this with `DownloadManager`. */
fun interface DownloadEnqueuer {
    fun enqueue(request: DownloadRequest)
}

/**
 * The download gate (spec §19). Sits between the WebView's download listener and the system
 * download manager so the accept/reject decision is a plain function rather than a closure inside
 * an `AndroidView` factory.
 *
 * Only http(s) is handed over. A `blob:`, `data:` or `content:` URL means nothing to
 * `DownloadManager`, and a `file:` URL is a request to copy something out of the app's own storage;
 * all of them are dropped rather than passed on.
 */
class DownloadCoordinator(private val enqueuer: DownloadEnqueuer) {

    /** Returns true if the download was handed to the system, false if it was refused. */
    fun onDownloadRequested(request: DownloadRequest): Boolean {
        if (!isDownloadable(request.url)) return false
        enqueuer.enqueue(request)
        return true
    }

    private fun isDownloadable(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }
}
