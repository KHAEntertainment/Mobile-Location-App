package com.geoalign.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Determines the app's effective public IP over the same default network path the app uses
 * (spec §7). Two providers for resilience; HTTPS-only, bounded, strict timeouts, no history.
 */
interface EffectiveIpRepository {
    suspend fun currentIp(): Result<EffectiveIp>
}

/**
 * OkHttp implementation using two keyless HTTPS IP-echo endpoints. Parsing/validation is delegated
 * to the pure EffectiveIpUtil; the network layer stays thin.
 */
class OkHttpEffectiveIpRepository(
    private val client: OkHttpClient = defaultClient(),
    private val providers: List<String> = DEFAULT_PROVIDERS,
) : EffectiveIpRepository {

    override suspend fun currentIp(): Result<EffectiveIp> = withContext(Dispatchers.IO) {
        var lastError: Throwable = IllegalStateException("no providers configured")
        for (url in providers) {
            val attempt = runCatching {
                val request = Request.Builder().url(url).header("Accept", "text/plain").get().build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code} from $url")
                    val text = resp.peekBody(MAX_BODY).string().trim()
                    EffectiveIpUtil.parse(text) ?: error("unparseable IP from $url")
                }
            }
            attempt.onSuccess { return@withContext Result.success(it) }
            attempt.onFailure { lastError = it }
        }
        Result.failure(lastError)
    }

    companion object {
        private const val MAX_BODY = 512L // an IP literal is tiny
        val DEFAULT_PROVIDERS = listOf(
            "https://api.ipify.org",       // returns bare IPv4/IPv6 text
            "https://icanhazip.com",       // returns bare IP text
        )

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }
}
