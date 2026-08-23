package com.geoalign.core.alignment

import com.geoalign.core.model.IpGeolocation
import com.geoalign.core.model.LocationProfile
import java.text.Normalizer
import java.util.Locale

/**
 * Answers the question the readiness screen exists to answer: does the saved browser profile
 * actually match the exit we are currently browsing through?
 *
 * This did not exist before. Readiness was previously the conjunction of "VPN up", "IP resolved"
 * and "a profile exists" — nothing ever compared the profile against the live estimate. A profile
 * captured from a stale observation, or left over from a previous VPN exit, reported "Ready" while
 * pointing somewhere else entirely.
 *
 * Pure and fully injected ([nowMillis], [thresholds]) so it is deterministic and unit-testable.
 */
enum class AlignmentVerdict {
    /** Nothing saved yet. */
    NO_PROFILE,

    /** We have a profile but no live estimate to compare it against. Never claim alignment here. */
    UNKNOWN,

    DRIFTED_COUNTRY,
    DRIFTED_CITY,
    DRIFTED_DISTANCE,

    /** Labels agree, but the profile was minted from an estimate already known to be stale. */
    STALE_CAPTURE,

    ALIGNED,
}

/** Which comparison actually carried an [AlignmentVerdict.ALIGNED] result. */
enum class MatchScope { CITY, COUNTRY }

enum class AlignmentReason {
    NO_PROFILE,
    EXIT_UNKNOWN,
    EXIT_NO_COORDINATES,
    COUNTRY_MISMATCH,
    CITY_MISMATCH,
    COORDINATES_FAR,
    CITY_UNVERIFIED,
    COUNTRY_UNVERIFIED,
    PROFILE_CAPTURED_FROM_STALE_ESTIMATE,
    PROFILE_MANUAL,
}

data class AlignmentResult(
    val verdict: AlignmentVerdict,
    /** Non-null only when [verdict] is [AlignmentVerdict.ALIGNED]. */
    val matchedOn: MatchScope?,
    val reasons: List<AlignmentReason>,
    val profileCountry: String?,
    val profileCity: String?,
    val exitCountry: String?,
    val exitCity: String?,
    /** Null when either side lacks coordinates. */
    val distanceKm: Double?,
    /** How stale the estimate was when the profile was written. Null if provenance is absent. */
    val captureLagMillis: Long?,
) {
    val isAligned: Boolean get() = verdict == AlignmentVerdict.ALIGNED
    fun hasReason(r: AlignmentReason): Boolean = reasons.contains(r)
}

/**
 * @param driftDistanceKm how far the profile may sit from the IP estimate before it counts as
 *   drift. IP geolocation resolves to a city/ISP centroid, so this has to tolerate real slack.
 * @param staleCaptureMillis how old the estimate behind a profile may be at the moment it was
 *   written. The bug this guards against wrote a profile from a 759-second-old observation.
 */
data class AlignmentThresholds(
    val driftDistanceKm: Double = 150.0,
    val staleCaptureMillis: Long = 120_000L,
) {
    companion object { val DEFAULT = AlignmentThresholds() }
}

object AlignmentChecker {

    fun check(
        profile: LocationProfile?,
        exit: IpGeolocation?,
        nowMillis: Long,
        thresholds: AlignmentThresholds = AlignmentThresholds.DEFAULT,
    ): AlignmentResult {
        if (profile == null) {
            return AlignmentResult(
                verdict = AlignmentVerdict.NO_PROFILE,
                matchedOn = null,
                reasons = listOf(AlignmentReason.NO_PROFILE),
                profileCountry = null, profileCity = null,
                exitCountry = null, exitCity = null,
                distanceKm = null, captureLagMillis = null,
            )
        }

        val reasons = mutableListOf<AlignmentReason>()

        val pCountry = normalizeCountry(profile.countryCode)
        val pCity = normalizeCity(profile.city)
        val eCountry = normalizeCountry(exit?.countryCode)
        val eCity = normalizeCity(exit?.city)

        val captureLag = profile.sourceApproxTimestampMillis
            ?.let { profile.updatedAtMillis - it }
            ?.coerceAtLeast(0L)

        // A manually-entered profile is a legitimate choice, not an error — but a manual profile
        // far from the exit is still a contradiction a site can see, so it never suppresses a
        // verdict. It only lets the copy explain itself accurately.
        if (!profile.generatedFromIp) reasons += AlignmentReason.PROFILE_MANUAL

        fun result(
            verdict: AlignmentVerdict,
            matchedOn: MatchScope? = null,
            distanceKm: Double? = null,
        ) = AlignmentResult(
            verdict = verdict,
            matchedOn = matchedOn,
            reasons = reasons.distinct(),
            profileCountry = pCountry, profileCity = profile.city?.trim()?.ifBlank { null },
            exitCountry = eCountry, exitCity = exit?.city?.trim()?.ifBlank { null },
            distanceKm = distanceKm, captureLagMillis = captureLag,
        )

        if (exit == null) {
            reasons += AlignmentReason.EXIT_UNKNOWN
            return result(AlignmentVerdict.UNKNOWN)
        }

        // 1. Country. The coarsest signal and the one sites act on most.
        if (pCountry != null && eCountry != null) {
            if (pCountry != eCountry) {
                reasons += AlignmentReason.COUNTRY_MISMATCH
                return result(AlignmentVerdict.DRIFTED_COUNTRY, distanceKm = distance(profile, exit))
            }
        } else {
            reasons += AlignmentReason.COUNTRY_UNVERIFIED
        }

        // 2. City. Absent on either side is unverified, never a mismatch — free providers vary in
        //    coverage and a missing label must not be reported as a contradiction.
        var cityCompared = false
        if (pCity != null && eCity != null) {
            cityCompared = true
            if (pCity != eCity) {
                reasons += AlignmentReason.CITY_MISMATCH
                return result(AlignmentVerdict.DRIFTED_CITY, distanceKm = distance(profile, exit))
            }
        } else {
            reasons += AlignmentReason.CITY_UNVERIFIED
        }

        // 3. Coordinates. Skipped entirely when the estimate has none, so it cannot false-positive.
        val km = distance(profile, exit)
        if (km == null) {
            reasons += AlignmentReason.EXIT_NO_COORDINATES
        } else if (km > thresholds.driftDistanceKm) {
            reasons += AlignmentReason.COORDINATES_FAR
            return result(AlignmentVerdict.DRIFTED_DISTANCE, distanceKm = km)
        }

        // 4. Provenance. Labels agreeing is not enough if the observation behind them was already
        //    stale when written — they may agree by luck. This is the 759s bug made visible.
        if (captureLag != null && captureLag > thresholds.staleCaptureMillis) {
            reasons += AlignmentReason.PROFILE_CAPTURED_FROM_STALE_ESTIMATE
            return result(AlignmentVerdict.STALE_CAPTURE, distanceKm = km)
        }

        return result(
            AlignmentVerdict.ALIGNED,
            matchedOn = if (cityCompared) MatchScope.CITY else MatchScope.COUNTRY,
            distanceKm = km,
        )
    }

    private fun distance(profile: LocationProfile, exit: IpGeolocation): Double? {
        val lat = exit.latitude ?: return null
        val lon = exit.longitude ?: return null
        return GeoDistance.haversineKm(profile.latitude, profile.longitude, lat, lon)
    }

    private fun normalizeCountry(raw: String?): String? =
        raw?.trim()?.uppercase(Locale.ROOT)?.ifBlank { null }

    /**
     * Case-folded, accent-stripped, whitespace-collapsed, so "São Paulo" and "SAO PAULO" are the
     * same city. Providers disagree on diacritics constantly; treating that as drift would cry
     * wolf on a correctly aligned profile.
     */
    private fun normalizeCity(raw: String?): String? {
        val t = raw?.trim()?.ifBlank { null } ?: return null
        val decomposed = Normalizer.normalize(t, Normalizer.Form.NFD)
        val stripped = decomposed.replace(Regex("\\p{Mn}+"), "")
        return stripped.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    }
}
