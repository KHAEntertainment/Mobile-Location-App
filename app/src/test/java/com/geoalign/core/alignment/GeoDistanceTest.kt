package com.geoalign.core.alignment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoDistanceTest {

    @Test fun identityIsZero() {
        assertEquals(0.0, GeoDistance.haversineKm(51.5074, -0.1278, 51.5074, -0.1278), 1e-9)
    }

    @Test fun londonToParisIsAboutThreeFortyFour() {
        val km = GeoDistance.haversineKm(51.5074, -0.1278, 48.8566, 2.3522)
        assertEquals(344.0, km, 3.0)
    }

    @Test fun oneDegreeOfLongitudeAtEquator() {
        assertEquals(111.3, GeoDistance.haversineKm(0.0, 0.0, 0.0, 1.0), 1.0)
    }

    @Test fun isSymmetric() {
        val a = GeoDistance.haversineKm(1.3521, 103.8198, 6.5244, 3.3792)
        val b = GeoDistance.haversineKm(6.5244, 3.3792, 1.3521, 103.8198)
        assertEquals(a, b, 1e-9)
    }

    /** The sqrt term can exceed 1.0 by float error here; asin would return NaN without the clamp. */
    @Test fun antipodalDoesNotProduceNaN() {
        val km = GeoDistance.haversineKm(0.0, 0.0, 0.0, 180.0)
        assertTrue("expected a real number, got $km", !km.isNaN())
        assertEquals(20015.0, km, 5.0)
    }
}
