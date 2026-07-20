package com.geoalign.core.model

import kotlinx.serialization.Serializable

/**
 * A browser location profile (spec §9): the user-facing/editable environment the browser applies.
 * Defaults to a *stationary* position — speed 0, no heading, no altitude — and a plausible (not
 * falsely precise) accuracy. @Serializable so it persists via kotlinx.serialization to a file.
 */
@Serializable
data class LocationProfile(
    val id: String,
    val name: String,
    val countryCode: String? = null,
    val region: String? = null,
    val city: String? = null,
    val latitude: Double,
    val longitude: Double,
    /** Plausible horizontal accuracy in meters; IP estimates are coarse, not GPS-precise. */
    val accuracyMeters: Double = DEFAULT_ACCURACY_M,
    val altitudeMeters: Double? = null,
    val headingDegrees: Double? = null,
    val speedMetersPerSec: Double = 0.0,
    /** IANA timezone id. */
    val timezone: String,
    /** Primary BCP-47 locale, e.g. "en-GB". */
    val primaryLocale: String,
    /** Ordered navigator.languages list, e.g. ["en-GB","en"]. */
    val languages: List<String>,
    val measurementSystem: MeasurementSystem = MeasurementSystem.METRIC,
    val desktopMode: Boolean = false,
    val userAgentProfileId: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val generatedFromIp: Boolean = false,
    val sourceProvider: String? = null,
    val sourceApproxTimestampMillis: Long? = null,
) {
    companion object {
        const val DEFAULT_ACCURACY_M = 1500.0
    }
}

@Serializable
enum class MeasurementSystem { METRIC, IMPERIAL }
