package com.geoalign.data.net

import com.geoalign.core.net.IpRedaction
import com.geoalign.core.net.LocalNetworkPolicy

/** IP protocol family of an effective public address (spec §7). */
enum class IpVersion { V4, V6 }

data class EffectiveIp(val ip: String, val version: IpVersion)

/**
 * Pure helpers for effective-IP handling (spec §7). Reuses the vetted parsers in
 * LocalNetworkPolicy to classify family and validity, so we don't duplicate address parsing.
 */
object EffectiveIpUtil {

    /** Parse a raw string into an EffectiveIp, or null if it is not a valid IP literal. */
    fun parse(raw: String?): EffectiveIp? {
        val s = raw?.trim()?.removeSurrounding("[", "]") ?: return null
        if (s.isEmpty()) return null
        if (LocalNetworkPolicy.parseIpv4Any(s) != null && !s.contains(':')) {
            return EffectiveIp(s, IpVersion.V4)
        }
        if (s.contains(':') && LocalNetworkPolicy.parseIpv6(s) != null) {
            return EffectiveIp(s, IpVersion.V6)
        }
        return null
    }

    /**
     * Redact an IP for release logging (spec §7 / §21): keep enough to be diagnostic, drop the
     * host-identifying tail. 203.0.113.42 -> "203.0.x.x"; 2606:4700:... -> "2606:4700:…".
     *
     * The rule itself is [IpRedaction], in `core/`, because the diagnostics report redacts the same
     * addresses and pure code cannot import `data/`. Kept here as the name every existing call site
     * already uses, so there is one implementation and two spellings rather than two rules.
     */
    fun redact(ip: String): String = IpRedaction.redact(ip)
}
