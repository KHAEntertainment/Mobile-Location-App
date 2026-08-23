package com.geoalign.core.browser

import com.geoalign.core.monitor.AlignmentMonitorState
import com.geoalign.core.monitor.MonitorReason
import com.geoalign.core.monitor.MonitorStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pause policy for the browser (issue #6), on the JVM. No Robolectric, no Mockito — the guard
 * is pure Kotlin and everything it decides is reachable from here.
 */
class BrowserAlignmentGuardTest {

    private fun state(status: MonitorStatus) = AlignmentMonitorState(
        status = status,
        reason = MonitorReason.VERIFIED,
    )

    private val problemStates = listOf(
        MonitorStatus.VPN_DISCONNECTED,
        MonitorStatus.EXIT_IP_CHANGED,
        MonitorStatus.PROFILE_MISMATCH,
        MonitorStatus.UNABLE_TO_VERIFY,
    )

    // ------------------------------------------------- the pause predicate is isActionable

    /**
     * **The bug this API was shaped to prevent.**
     *
     * `RECHECKING` is not aligned, so a guard written as `!isAligned` would pause here. Every
     * `CheckRequested` produces this state — a manual refresh, a lifecycle resume, and every
     * transport callback that `registerDefaultNetworkCallback` re-fires — so that guard would
     * interrupt the user on each transient connectivity blip. `isActionable` is false here, and
     * that is the whole difference between the two predicates.
     */
    @Test fun recheckingIsNotAlignedButIsNotAProblemEither() {
        val rechecking = state(MonitorStatus.RECHECKING)

        // The premise: this state would trip a `!isAligned` guard.
        assertFalse("RECHECKING must not read as aligned", rechecking.isAligned)
        assertFalse("RECHECKING must not be actionable", rechecking.isActionable)

        val decision = BrowserAlignmentGuard.decide(rechecking)

        assertFalse("a re-check must not pause navigation", decision.navigationHeld)
        assertFalse("a re-check must not prompt", decision.promptVisible)
        assertEquals(AlignmentSeverity.CHECKING, decision.severity)
        // And emphatically not a pass either.
        assertFalse("a re-check is not a verified state", decision.mayRenderVerified)
    }

    /** The counterpart: a real problem state, which differs from RECHECKING only in isActionable. */
    @Test fun everyActionableStatusPausesNavigationAndPrompts() {
        problemStates.forEach { status ->
            val problem = state(status)
            assertFalse("$status must not read as aligned", problem.isAligned)
            assertTrue("$status must be actionable", problem.isActionable)

            val decision = BrowserAlignmentGuard.decide(problem)

            assertTrue("$status must pause navigation", decision.navigationHeld)
            assertTrue("$status must prompt", decision.promptVisible)
            assertFalse("$status must never render verified", decision.mayRenderVerified)
        }
    }

    /**
     * Stated as an exhaustive sweep so the two predicates cannot quietly converge later: if some
     * future change made `navigationHeld` equal `!isAligned`, RECHECKING would flip and this fails.
     */
    @Test fun navigationHoldTracksIsActionableAndNotNotAligned() {
        MonitorStatus.entries.forEach { status ->
            val s = state(status)
            val held = BrowserAlignmentGuard.decide(s).navigationHeld
            assertEquals("hold must follow isActionable for $status", s.isActionable, held)
            if (status == MonitorStatus.RECHECKING) {
                assertTrue("RECHECKING is the case where the two predicates disagree", !s.isAligned)
                assertFalse("and the hold must follow isActionable, not !isAligned", held)
            }
        }
    }

    @Test fun alignedNeitherPausesNorPrompts() {
        val decision = BrowserAlignmentGuard.decide(state(MonitorStatus.ALIGNED))
        assertFalse(decision.navigationHeld)
        assertFalse(decision.promptVisible)
        assertTrue(decision.mayRenderVerified)
        assertEquals(emptyList<AlignmentRecoveryAction>(), decision.actions)
    }

    // ------------------------------------------------- the four choices

    @Test fun everyProblemOffersAllFourRecoveries() {
        val expected = listOf(
            AlignmentRecoveryAction.RECHECK,
            AlignmentRecoveryAction.REMATCH_PROFILE,
            AlignmentRecoveryAction.CONTINUE_WITH_WARNING,
            AlignmentRecoveryAction.LEAVE_BROWSER,
        )
        problemStates.forEach { status ->
            assertEquals("$status", expected, BrowserAlignmentGuard.decide(state(status)).actions)
        }
    }

    // ------------------------------------------------- continue with warning

    @Test fun continuingWithAWarningReleasesNavigationButNeverRendersVerified() {
        val problem = state(MonitorStatus.VPN_DISCONNECTED)
        val accepted = BrowserAlignmentGuard.acknowledge(problem)

        val decision = BrowserAlignmentGuard.decide(problem, accepted)

        assertFalse("accepted risk must let new pages load", decision.navigationHeld)
        assertFalse("the prompt is answered", decision.promptVisible)
        assertEquals(AlignmentSeverity.ACCEPTED_RISK, decision.severity)
        assertFalse("continue-with-warning is never verified", decision.mayRenderVerified)
    }

    @Test fun acceptedRiskIsNeverVerifiedForAnyProblemStatus() {
        problemStates.forEach { status ->
            val problem = state(status)
            val decision = BrowserAlignmentGuard.decide(problem, BrowserAlignmentGuard.acknowledge(problem))
            assertFalse("$status accepted must not be verified", decision.mayRenderVerified)
            assertEquals("$status", AlignmentSeverity.ACCEPTED_RISK, decision.severity)
        }
    }

    @Test fun thereIsNothingToAcknowledgeWhenNothingIsWrong() {
        assertNull(BrowserAlignmentGuard.acknowledge(state(MonitorStatus.ALIGNED)))
        assertNull(BrowserAlignmentGuard.acknowledge(state(MonitorStatus.RECHECKING)))
    }

    // ------------------------------------------------- acknowledgement lifetime

    /**
     * Consent is to one situation, not to a mood — the same rule `NoVpnAcceptance` applies to the
     * readiness screen. Inheriting it would silently un-pause the browser at the moment it should
     * pause.
     */
    @Test fun acceptingOneProblemIsNotConsentToADifferentOne() {
        val accepted = BrowserAlignmentGuard.acknowledge(state(MonitorStatus.VPN_DISCONNECTED))
        val different = state(MonitorStatus.PROFILE_MISMATCH)

        assertNull(BrowserAlignmentGuard.nextAcknowledgement(accepted, different))
        val decision = BrowserAlignmentGuard.decide(different, accepted)
        assertTrue("a new problem must re-pause", decision.navigationHeld)
        assertTrue("and re-prompt", decision.promptVisible)
    }

    @Test fun acceptanceSurvivesTheSameProblemBeingReObserved() {
        val problem = state(MonitorStatus.EXIT_IP_CHANGED)
        val accepted = BrowserAlignmentGuard.acknowledge(problem)
        assertEquals(accepted, BrowserAlignmentGuard.nextAcknowledgement(accepted, problem))
        assertFalse(BrowserAlignmentGuard.decide(problem, accepted).navigationHeld)
    }

    @Test fun recoveringClearsAcceptanceSoALaterDropPromptsAgain() {
        val accepted = BrowserAlignmentGuard.acknowledge(state(MonitorStatus.VPN_DISCONNECTED))

        val cleared = BrowserAlignmentGuard.nextAcknowledgement(accepted, state(MonitorStatus.ALIGNED))
        assertNull(cleared)

        // The same problem returning is a new decision, not the old one still standing.
        val again = BrowserAlignmentGuard.decide(state(MonitorStatus.VPN_DISCONNECTED), cleared)
        assertTrue(again.navigationHeld)
        assertTrue(again.promptVisible)
    }

    @Test fun aRecheckDoesNotBurnTheAcceptanceOfTheProblemBeingRechecked() {
        val accepted = BrowserAlignmentGuard.acknowledge(state(MonitorStatus.VPN_DISCONNECTED))
        // RECHECKING is not actionable, so acceptance is dropped — and correctly so: a check that
        // has not finished cannot be the thing the user accepted. What matters is that the interim
        // state does not pause, which is asserted here rather than assumed.
        val interim = BrowserAlignmentGuard.decide(state(MonitorStatus.RECHECKING), accepted)
        assertFalse(interim.navigationHeld)
        assertFalse(interim.promptVisible)
    }

    /** A stale acknowledgement handed in by a caller must not suppress a prompt it never covered. */
    @Test fun decideReDerivesAcceptanceRatherThanTrustingTheCaller() {
        val stale = MonitorStatus.UNABLE_TO_VERIFY
        val now = state(MonitorStatus.PROFILE_MISMATCH)
        assertTrue(BrowserAlignmentGuard.decide(now, stale).promptVisible)
    }

    // ------------------------------------------------- the no-VPN opt-in

    /**
     * Continuing past a lost tunnel *is* the readiness screen's no-VPN opt-in. If the browser did
     * not say so, the two surfaces would disagree about whether to warn.
     */
    @Test fun continuingPastALostVpnIsTheNoVpnOptIn() {
        val dropped = state(MonitorStatus.VPN_DISCONNECTED)
        assertFalse(BrowserAlignmentGuard.decide(dropped).userAcceptedNoVpn)
        assertTrue(
            BrowserAlignmentGuard.decide(dropped, BrowserAlignmentGuard.acknowledge(dropped))
                .userAcceptedNoVpn,
        )
    }

    @Test fun acceptingSomeOtherProblemIsNotANoVpnOptIn() {
        listOf(
            MonitorStatus.EXIT_IP_CHANGED,
            MonitorStatus.PROFILE_MISMATCH,
            MonitorStatus.UNABLE_TO_VERIFY,
        ).forEach { status ->
            val s = state(status)
            assertFalse(
                "$status",
                BrowserAlignmentGuard.decide(s, BrowserAlignmentGuard.acknowledge(s)).userAcceptedNoVpn,
            )
        }
    }

    @Test fun theNoVpnOptInLapsesWhenTheVpnComesBack() {
        val accepted = BrowserAlignmentGuard.acknowledge(state(MonitorStatus.VPN_DISCONNECTED))
        val back = BrowserAlignmentGuard.decide(state(MonitorStatus.ALIGNED), accepted)
        assertFalse(back.userAcceptedNoVpn)
    }
}
