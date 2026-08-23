package com.geoalign.ui.state

import com.geoalign.core.model.IpGeolocation
import com.geoalign.core.model.LocationProfile
import com.geoalign.core.readiness.ReadinessInputs
import com.geoalign.core.readiness.ReadinessReducer
import com.geoalign.core.readiness.StepState
import com.geoalign.core.readiness.VpnTransport
import com.geoalign.data.net.EffectiveIp
import com.geoalign.data.net.IpVersion
import com.geoalign.data.readiness.ReadinessService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessPresenterTest {

    private val now = 1_700_000_000_000L
    private val rawIp = "203.0.113.42"

    private fun geo(
        country: String? = "NG",
        city: String? = "Lagos",
        lat: Double? = 6.5244,
        lon: Double? = 3.3792,
    ) = IpGeolocation(
        ip = rawIp,
        countryCode = country,
        countryName = "Nigeria",
        city = city,
        latitude = lat,
        longitude = lon,
        timezone = "Africa/Lagos",
        org = "Example ISP",
        providerName = "ipwho.is",
        timestampMillis = now,
    )

    private fun profile(
        country: String? = "NG",
        city: String? = "Lagos",
        lat: Double = 6.5244,
        lon: Double = 3.3792,
        sourceAt: Long? = now,
    ) = LocationProfile(
        id = "p1",
        name = city ?: "profile",
        countryCode = country,
        city = city,
        latitude = lat,
        longitude = lon,
        timezone = "Africa/Lagos",
        primaryLocale = "en-NG",
        languages = listOf("en-NG", "en"),
        createdAtMillis = now,
        updatedAtMillis = now,
        generatedFromIp = true,
        sourceApproxTimestampMillis = sourceAt,
    )

    private fun eval(
        vpn: VpnTransport = VpnTransport.DETECTED,
        internet: StepState = StepState.OK,
        ipStep: StepState = StepState.OK,
        geoStep: StepState = StepState.OK,
        profileSelected: Boolean = true,
        acceptedNoVpn: Boolean = false,
        divergence: Boolean = false,
        geolocation: IpGeolocation? = geo(),
        ip: EffectiveIp? = EffectiveIp(rawIp, IpVersion.V4),
    ): ReadinessService.Evaluation {
        val inputs = ReadinessInputs(
            vpn = vpn,
            internetReachable = internet,
            effectiveIp = ipStep,
            geolocation = geoStep,
            profileSelected = profileSelected,
            userAcceptedNoVpn = acceptedNoVpn,
            ipStackDivergence = divergence,
        )
        return ReadinessService.Evaluation(inputs, ReadinessReducer.reduce(inputs), ip, geolocation)
    }

    private fun input(
        phase: LoadPhase = LoadPhase.LOADED,
        evaluation: ReadinessService.Evaluation? = eval(),
        prof: LocationProfile? = profile(),
        checkedAt: Long? = now - 5_000,
        accepted: Boolean = false,
        liveVpn: VpnTransport? = null,
        error: String? = null,
    ) = ReadinessPresentationInput(
        phase = phase,
        evaluation = evaluation,
        profile = prof,
        errorMessage = error,
        checkedAtMillis = checkedAt,
        nowMillis = now,
        userAcceptedNoVpn = accepted,
        liveVpn = liveVpn,
    )

    // --- baseline states -------------------------------------------------------------------

    @Test fun initialLoadShowsSpinnerAndBlocksBrowsing() {
        val s = ReadinessPresenter.present(
            input(phase = LoadPhase.INITIAL, evaluation = null, prof = null, checkedAt = null),
        )
        assertEquals(StatusGlyph.SPINNER, s.status.glyph)
        assertEquals(StatusTone.NEUTRAL, s.status.tone)
        assertFalse(s.primaryAction.enabled)
        assertTrue(s.status.notes.isEmpty())
    }

    @Test fun alignedAndFreshEarnsTheGreenCheck() {
        val s = ReadinessPresenter.present(input())
        assertEquals(StatusTone.VERIFIED, s.status.tone)
        assertEquals(StatusGlyph.CHECK, s.status.glyph)
        assertEquals("Lagos, Nigeria", s.status.exitLine)
        assertTrue(s.primaryAction.enabled)
        assertEquals("Checked moments ago", s.status.freshnessLine)
    }

    // --- no VPN ----------------------------------------------------------------------------

    @Test fun noVpnBlocksAndOffersAnExplicitOptIn() {
        val s = ReadinessPresenter.present(input(evaluation = eval(vpn = VpnTransport.NOT_DETECTED)))
        assertEquals(StatusTone.BLOCKED, s.status.tone)
        assertFalse(s.primaryAction.enabled)
        assertNotNull(s.noVpnPrompt)
        val optIn = s.secondaryActions.single { it.id == ActionId.CONTINUE_WITHOUT_VPN }
        assertEquals(Emphasis.TEXT, optIn.emphasis)
    }

    /** With no network there is no VPN decision to make; offering one would imply a false choice. */
    @Test fun noNetworkOffersNoOptIn() {
        val s = ReadinessPresenter.present(
            input(evaluation = eval(
                vpn = VpnTransport.NETWORK_UNAVAILABLE,
                internet = StepState.FAILED, ipStep = StepState.FAILED,
                geoStep = StepState.UNKNOWN, geolocation = null, ip = null,
            )),
        )
        assertEquals(StatusTone.BLOCKED, s.status.tone)
        assertNull(s.noVpnPrompt)
        assertTrue(s.secondaryActions.none { it.id == ActionId.CONTINUE_WITHOUT_VPN })
    }

    @Test fun unconfirmedTransportIsStillAcceptableWithRisk() {
        val s = ReadinessPresenter.present(input(evaluation = eval(vpn = VpnTransport.ERROR)))
        assertNotNull(s.noVpnPrompt)
    }

    @Test fun acceptingNoVpnUnblocksButNeverTurnsGreen() {
        val s = ReadinessPresenter.present(
            input(
                evaluation = eval(vpn = VpnTransport.NOT_DETECTED, acceptedNoVpn = true),
                accepted = true,
            ),
        )
        assertTrue(s.primaryAction.enabled)
        assertEquals(StatusTone.ATTENTION, s.status.tone)
        assertTrue(s.status.notes.any { it.id == NoteId.NO_VPN_ACCEPTED })
        assertNull(s.noVpnPrompt)
    }

    /** Blocked users must not have to find a small glyph to recover. */
    @Test fun blockedStateAlsoOffersAFullWidthCheckAgain() {
        val s = ReadinessPresenter.present(input(evaluation = eval(vpn = VpnTransport.NOT_DETECTED)))
        assertTrue(s.secondaryActions.any { it.id == ActionId.REFRESH })
    }

    // --- drift ------------------------------------------------------------------------------

    @Test fun driftWarnsWithoutBlockingBrowsing() {
        val s = ReadinessPresenter.present(
            input(prof = profile(country = "SG", city = "Singapore", lat = 1.35, lon = 103.82)),
        )
        assertEquals(StatusTone.ATTENTION, s.status.tone)
        assertEquals(StatusGlyph.ALERT, s.status.glyph)
        assertTrue(s.status.notes.any { it.id == NoteId.DRIFT })
        assertTrue("drift informs, it does not lock the user out", s.primaryAction.enabled)
    }

    /**
     * Regression guard for 3d3108b. Every label agrees, readiness is READY — only the capture
     * provenance reveals the profile was minted from a 759s-old estimate. It must not read green.
     */
    @Test fun staleCaptureNeverShowsTheGreenCheck() {
        val s = ReadinessPresenter.present(input(prof = profile(sourceAt = now - 759_000)))
        assertEquals(StatusTone.ATTENTION, s.status.tone)
        assertTrue(s.status.notes.any { it.id == NoteId.STALE_CAPTURE })
        assertFalse(s.status.glyph == StatusGlyph.CHECK)
    }

    @Test fun staleReadingDowngradesFromVerified() {
        val s = ReadinessPresenter.present(input(checkedAt = now - 12 * 60_000))
        assertEquals(StatusTone.NEUTRAL, s.status.tone)
        assertTrue(s.status.notes.any { it.id == NoteId.STALE_EVALUATION })
        assertEquals("Checked 12 min ago", s.status.freshnessLine)
    }

    // --- profile / geo gaps -------------------------------------------------------------------

    @Test fun noProfileDisablesBrowsingButAllowsMatching() {
        val s = ReadinessPresenter.present(
            input(evaluation = eval(profileSelected = false), prof = null),
        )
        assertEquals("No browser profile yet", s.status.headline)
        assertFalse(s.primaryAction.enabled)
        assertTrue(s.secondaryActions.single { it.id == ActionId.REMATCH }.enabled)
    }

    @Test fun missingGeolocationDisablesMatching() {
        val s = ReadinessPresenter.present(
            input(evaluation = eval(geoStep = StepState.FAILED, geolocation = null), prof = null),
        )
        assertFalse(s.secondaryActions.single { it.id == ActionId.REMATCH }.enabled)
        assertTrue(s.status.notes.any { it.id == NoteId.GEO_FAILED })
    }

    @Test fun errorPhaseSurfacesAndOffersRetry() {
        val s = ReadinessPresenter.present(
            input(phase = LoadPhase.ERROR, evaluation = null, error = "boom"),
        )
        assertEquals(StatusTone.ATTENTION, s.status.tone)
        assertTrue(s.status.notes.any { it.id == NoteId.ERROR })
        assertTrue(s.refresh.enabled)
    }

    // --- the live-VPN seam ---------------------------------------------------------------------

    @Test fun aLiveVpnDropOverridesACachedReadyEvaluation() {
        val s = ReadinessPresenter.present(input(liveVpn = VpnTransport.NOT_DETECTED))
        assertEquals(StatusTone.BLOCKED, s.status.tone)
        assertEquals(StatusTone.BLOCKED, s.status.transportTone)
        assertFalse(s.primaryAction.enabled)
        assertTrue(s.status.notes.any { it.id == NoteId.VPN_DROPPED_LIVE })
    }

    @Test fun absentLiveSignalLeavesTheCachedEvaluationAlone() {
        val withNull = ReadinessPresenter.present(input(liveVpn = null))
        assertEquals(StatusTone.VERIFIED, withNull.status.tone)
        assertTrue(withNull.status.notes.none { it.id == NoteId.VPN_DROPPED_LIVE })
    }

    /** The status block must not say the same thing twice in two type sizes. */
    @Test fun theTransportLineIsDroppedWhenItOnlyRestatesTheHeadline() {
        val blocked = ReadinessPresenter.present(input(evaluation = eval(vpn = VpnTransport.NOT_DETECTED)))
        assertEquals("No VPN detected", blocked.status.headline)
        assertNull(blocked.status.transportLine)

        // When it adds something, it stays.
        val aligned = ReadinessPresenter.present(input())
        assertEquals("Browser aligned", aligned.status.headline)
        assertEquals("VPN detected", aligned.status.transportLine)
    }

    // --- invariants ----------------------------------------------------------------------------

    /** The raw address must never reach the screen; only the redacted form may. */
    @Test fun theRawIpNeverAppearsInAnyUserVisibleString() {
        val cases = listOf(
            input(),
            input(evaluation = eval(vpn = VpnTransport.NOT_DETECTED)),
            input(prof = profile(country = "SG", city = "Singapore", lat = 1.35, lon = 103.82)),
            input(phase = LoadPhase.ERROR),
            input(liveVpn = VpnTransport.NOT_DETECTED),
        )
        for (c in cases) {
            val s = ReadinessPresenter.present(c)
            val visible = buildList {
                add(s.status.headline)
                s.status.exitLine?.let { add(it) }
                s.status.transportLine?.let { add(it) }
                s.status.freshnessLine?.let { add(it) }
                s.status.notes.forEach { add(it.text) }
                s.connectionDetails.forEach { add(it.label); add(it.value) }
                s.disclosures.forEach { add(it.label); it.summary?.let { v -> add(v) } }
                s.allActions.forEach { add(it.label) }
            }
            for (v in visible) {
                assertFalse("raw IP leaked into \"$v\"", v.contains(rawIp))
            }
            assertTrue(s.connectionDetails.any { it.value == "203.0.x.x" })
        }
    }

    /** Exactly one strong call to action, always the same one. */
    @Test fun thereIsAlwaysExactlyOnePrimaryActionAndItIsOpenBrowser() {
        val transports = listOf(
            VpnTransport.DETECTED, VpnTransport.NOT_DETECTED,
            VpnTransport.ERROR, VpnTransport.NETWORK_UNAVAILABLE, VpnTransport.CHECKING,
        )
        for (t in transports) for (hasProfile in listOf(true, false)) for (accepted in listOf(true, false)) {
            for (phase in LoadPhase.entries) {
                val s = ReadinessPresenter.present(
                    input(
                        phase = phase,
                        evaluation = eval(vpn = t, profileSelected = hasProfile, acceptedNoVpn = accepted),
                        prof = if (hasProfile) profile() else null,
                        accepted = accepted,
                    ),
                )
                val primaries = s.allActions.filter { it.emphasis == Emphasis.PRIMARY }
                assertEquals("t=$t profile=$hasProfile accepted=$accepted phase=$phase",
                    1, primaries.size)
                assertEquals(ActionId.OPEN_BROWSER, primaries.single().id)
            }
        }
    }
}
