package com.geoalign.web.config

import com.geoalign.core.browser.BrowserCapability
import com.geoalign.core.browser.GateVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bridge from the probe's answers to the pure decision types.
 *
 * If a field is ever added to [WebViewCapabilities] and not mapped here, the gate and the Site &
 * privacy sheet would silently treat it as absent — so the mapping is asserted field by field, in
 * both directions.
 */
class BrowserCapabilityBridgeTest {

    private val everything = WebViewCapabilities(
        documentStartScript = true,
        userAgentMetadata = true,
        safeBrowsing = true,
        serviceWorkerControl = true,
        errorDescription = true,
        packageName = "com.google.android.webview",
        packageVersion = "151.0.7922.169",
    )

    @Test fun nothingSupportedMapsToAnEmptySet() {
        assertTrue(WebViewCapabilities.NONE.supportedBrowserCapabilities().isEmpty())
    }

    @Test fun everythingSupportedMapsToEveryCapability() {
        assertEquals(
            BrowserCapability.entries.toSet(),
            everything.supportedBrowserCapabilities(),
        )
    }

    @Test fun eachFlagMapsToExactlyOneCapability() {
        assertEquals(
            setOf(BrowserCapability.DOCUMENT_START_SCRIPT),
            WebViewCapabilities.NONE.copy(documentStartScript = true).supportedBrowserCapabilities(),
        )
        assertEquals(
            setOf(BrowserCapability.USER_AGENT_METADATA),
            WebViewCapabilities.NONE.copy(userAgentMetadata = true).supportedBrowserCapabilities(),
        )
        assertEquals(
            setOf(BrowserCapability.SAFE_BROWSING),
            WebViewCapabilities.NONE.copy(safeBrowsing = true).supportedBrowserCapabilities(),
        )
        assertEquals(
            setOf(BrowserCapability.SERVICE_WORKER_CONTROL),
            WebViewCapabilities.NONE.copy(serviceWorkerControl = true).supportedBrowserCapabilities(),
        )
        assertEquals(
            setOf(BrowserCapability.ERROR_DESCRIPTION),
            WebViewCapabilities.NONE.copy(errorDescription = true).supportedBrowserCapabilities(),
        )
    }

    @Test fun theGateDecisionCarriesTheProbedWebViewIdentity() {
        val decision = everything.gateDecision()

        assertEquals(GateVerdict.ALLOWED, decision.verdict)
        assertEquals("com.google.android.webview", decision.webViewPackageName)
        assertEquals("151.0.7922.169", decision.webViewPackageVersion)
    }

    @Test fun aProbeResultWithoutDocumentStartSupportBlocksTheBrowser() {
        // The end-to-end shape of the acceptance criterion, still with the capability stubbed: a
        // probe result is all the gate ever sees, so this is the whole production path.
        val stubbed = WebViewCapabilityProbe { everything.copy(documentStartScript = false) }

        val decision = stubbed.probe().gateDecision()

        assertFalse(decision.allowsAlignedBrowsing)
        assertEquals(listOf(BrowserCapability.DOCUMENT_START_SCRIPT), decision.missingRequired)
        assertTrue(decision.missingOptional.isEmpty())
        assertTrue(decision.reason.isNotBlank())
    }
}
