package com.geoalign.core.net

import java.net.URLEncoder

/**
 * Turns raw address-bar input into a loadable URL (spec §10). HTTPS-first: a bare host gets
 * https://; input that isn't host-like becomes a search query. Pure and unit-tested.
 */
object UrlNormalizer {

    private const val SEARCH_PREFIX = "https://duckduckgo.com/?q="

    /** Returns a URL string, or null for blank input. */
    fun normalize(raw: String?): String? {
        val input = raw?.trim() ?: return null
        if (input.isEmpty()) return null

        // Already has an explicit scheme — keep it (the HTTP warning flow handles cleartext later).
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(input)) {
            return input
        }

        // Host-like: no spaces AND (contains a dot with a non-empty TLD, or is localhost).
        val looksLikeHost = !input.contains(' ') &&
            (input == "localhost" || input.startsWith("localhost:") ||
                Regex("^[^/\\s]+\\.[^/\\s.]{2,}(?:[:/].*)?$").containsMatchIn(input))

        return if (looksLikeHost) {
            "https://$input"
        } else {
            SEARCH_PREFIX + URLEncoder.encode(input, "UTF-8")
        }
    }
}
