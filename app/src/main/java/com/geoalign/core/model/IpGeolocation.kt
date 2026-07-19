package com.geoalign.core.model

/**
 * Normalized IP-geolocation result (spec §8). Provider-agnostic: each IpGeolocationProvider
 * maps its raw response into this shape so the rest of the app never depends on a specific vendor.
 *
 * All positional fields are nullable because free providers vary in coverage, and the app must
 * never present an IP estimate as precise GPS. `privacy*` flags carry hosting/proxy/VPN
 * classification when the source supplies it.
 */
data class IpGeolocation(
    val ip: String,
    val countryCode: String? = null,
    val countryName: String? = null,
    val region: String? = null,
    val city: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** IANA timezone id, e.g. "America/Los_Angeles". */
    val timezone: String? = null,
    /** Network organization or ASN string when available. */
    val org: String? = null,
    /** True when the source flags the network as hosting/datacenter. */
    val isHosting: Boolean? = null,
    /** True when the source flags the network as a proxy. */
    val isProxy: Boolean? = null,
    /** True when the source flags the network as a VPN. */
    val isVpn: Boolean? = null,
    /** Free-form confidence/accuracy metadata when available. */
    val confidence: String? = null,
    val providerName: String,
    val timestampMillis: Long,
) {
    val hasCoordinates: Boolean get() = latitude != null && longitude != null
}
