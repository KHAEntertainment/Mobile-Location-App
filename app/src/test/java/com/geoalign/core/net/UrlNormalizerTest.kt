package com.geoalign.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlNormalizerTest {

    @Test fun blankIsNull() {
        assertNull(UrlNormalizer.normalize(null))
        assertNull(UrlNormalizer.normalize("   "))
    }

    @Test fun bareHostGetsHttps() {
        assertEquals("https://example.com", UrlNormalizer.normalize("example.com"))
    }

    @Test fun hostWithPathAndTrimmed() {
        assertEquals("https://example.com/a/b", UrlNormalizer.normalize("  example.com/a/b  "))
    }

    @Test fun subdomainHost() {
        assertEquals("https://api.example.co.uk", UrlNormalizer.normalize("api.example.co.uk"))
    }

    @Test fun existingHttpsKept() {
        assertEquals("https://x.com", UrlNormalizer.normalize("https://x.com"))
    }

    @Test fun existingHttpKept() {
        assertEquals("http://x.com", UrlNormalizer.normalize("http://x.com"))
    }

    @Test fun customSchemeKept() {
        assertEquals("about:blank", UrlNormalizer.normalize("about:blank"))
    }

    @Test fun multiWordBecomesSearch() {
        val out = UrlNormalizer.normalize("hello world")!!
        assertTrue(out.startsWith("https://duckduckgo.com/?q="))
        assertTrue(out.contains("hello"))
    }

    @Test fun singleWordNoDotBecomesSearch() {
        val out = UrlNormalizer.normalize("kotlin")!!
        assertTrue(out.startsWith("https://duckduckgo.com/?q="))
    }

    @Test fun localhostTreatedAsHost() {
        assertEquals("https://localhost", UrlNormalizer.normalize("localhost"))
        assertEquals("https://localhost:8080", UrlNormalizer.normalize("localhost:8080"))
    }
}
