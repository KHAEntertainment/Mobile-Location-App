package com.geoalign.core.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadErrorPolicyTest {

    @Test fun mainFrameNetworkFailureBecomesAnErrorPage() {
        val error = LoadErrorPolicy.forNetworkFailure(true, "https://example.com/a", "net::ERR_NAME_NOT_RESOLVED")
        assertNotNull(error)
        assertEquals(PageError.Kind.NETWORK, error!!.kind)
        assertEquals("example.com", error.host)
    }

    @Test fun subframeNetworkFailureIsIgnored() {
        assertNull(LoadErrorPolicy.forNetworkFailure(false, "https://tracker.example/pixel.gif", "boom"))
    }

    @Test fun blankUrlIsIgnored() {
        assertNull(LoadErrorPolicy.forNetworkFailure(true, "", "boom"))
        assertNull(LoadErrorPolicy.forHttpStatus(true, "", 500))
    }

    @Test fun mainFrameServerErrorBecomesAnErrorPage() {
        val error = LoadErrorPolicy.forHttpStatus(true, "https://example.com/x", 503, "Service Unavailable")
        assertNotNull(error)
        assertEquals(PageError.Kind.HTTP, error!!.kind)
        assertEquals(503, error.statusCode)
        assertTrue(error.detail.contains("503"))
    }

    @Test fun subframeServerErrorIsIgnored() {
        assertNull(LoadErrorPolicy.forHttpStatus(false, "https://ads.example/frame", 500))
    }

    @Test fun successAndRedirectStatusesAreNotErrors() {
        assertNull(LoadErrorPolicy.forHttpStatus(true, "https://example.com/", 200))
        assertNull(LoadErrorPolicy.forHttpStatus(true, "https://example.com/", 302))
        assertNull(LoadErrorPolicy.forHttpStatus(true, "https://example.com/", 399))
    }

    @Test fun clientErrorsAreErrors() {
        assertNotNull(LoadErrorPolicy.forHttpStatus(true, "https://example.com/", 404))
        assertNotNull(LoadErrorPolicy.forHttpStatus(true, "https://example.com/", 400))
    }

    @Test fun openExternallyIsOfferedOnlyForHttpUrls() {
        assertTrue(PageError("https://example.com/", PageError.Kind.NETWORK).canOpenExternally)
        assertTrue(PageError("http://example.com/", PageError.Kind.NETWORK).canOpenExternally)
        // Handing a non-web scheme to another browser would fail there too, or launch something else.
        assertFalse(PageError("about:blank", PageError.Kind.NETWORK).canOpenExternally)
        assertFalse(PageError("data:text/html,hi", PageError.Kind.NETWORK).canOpenExternally)
    }

    @Test fun blankDescriptionIsDropped() {
        assertNull(LoadErrorPolicy.forNetworkFailure(true, "https://example.com/", "   ")!!.description)
    }

    @Test fun hostFallsBackToTheWholeUrlWhenThereIsNoAuthority() {
        assertEquals("about:blank", PageError("about:blank", PageError.Kind.NETWORK).host)
    }
}
