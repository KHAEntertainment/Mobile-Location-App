package com.geoalign.core.monitor

import com.geoalign.core.alignment.MatchScope
import com.geoalign.core.model.IpGeolocation
import com.geoalign.core.model.LocationProfile
import com.geoalign.core.readiness.VpnTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AlignmentMonitorReducerTest {

    private val now = 1_700_000_000_000L
    private val lagosIp = "203.0.113.42"
    private val otherIp = "198.51.100.7"

    private fun profile(
        country: String? = "NG",
        city: String? = "Lagos",
        lat: Double = 6.5244,
        lon: Double = 3.3792,
        sourceAt: Long? = now,
        createdAt: Long = now,
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
        createdAtMillis = createdAt,
        updatedAtMillis = createdAt,
        generatedFromIp = true,
        sourceApproxTimestampMillis = sourceAt,
    )

    private fun exit(
        ip: String = lagosIp,
        country: String? = "NG",
        city: String? = "Lagos",
        lat: Double? = 6.5244,
        lon: Double? = 3.3792,
    ) = IpGeolocation(
        ip = ip,
        countryCode = country,
        countryName = "Nigeria",
        city = city,
        latitude = lat,
        longitude = lon,
        providerName = "test",
        timestampMillis = now,
    )

    private fun reduce(
        state: AlignmentMonitorState,
        vararg events: AlignmentMonitorEvent,
    ): AlignmentMonitorState = events.fold(state) { acc, e -> AlignmentMonitorReducer.reduce(acc, e) }

    private fun completed(
        transport: VpnTransport = VpnTransport.DETECTED,
        ip: String? = lagosIp,
        geo: IpGeolocation? = exit(),
        prof: LocationProfile? = profile(),
        at: Long = now,
    ) = AlignmentMonitorEvent.CheckCompleted(transport, ip, geo, prof, at)

    private fun observed(t: VpnTransport, at: Long = now) =
        AlignmentMonitorEvent.TransportObserved(t, at)

    /** Detected transport + resolved exit + matching profile, the only path to a green claim. */
    private fun aligned(): AlignmentMonitorState =
        reduce(AlignmentMonitorState(), observed(VpnTransport.DETECTED), completed())

    // --- baseline ------------------------------------------------------------------------------

    @Test fun startsOwingACheckAndClaimingNothing() {
        val s = AlignmentMonitorState()
        assertEquals(MonitorStatus.RECHECKING, s.status)
        assertEquals(MonitorReason.INITIAL, s.reason)
        assertEquals(VpnTransport.CHECKING, s.transport)
        assertFalse(s.isAligned)
        assertTrue(s.needsCheck)
    }

    @Test fun aCompletedCheckOverAMatchingProfileIsAligned() {
        val s = aligned()
        assertEquals(MonitorStatus.ALIGNED, s.status)
        assertEquals(MonitorReason.VERIFIED, s.reason)
        assertTrue(s.isAligned)
        assertFalse(s.needsCheck)
        assertFalse(s.isActionable)
        assertEquals(lagosIp, s.verifiedExitIp)
        assertEquals(now, s.lastVerifiedAtMillis)
    }

    // --- transport flap: DETECTED -> NOT_DETECTED -> DETECTED -----------------------------------

    @Test fun losingTheTransportBlocksImmediatelyWithoutWaitingForACheck() {
        val dropped = reduce(aligned(), observed(VpnTransport.NOT_DETECTED, now + 1_000))
        assertEquals(MonitorStatus.VPN_DISCONNECTED, dropped.status)
        assertEquals(MonitorReason.TRANSPORT_LOST, dropped.reason)
        assertFalse(dropped.isAligned)
        // No check is owed while there is no tunnel to check through.
        assertFalse(dropped.needsCheck)
    }

    @Test fun theTransportReturningOwesACheckRatherThanRestoringTheOldVerdict() {
        val flapped = reduce(
            aligned(),
            observed(VpnTransport.NOT_DETECTED, now + 1_000),
            observed(VpnTransport.DETECTED, now + 2_000),
        )
        assertEquals(MonitorStatus.RECHECKING, flapped.status)
        assertEquals(MonitorReason.TRANSPORT_RESTORED, flapped.reason)
        assertFalse(flapped.isAligned)
        assertTrue(flapped.needsCheck)
        // The pre-drop exit survives the flap so the next check can tell whether it moved.
        assertEquals(lagosIp, flapped.verifiedExitIp)
    }

    @Test fun aFlapThatReturnsToTheSameExitIsAlignedAgain() {
        val s = reduce(
            aligned(),
            observed(VpnTransport.NOT_DETECTED, now + 1_000),
            observed(VpnTransport.DETECTED, now + 2_000),
            completed(at = now + 3_000),
        )
        assertEquals(MonitorStatus.ALIGNED, s.status)
    }

    @Test fun aFlapThatReturnsOnADifferentExitReportsTheChange() {
        val s = reduce(
            aligned(),
            observed(VpnTransport.NOT_DETECTED, now + 1_000),
            observed(VpnTransport.DETECTED, now + 2_000),
            completed(ip = otherIp, geo = exit(ip = otherIp), at = now + 3_000),
        )
        assertEquals(MonitorStatus.EXIT_IP_CHANGED, s.status)
        assertEquals(otherIp, s.verifiedExitIp)
    }

    /**
     * registerDefaultNetworkCallback re-delivers unchanged capabilities. Treating each one as news
     * would put the screen in a permanent re-check loop.
     */
    @Test fun repeatingTheSameTransportIsANoOp() {
        val s = aligned()
        val again = reduce(s, observed(VpnTransport.DETECTED, now + 500))
        assertSame(s, again)
        assertEquals(MonitorStatus.ALIGNED, again.status)
    }

    @Test fun networkUnavailableAndTransportErrorBothCountAsDisconnected() {
        listOf(VpnTransport.NETWORK_UNAVAILABLE, VpnTransport.ERROR).forEach { t ->
            val s = reduce(aligned(), observed(t, now + 1_000))
            assertEquals(t.name, MonitorStatus.VPN_DISCONNECTED, s.status)
            assertFalse(t.name, s.isAligned)
        }
    }

    // --- exit IP change under an unchanged transport --------------------------------------------

    @Test fun anExitIpChangeWithNoTransportEventIsStillCaught() {
        // The transport never left DETECTED — only a completed check reveals the move.
        val s = reduce(aligned(), completed(ip = otherIp, geo = exit(ip = otherIp), at = now + 60_000))
        assertEquals(MonitorStatus.EXIT_IP_CHANGED, s.status)
        assertEquals(MonitorReason.EXIT_IP_CHANGED, s.reason)
        assertFalse(s.isAligned)
        assertTrue(s.isActionable)
    }

    @Test fun anAcknowledgedExitChangeSettlesBackToAlignedOnTheNextCheck() {
        val moved = reduce(aligned(), completed(ip = otherIp, geo = exit(ip = otherIp), at = now + 60_000))
        val settled = reduce(moved, completed(ip = otherIp, geo = exit(ip = otherIp), at = now + 90_000))
        assertEquals(MonitorStatus.ALIGNED, settled.status)
    }

    @Test fun theFirstEverObservationIsNotAnExitChange() {
        val s = reduce(AlignmentMonitorState(), observed(VpnTransport.DETECTED), completed())
        assertEquals(MonitorStatus.ALIGNED, s.status)
    }

    /** A contradiction a site can see right now outranks the news that the address moved. */
    @Test fun anExitThatMovedAndAlsoDriftedReportsTheMismatch() {
        val s = reduce(
            aligned(),
            completed(
                ip = otherIp,
                geo = exit(ip = otherIp, country = "DE", city = "Berlin", lat = 52.52, lon = 13.405),
                at = now + 60_000,
            ),
        )
        assertEquals(MonitorStatus.PROFILE_MISMATCH, s.status)
        assertEquals(MonitorReason.PROFILE_DRIFTED, s.reason)
    }

    // --- verification failure --------------------------------------------------------------------

    @Test fun aFailedCheckIsUnableToVerifyAndNeverAligned() {
        val s = reduce(aligned(), AlignmentMonitorEvent.CheckFailed(now + 60_000, "provider unreachable"))
        assertEquals(MonitorStatus.UNABLE_TO_VERIFY, s.status)
        assertEquals(MonitorReason.CHECK_FAILED, s.reason)
        assertFalse(s.isAligned)
        assertTrue(s.isActionable)
        assertEquals(now + 60_000, s.lastCheckedAtMillis)
        // The successful verification behind it is still the last one that happened.
        assertEquals(now, s.lastVerifiedAtMillis)
    }

    @Test fun aCheckThatResolvedNoExitIsUnableToVerify() {
        val s = reduce(aligned(), completed(ip = null, geo = null, at = now + 60_000))
        assertEquals(MonitorStatus.UNABLE_TO_VERIFY, s.status)
        assertEquals(MonitorReason.EXIT_UNKNOWN, s.reason)
        assertFalse(s.isAligned)
    }

    /**
     * The invariant the issue is explicit about. A failed check does not decay into a pass on the
     * next transport callback, and only a genuinely successful check can restore ALIGNED.
     */
    @Test fun unableToVerifyNeverCollapsesIntoAligned() {
        val failed = reduce(aligned(), AlignmentMonitorEvent.CheckFailed(now + 10_000))
        assertFalse(failed.isAligned)

        // Repeated identical transport callbacks: still not aligned.
        val nudged = reduce(failed, observed(VpnTransport.DETECTED, now + 11_000))
        assertFalse(nudged.isAligned)
        assertEquals(MonitorStatus.UNABLE_TO_VERIFY, nudged.status)

        // Asking for another check does not answer it either.
        val asked = reduce(
            nudged,
            AlignmentMonitorEvent.CheckRequested(MonitorReason.LIFECYCLE_RESUMED, now + 12_000),
        )
        assertEquals(MonitorStatus.RECHECKING, asked.status)
        assertFalse(asked.isAligned)

        // Only a check that actually completes with evidence restores it.
        val restored = reduce(asked, completed(at = now + 13_000))
        assertTrue(restored.isAligned)
    }

    @Test fun aFailedCheckKeepsTheLastKnownExitSoTheNextOneCanStillSpotAMove() {
        val failed = reduce(aligned(), AlignmentMonitorEvent.CheckFailed(now + 10_000))
        assertEquals(lagosIp, failed.verifiedExitIp)

        val recovered = reduce(failed, completed(ip = otherIp, geo = exit(ip = otherIp), at = now + 20_000))
        assertEquals(MonitorStatus.EXIT_IP_CHANGED, recovered.status)
    }

    @Test fun aCompletedCheckThatFindsNoVpnReportsTheTunnelNotTheProfile() {
        val s = reduce(
            aligned(),
            completed(transport = VpnTransport.NOT_DETECTED, at = now + 10_000),
        )
        assertEquals(MonitorStatus.VPN_DISCONNECTED, s.status)
        assertFalse(s.isAligned)
    }

    @Test fun anExitWithoutAProviderVerdictIsUnknownNotAligned() {
        // A profile exists but the estimate carries no usable labels or coordinates.
        val s = reduce(
            AlignmentMonitorState(),
            observed(VpnTransport.DETECTED),
            completed(geo = exit(country = null, city = null, lat = null, lon = null)),
        )
        assertEquals(MonitorStatus.UNABLE_TO_VERIFY, s.status)
        assertFalse(s.isAligned)
    }

    /**
     * Labels absent on the estimate but coordinates present and close, so the haversine check does
     * run and passes. That is still not evidence about the country and city a site reads, so the
     * monitor reports a failure to verify rather than a green claim. Guards the seam this fix
     * moved: the reducer now reads `matchedOn == MatchScope.NONE` instead of inferring the same
     * fact from the checker's two unverified reasons (issue #19).
     */
    @Test fun agreeingCoordinatesWithoutAnyLabelIsStillUnableToVerify() {
        val s = reduce(
            AlignmentMonitorState(),
            observed(VpnTransport.DETECTED),
            completed(geo = exit(country = null, city = null)),
        )
        assertEquals(MonitorStatus.UNABLE_TO_VERIFY, s.status)
        assertEquals(MonitorReason.EXIT_UNKNOWN, s.reason)
        assertFalse(s.isAligned)
        assertEquals(MatchScope.NONE, s.alignment?.matchedOn)
    }

    // --- no profile ------------------------------------------------------------------------------

    @Test fun noProfileIsAMismatchAndNeverAligned() {
        val s = reduce(AlignmentMonitorState(), observed(VpnTransport.DETECTED), completed(prof = null))
        assertEquals(MonitorStatus.PROFILE_MISMATCH, s.status)
        assertEquals(MonitorReason.PROFILE_ABSENT, s.reason)
        assertFalse(s.isAligned)
        assertTrue(s.isActionable)
        // The exit is still known — the user can match against it.
        assertEquals(lagosIp, s.verifiedExitIp)
        assertNotNull(s.alignment)
    }

    @Test fun savingAProfileForTheCurrentExitClearsTheMismatch() {
        val none = reduce(AlignmentMonitorState(), observed(VpnTransport.DETECTED), completed(prof = null))
        val matched = reduce(none, completed(at = now + 5_000))
        assertEquals(MonitorStatus.ALIGNED, matched.status)
    }

    // --- drift and provenance, delegated to AlignmentChecker --------------------------------------

    @Test fun aProfileInAnotherCountryIsAMismatch() {
        val s = reduce(
            AlignmentMonitorState(),
            observed(VpnTransport.DETECTED),
            completed(geo = exit(country = "DE", city = "Berlin", lat = 52.52, lon = 13.405)),
        )
        assertEquals(MonitorStatus.PROFILE_MISMATCH, s.status)
        assertEquals(MonitorReason.PROFILE_DRIFTED, s.reason)
    }

    @Test fun aProfileMintedFromAStaleEstimateIsAMismatchEvenWhenTheLabelsAgree() {
        val s = reduce(
            AlignmentMonitorState(),
            observed(VpnTransport.DETECTED),
            completed(prof = profile(sourceAt = now - 759_000, createdAt = now)),
        )
        assertEquals(MonitorStatus.PROFILE_MISMATCH, s.status)
        assertEquals(MonitorReason.PROFILE_STALE_CAPTURE, s.reason)
        assertFalse(s.isAligned)
    }

    @Test fun aRequestedCheckKeepsWhateverWasAlreadyKnown() {
        val asked = reduce(
            aligned(),
            AlignmentMonitorEvent.CheckRequested(MonitorReason.LIFECYCLE_RESUMED, now + 1_000),
        )
        assertEquals(MonitorStatus.RECHECKING, asked.status)
        assertEquals(MonitorReason.LIFECYCLE_RESUMED, asked.reason)
        assertEquals(lagosIp, asked.verifiedExitIp)
        assertNotNull(asked.alignment)
        assertFalse(asked.isAligned)
    }

    @Test fun theInitialTransportObservationIsNotReportedAsARestore() {
        val s = reduce(AlignmentMonitorState(), observed(VpnTransport.DETECTED))
        assertEquals(MonitorReason.INITIAL, s.reason)
        assertNull(s.verifiedExitIp)
    }
}
