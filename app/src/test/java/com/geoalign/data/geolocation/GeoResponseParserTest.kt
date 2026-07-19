package com.geoalign.data.geolocation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoResponseParserTest {

    @Test fun parsesIpWhoIsFullPayload() {
        val body = "{\"ip\":\"203.0.113.7\",\"success\":true,\"country\":\"United States\",\"country_code\":\"US\",\"region\":\"California\",\"city\":\"Mountain View\",\"latitude\":37.4056,\"longitude\":-122.0775,\"connection\":{\"asn\":15169,\"org\":\"Google LLC\",\"isp\":\"Google\"},\"timezone\":{\"id\":\"America/Los_Angeles\"},\"security\":{\"vpn\":false,\"proxy\":false,\"hosting\":true}}"
        val g = GeoResponseParser.parseIpWhoIs(body, 1000L)!!
        assertEquals("203.0.113.7", g.ip)
        assertEquals("US", g.countryCode)
        assertEquals("Mountain View", g.city)
        assertEquals(37.4056, g.latitude!!, 1e-6)
        assertEquals(-122.0775, g.longitude!!, 1e-6)
        assertEquals("America/Los_Angeles", g.timezone)
        assertEquals("Google LLC", g.org)
        assertEquals(true, g.isHosting)
        assertEquals(false, g.isVpn)
        assertEquals("ipwho.is", g.providerName)
        assertEquals(1000L, g.timestampMillis)
        assertTrue(g.hasCoordinates)
    }

    @Test fun ipWhoIsFailureReturnsNull() {
        val body = "{\"ip\":\"\",\"success\":false,\"message\":\"Invalid IP address\"}"
        assertNull(GeoResponseParser.parseIpWhoIs(body, 1L))
    }

    @Test fun ipWhoIsMissingFieldsDegradeToNull() {
        val body = "{\"ip\":\"8.8.8.8\",\"success\":true}"
        val g = GeoResponseParser.parseIpWhoIs(body, 5L)!!
        assertEquals("8.8.8.8", g.ip)
        assertNull(g.city)
        assertNull(g.latitude)
        assertNull(g.timezone)
    }

    @Test fun garbageReturnsNull() {
        assertNull(GeoResponseParser.parseIpWhoIs("not json", 1L))
        assertNull(GeoResponseParser.parseIpWhoIs("", 1L))
    }

    @Test fun parsesIpInfoLocSplit() {
        val body = "{\"ip\":\"203.0.113.9\",\"city\":\"Berlin\",\"region\":\"Berlin\",\"country\":\"DE\",\"loc\":\"52.5200,13.4050\",\"org\":\"AS3320 Deutsche Telekom AG\",\"timezone\":\"Europe/Berlin\",\"privacy\":{\"vpn\":true,\"proxy\":false,\"hosting\":false}}"
        val g = GeoResponseParser.parseIpInfo(body, 42L)!!
        assertEquals("DE", g.countryCode)
        assertEquals("Berlin", g.city)
        assertEquals(52.52, g.latitude!!, 1e-6)
        assertEquals(13.405, g.longitude!!, 1e-6)
        assertEquals("Europe/Berlin", g.timezone)
        assertEquals("AS3320 Deutsche Telekom AG", g.org)
        assertEquals(true, g.isVpn)
        assertEquals("ipinfo.io", g.providerName)
    }
}
