package com.geoalign.data.profiles

import com.geoalign.core.i18n.LocaleSuggester
import com.geoalign.core.model.IpGeolocation
import com.geoalign.core.model.LocationProfile

/**
 * Builds a LocationProfile from an IpGeolocation estimate (spec §9 "Match Browser to VPN").
 * Pure: `id`, `now`, and the user's content language are injected so the result is deterministic
 * and unit-testable. Applies the stationary defaults required by the spec.
 */
object ProfileFactory {

    /** Returns null when the estimate lacks coordinates — we never fabricate a position. */
    fun fromGeolocation(
        geo: IpGeolocation,
        contentLanguage: String,
        id: String,
        nowMillis: Long,
        name: String? = null,
    ): LocationProfile? {
        val lat = geo.latitude ?: return null
        val lng = geo.longitude ?: return null
        val suggestion = LocaleSuggester.suggest(contentLanguage, geo.countryCode)

        return LocationProfile(
            id = id,
            name = name ?: geo.city ?: geo.countryName ?: "VPN exit",
            countryCode = geo.countryCode,
            region = geo.region,
            city = geo.city,
            latitude = lat,
            longitude = lng,
            accuracyMeters = LocationProfile.DEFAULT_ACCURACY_M, // plausible, not falsely precise
            altitudeMeters = null,   // stationary defaults (spec §9)
            headingDegrees = null,
            speedMetersPerSec = 0.0,
            timezone = geo.timezone ?: "UTC",
            primaryLocale = suggestion.regionalLocale,
            languages = suggestion.languages,
            measurementSystem = suggestion.measurementSystem,
            desktopMode = false,
            createdAtMillis = nowMillis,
            updatedAtMillis = nowMillis,
            generatedFromIp = true,
            sourceProvider = geo.providerName,
            sourceApproxTimestampMillis = geo.timestampMillis,
        )
    }
}
