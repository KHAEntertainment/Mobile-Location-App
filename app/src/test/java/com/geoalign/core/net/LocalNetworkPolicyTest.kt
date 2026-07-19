package com.geoalign.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkPolicyTest {

    private fun blocked(host: String) =
        assertTrue("expected BLOCK for $host", LocalNetworkPolicy.classifyHost(host).isBlocked)

    private fun allowed(host: String) =
        assertFalse("expected ALLOW for $host", LocalNetworkPolicy.classifyHost(host).isBlocked)

    // --- IPv4 private / special ranges (spec §16) ---------------------------

    @Test fun blocksLoopbackV4() = blocked("127.0.0.1")
    @Test fun blocksPrivate10() = blocked("10.1.2.3")
    @Test fun blocksPrivate172() = blocked("172.16.5.4")
    @Test fun blocks172UpperBound() = blocked("172.31.255.255")
    @Test fun allows172JustOutside() = allowed("172.32.0.1")
    @Test fun blocksPrivate192() = blocked("192.168.1.1")
    @Test fun blocksCgnat() = blocked("100.64.0.1")
    @Test fun allowsCgnatBoundaryOutside() = allowed("100.128.0.1")
    @Test fun blocksLinkLocal() = blocked("169.254.10.10")
    @Test fun blocksMulticast() = blocked("239.255.0.1")
    @Test fun blocksReserved240() = blocked("255.255.255.255")
    @Test fun blocksThisNetwork() = blocked("0.0.0.0")

    @Test fun allowsPublicV4() = allowed("8.8.8.8")
    @Test fun allowsPublicV4b() = allowed("1.1.1.1")

    // --- Alternative IPv4 notations (bypass attempts) -----------------------

    @Test fun blocksDwordLoopback() = blocked("2130706433")          // 127.0.0.1
    @Test fun blocksHexLoopback() = blocked("0x7f.0x0.0x0.0x1")
    @Test fun blocksOctalLoopback() = blocked("0177.0.0.1")
    @Test fun blocksShortFormLoopback() = blocked("127.1")           // -> 127.0.0.1
    @Test fun blocksHexDword() = blocked("0x7f000001")

    // --- Hostnames ----------------------------------------------------------

    @Test fun blocksLocalhost() = blocked("localhost")
    @Test fun blocksLocalhostWithPort() = blocked("localhost:8080")
    @Test fun blocksDotLocal() = blocked("printer.local")
    @Test fun blocksTrailingDotLocalhost() = blocked("localhost.")
    @Test fun blocksHomeArpa() = blocked("router.home.arpa")
    @Test fun allowsPublicHostname() = allowed("example.com")
    @Test fun allowsPublicHostnameWithSubdomain() = allowed("api.example.co.uk")

    // --- Embedded credentials & ports (smuggling) ---------------------------

    @Test fun blocksCredsToLoopback() = blocked("user:pass@127.0.0.1")
    @Test fun blocksCredsToPrivate() = blocked("admin@192.168.0.1:443")
    @Test fun allowsCredsToPublic() = allowed("user:pass@example.com")

    // --- IPv6 ---------------------------------------------------------------

    @Test fun blocksIpv6Loopback() = blocked("[::1]")
    @Test fun blocksIpv6LoopbackNoBracket() = blocked("::1")
    @Test fun blocksIpv6LinkLocal() = blocked("[fe80::1]")
    @Test fun blocksIpv6UniqueLocal() = blocked("[fd12:3456::1]")
    @Test fun blocksIpv6Multicast() = blocked("[ff02::1]")
    @Test fun blocksIpv6Unspecified() = blocked("::")
    @Test fun allowsIpv6Public() = allowed("[2606:4700:4700::1111]")   // Cloudflare
    @Test fun blocksIpv4MappedLoopback() = blocked("[::ffff:127.0.0.1]")
    @Test fun allowsIpv4MappedPublic() = allowed("[::ffff:8.8.8.8]")
    @Test fun blocksIpv4CompatibleLoopback() = blocked("[::127.0.0.1]")   // deprecated ::a.b.c.d
    @Test fun blocksIpv4CompatiblePrivate() = blocked("[::192.168.0.1]")
    @Test fun blocks6to4RelayAnycast() = blocked("192.88.99.1")

    // --- parser hardening (from adversarial review) -------------------------

    @Test fun rejectsNegativeOctet() {
        // "-1" must not slip past the >255 guard as a valid octet.
        assertNull(LocalNetworkPolicy.parseIpv4Any("-1.0.0.1"))
    }

    // --- Direct classifier unit checks --------------------------------------

    @Test fun ipv4ParserDword() {
        assertEquals(2130706433L, LocalNetworkPolicy.parseIpv4Any("2130706433"))
    }

    @Test fun ipv4ParserRejectsGarbage() {
        assertNull(LocalNetworkPolicy.parseIpv4Any("not.an.ip.addr"))
    }

    @Test fun ipv6ParserRoundTripLoopback() {
        val b = LocalNetworkPolicy.parseIpv6("::1")
        assertNotNull(b)
        assertEquals(1, b!![15].toInt())
    }

    @Test fun ipv6ParserRejectsDoubleColonTwice() {
        assertNull(LocalNetworkPolicy.parseIpv6("1::2::3"))
    }

    @Test fun emptyHostIsBlocked() {
        assertTrue(LocalNetworkPolicy.classifyHost("").isBlocked)
        assertTrue(LocalNetworkPolicy.classifyHost(null).isBlocked)
    }
}
