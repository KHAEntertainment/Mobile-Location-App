package com.geoalign.ui.state

import com.geoalign.core.model.IpGeolocation
import com.geoalign.core.model.LocationProfile
import com.geoalign.core.monitor.AlignmentMonitorState
import com.geoalign.core.monitor.MonitorReason
import com.geoalign.core.monitor.MonitorStatus
import com.geoalign.core.readiness.ReadinessInputs
import com.geoalign.core.readiness.ReadinessReducer
import com.geoalign.core.readiness.StepState
import com.geoalign.core.readiness.VpnTransport
import com.geoalign.data.monitor.AlignmentSnapshot
import com.geoalign.data.net.EffectiveIp
import com.geoalign.data.net.IpVersion
import com.geoalign.data.readiness.ReadinessService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The glue between the live monitor and the screen. Every assertion here is one the composable
 * would otherwise have had to make inline, where nothing could test it.
 */
class LiveReadinessTest {

    private val now = 1_700_000_000_000L
    private val rawIp = "203.0.113.42"

    private fun geo(country: String? = "NG", city: String? = "Lagos") = IpGeolocation(
        ip = rawIp,
        countryCode = country,
        countryName = "Nigeria",
        city = city,
        latitude = 6.5244,
        longitude = 3.3792,
        timezone = "Africa/Lagos",
        providerName = "fake",
        timestampMillis = now,
    )

    private fun profile() = LocationProfile(
        id = "p1",
        name = "Lagos",
        countryCode = "NG",
        city = "Lagos",
        latitude = 6.5244,
        longitude = 3.3792,
        timezone = "Africa/Lagos",
        primaryLocale = "en-NG",
        languages = listOf("en-NG", "en"),
        createdAtMillis = now,
        updatedAtMillis = now,
        generatedFromIp = true,
        sourceApproxTimestampMillis = now,
    )

    private fun evaluation(
        vpn: VpnTransport = VpnTransport.DETECTED,
        geoStep: StepState = StepState.OK,
        geolocation: IpGeolocation? = geo(),
    ): ReadinessService.Evaluation {
        val inputs = ReadinessInputs(
            vpn = vpn,
            internetReachable = StepState.OK,
            effectiveIp = StepState.OK,
            geolocation = geoStep,
            profileSelected = true,
        )
        return ReadinessService.Evaluation(
            inputs = inputs,
            state = ReadinessReducer.reduce(inputs),
            effectiveIp = EffectiveIp(rawIp, IpVersion.V4),
            geolocation = geolocation,
        )
    }

    private fun snapshot(
        status: MonitorStatus = MonitorStatus.ALIGNED,
        reason: MonitorReason = MonitorReason.VERIFIED,
        transport: VpnTransport = VpnTransport.DETECTED,
        evaluation: ReadinessService.Evaluation? = evaluation(),
        checkedAt: Long? = now - 5_000,
        checking: Boolean = false,
        error: String? = null,
    ) = AlignmentSnapshot(
        monitor = AlignmentMonitorState(
            status = status,
            reason = reason,
            transport = transport,
            verifiedExitIp = rawIp,
            lastCheckedAtMillis = checkedAt,
            lastVerifiedAtMillis = checkedAt,
        ),
        evaluation = evaluation,
        profile = profile(),
        checkedAtMillis = checkedAt,
        errorMessage = error,
        checking = checking,
    )

    private fun present(s: AlignmentSnapshot, accepted: Boolean = false, matching: Boolean = false, matchError: String? = null) =
        ReadinessPresenter.present(
            LiveReadiness.input(s, now, accepted, pendingAction = matching, actionError = matchError),
        )

    // --- phase ------------------------------------------------------------------------------

    @Test fun nothingCheckedYetIsTheInitialLoad() {
        val input = LiveReadiness.input(
            AlignmentSnapshot(checking = true),
            now,
        )
        assertEquals(LoadPhase.INITIAL, input.phase)
        // The monitor's CHECKING placeholder is not an observation and must not read as one.
        assertNull(input.liveVpn)
    }

    @Test fun aCheckInFlightOverPriorEvidenceIsARefresh() {
        assertEquals(LoadPhase.REFRESHING, LiveReadiness.input(snapshot(checking = true), now).phase)
    }

    @Test fun aSettledCheckIsLoaded() {
        assertEquals(LoadPhase.LOADED, LiveReadiness.input(snapshot(), now).phase)
    }

    @Test fun aFailedCheckIsAnErrorEvenOnFirstLoad() {
        val input = LiveReadiness.input(
            AlignmentSnapshot(errorMessage = "provider unreachable"),
            now,
        )
        assertEquals(LoadPhase.ERROR, input.phase)
        assertEquals("provider unreachable", input.errorMessage)
    }

    @Test fun screenSideWorkIsFoldedInWithoutTheComposableDecidingAnything() {
        assertEquals(LoadPhase.REFRESHING, LiveReadiness.input(snapshot(), now, pendingAction = true).phase)

        val failed = LiveReadiness.input(snapshot(), now, actionError = "could not save profile")
        assertEquals(LoadPhase.ERROR, failed.phase)
        assertEquals("could not save profile", failed.errorMessage)
    }

    // --- what the screen ends up showing --------------------------------------------------------

    @Test fun anAlignedMonitorStillEarnsTheGreenCheck() {
        val s = present(snapshot())
        assertEquals(StatusTone.VERIFIED, s.status.tone)
        assertEquals(StatusGlyph.CHECK, s.status.glyph)
    }

    /**
     * The line this issue exists to hold. The cached evaluation is READY, the profile matches the
     * exit, the transport is present — and the live monitor could not complete its check. That is
     * not a pass, and the screen must not render it as one.
     */
    @Test fun unableToVerifyNeverReadsAsAligned() {
        val s = present(
            snapshot(status = MonitorStatus.UNABLE_TO_VERIFY, reason = MonitorReason.CHECK_FAILED),
        )
        assertFalse(StatusTone.VERIFIED == s.status.tone)
        assertEquals(StatusTone.ATTENTION, s.status.tone)
        assertTrue(s.status.notes.any { it.id == NoteId.UNABLE_TO_VERIFY })
    }

    @Test fun anExitThatMovedWithholdsTheGreenCheckAndSaysWhy() {
        val s = present(snapshot(status = MonitorStatus.EXIT_IP_CHANGED, reason = MonitorReason.EXIT_IP_CHANGED))
        assertFalse(StatusTone.VERIFIED == s.status.tone)
        assertTrue(s.status.notes.any { it.id == NoteId.EXIT_IP_CHANGED })
    }

    @Test fun aDroppedTransportBlocksTheScreenWithNoUserAction() {
        val s = present(
            snapshot(status = MonitorStatus.VPN_DISCONNECTED, reason = MonitorReason.TRANSPORT_LOST,
                transport = VpnTransport.NOT_DETECTED),
        )
        assertEquals(StatusTone.BLOCKED, s.status.tone)
        assertFalse(s.primaryAction.enabled)
        assertTrue(s.status.notes.any { it.id == NoteId.VPN_DROPPED_LIVE })
    }

    @Test fun aRecheckInFlightIsNotAGreenCheck() {
        val s = present(snapshot(status = MonitorStatus.RECHECKING, reason = MonitorReason.TRANSPORT_RESTORED, checking = true))
        assertFalse(StatusTone.VERIFIED == s.status.tone)
        assertEquals(StatusGlyph.SPINNER, s.status.glyph)
    }

    @Test fun aProfileMismatchIsRenderedFromTheEvaluationItself() {
        val s = present(
            snapshot(
                status = MonitorStatus.PROFILE_MISMATCH,
                reason = MonitorReason.PROFILE_DRIFTED,
                evaluation = evaluation(geolocation = geo(country = "DE", city = "Berlin")),
            ),
        )
        assertFalse(StatusTone.VERIFIED == s.status.tone)
        assertTrue(s.status.notes.any { it.id == NoteId.DRIFT })
    }

    /** A general "couldn't verify" underneath a specific "geolocation failed" says it twice. */
    @Test fun theUnverifiedNoteDefersToTheMoreSpecificWarning() {
        val s = present(
            snapshot(
                status = MonitorStatus.UNABLE_TO_VERIFY,
                reason = MonitorReason.EXIT_UNKNOWN,
                evaluation = evaluation(geoStep = StepState.FAILED, geolocation = null),
            ),
        )
        assertTrue(s.status.notes.any { it.id == NoteId.GEO_FAILED })
        assertFalse(s.status.notes.any { it.id == NoteId.UNABLE_TO_VERIFY })
    }
}
