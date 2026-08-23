package com.geoalign.core.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate's decision table.
 *
 * The acceptance criterion this covers — "with document-start support absent, the browser refuses to
 * open in aligned mode and states the reason" — is verified by *stubbing the capability set*, which
 * is the only way it can be verified in this repo: there is no Robolectric and no Mockito, and
 * uninstalling a system WebView is not a unit test.
 */
class BrowserCapabilityGateTest {

    private val all = BrowserCapability.entries.toSet()

    @Test fun everyCapabilityPresentAllowsAlignedBrowsing() {
        val decision = BrowserCapabilityGate.decide(all)

        assertEquals(GateVerdict.ALLOWED, decision.verdict)
        assertTrue(decision.allowsAlignedBrowsing)
        assertTrue(decision.missingRequired.isEmpty())
        assertTrue(decision.missingOptional.isEmpty())
    }

    @Test fun documentStartAbsentBlocksAlignedBrowsing() {
        // The stub: everything the WebView could offer, except the one capability that makes the
        // virtual environment installable at all.
        val decision = BrowserCapabilityGate.decide(all - BrowserCapability.DOCUMENT_START_SCRIPT)

        assertEquals(GateVerdict.BLOCKED, decision.verdict)
        assertFalse(decision.allowsAlignedBrowsing)
        assertEquals(listOf(BrowserCapability.DOCUMENT_START_SCRIPT), decision.missingRequired)
    }

    @Test fun aBlockedDecisionStatesTheReason() {
        val decision = BrowserCapabilityGate.decide(emptySet())

        assertTrue(decision.reason.contains("Document-start script injection is unavailable"))
        assertTrue(decision.reason.contains(BrowserCapability.DOCUMENT_START_SCRIPT.consequence))
        assertTrue(decision.headline.isNotBlank())
    }

    @Test fun anAllowedDecisionStatesNoReason() {
        // A caller that renders `reason` unconditionally must show nothing, never a reassurance.
        assertEquals("", BrowserCapabilityGate.decide(all).reason)
    }

    @Test fun optionalCapabilitiesNeverBlock() {
        // Every optional capability missing, one at a time and then all together: still allowed.
        BrowserCapability.OPTIONAL.forEach { optional ->
            val decision = BrowserCapabilityGate.decide(all - optional)
            assertTrue(
                "${optional.name} must not block aligned browsing",
                decision.allowsAlignedBrowsing,
            )
            assertEquals(listOf(optional), decision.missingOptional)
        }

        val noneOptional = BrowserCapabilityGate.decide(setOf(BrowserCapability.DOCUMENT_START_SCRIPT))
        assertTrue(noneOptional.allowsAlignedBrowsing)
        assertEquals(BrowserCapability.OPTIONAL, noneOptional.missingOptional)
    }

    @Test fun optionalGapsAreReportedSeparatelyFromRequiredOnes() {
        val decision = BrowserCapabilityGate.decide(emptySet())

        // The two lists never overlap, and the block reason never mentions an optional gap — the
        // point of the split is that a user is not told Safe Browsing is why they cannot browse.
        assertTrue(decision.missingRequired.none { it in decision.missingOptional })
        assertTrue(decision.missingRequired.all { it.required })
        assertTrue(decision.missingOptional.none { it.required })
        BrowserCapability.OPTIONAL.forEach {
            assertFalse(decision.reason.contains(it.displayName))
        }
        assertNotNull(decision.optionalNotice)
        BrowserCapability.OPTIONAL.forEach {
            assertTrue(decision.optionalNotice!!.contains(it.displayName))
        }
    }

    @Test fun thereIsNoOptionalNoticeWhenNothingOptionalIsMissing() {
        assertNull(BrowserCapabilityGate.decide(all).optionalNotice)
    }

    @Test fun exactlyOneCapabilityIsRequired() {
        // Guards the honesty rule from the other direction: quietly marking a second capability
        // required would start blocking devices the browser can in fact serve.
        assertEquals(listOf(BrowserCapability.DOCUMENT_START_SCRIPT), BrowserCapability.REQUIRED)
        assertEquals(BrowserCapability.entries.size - 1, BrowserCapability.OPTIONAL.size)
    }

    @Test fun theTableIsTotalOverEverySubsetOfCapabilities() {
        // 2^n subsets; every one resolves, and the verdict depends on the required capability alone.
        val subsets = powerSet(BrowserCapability.entries.toList())
        assertEquals(32, subsets.size)
        subsets.forEach { supported ->
            val decision = BrowserCapabilityGate.decide(supported)
            assertEquals(
                "verdict for $supported",
                BrowserCapability.DOCUMENT_START_SCRIPT in supported,
                decision.allowsAlignedBrowsing,
            )
            assertEquals(supported, decision.supported)
            assertEquals(
                BrowserCapability.entries.count { it !in supported },
                decision.missingRequired.size + decision.missingOptional.size,
            )
        }
    }

    @Test fun theInstalledWebViewIsNamedWithoutBeingInvented() {
        val known = BrowserCapabilityGate.decide(
            supported = emptySet(),
            webViewPackageName = "com.google.android.webview",
            webViewPackageVersion = "151.0.7922.169",
        )
        assertEquals(
            "Installed WebView: com.google.android.webview 151.0.7922.169.",
            known.installedWebViewLabel,
        )

        val versionless = BrowserCapabilityGate.decide(
            supported = emptySet(),
            webViewPackageName = "com.android.webview",
        )
        assertTrue(versionless.installedWebViewLabel.contains("version unknown"))

        // No WebView resolved at all: say so rather than naming a build that is not there.
        val unknown = BrowserCapabilityGate.decide(emptySet())
        assertEquals(
            "No WebView implementation could be identified on this device.",
            unknown.installedWebViewLabel,
        )
    }

    @Test fun everyCapabilityCarriesUserFacingText() {
        BrowserCapability.entries.forEach {
            assertTrue(it.name, it.displayName.isNotBlank())
            assertTrue(it.name, it.consequence.isNotBlank())
        }
    }

    private fun powerSet(items: List<BrowserCapability>): List<Set<BrowserCapability>> =
        (0 until (1 shl items.size)).map { mask ->
            items.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
        }
}
