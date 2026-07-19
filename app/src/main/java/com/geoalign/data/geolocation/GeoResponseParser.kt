package com.geoalign.data.geolocation

import com.geoalign.core.model.IpGeolocation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure, provider-specific JSON to IpGeolocation mapping (spec §8). Kept dependency-free (no
 * network, no Android) so it can be unit-tested against captured provider payloads. Parsing is
 * defensive: free tiers omit fields, so everything degrades to null rather than throwing.
 */
object GeoResponseParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNullSafe()

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? {
        val c = this.content
        return if (c.isBlank() || c == "null") null else c
    }

    /**
     * ipwho.is response shape:
     * {"ip":"1.2.3.4","success":true,"country":"United States","country_code":"US",
     *  "region":"California","city":"Mountain View","latitude":37.4,"longitude":-122.1,
     *  "connection":{"asn":15169,"org":"Google LLC","isp":"Google"},
     *  "timezone":{"id":"America/Los_Angeles"},"security":{"vpn":false,"proxy":false,"hosting":false}}
     */
    fun parseIpWhoIs(body: String, nowMillis: Long): IpGeolocation? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        // ipwho.is signals failure with success:false.
        if (root["success"]?.jsonPrimitive?.booleanOrNull == false) return null
        val ip = root.str("ip") ?: return null

        val connection = root["connection"]?.jsonObject
        val timezone = root["timezone"]?.jsonObject?.str("id")
        val security = root["security"]?.jsonObject

        return IpGeolocation(
            ip = ip,
            countryCode = root.str("country_code"),
            countryName = root.str("country"),
            region = root.str("region"),
            city = root.str("city"),
            latitude = root["latitude"]?.jsonPrimitive?.doubleOrNull,
            longitude = root["longitude"]?.jsonPrimitive?.doubleOrNull,
            timezone = timezone,
            org = connection?.str("org") ?: connection?.str("isp"),
            isHosting = security?.get("hosting")?.jsonPrimitive?.booleanOrNull,
            isProxy = security?.get("proxy")?.jsonPrimitive?.booleanOrNull,
            isVpn = security?.get("vpn")?.jsonPrimitive?.booleanOrNull,
            confidence = null,
            providerName = "ipwho.is",
            timestampMillis = nowMillis,
        )
    }

    /**
     * ipinfo.io (Core) response shape:
     * {"ip":"1.2.3.4","city":"Mountain View","region":"California","country":"US",
     *  "loc":"37.4056,-122.0775","org":"AS15169 Google LLC","timezone":"America/Los_Angeles",
     *  "privacy":{"vpn":false,"proxy":false,"hosting":true}}  // privacy only on paid tiers
     */
    fun parseIpInfo(body: String, nowMillis: Long): IpGeolocation? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val ip = root.str("ip") ?: return null

        val loc = root.str("loc")
        val lat = loc?.substringBefore(',', "")?.toDoubleOrNull()
        val lng = loc?.substringAfter(',', "")?.toDoubleOrNull()
        val privacy = root["privacy"]?.jsonObject

        return IpGeolocation(
            ip = ip,
            countryCode = root.str("country"),
            countryName = null, // ipinfo returns ISO code only at this tier
            region = root.str("region"),
            city = root.str("city"),
            latitude = lat,
            longitude = lng,
            timezone = root.str("timezone"),
            org = root.str("org"),
            isHosting = privacy?.get("hosting")?.jsonPrimitive?.booleanOrNull,
            isProxy = privacy?.get("proxy")?.jsonPrimitive?.booleanOrNull,
            isVpn = privacy?.get("vpn")?.jsonPrimitive?.booleanOrNull,
            confidence = null,
            providerName = "ipinfo.io",
            timestampMillis = nowMillis,
        )
    }
}
