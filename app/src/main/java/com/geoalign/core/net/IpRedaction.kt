package com.geoalign.core.net

/**
 * Redaction of public IP addresses for anything a user can see, copy or send (spec §7 / §21).
 *
 * This lives in `core/` because two surfaces now depend on it and they are in different layers: the
 * readiness screen's presenter, which has always redacted through `EffectiveIpUtil`, and the
 * diagnostics report, which is pure `core/` code and must not reach down into `data/`. Copying the
 * three lines into the second caller would have been the way this invariant eventually diverges —
 * one caller redacting and the other not is precisely the failure the copyable report cannot afford.
 *
 * `EffectiveIpUtil.redact` delegates here, so there is still exactly one implementation.
 */
object IpRedaction {

    /**
     * Keep enough of [ip] to be diagnostic, drop the host-identifying tail:
     * `203.0.113.42` -> `203.0.x.x`; `2606:4700:4700::1111` -> `2606:4700:…`.
     *
     * Anything that is not recognisably an address collapses to `x.x.x.x` rather than being passed
     * through — a value this function does not understand is the one case where echoing the input
     * would leak it.
     */
    fun redact(ip: String): String = if (ip.contains(':')) {
        val parts = ip.split(':').filter { it.isNotEmpty() }
        (parts.take(2).joinToString(":")) + ":…"
    } else {
        val octets = ip.split('.')
        if (octets.size == 4) "${octets[0]}.${octets[1]}.x.x" else "x.x.x.x"
    }
}
