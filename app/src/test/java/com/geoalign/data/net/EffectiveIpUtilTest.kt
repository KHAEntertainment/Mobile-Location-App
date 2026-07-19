package com.geoalign.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EffectiveIpUtilTest {

    @Test fun parsesIpv4() {
        val e = EffectiveIpUtil.parse("203.0.113.42")!!
        assertEquals(IpVersion.V4, e.version)
        assertEquals("203.0.113.42", e.ip)
    }

    @Test fun parsesIpv6() {
        val e = EffectiveIpUtil.parse("2606:4700:4700::1111")!!
        assertEquals(IpVersion.V6, e.version)
    }

    @Test fun parsesBracketedIpv6() {
        val e = EffectiveIpUtil.parse("[2606:4700::1]")!!
        assertEquals(IpVersion.V6, e.version)
    }

    @Test fun trimsWhitespaceFromEcho() {
        val e = EffectiveIpUtil.parse("  8.8.8.8\n")!!
        assertEquals(IpVersion.V4, e.version)
    }

    @Test fun rejectsGarbage() {
        assertNull(EffectiveIpUtil.parse("not-an-ip"))
        assertNull(EffectiveIpUtil.parse(""))
        assertNull(EffectiveIpUtil.parse(null))
    }

    @Test fun redactsIpv4Tail() {
        assertEquals("203.0.x.x", EffectiveIpUtil.redact("203.0.113.42"))
    }

    @Test fun redactsIpv6Tail() {
        assertEquals("2606:4700:…", EffectiveIpUtil.redact("2606:4700:4700::1111"))
    }
}
