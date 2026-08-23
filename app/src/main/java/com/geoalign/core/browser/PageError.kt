package com.geoalign.core.browser

/**
 * A main-frame load failure the user has to be told about, with the two recoveries that are always
 * available: retry the same URL, or hand it to another browser.
 *
 * Only main-frame failures reach here. A tracking pixel that 404s, a font that times out or a
 * blocked private-network subresource must not replace the page the user is reading — see
 * [LoadErrorPolicy].
 */
data class PageError(
    val url: String,
    val kind: Kind,
    /** HTTP status for [Kind.HTTP], null for a network-level failure. */
    val statusCode: Int? = null,
    /** Platform description where one was available; never required to render the page. */
    val description: String? = null,
) {
    enum class Kind { NETWORK, HTTP }

    val headline: String
        get() = when (kind) {
            Kind.NETWORK -> "This page couldn't be loaded"
            Kind.HTTP -> "This site returned an error"
        }

    val detail: String
        get() = when (kind) {
            Kind.NETWORK ->
                "GeoAlign couldn't reach $host. Check that your VPN is still connected, then try again."
            Kind.HTTP ->
                "$host answered with HTTP ${statusCode ?: 0}." +
                    description?.let { " $it" }.orEmpty()
        }

    /** Host for display, falling back to the whole URL when there isn't one to extract. */
    val host: String
        get() = url.substringAfter("://", url).substringBefore('/').ifBlank { url }

    /**
     * Whether "Open externally" makes sense. Only http(s) can be handed to another browser; a
     * failure on some other scheme would just fail there too, or worse, launch something unrelated.
     */
    val canOpenExternally: Boolean
        get() = url.startsWith("http://") || url.startsWith("https://")
}

/**
 * Decides which load failures deserve an error page (spec §10).
 *
 * The single rule that matters: **main frame only**. `onReceivedError` and `onReceivedHttpError`
 * both fire per resource, so a page that loads perfectly well can emit a dozen subframe failures;
 * acting on those would flash an error over a working page.
 */
object LoadErrorPolicy {

    /** Network-level failure (DNS, connection refused, timeout, blocked scheme…). */
    fun forNetworkFailure(
        isForMainFrame: Boolean,
        url: String,
        description: String? = null,
    ): PageError? {
        if (!isForMainFrame) return null
        if (url.isBlank()) return null
        return PageError(url = url, kind = PageError.Kind.NETWORK, description = description?.ifBlank { null })
    }

    /**
     * HTTP response failure. Only 4xx/5xx count: a redirect or an informational status is not an
     * error, and a server that returns 200 with an apology page is not ours to second-guess.
     */
    fun forHttpStatus(
        isForMainFrame: Boolean,
        url: String,
        statusCode: Int,
        description: String? = null,
    ): PageError? {
        if (!isForMainFrame) return null
        if (url.isBlank()) return null
        if (statusCode < 400) return null
        return PageError(
            url = url,
            kind = PageError.Kind.HTTP,
            statusCode = statusCode,
            description = description?.ifBlank { null },
        )
    }
}
