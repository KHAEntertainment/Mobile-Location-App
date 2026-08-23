package com.geoalign.core.alignment

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Great-circle distance. Pure, no dependencies. */
object GeoDistance {

    const val EARTH_RADIUS_KM = 6371.0088

    /**
     * Haversine distance in kilometres. The `min(1.0, ...)` guard keeps [asin] in domain when
     * floating-point error pushes the term marginally above 1 for near-antipodal pairs.
     */
    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val r1 = Math.toRadians(lat1)
        val r2 = Math.toRadians(lat2)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(r1) * cos(r2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_KM * asin(min(1.0, sqrt(a)))
    }
}
