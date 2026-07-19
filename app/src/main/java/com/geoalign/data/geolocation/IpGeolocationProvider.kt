package com.geoalign.data.geolocation

import com.geoalign.core.model.IpGeolocation

/**
 * Provider abstraction (spec §8) — swapping vendors is a one-class change. Implementations must
 * be HTTPS-only, bounded, and must not log full IPs in release builds.
 */
interface IpGeolocationProvider {
    val name: String

    /** Look up geolocation for [ip], or for the caller's effective IP when [ip] is null. */
    suspend fun locate(ip: String? = null): Result<IpGeolocation>
}
