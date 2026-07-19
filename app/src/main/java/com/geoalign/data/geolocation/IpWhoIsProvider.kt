package com.geoalign.data.geolocation

import com.geoalign.core.model.IpGeolocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Default MVP geolocation provider: ipwho.is — keyless, HTTPS, returns city/lat-long/timezone/ASN.
 * (ipinfo.io Core is the optional keyed upgrade; its parser lives in GeoResponseParser.parseIpInfo.)
 *
 * Networking is intentionally thin; all field mapping is delegated to the pure, unit-tested
 * GeoResponseParser. Bounded response read, strict timeouts, no request history.
 */
class IpWhoIsProvider(
    private val client: OkHttpClient = defaultClient(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) : IpGeolocationProvider {

    override val name: String = "ipwho.is"

    override suspend fun locate(ip: String?): Result<IpGeolocation> = withContext(Dispatchers.IO) {
        val url = if (ip.isNullOrBlank()) "https://ipwho.is/" else "https://ipwho.is/$ip"
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                // Bound the body to guard against oversized/hostile responses.
                val body = resp.peekBody(MAX_BODY).string()
                GeoResponseParser.parseIpWhoIs(body, clock()) ?: error("unparseable geolocation response")
            }
        }
    }

    companion object {
        private const val MAX_BODY = 64L * 1024 // 64 KiB cap

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
