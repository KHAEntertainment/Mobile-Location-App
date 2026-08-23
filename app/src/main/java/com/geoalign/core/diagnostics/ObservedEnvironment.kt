package com.geoalign.core.diagnostics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a page running in the configured browser actually saw.
 *
 * Every field here was read by page JavaScript *after* the production document-start bundles had
 * been installed by `WebViewConfigurator`, which is the only evidence that means anything: the old
 * diagnostics screen described its own separately-configured WebView and could not have detected a
 * production regression if it tried.
 *
 * All fields are nullable with defaults. A WebView that installed no bundles still answers most of
 * them (with its own values), and one that failed mid-collection answers some — a missing field is a
 * fact about this device, not a parse error.
 */
@Serializable
data class ObservedEnvironment(
    /**
     * `navigator.geolocation.getCurrentPosition` is not `[native code]`, i.e. the environment bundle
     * replaced it. The one direct piece of evidence that the document-start script ran at all.
     */
    @SerialName("geolocationShimmed") val geolocationShimmed: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Double? = null,
    /** Why no position arrived, when none did. */
    val geolocationError: String? = null,
    val timezone: String? = null,
    val timezoneOffsetMinutes: Int? = null,
    val language: String? = null,
    val languages: List<String> = emptyList(),
    val userAgent: String? = null,
    val platform: String? = null,
    val userAgentDataPresent: Boolean = false,
    val userAgentDataPlatform: String? = null,
    val userAgentDataMobile: Boolean? = null,
    val screenWidth: Int? = null,
    val screenHeight: Int? = null,
    val devicePixelRatio: Double? = null,
    val maxTouchPoints: Int? = null,
    /** Set by the collector when it threw. */
    val error: String? = null,
)

/**
 * How the observation went. The report says something different in each case, and none of them is
 * "assume it worked" — a screen that renders an empty observation as a pass is the failure mode this
 * whole issue exists to remove.
 */
sealed interface ObservationOutcome {

    /** The WebView is configured and loading; no answer yet. */
    data object Pending : ObservationOutcome

    /**
     * Nothing was installed, so there is nothing to observe. Reached when the installed WebView has
     * no `DOCUMENT_START_SCRIPT` support — the browser is blocked in that state anyway.
     */
    data object NotInstalled : ObservationOutcome

    /** The collector could not be run or its answer could not be read. */
    data class Failed(val reason: String) : ObservationOutcome

    data class Completed(val environment: ObservedEnvironment) : ObservationOutcome
}

/**
 * Parses what `WebView.evaluateJavascript` handed back.
 *
 * `evaluateJavascript` delivers a *JSON-encoded* value, so a collector that returns a JSON string
 * arrives double-encoded (`"{\"timezone\":…}"`), and a collector that returned nothing arrives as
 * the four characters `null`. Both shapes are handled here, in pure code, because this is the exact
 * spot where an off-by-one unwrapping would silently produce an empty observation that the report
 * would then describe as a device with no virtual environment.
 */
object ObservedEnvironmentParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Null when [raw] carries no observation at all (not yet ready, or unreadable). */
    fun parse(raw: String?): ObservedEnvironment? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "null" || trimmed == "undefined") return null
        val payload = if (trimmed.startsWith("\"")) {
            runCatching { json.decodeFromString<String>(trimmed) }.getOrNull() ?: return null
        } else {
            trimmed
        }
        if (!payload.trimStart().startsWith("{")) return null
        return runCatching { json.decodeFromString<ObservedEnvironment>(payload) }.getOrNull()
    }
}
