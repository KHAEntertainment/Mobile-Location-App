package com.geoalign.web.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That this test compiles and runs on the JVM is the point of the type: capability facts are plain
 * data, so the code that branches on them can be exercised without a device, an emulator, or a
 * WebView implementation to ask.
 */
class WebViewCapabilitiesTest {

    @Test
    fun `the pessimistic baseline supports nothing and knows nothing`() {
        val none = WebViewCapabilities.NONE
        assertFalse(none.documentStartScript)
        assertFalse(none.userAgentMetadata)
        assertFalse(none.safeBrowsing)
        assertFalse(none.serviceWorkerControl)
        assertNull(none.packageName)
        assertNull(none.packageVersion)
    }

    @Test
    fun `capabilities are constructible from a fake probe with no Android involved`() {
        val probe = WebViewCapabilityProbe {
            WebViewCapabilities(
                documentStartScript = true,
                userAgentMetadata = true,
                safeBrowsing = true,
                serviceWorkerControl = true,
                packageName = "com.google.android.webview",
                packageVersion = "151.0.7922.169",
            )
        }

        val capabilities = probe.probe()

        assertTrue(capabilities.documentStartScript)
        assertTrue(capabilities.userAgentMetadata)
        assertTrue(capabilities.safeBrowsing)
        assertTrue(capabilities.serviceWorkerControl)
        assertEquals("com.google.android.webview", capabilities.packageName)
        assertEquals("151.0.7922.169", capabilities.packageVersion)
    }

    @Test
    fun `an unresolvable WebView package is representable`() {
        // getCurrentWebViewPackage returns null on images with no WebView implementation; the type
        // has to be able to say "supported, but I do not know which build" rather than inventing one.
        val capabilities = WebViewCapabilities.NONE.copy(documentStartScript = true)
        assertTrue(capabilities.documentStartScript)
        assertNull(capabilities.packageName)
        assertNull(capabilities.packageVersion)
    }

    @Test
    fun `capabilities compare by value, so one probe result can be diffed against another`() {
        val a = WebViewCapabilities.NONE.copy(safeBrowsing = true, packageVersion = "151.0.7922.169")
        val b = WebViewCapabilities.NONE.copy(safeBrowsing = true, packageVersion = "151.0.7922.169")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a != b.copy(safeBrowsing = false))
    }
}
