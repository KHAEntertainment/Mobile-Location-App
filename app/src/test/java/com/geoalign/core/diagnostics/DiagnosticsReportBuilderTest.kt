package com.geoalign.core.diagnostics

import com.geoalign.core.browser.BrowserCapability
import com.geoalign.core.browser.BrowserCapabilityGate
import com.geoalign.core.device.DeviceProfile
import com.geoalign.core.device.DeviceProfiles
import com.geoalign.core.model.IpGeolocation
import com.geoalign.core.model.LocationProfile
import com.geoalign.core.readiness.VpnTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The report the user copies into a bug report about a hostile site.
 *
 * Two things are asserted hardest here, because they are the two the old screen got wrong: that no
 * check reports a protection as in force without evidence that it is, and that no full address
 * survives into anything a reader can copy.
 */
class DiagnosticsReportBuilderTest {

    private val rawIp = "203.0.113.42"
    private val rawIpv6 = "2606:4700:4700::1111"

    private val sydney = LocationProfile(
        id = "p1",
        name = "Sydney",
        countryCode = "AU",
        city = "Sydney",
        latitude = -33.8688,
        longitude = 151.2093,
        timezone = "Australia/Sydney",
        primaryLocale = "en-AU",
        languages = listOf("en-AU", "en"),
        createdAtMillis = 0,
        updatedAtMillis = 0,
    )

    private val spoof = DeviceProfile(
        id = "pixel",
        displayName = "Pixel 8",
        deviceClass = com.geoalign.core.device.DeviceClass.ANDROID,
        userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8) Chrome/126.0.0.0 Mobile Safari/537.36",
        mobile = true,
        navPlatform = "Linux armv8l",
        uachPlatform = "Android",
        platformVersion = "14.0.0",
        architecture = "",
        bitness = "",
        model = "Pixel 8",
        browserFullVersion = "126.0.6478.0",
        brands = emptyList(),
        screenWidth = 412,
        screenHeight = 915,
        devicePixelRatio = 2.625,
        maxTouchPoints = 5,
        emitsClientHints = true,
    )

    private fun gate(supported: Set<BrowserCapability> = BrowserCapability.entries.toSet()) =
        BrowserCapabilityGate.decide(
            supported = supported,
            webViewPackageName = "com.google.android.webview",
            webViewPackageVersion = "151.0.7922.169",
        )

    private fun observed(
        latitude: Double? = -33.8688,
        longitude: Double? = 151.2093,
        timezone: String? = "Australia/Sydney",
        language: String? = "en-AU",
        userAgent: String? = "Mozilla/5.0 (Linux; Android 10; K) Chrome/151.0.0.0 Mobile Safari/537.36",
        shimmed: Boolean = true,
        uadPresent: Boolean = true,
        uadPlatform: String? = "Android",
        uadMobile: Boolean? = true,
    ) = ObservedEnvironment(
        geolocationShimmed = shimmed,
        latitude = latitude,
        longitude = longitude,
        accuracy = 1500.0,
        timezone = timezone,
        timezoneOffsetMinutes = -600,
        language = language,
        languages = listOf("en-AU", "en"),
        userAgent = userAgent,
        platform = "Linux armv8l",
        userAgentDataPresent = uadPresent,
        userAgentDataPlatform = uadPlatform,
        userAgentDataMobile = uadMobile,
        screenWidth = 412,
        screenHeight = 915,
        devicePixelRatio = 2.625,
        maxTouchPoints = 5,
    )

    private fun input(
        supported: Set<BrowserCapability> = BrowserCapability.entries.toSet(),
        profile: LocationProfile? = sydney,
        device: DeviceProfile = DeviceProfiles.NATIVE,
        expectedUserAgent: String? =
            "Mozilla/5.0 (Linux; Android 10; K) Chrome/151.0.0.0 Mobile Safari/537.36",
        observation: ObservationOutcome = ObservationOutcome.Completed(observed()),
        vpn: VpnTransport? = VpnTransport.DETECTED,
        effectiveIp: String? = rawIp,
        exit: IpGeolocation? = IpGeolocation(
            ip = rawIp,
            countryCode = "AU",
            countryName = "Australia",
            city = "Sydney",
            latitude = -33.87,
            longitude = 151.21,
            timezone = "Australia/Sydney",
            providerName = "ipwho.is",
            timestampMillis = 0,
        ),
    ) = DiagnosticsInput(
        gate = gate(supported),
        profile = profile,
        device = device,
        expectedUserAgent = expectedUserAgent,
        observation = observation,
        vpn = vpn,
        effectiveIp = effectiveIp,
        exit = exit,
    )

    private fun DiagnosticsReport.check(label: String): DiagnosticCheck =
        checks.first { it.label == label }

    // --- the invariant that makes the report safe to paste ---------------------------------------

    /**
     * Mirrors `ReadinessPresenterTest.theRawIpNeverAppearsInAnyUserVisibleString`. The report exists
     * to be copied to a stranger, so the raw address must not reach a label, a detail, a summary
     * line, or the copied text — under any combination of inputs.
     */
    @Test fun theRawIpNeverAppearsAnywhereInTheCopiedReport() {
        val cases = listOf(
            input(),
            input(effectiveIp = rawIpv6, exit = null),
            input(observation = ObservationOutcome.Pending),
            input(observation = ObservationOutcome.NotInstalled),
            input(observation = ObservationOutcome.Failed("renderer gone")),
            input(supported = emptySet()),
            input(profile = null, vpn = VpnTransport.NOT_DETECTED),
            input(device = spoof, observation = ObservationOutcome.Completed(observed(latitude = 1.0))),
        )
        for (case in cases) {
            val report = DiagnosticsReportBuilder.build(case)
            val visible = buildList {
                add(report.title)
                addAll(report.summary)
                report.sections.forEach { section ->
                    add(section.title)
                    section.checks.forEach { add(it.label); add(it.detail); add(it.line) }
                }
                add(report.disclaimer)
                add(report.text)
            }
            for (value in visible) {
                assertFalse("raw IPv4 leaked into \"$value\"", value.contains(rawIp))
                assertFalse("raw IPv6 leaked into \"$value\"", value.contains(rawIpv6))
            }
        }
    }

    @Test fun theEffectiveIpIsReportedInItsRedactedForm() {
        assertEquals("Redacted: 203.0.x.x", DiagnosticsReportBuilder.build(input()).check("Effective IP").detail)
        assertEquals(
            "Redacted: 2606:4700:…",
            DiagnosticsReportBuilder.build(input(effectiveIp = rawIpv6)).check("Effective IP").detail,
        )
    }

    @Test fun anUnverifiedEffectiveIpWarnsRatherThanClaimingOne() {
        val check = DiagnosticsReportBuilder.build(input(effectiveIp = null)).check("Effective IP")
        assertEquals(CheckStatus.WARNING, check.status)
    }

    // --- the four states ------------------------------------------------------------------------

    /** All four are reachable, and the copied report distinguishes them. */
    @Test fun everyResultStateIsReachableAndDistinctInTheCopiedReport() {
        val report = DiagnosticsReportBuilder.build(
            input(
                supported = setOf(BrowserCapability.DOCUMENT_START_SCRIPT),
                vpn = VpnTransport.CHECKING,
                observation = ObservationOutcome.Completed(observed(timezone = "Europe/London")),
            ),
        )
        val statuses = report.checks.map { it.status }.toSet()
        assertEquals(CheckStatus.entries.toSet(), statuses)
        // Distinct in the text, not only in the data.
        CheckStatus.entries.forEach { status ->
            assertTrue(
                "${status.name} is not visible in the copied report",
                report.text.contains("[${status.paddedMarker}]"),
            )
        }
    }

    @Test fun aMissingRequiredCapabilityFailsAndAMissingOptionalOneWarns() {
        val report = DiagnosticsReportBuilder.build(input(supported = emptySet()))
        assertEquals(
            CheckStatus.FAILED,
            report.check(BrowserCapability.DOCUMENT_START_SCRIPT.displayName).status,
        )
        assertEquals(
            CheckStatus.WARNING,
            report.check(BrowserCapability.SAFE_BROWSING.displayName).status,
        )
        assertTrue(report.hasFailures)
    }

    /**
     * `ERROR_DESCRIPTION` is in the gate's optional list and deliberately absent from the Site &
     * privacy sheet — it is a diagnostic, not a protection. It belongs here.
     */
    @Test fun loadErrorDescriptionsAreReportedHereEvenThoughTheyAreNotAProtection() {
        val report = DiagnosticsReportBuilder.build(input())
        assertEquals(
            CheckStatus.PASS,
            report.check(BrowserCapability.ERROR_DESCRIPTION.displayName).status,
        )
    }

    @Test fun everyCapabilityTheGateKnowsAboutGetsALine() {
        val report = DiagnosticsReportBuilder.build(input())
        BrowserCapability.entries.forEach { capability ->
            assertTrue(
                "${capability.displayName} is missing from the report",
                report.checks.any { it.label == capability.displayName },
            )
        }
    }

    // --- what the page actually saw ---------------------------------------------------------------

    @Test fun coordinatesPassOnlyWhenThePageWasGivenTheProfilesPosition() {
        assertEquals(CheckStatus.PASS, DiagnosticsReportBuilder.build(input()).check("Coordinates").status)

        val london = ObservationOutcome.Completed(observed(latitude = 51.5074, longitude = -0.1278))
        val drifted = DiagnosticsReportBuilder.build(input(observation = london)).check("Coordinates")
        assertEquals(CheckStatus.FAILED, drifted.status)
        assertTrue(drifted.detail.contains("-33.8688, 151.2093"))
    }

    /** The exact failure the deleted POC could never have surfaced: the bundle never ran. */
    @Test fun anUnshimmedGeolocationIsAFailureNotAPass() {
        val native = ObservationOutcome.Completed(
            observed(shimmed = false, latitude = null, longitude = null),
        )
        val report = DiagnosticsReportBuilder.build(input(observation = native))
        assertEquals(CheckStatus.FAILED, report.check("Document-start injection").status)
        assertEquals(CheckStatus.FAILED, report.check("Coordinates").status)
    }

    @Test fun aTimezoneOrLanguageThatDidNotTakeIsReportedAsAFailure() {
        val wrong = ObservationOutcome.Completed(observed(timezone = "Europe/London", language = "en-GB"))
        val report = DiagnosticsReportBuilder.build(input(observation = wrong))
        assertEquals(CheckStatus.FAILED, report.check("Timezone").status)
        assertEquals(CheckStatus.FAILED, report.check("Language").status)
        assertTrue(report.check("Timezone").detail.contains("Australia/Sydney"))
    }

    @Test fun aUserAgentStillCarryingTheWebViewMarkerFails() {
        val wv = ObservationOutcome.Completed(
            observed(userAgent = "Mozilla/5.0 (Linux; Android 14; SM-F956U1; wv) Chrome/151.0.7922.169"),
        )
        val check = DiagnosticsReportBuilder.build(input(observation = wv)).check("User-agent string")
        assertEquals(CheckStatus.FAILED, check.status)
    }

    @Test fun aUserAgentThatDoesNotMatchWhatThisDeviceModeSetsFails() {
        val other = ObservationOutcome.Completed(observed(userAgent = "Mozilla/5.0 (X11; Linux) Chrome/126"))
        val check = DiagnosticsReportBuilder.build(input(observation = other)).check("User-agent string")
        assertEquals(CheckStatus.FAILED, check.status)
    }

    @Test fun clientHintsWarnRatherThanFailWhenTheWebViewCannotSetThem() {
        val supported = BrowserCapability.entries.toSet() - BrowserCapability.USER_AGENT_METADATA
        val check = DiagnosticsReportBuilder.build(
            input(supported = supported, observation = ObservationOutcome.Completed(observed(uadPresent = false))),
        ).check("Client hints")
        assertEquals(CheckStatus.WARNING, check.status)
    }

    @Test fun clientHintsFailWhenThePageSeesAPlatformTheDeviceDoesNotClaim() {
        val check = DiagnosticsReportBuilder.build(
            input(
                device = spoof,
                expectedUserAgent = spoof.userAgent,
                observation = ObservationOutcome.Completed(
                    observed(userAgent = spoof.userAgent, uadPlatform = "Windows"),
                ),
            ),
        ).check("Client hints")
        assertEquals(CheckStatus.FAILED, check.status)
    }

    @Test fun aSpoofPresetWhoseGeometryDidNotTakeFails() {
        val check = DiagnosticsReportBuilder.build(
            input(
                device = spoof,
                expectedUserAgent = spoof.userAgent,
                observation = ObservationOutcome.Completed(
                    observed(userAgent = spoof.userAgent).copy(screenWidth = 1080, screenHeight = 2400),
                ),
            ),
        ).check("Screen & touch")
        assertEquals(CheckStatus.FAILED, check.status)
    }

    // --- observation outcomes ---------------------------------------------------------------------

    @Test fun anObservationThatNeverArrivedIsNeverReportedAsAPass() {
        listOf(
            ObservationOutcome.Pending to CheckStatus.WARNING,
            ObservationOutcome.NotInstalled to CheckStatus.UNSUPPORTED,
            ObservationOutcome.Failed("renderer gone") to CheckStatus.FAILED,
        ).forEach { (outcome, expected) ->
            val report = DiagnosticsReportBuilder.build(input(observation = outcome))
            assertEquals(expected, report.check("Observation").status)
            // And nothing else in that section claims anything at all.
            val environment = report.sections.first { it.title == "Environment seen by pages" }
            assertEquals(1, environment.checks.size)
        }
    }

    // --- connection ---------------------------------------------------------------------------

    @Test fun theVpnTransportIsReportedWithoutSofteningWhatIsMissing() {
        assertEquals(CheckStatus.PASS, DiagnosticsReportBuilder.build(input()).check("VPN transport").status)
        assertEquals(
            CheckStatus.FAILED,
            DiagnosticsReportBuilder.build(input(vpn = VpnTransport.NOT_DETECTED)).check("VPN transport").status,
        )
        assertEquals(
            CheckStatus.WARNING,
            DiagnosticsReportBuilder.build(input(vpn = VpnTransport.ERROR)).check("VPN transport").status,
        )
    }

    // --- known gaps -----------------------------------------------------------------------------

    /** These are limits of an app-level WebView; they are never a pass and never a fault. */
    @Test fun theKnownGapsAreAlwaysReportedAsUnsupportedOnEveryDevice() {
        val cases = listOf(input(), input(supported = emptySet()), input(profile = null))
        for (case in cases) {
            val gaps = DiagnosticsReportBuilder.build(case).sections.first { it.title == "Known gaps" }
            assertTrue(gaps.checks.isNotEmpty())
            assertTrue(gaps.checks.all { it.status == CheckStatus.UNSUPPORTED })
            assertTrue(gaps.checks.any { it.label.contains("WebSocket") })
            assertTrue(gaps.checks.any { it.label.contains("DNS rebinding") })
        }
    }

    // --- the copied text ------------------------------------------------------------------------

    @Test fun theCopiedTextCarriesTheProfileTheDeviceAndEveryCheck() {
        val report = DiagnosticsReportBuilder.build(input())
        val text = report.text
        assertTrue(text.startsWith(DiagnosticsReportBuilder.TITLE))
        assertTrue(text.contains("Sydney"))
        assertTrue(text.contains(DeviceProfiles.NATIVE.displayName))
        assertTrue(text.contains("com.google.android.webview 151.0.7922.169"))
        report.checks.forEach { assertTrue("${it.label} missing from text", text.contains(it.label)) }
        assertTrue(text.endsWith(DiagnosticsReportBuilder.DISCLAIMER))
    }

    /** A report copied on a device with a comma decimal separator must still be parseable. */
    @Test fun coordinatesAreFormattedInTheRootLocale() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val report = DiagnosticsReportBuilder.build(input())
            assertTrue(report.text.contains("-33.8688, 151.2093"))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test fun noProfileIsAWarningRatherThanAFailureOfTheBrowser() {
        val report = DiagnosticsReportBuilder.build(input(profile = null))
        assertEquals(CheckStatus.WARNING, report.check("Coordinates").status)
        assertEquals(CheckStatus.WARNING, report.check("Timezone").status)
        assertTrue(report.summary.any { it.contains("none selected") })
    }
}
