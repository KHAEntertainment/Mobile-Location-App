package com.geoalign.data.profiles

import com.geoalign.core.model.IpGeolocation
import com.geoalign.core.model.MeasurementSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileFactoryTest {

    private fun geo(
        lat: Double? = 51.5074,
        lng: Double? = -0.1278,
        tz: String? = "Europe/London",
        cc: String? = "GB",
        city: String? = "London",
    ) = IpGeolocation(
        ip = "203.0.113.1",
        countryCode = cc,
        countryName = "United Kingdom",
        region = "England",
        city = city,
        latitude = lat,
        longitude = lng,
        timezone = tz,
        org = "Example ISP",
        providerName = "ipwho.is",
        timestampMillis = 555L,
    )

    @Test fun buildsStationaryProfileWithPreservedLanguage() {
        val p = ProfileFactory.fromGeolocation(geo(), contentLanguage = "en", id = "id-1", nowMillis = 999L)!!
        assertEquals("id-1", p.id)
        assertEquals("London", p.name)
        assertEquals(51.5074, p.latitude, 1e-9)
        assertEquals(0.0, p.speedMetersPerSec, 0.0)     // stationary
        assertNull(p.headingDegrees)
        assertNull(p.altitudeMeters)
        assertEquals(1500.0, p.accuracyMeters, 0.0)     // plausible, not GPS-precise
        assertEquals("Europe/London", p.timezone)
        assertEquals("en-GB", p.primaryLocale)          // language preserved, region aligned
        assertEquals(MeasurementSystem.METRIC, p.measurementSystem)
        assertTrue(p.generatedFromIp)
        assertEquals("ipwho.is", p.sourceProvider)
        assertEquals(555L, p.sourceApproxTimestampMillis)
        assertEquals(999L, p.createdAtMillis)
    }

    @Test fun missingCoordinatesYieldsNull() {
        assertNull(ProfileFactory.fromGeolocation(geo(lat = null), "en", "x", 1L))
        assertNull(ProfileFactory.fromGeolocation(geo(lng = null), "en", "x", 1L))
    }

    @Test fun missingTimezoneFallsBackToUtc() {
        val p = ProfileFactory.fromGeolocation(geo(tz = null), "en", "x", 1L)!!
        assertEquals("UTC", p.timezone)
    }

    @Test fun nameFallsBackWhenCityMissing() {
        val p = ProfileFactory.fromGeolocation(geo(city = null), "en", "x", 1L)!!
        assertEquals("United Kingdom", p.name)
    }
}
