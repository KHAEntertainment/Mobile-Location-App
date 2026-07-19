package com.geoalign.core.net

import java.util.Locale

/**
 * Decides whether a webpage-originated request destination is a public address that may be
 * loaded, or a local / special-use destination that must be blocked (spec §16).
 *
 * This class is deliberately pure and dependency-free so it can be unit-tested exhaustively
 * without a device or WebView. It normalizes hosts before classification to defeat common
 * bypasses: alternative numeric IP notations, embedded credentials, bracketed IPv6, trailing
 * dots, and case tricks. It does NOT perform DNS resolution itself; the WebView interception
 * layer supplies both the literal host and (where available) any resolved address, and calls
 * this classifier on each.
 */
object LocalNetworkPolicy {

    enum class Decision { ALLOW, BLOCK }

    data class Result(val decision: Decision, val reason: String) {
        val isBlocked: Boolean get() = decision == Decision.BLOCK
    }

    private val BLOCKED_HOST_SUFFIXES = listOf(".local", ".localhost", ".internal", ".home.arpa")
    private val BLOCKED_EXACT_HOSTS = setOf("localhost")

    /**
     * Classify a raw authority/host string taken from a URL.
     * Accepts forms like "user:pass@[::1]:8080", "192.168.0.1", "0x7f.1", "example.local".
     */
    fun classifyHost(rawHost: String?): Result {
        if (rawHost.isNullOrBlank()) {
            return Result(Decision.BLOCK, "empty or missing host")
        }

        var host = rawHost.trim()

        // Strip embedded credentials (user:pass@host) — a classic SSRF/rebinding smuggling vector.
        val at = host.lastIndexOf('@')
        if (at >= 0) host = host.substring(at + 1)

        // Strip port. Handle bracketed IPv6 [::1]:443 as well as host:443.
        host = stripPort(host)

        // Normalize: lowercase, remove a single trailing dot (FQDN root).
        host = host.lowercase(Locale.US).removeSuffix(".")

        if (host.isEmpty()) return Result(Decision.BLOCK, "host empty after normalization")

        // Bracketed IPv6 literal.
        if (host.startsWith("[") && host.endsWith("]")) {
            val inner = host.substring(1, host.length - 1)
            return classifyIpv6(inner) ?: Result(Decision.BLOCK, "unparseable IPv6 literal")
        }

        // Exact / suffix hostname blocks (localhost, *.local, mDNS, etc.).
        if (host in BLOCKED_EXACT_HOSTS) {
            return Result(Decision.BLOCK, "reserved hostname: $host")
        }
        for (suffix in BLOCKED_HOST_SUFFIXES) {
            if (host.endsWith(suffix)) {
                return Result(Decision.BLOCK, "reserved TLD/suffix: $suffix")
            }
        }

        // Numeric IPv4, including alternative notations (octal/hex/dword/short-form).
        val v4 = parseIpv4Any(host)
        if (v4 != null) {
            return classifyIpv4(v4)
        }

        // Unbracketed IPv6 (rare in a host position but possible).
        if (host.contains(':')) {
            classifyIpv6(host)?.let { return it }
        }

        // A normal-looking public hostname. Allowed at the literal-host stage; the interception
        // layer is still expected to re-run classification on the resolved IP to catch
        // public-hostname-resolves-to-private (DNS rebinding).
        return Result(Decision.ALLOW, "public hostname")
    }

    /** Classify an already-parsed 32-bit IPv4 value. */
    fun classifyIpv4(addr: Long): Result {
        val a = (addr ushr 24) and 0xFF
        val b = (addr ushr 16) and 0xFF
        val c = (addr ushr 8) and 0xFF

        fun block(r: String) = Result(Decision.BLOCK, r)

        return when {
            a == 0L -> block("0.0.0.0/8 (this-network)")
            a == 10L -> block("10.0.0.0/8 (private)")
            a == 127L -> block("127.0.0.0/8 (loopback)")
            a == 100L && b in 64..127 -> block("100.64.0.0/10 (CGNAT)")
            a == 169L && b == 254L -> block("169.254.0.0/16 (link-local)")
            a == 172L && b in 16..31 -> block("172.16.0.0/12 (private)")
            a == 192L && b == 168L -> block("192.168.0.0/16 (private)")
            a == 192L && b == 0L && c == 0L -> block("192.0.0.0/24 (IETF)")
            a == 192L && b == 0L && c == 2L -> block("192.0.2.0/24 (TEST-NET-1)")
            a == 198L && b == 18L -> block("198.18.0.0/15 (benchmark)")
            a == 198L && b == 19L -> block("198.18.0.0/15 (benchmark)")
            a == 198L && b == 51L && c == 100L -> block("198.51.100.0/24 (TEST-NET-2)")
            a == 203L && b == 0L && c == 113L -> block("203.0.113.0/24 (TEST-NET-3)")
            a in 224..239 -> block("224.0.0.0/4 (multicast)")
            a in 240..255 -> block("240.0.0.0/4 (reserved)")
            else -> Result(Decision.ALLOW, "public IPv4")
        }
    }

    /** Returns null if [text] is not a parseable IPv6 address. */
    fun classifyIpv6(text: String): Result? {
        val bytes = parseIpv6(text) ?: return null
        fun block(r: String) = Result(Decision.BLOCK, r)

        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF

        // ::1 loopback
        if (bytes.take(15).all { it.toInt() == 0 } && bytes[15].toInt() == 1) {
            return block("::1 (IPv6 loopback)")
        }
        // :: unspecified
        if (bytes.all { it.toInt() == 0 }) return block(":: (unspecified)")

        // IPv4-mapped ::ffff:0:0/96 and IPv4-compatible — reclassify the embedded v4.
        val firstTenZero = (0 until 10).all { bytes[it].toInt() == 0 }
        if (firstTenZero && ((bytes[10].toInt() and 0xFF) == 0xFF) && ((bytes[11].toInt() and 0xFF) == 0xFF)) {
            val v4 = ((bytes[12].toLong() and 0xFF) shl 24) or
                ((bytes[13].toLong() and 0xFF) shl 16) or
                ((bytes[14].toLong() and 0xFF) shl 8) or
                (bytes[15].toLong() and 0xFF)
            val inner = classifyIpv4(v4)
            return if (inner.isBlocked) block("IPv4-mapped ${inner.reason}") else Result(Decision.ALLOW, "IPv4-mapped public")
        }

        return when {
            b0 == 0xFE && (b1 and 0xC0) == 0x80 -> block("fe80::/10 (link-local)")
            (b0 and 0xFE) == 0xFC -> block("fc00::/7 (unique-local)")
            b0 == 0xFF -> block("ff00::/8 (multicast)")
            else -> Result(Decision.ALLOW, "public IPv6")
        }
    }

    // --- parsing helpers -----------------------------------------------------

    private fun stripPort(host: String): String {
        if (host.startsWith("[")) {
            val close = host.indexOf(']')
            if (close >= 0) return host.substring(0, close + 1)
            return host
        }
        // Only treat as host:port if there's exactly one colon (otherwise it's IPv6).
        val colon = host.indexOf(':')
        if (colon >= 0 && host.indexOf(':', colon + 1) < 0) {
            return host.substring(0, colon)
        }
        return host
    }

    /**
     * Parse IPv4 in dotted-decimal AND the alternative notations browsers historically accept:
     * dotted-hex (0x7f.0x0.0x0.0x1), dotted-octal (0177.0.0.1), dword (2130706433),
     * and short forms (127.1). Returns a 32-bit value or null.
     */
    fun parseIpv4Any(host: String): Long? {
        if (host.isEmpty()) return null
        // Reject things containing letters that aren't valid hex-with-0x parts handled below.
        val parts = host.split(".")
        if (parts.size > 4) return null

        val nums = ArrayList<Long>(parts.size)
        for (p in parts) {
            val v = parseUintAnyRadix(p) ?: return null
            nums.add(v)
        }

        return when (nums.size) {
            1 -> nums[0].takeIf { it in 0..0xFFFFFFFFL }
            2 -> { // a.b -> a.(24-bit b)
                val a = nums[0]; val b = nums[1]
                if (a > 0xFF || b > 0xFFFFFF) null else (a shl 24) or b
            }
            3 -> { // a.b.c -> a.b.(16-bit c)
                val a = nums[0]; val b = nums[1]; val c = nums[2]
                if (a > 0xFF || b > 0xFF || c > 0xFFFF) null else (a shl 24) or (b shl 16) or c
            }
            4 -> {
                if (nums.any { it > 0xFF }) null
                else (nums[0] shl 24) or (nums[1] shl 16) or (nums[2] shl 8) or nums[3]
            }
            else -> null
        }
    }

    private fun parseUintAnyRadix(s: String): Long? {
        if (s.isEmpty()) return null
        return try {
            when {
                s.startsWith("0x") || s.startsWith("0X") ->
                    if (s.length == 2) null else s.substring(2).toLong(16)
                s.length > 1 && s[0] == '0' ->
                    s.toLong(8) // leading-zero octal
                else -> s.toLong(10)
            }
        } catch (e: NumberFormatException) {
            null
        }
    }

    /** Minimal RFC-4291 IPv6 parser (supports "::" compression). Returns 16 bytes or null. */
    fun parseIpv6(input: String): ByteArray? {
        var text = input
        if (text.isEmpty()) return null

        // Handle an embedded IPv4 tail (e.g. ::ffff:127.0.0.1).
        var tailV4: Long? = null
        val lastColon = text.lastIndexOf(':')
        if (lastColon >= 0 && text.substring(lastColon + 1).contains('.')) {
            val v4str = text.substring(lastColon + 1)
            tailV4 = parseIpv4Any(v4str) ?: return null
            text = text.substring(0, lastColon + 1) + "0:0"
        }

        val doubleColon = text.indexOf("::")
        val head: List<String>
        val tail: List<String>
        if (doubleColon >= 0) {
            if (text.indexOf("::", doubleColon + 1) >= 0) return null // more than one "::"
            head = text.substring(0, doubleColon).split(":").filter { it.isNotEmpty() }
            tail = text.substring(doubleColon + 2).split(":").filter { it.isNotEmpty() }
        } else {
            head = text.split(":")
            tail = emptyList()
        }

        val groups = head.size + tail.size
        if (doubleColon < 0 && groups != 8) return null
        if (doubleColon >= 0 && groups > 7) return null

        val words = IntArray(8)
        var idx = 0
        for (h in head) { words[idx++] = h.toIntOrNull(16)?.also { if (it !in 0..0xFFFF) return null } ?: return null }
        val zeros = 8 - groups
        idx += if (doubleColon >= 0) zeros else 0
        for (t in tail) { words[idx++] = t.toIntOrNull(16)?.also { if (it !in 0..0xFFFF) return null } ?: return null }

        val bytes = ByteArray(16)
        for (i in 0 until 8) {
            bytes[i * 2] = ((words[i] ushr 8) and 0xFF).toByte()
            bytes[i * 2 + 1] = (words[i] and 0xFF).toByte()
        }
        tailV4?.let {
            bytes[12] = ((it ushr 24) and 0xFF).toByte()
            bytes[13] = ((it ushr 16) and 0xFF).toByte()
            bytes[14] = ((it ushr 8) and 0xFF).toByte()
            bytes[15] = (it and 0xFF).toByte()
        }
        return bytes
    }
}
