package com.geoalign.ui.state

import com.geoalign.core.alignment.AlignmentReason
import com.geoalign.core.alignment.AlignmentResult
import com.geoalign.core.alignment.AlignmentVerdict
import com.geoalign.core.alignment.MatchScope
import com.geoalign.core.browser.AlignmentRecoveryAction
import com.geoalign.core.browser.BrowserAlignmentGuard
import com.geoalign.core.monitor.AlignmentMonitorState
import com.geoalign.core.monitor.MonitorStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The browser's alignment surface, decided outside any `@Composable`.
 *
 * The tests that matter most here are the exhaustive ones: green is the strongest claim this app
 * makes, and the acceptance criterion is that "continue with warning" can never produce it. That is
 * checked over every status crossed with every acknowledgement rather than on one example.
 */
class BrowserAlignmentPresenterTest {

    private fun state(
        status: MonitorStatus,
        alignment: AlignmentResult? = null,
    ) = AlignmentMonitorState(status = status, alignment = alignment)

    private fun aligned(scope: MatchScope) = AlignmentResult(
        verdict = AlignmentVerdict.ALIGNED,
        matchedOn = scope,
        reasons = emptyList<AlignmentReason>(),
        profileCountry = "GB",
        profileCity = "London",
        exitCountry = "GB",
        exitCity = "London",
        distanceKm = 0.0,
        captureLagMillis = null,
    )

    private val problemStates = listOf(
        MonitorStatus.VPN_DISCONNECTED,
        MonitorStatus.EXIT_IP_CHANGED,
        MonitorStatus.PROFILE_MISMATCH,
        MonitorStatus.UNABLE_TO_VERIFY,
    )

    // ------------------------------------------------- green means one thing

    @Test fun onlyVerifiedAlignmentRendersGreen() {
        MonitorStatus.entries.forEach { status ->
            val ui = BrowserAlignmentPresenter.present(state(status, aligned(MatchScope.CITY)))
            assertEquals(
                "only ALIGNED may render VERIFIED (was $status)",
                status == MonitorStatus.ALIGNED,
                ui.rendersVerified,
            )
        }
    }

    /**
     * The acceptance criterion, stated over the whole cross-product: for every problem the user can
     * accept, the accepted rendering is still not verified. A `!isAligned`-shaped implementation
     * would pass some of these by accident; this fails the moment acceptance is allowed to soften
     * the claim.
     */
    @Test fun continuingWithAWarningNeverRendersAlignedOrVerified() {
        problemStates.forEach { status ->
            val s = state(status, aligned(MatchScope.CITY))
            val accepted = BrowserAlignmentGuard.acknowledge(s)
            val ui = BrowserAlignmentPresenter.present(s, accepted)

            assertFalse("$status accepted must not render verified", ui.rendersVerified)
            assertTrue("$status accepted must not use the check glyph", ui.glyph == StatusGlyph.ALERT)
            assertNotNull("$status accepted must carry a standing warning", ui.banner)
            assertTrue(
                "$status accepted must say so on the indicator",
                ui.indicatorLabel.contains("Unverified"),
            )
            assertFalse(
                "$status accepted must not claim alignment in words either",
                ui.contentDescription.contains("verified.") && ui.tone == StatusTone.VERIFIED,
            )
        }
    }

    /**
     * Acceptance changes what the user may do, not how bad the situation is. Repainting red as
     * amber on the tap would make acknowledging a problem look like fixing one.
     */
    @Test fun acceptingARiskDoesNotSoftenItsTone() {
        problemStates.forEach { status ->
            val s = state(status)
            val before = BrowserAlignmentPresenter.present(s).tone
            val after = BrowserAlignmentPresenter.present(s, BrowserAlignmentGuard.acknowledge(s)).tone
            assertEquals("$status", before, after)
        }
    }

    // ------------------------------------------------- rechecking is not a problem

    @Test fun recheckingIsNeutralAndSilentRatherThanAWarning() {
        val ui = BrowserAlignmentPresenter.present(state(MonitorStatus.RECHECKING))

        assertEquals(StatusTone.NEUTRAL, ui.tone)
        assertEquals(StatusGlyph.SPINNER, ui.glyph)
        assertFalse("a re-check must not render verified", ui.rendersVerified)
        assertNull("a re-check must not raise the four-choice prompt", ui.prompt)
        assertFalse("a re-check must not pause navigation", ui.navigationHeld)
        assertNull("a re-check is not a standing warning", ui.banner)
    }

    // ------------------------------------------------- the prompt

    @Test fun aProblemRaisesAllFourChoices() {
        problemStates.forEach { status ->
            val prompt = BrowserAlignmentPresenter.present(state(status)).prompt
            assertNotNull("$status", prompt)
            assertEquals(
                "$status",
                listOf(
                    AlignmentRecoveryAction.RECHECK,
                    AlignmentRecoveryAction.REMATCH_PROFILE,
                    AlignmentRecoveryAction.CONTINUE_WITH_WARNING,
                    AlignmentRecoveryAction.LEAVE_BROWSER,
                ),
                prompt!!.actions.map { it.id },
            )
            assertTrue(
                "the body must say the current page is untouched",
                prompt.body.contains("untouched"),
            )
        }
    }

    @Test fun aRematchInFlightDisablesEveryChoice() {
        val ui = BrowserAlignmentPresenter.present(
            state(MonitorStatus.PROFILE_MISMATCH),
            rematching = true,
        )
        assertTrue(ui.prompt!!.actions.none { it.enabled })
    }

    @Test fun aFailedRematchIsReportedInsideThePromptWhichStaysUp() {
        val ui = BrowserAlignmentPresenter.present(
            state(MonitorStatus.VPN_DISCONNECTED),
            rematchError = "no location estimate for the current connection",
        )
        assertNotNull("nothing was fixed, so the prompt stays", ui.prompt)
        assertEquals("no location estimate for the current connection", ui.rematchError)
    }

    @Test fun aStaleRematchErrorIsNotShownOnceThereIsNoPrompt() {
        val ui = BrowserAlignmentPresenter.present(state(MonitorStatus.ALIGNED), rematchError = "boom")
        assertNull(ui.rematchError)
    }

    // ------------------------------------------------- held navigation

    @Test fun aQueuedNavigationSaysBothWhatIsWaitingAndThatThePageIsIntact() {
        val ui = BrowserAlignmentPresenter.present(
            state(MonitorStatus.VPN_DISCONNECTED),
            heldNavigationUrl = "https://example.com/next",
        )
        val notice = ui.heldNavigationNotice
        assertNotNull(notice)
        assertTrue(notice!!.contains("https://example.com/next"))
        assertTrue(notice.contains("untouched"))
    }

    @Test fun noHeldNoticeWhenNavigationIsNotActuallyHeld() {
        val ui = BrowserAlignmentPresenter.present(
            state(MonitorStatus.ALIGNED),
            heldNavigationUrl = "https://example.com/next",
        )
        assertNull(ui.heldNavigationNotice)
    }

    // ------------------------------------------------- matchedOn (issue #19)

    /**
     * `matchedOn` is rendered from what was actually compared. [MatchScope.NONE] must describe an
     * absence of evidence rather than borrow a coarser match — the reason #19 added the constant.
     */
    @Test fun matchEvidenceNamesWhatWasComparedIncludingNothing() {
        fun description(scope: MatchScope?) = BrowserAlignmentPresenter
            .present(state(MonitorStatus.ALIGNED, scope?.let(::aligned)))
            .contentDescription

        assertTrue(description(MatchScope.CITY).contains("City matches"))
        assertTrue(description(MatchScope.COUNTRY).contains("Country matches"))
        assertTrue(
            "NONE must not be reported as a country match",
            description(MatchScope.NONE).contains("No location label could be compared"),
        )
        assertFalse(description(MatchScope.NONE).contains("Country matches"))
        assertTrue(description(null).contains("No comparison recorded"))
    }

    // ------------------------------------------------- the indicator is always there

    @Test fun theIndicatorNeverGoesBlank() {
        MonitorStatus.entries.forEach { status ->
            listOf(null, BrowserAlignmentGuard.acknowledge(state(status))).forEach { ack ->
                val ui = BrowserAlignmentPresenter.present(state(status), ack)
                assertTrue("$status/$ack label", ui.indicatorLabel.isNotBlank())
                assertTrue("$status/$ack description", ui.contentDescription.isNotBlank())
            }
        }
    }

    @Test fun verifiedAlignmentReadsAsAlignedWithACheck() {
        val ui = BrowserAlignmentPresenter.present(state(MonitorStatus.ALIGNED, aligned(MatchScope.CITY)))
        assertEquals(StatusTone.VERIFIED, ui.tone)
        assertEquals(StatusGlyph.CHECK, ui.glyph)
        assertEquals("Aligned", ui.indicatorLabel)
        assertFalse(ui.navigationHeld)
        assertNull(ui.prompt)
    }
}
