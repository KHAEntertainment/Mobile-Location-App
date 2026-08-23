package com.geoalign.core.alignment

import com.geoalign.core.model.IpGeolocation
import com.geoalign.core.model.LocationProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlignmentCheckerTest {

    private val now = 1_700_000_000_000L

    private fun profile(
        country: String? = "NG",
        city: String? = "Lagos",
        lat: Double = 6.5244,
        lon: Double = 3.3792,
        generatedFromIp: Boolean = true,
        createdAt: Long = now,
        updatedAt: Long = now,
        sourceAt: Long? = now,
    ) = LocationProfile(
        id = "p1",
        name = city ?: "profile",
        countryCode = country,
        city = city,
        latitude = lat,
        longitude = lon,
        timezone = "Africa/Lagos",
        primaryLocale = "en-NG",
        languages = listOf("en-NG", "en"),
        createdAtMillis = createdAt,
        updatedAtMillis = updatedAt,
        generatedFromIp = generatedFromIp,
        sourceApproxTimestampMillis = sourceAt,
    )

    private fun exit(
        country: String? = "NG",
        city: String? = "Lagos",
        lat: Double? = 6.5244,
        lon: Double? = 3.3792,
    ) = IpGeolocation(
        ip = "203.0.113.42",
        countryCode = country,
        countryName = "Nigeria",
        city = city,
        latitude = lat,
        longitude = lon,
        providerName = "test",
        timestampMillis = now,
    )

    @Test fun noProfileIsNotAlignment() {
        val r = AlignmentChecker.check(null, exit(), now)
        assertEquals(AlignmentVerdict.NO_PROFILE, r.verdict)
        assertNull(r.matchedOn)
        assertFalse(r.isAligned)
    }

    /**
     * Without a live estimate there is nothing to compare against, so the honest answer is
     * "unknown". The old screen's failure mode was reporting readiness in exactly this situation.
     */
    @Test fun missingExitIsUnknownNeverAligned() {
        val r = AlignmentChecker.check(profile(), null, now)
        assertEquals(AlignmentVerdict.UNKNOWN, r.verdict)
        assertTrue(r.hasReason(AlignmentReason.EXIT_UNKNOWN))
    }

    @Test fun exactMatchIsAlignedOnCity() {
        val r = AlignmentChecker.check(profile(), exit(), now)
        assertEquals(AlignmentVerdict.ALIGNED, r.verdict)
        assertEquals(MatchScope.CITY, r.matchedOn)
    }

    @Test fun diacriticsAndCaseDoNotCountAsDrift() {
        val p = profile(country = "br", city = "Sao Paulo", lat = -23.55, lon = -46.63)
        val e = exit(country = "BR", city = "São Paulo", lat = -23.55, lon = -46.63)
        assertEquals(AlignmentVerdict.ALIGNED, AlignmentChecker.check(p, e, now).verdict)
    }

    @Test fun missingExitCityFallsBackToCountryMatch() {
        val r = AlignmentChecker.check(profile(), exit(city = null), now)
        assertEquals(AlignmentVerdict.ALIGNED, r.verdict)
        assertEquals(MatchScope.COUNTRY, r.matchedOn)
        assertTrue(r.hasReason(AlignmentReason.CITY_UNVERIFIED))
    }

    /**
     * The scenario behind commit 3d3108b: the profile was written from the previous VPN exit
     * (Singapore) while the live exit had moved to Nigeria. The old screen showed "Ready".
     */
    @Test fun profileFromAPreviousExitIsCountryDrift() {
        val p = profile(country = "SG", city = "Singapore", lat = 1.3521, lon = 103.8198)
        val r = AlignmentChecker.check(p, exit(), now)
        assertEquals(AlignmentVerdict.DRIFTED_COUNTRY, r.verdict)
        assertTrue(r.hasReason(AlignmentReason.COUNTRY_MISMATCH))
        assertEquals("SG", r.profileCountry)
        assertEquals("NG", r.exitCountry)
        assertFalse(r.isAligned)
    }

    @Test fun sameCountryDifferentCityIsCityDrift() {
        val p = profile(city = "Abuja", lat = 9.0765, lon = 7.3986)
        val r = AlignmentChecker.check(p, exit(), now)
        assertEquals(AlignmentVerdict.DRIFTED_CITY, r.verdict)
    }

    @Test fun matchingLabelsButFarApartIsDistanceDrift() {
        // Same labels, coordinates ~900 km away.
        val p = profile(lat = 13.0, lon = 5.0)
        val r = AlignmentChecker.check(p, exit(), now)
        assertEquals(AlignmentVerdict.DRIFTED_DISTANCE, r.verdict)
        assertTrue(r.hasReason(AlignmentReason.COORDINATES_FAR))
    }

    /** IP geolocation resolves to a centroid; modest separation must not cry wolf. */
    @Test fun nearbyCoordinatesAreStillAligned() {
        val p = profile(lat = 6.80, lon = 3.50)  // ~35 km from the exit
        assertEquals(AlignmentVerdict.ALIGNED, AlignmentChecker.check(p, exit(), now).verdict)
    }

    /**
     * The 759-second bug. Every label agrees, so a label-only check would report alignment — but
     * the estimate was already 759s old when the profile was written, so the agreement is luck.
     */
    @Test fun staleCaptureIsNotAlignedEvenWhenLabelsMatch() {
        val p = profile(createdAt = now, sourceAt = now - 759_000L)
        val r = AlignmentChecker.check(p, exit(), now)
        assertEquals(AlignmentVerdict.STALE_CAPTURE, r.verdict)
        assertTrue(r.hasReason(AlignmentReason.PROFILE_CAPTURED_FROM_STALE_ESTIMATE))
        assertEquals(759_000L, r.captureLagMillis)
        assertFalse(r.isAligned)
    }

    /**
     * Editing a profile bumps updatedAtMillis but does not re-capture the estimate. Measuring the
     * lag against updatedAtMillis would report every edited profile as stale within two minutes of
     * being saved.
     */
    @Test fun editingAProfileDoesNotMakeItLookStale() {
        val p = profile(createdAt = now, updatedAt = now + 489_000L, sourceAt = now)
        val r = AlignmentChecker.check(p, exit(), now + 489_000L)
        assertEquals(AlignmentVerdict.ALIGNED, r.verdict)
        assertEquals(0L, r.captureLagMillis)
    }

    @Test fun staleCaptureBoundaryIsExclusive() {
        val at = AlignmentChecker.check(profile(sourceAt = now - 120_000L), exit(), now)
        assertEquals(AlignmentVerdict.ALIGNED, at.verdict)
        val past = AlignmentChecker.check(profile(sourceAt = now - 120_001L), exit(), now)
        assertEquals(AlignmentVerdict.STALE_CAPTURE, past.verdict)
    }

    @Test fun absentProvenanceMakesNoStaleClaim() {
        val r = AlignmentChecker.check(profile(sourceAt = null), exit(), now)
        assertEquals(AlignmentVerdict.ALIGNED, r.verdict)
        assertNull(r.captureLagMillis)
    }

    @Test fun blankLabelsAreUnverifiedNotMismatched() {
        val r = AlignmentChecker.check(profile(country = "  ", city = ""), exit(), now)
        assertTrue(r.hasReason(AlignmentReason.COUNTRY_UNVERIFIED))
        assertTrue(r.hasReason(AlignmentReason.CITY_UNVERIFIED))
        assertFalse(r.hasReason(AlignmentReason.COUNTRY_MISMATCH))
        assertEquals(AlignmentVerdict.ALIGNED, r.verdict)
        // Nothing was compared, so nothing may be claimed as matched (issue #19).
        assertEquals(MatchScope.NONE, r.matchedOn)
    }

    /**
     * Issue #19. An estimate with neither label leaves both comparisons unrun; the coordinates
     * still agree, so the verdict is ALIGNED — but `matchedOn` used to fall through to COUNTRY and
     * claim a country match that never happened. Nothing was matched, and NONE says so.
     */
    @Test fun anExitWithNoLabelsMatchesNothing() {
        val r = AlignmentChecker.check(profile(), exit(country = null, city = null), now)
        assertEquals(AlignmentVerdict.ALIGNED, r.verdict)
        assertEquals(MatchScope.NONE, r.matchedOn)
        assertTrue(r.hasReason(AlignmentReason.COUNTRY_UNVERIFIED))
        assertTrue(r.hasReason(AlignmentReason.CITY_UNVERIFIED))
    }

    /** The same, with no coordinates either — every check skipped, still not a country match. */
    @Test fun anExitWithNoLabelsAndNoCoordinatesMatchesNothing() {
        val r = AlignmentChecker.check(
            profile(),
            exit(country = null, city = null, lat = null, lon = null),
            now,
        )
        assertEquals(AlignmentVerdict.ALIGNED, r.verdict)
        assertEquals(MatchScope.NONE, r.matchedOn)
        assertTrue(r.hasReason(AlignmentReason.EXIT_NO_COORDINATES))
    }

    /** A profile with no country still matches on city when both cities are present and agree. */
    @Test fun cityMatchIsReportedEvenWhenCountryCouldNotBeCompared() {
        val r = AlignmentChecker.check(profile(country = null), exit(), now)
        assertEquals(AlignmentVerdict.ALIGNED, r.verdict)
        assertEquals(MatchScope.CITY, r.matchedOn)
        assertTrue(r.hasReason(AlignmentReason.COUNTRY_UNVERIFIED))
    }

    /** A hand-edited profile is a legitimate choice, but drift is still drift. */
    @Test fun manualProfileIsFlaggedButStillJudged() {
        val p = profile(country = "SG", city = "Singapore", lat = 1.3521, lon = 103.8198,
            generatedFromIp = false)
        val r = AlignmentChecker.check(p, exit(), now)
        assertEquals(AlignmentVerdict.DRIFTED_COUNTRY, r.verdict)
        assertTrue(r.hasReason(AlignmentReason.PROFILE_MANUAL))
    }

    @Test fun exitWithoutCoordinatesSkipsDistanceCheck() {
        val r = AlignmentChecker.check(profile(lat = 50.0, lon = 10.0), exit(lat = null, lon = null), now)
        assertEquals(AlignmentVerdict.ALIGNED, r.verdict)
        assertTrue(r.hasReason(AlignmentReason.EXIT_NO_COORDINATES))
        assertNull(r.distanceKm)
    }
}
