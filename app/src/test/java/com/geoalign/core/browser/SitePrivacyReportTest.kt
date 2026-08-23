package com.geoalign.core.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The honesty test for the Site & privacy sheet.
 *
 * The bug it exists to prevent: the sheet stated "Location: virtual — pages see your profile's
 * coordinates, not the device GPS" unconditionally, on every device, including one whose WebView
 * cannot register a document-start script and therefore never received a virtual environment at
 * all. No protection may be reported active merely because it was requested — so these tests drive
 * the generator over every capability subset and assert that no line claims a protection the
 * capability set did not confirm.
 */
class SitePrivacyReportTest {

    private val all = BrowserCapability.entries.toSet()

    private fun report(supported: Set<BrowserCapability>) =
        SitePrivacySheet.forSession(host = "example.com", deviceLabel = "Pixel 8", supported = supported)

    // ------------------------------------------------------------------ the structural invariant

    @Test fun noClaimIsActiveWithoutTheCapabilitiesItNames() {
        // The general form of the criterion: over all 2^5 capability sets, a claim may only reach
        // ACTIVE when every capability it declares a dependency on is actually present.
        powerSet(BrowserCapability.entries.toList()).forEach { supported ->
            report(supported).claims.forEach { claim ->
                if (claim.state == ProtectionState.ACTIVE) {
                    assertTrue(
                        "claim \"${claim.title}\" is ACTIVE with $supported but needs ${claim.requiresAll}",
                        supported.containsAll(claim.requiresAll),
                    )
                }
            }
        }
    }

    @Test fun aClaimThatNamesNoCapabilityIsOneThisAppEnforcesItself() {
        // Camera/microphone denial and certificate refusal live in this app's WebChromeClient,
        // WebViewClient and settings, so no WebView build can take them away. Everything else has
        // to name what it depends on, or the invariant above would be vacuous.
        val independent = report(all).claims.filter { it.requiresAll.isEmpty() }
        assertEquals(
            listOf("Camera & microphone", "Connections"),
            independent.filter { it.state == ProtectionState.ACTIVE }.map { it.title },
        )
    }

    // --------------------------------------------------------------------- the phrase-level check

    @Test fun noPhraseAssertsAProtectionTheCapabilitySetDidNotConfirm() {
        // Belt and braces over the invariant above: the specific words a reader would take as a
        // promise, each tied to the capability that has to be present for them to appear at all.
        val claimPhrases = mapOf(
            "virtual" to BrowserCapability.DOCUMENT_START_SCRIPT,
            "the client hints match it" to BrowserCapability.USER_AGENT_METADATA,
            "Safe Browsing is on" to BrowserCapability.SAFE_BROWSING,
        )

        powerSet(BrowserCapability.entries.toList()).forEach { supported ->
            val text = report(supported).text
            claimPhrases.forEach { (phrase, capability) ->
                if (capability !in supported) {
                    assertFalse(
                        "\"$phrase\" appears with $supported, which lacks $capability:\n$text",
                        text.contains(phrase),
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------------ the specific claims

    @Test fun locationIsOnlyCalledVirtualWhenTheEnvironmentCanBeInstalled() {
        val withInjection = report(setOf(BrowserCapability.DOCUMENT_START_SCRIPT)).claims.first()
        assertEquals("Location", withInjection.title)
        assertEquals(ProtectionState.ACTIVE, withInjection.state)
        assertTrue(withInjection.detail.contains("virtual"))

        val without = report(all - BrowserCapability.DOCUMENT_START_SCRIPT).claims.first()
        assertEquals("Location", without.title)
        assertEquals(ProtectionState.UNAVAILABLE, without.state)
        assertFalse(without.detail.contains("virtual"))
        // And it says what is actually true instead of staying silent.
        assertTrue(without.detail.contains("this device's real location APIs"))
    }

    @Test fun deviceEmulationIsDegradedWhenClientHintsAreUnavailable() {
        // The quieter half of the same overclaim: the user-agent string always applies, but the
        // Sec-CH-UA request headers need a capability JavaScript cannot substitute for.
        val degraded = deviceClaim(all - BrowserCapability.USER_AGENT_METADATA)
        assertEquals(ProtectionState.DEGRADED, degraded.state)
        assertTrue(degraded.detail.contains("Pixel 8"))
        assertTrue(degraded.detail.contains("Sec-CH-UA request headers still identify it"))

        val active = deviceClaim(all)
        assertEquals(ProtectionState.ACTIVE, active.state)
        assertEquals(
            setOf(BrowserCapability.DOCUMENT_START_SCRIPT, BrowserCapability.USER_AGENT_METADATA),
            active.requiresAll,
        )
    }

    @Test fun deviceEmulationIsUnavailableWithNoDocumentStartScript() {
        val claim = deviceClaim(all - BrowserCapability.DOCUMENT_START_SCRIPT)
        assertEquals(ProtectionState.UNAVAILABLE, claim.state)
        assertTrue(claim.detail.contains("the device bundle could not be installed"))
    }

    @Test fun safeBrowsingIsClaimedOnlyWhenItCanBeSwitchedOn() {
        assertEquals(ProtectionState.ACTIVE, claim(all, "Malicious sites").state)
        assertEquals(
            ProtectionState.UNAVAILABLE,
            claim(all - BrowserCapability.SAFE_BROWSING, "Malicious sites").state,
        )
    }

    @Test fun serviceWorkerFilteringIsNeverClaimedActiveOnAnyDevice() {
        // `serviceWorkerControl` is carried as a fact and nothing consumes it yet, so even a WebView
        // that supports it buys the user nothing today. Reporting it as a working filter would be
        // this same bug in a new place.
        powerSet(BrowserCapability.entries.toList()).forEach { supported ->
            val claim = claim(supported, "Local-network filtering")
            assertTrue(
                "local-network filtering claimed ACTIVE with $supported",
                claim.state != ProtectionState.ACTIVE,
            )
        }
        assertEquals(
            ProtectionState.DEGRADED,
            claim(all, "Local-network filtering").state,
        )
        assertEquals(
            ProtectionState.UNAVAILABLE,
            claim(all - BrowserCapability.SERVICE_WORKER_CONTROL, "Local-network filtering").state,
        )
    }

    // ------------------------------------------------------------------------------- the rendering

    @Test fun theRenderedSheetCarriesEverySiteAndDisclaimerLine() {
        val text = report(all).text
        assertTrue(text.startsWith("Site: example.com"))
        assertTrue(text.endsWith(SitePrivacySheet.DISCLAIMER))
        report(all).claims.forEach { assertTrue(text.contains(it.line)) }
    }

    @Test fun aBlankHostRendersAsAnEmDashRatherThanAnEmptyLine() {
        assertEquals("Site: —", SitePrivacySheet.forSession("", "Pixel 8", all).siteLine)
    }

    @Test fun activeClaimsAreTheOnlyOnesAReaderMayRelyOn() {
        val none = report(emptySet())
        assertEquals(
            listOf("Camera & microphone", "Connections"),
            none.activeClaims.map { it.title },
        )
    }

    private fun claim(supported: Set<BrowserCapability>, title: String): ProtectionClaim =
        report(supported).claims.first { it.title == title }

    private fun deviceClaim(supported: Set<BrowserCapability>): ProtectionClaim =
        claim(supported, "Device")

    private fun powerSet(items: List<BrowserCapability>): List<Set<BrowserCapability>> =
        (0 until (1 shl items.size)).map { mask ->
            items.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
        }
}
