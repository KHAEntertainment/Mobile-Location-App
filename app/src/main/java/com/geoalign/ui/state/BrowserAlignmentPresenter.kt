package com.geoalign.ui.state

import com.geoalign.core.alignment.MatchScope
import com.geoalign.core.browser.AlignmentRecoveryAction
import com.geoalign.core.browser.AlignmentSeverity
import com.geoalign.core.browser.BrowserAlignmentGuard
import com.geoalign.core.browser.BrowserAlignmentGuardDecision
import com.geoalign.core.monitor.AlignmentMonitorState
import com.geoalign.core.monitor.MonitorStatus

/** One recovery button, fully described. The screen renders it and decides nothing. */
data class AlignmentActionState(
    val id: AlignmentRecoveryAction,
    val label: String,
    val emphasis: Emphasis,
    val enabled: Boolean = true,
)

/**
 * The four-choice recovery prompt. Non-null only while new navigation is actually held, so a screen
 * cannot put it in front of the user at a moment when there is nothing to decide.
 */
data class AlignmentPrompt(
    val title: String,
    val body: String,
    val actions: List<AlignmentActionState>,
)

/**
 * The complete description of the browser's alignment surface, derived from observed facts by
 * [BrowserAlignmentPresenter]. No Compose and no Android types, so every decision worth getting
 * right is reachable from a JVM unit test — the same split as [ReadinessUiState], and for the same
 * reason: the stale-profile bug in `3d3108b` lived inside a `@Composable` where nothing could
 * reach it.
 */
data class BrowserAlignmentUiState(
    val tone: StatusTone,
    val glyph: StatusGlyph,
    /** Short text on the persistent indicator. Always present — the indicator never goes blank. */
    val indicatorLabel: String,
    /** Spoken description for the indicator, which is otherwise a two-word chip. */
    val contentDescription: String,
    /** Standing warning shown while browsing continues under an accepted risk. Else null. */
    val banner: String?,
    val prompt: AlignmentPrompt?,
    val navigationHeld: Boolean,
    /** Explains a navigation that was queued rather than performed. Null when nothing is queued. */
    val heldNavigationNotice: String?,
    val userAcceptedNoVpn: Boolean,
    /** A re-match failed. Shown inside the prompt, which stays up — nothing was fixed. */
    val rematchError: String? = null,
) {
    /**
     * The single green predicate for this surface, written as an equality against one tone so a
     * tone added later cannot inherit it. Tested exhaustively against every monitor status and
     * every acknowledgement.
     */
    val rendersVerified: Boolean get() = tone == StatusTone.VERIFIED
}

/**
 * Turns the live monitor reading into the browser's alignment surface.
 *
 * Everything colour-shaped happens here and nowhere else. The rule the app is built around — green
 * means one thing, "this browser is verified as aligned with the exit it is using right now" — is
 * enforced by a single mapping from [AlignmentSeverity] to [StatusTone], so
 * [AlignmentSeverity.ACCEPTED_RISK] cannot reach [StatusTone.VERIFIED] by any path. That is the
 * same discipline that keeps green out of the Material `ColorScheme` and inside `GeoStatusColors`:
 * nothing should be able to inherit it by accident.
 *
 * Accepting a risk deliberately does **not** soften the tone. A user who continued past a dropped
 * VPN is in exactly the situation they were in a moment earlier; repainting red as amber the
 * instant they tap "Continue" would make the act of acknowledging a problem look like fixing it.
 */
object BrowserAlignmentPresenter {

    fun present(
        state: AlignmentMonitorState,
        acknowledged: MonitorStatus? = null,
        heldNavigationUrl: String? = null,
        rematching: Boolean = false,
        rematchError: String? = null,
    ): BrowserAlignmentUiState = present(
        state = state,
        decision = BrowserAlignmentGuard.decide(state, acknowledged),
        heldNavigationUrl = heldNavigationUrl,
        rematching = rematching,
        rematchError = rematchError,
    )

    fun present(
        state: AlignmentMonitorState,
        decision: BrowserAlignmentGuardDecision,
        heldNavigationUrl: String?,
        rematching: Boolean = false,
        rematchError: String? = null,
    ): BrowserAlignmentUiState {
        val tone = tone(decision)
        return BrowserAlignmentUiState(
            tone = tone,
            glyph = glyph(decision),
            indicatorLabel = indicatorLabel(decision),
            contentDescription = contentDescription(state, decision),
            banner = if (decision.severity == AlignmentSeverity.ACCEPTED_RISK) {
                "Browsing without verified alignment — ${problem(decision.status)}"
            } else {
                null
            },
            prompt = if (decision.promptVisible) prompt(decision, rematching) else null,
            navigationHeld = decision.navigationHeld,
            heldNavigationNotice = heldNavigationUrl
                ?.takeIf { decision.navigationHeld }
                ?.let { "Waiting to open $it — this page is untouched." },
            userAcceptedNoVpn = decision.userAcceptedNoVpn,
            rematchError = rematchError?.takeIf { decision.promptVisible },
        )
    }

    /**
     * The one place a tone is chosen. [AlignmentSeverity.VERIFIED] is the only branch that produces
     * [StatusTone.VERIFIED], and it is reachable only from `AlignmentMonitorState.isAligned`.
     *
     * `CHECKING` maps to NEUTRAL rather than ATTENTION: a re-check is a question mark, and painting
     * every transient network callback amber would make the indicator cry wolf until it is ignored.
     */
    private fun tone(decision: BrowserAlignmentGuardDecision): StatusTone = when (decision.severity) {
        AlignmentSeverity.VERIFIED -> StatusTone.VERIFIED
        AlignmentSeverity.CHECKING -> StatusTone.NEUTRAL
        // Acceptance changes what the user may do, never how bad the situation is.
        AlignmentSeverity.ACTIONABLE, AlignmentSeverity.ACCEPTED_RISK -> severityTone(decision.status)
    }

    /**
     * A contradiction a site can read right now is stronger news than an address that moved.
     * `VPN_DISCONNECTED` and `PROFILE_MISMATCH` are the two states where what the page sees is
     * actively wrong; the other two are "we cannot vouch for this", which is amber, not red.
     */
    private fun severityTone(status: MonitorStatus): StatusTone = when (status) {
        MonitorStatus.VPN_DISCONNECTED, MonitorStatus.PROFILE_MISMATCH -> StatusTone.BLOCKED
        MonitorStatus.EXIT_IP_CHANGED, MonitorStatus.UNABLE_TO_VERIFY -> StatusTone.ATTENTION
        MonitorStatus.ALIGNED, MonitorStatus.RECHECKING -> StatusTone.NEUTRAL
    }

    private fun glyph(decision: BrowserAlignmentGuardDecision): StatusGlyph = when (decision.severity) {
        AlignmentSeverity.VERIFIED -> StatusGlyph.CHECK
        AlignmentSeverity.CHECKING -> StatusGlyph.SPINNER
        AlignmentSeverity.ACTIONABLE, AlignmentSeverity.ACCEPTED_RISK -> StatusGlyph.ALERT
    }

    private fun indicatorLabel(decision: BrowserAlignmentGuardDecision): String =
        when (decision.severity) {
            AlignmentSeverity.VERIFIED -> "Aligned"
            AlignmentSeverity.CHECKING -> "Checking"
            AlignmentSeverity.ACTIONABLE -> shortProblem(decision.status)
            // Says the risk out loud on the chip itself, so the state is legible without opening
            // anything. "Aligned" would be a lie and "Warning" alone would not say what about.
            AlignmentSeverity.ACCEPTED_RISK -> "Unverified · ${shortProblem(decision.status)}"
        }

    private fun contentDescription(
        state: AlignmentMonitorState,
        decision: BrowserAlignmentGuardDecision,
    ): String = when (decision.severity) {
        AlignmentSeverity.VERIFIED -> "Alignment verified. ${matchEvidence(state)}"
        AlignmentSeverity.CHECKING -> "Checking alignment."
        AlignmentSeverity.ACTIONABLE ->
            "Alignment problem: ${problem(decision.status)} New pages are paused."
        AlignmentSeverity.ACCEPTED_RISK ->
            "Browsing without verified alignment: ${problem(decision.status)}"
    }

    /**
     * What the green claim actually rests on.
     *
     * Read straight off `AlignmentResult.matchedOn`, which since #19 reports what was *compared*
     * rather than what was attempted. The `when` is exhaustive so [MatchScope.NONE] has to be
     * answered rather than folded into a country match — though the monitor's reducer already
     * refuses to call `NONE` aligned, so this branch describes a state the guard should never show
     * green for, and says so instead of inventing evidence.
     */
    private fun matchEvidence(state: AlignmentMonitorState): String =
        when (state.alignment?.matchedOn) {
            MatchScope.CITY -> "City matches the current exit."
            MatchScope.COUNTRY -> "Country matches the current exit; city could not be compared."
            MatchScope.NONE -> "No location label could be compared."
            null -> "No comparison recorded."
        }

    private fun prompt(
        decision: BrowserAlignmentGuardDecision,
        rematching: Boolean,
    ): AlignmentPrompt = AlignmentPrompt(
        title = title(decision.status),
        body = body(decision.status),
        actions = decision.actions.map { action(it, rematching) },
    )

    /**
     * A re-match in flight disables every button rather than just its own. The other three all
     * change what the in-flight work is about to write — leaving, accepting the risk, or kicking
     * off a competing check — and letting one land mid-flight is how the saved profile ends up
     * describing an exit that was observed by a different request.
     */
    private fun action(id: AlignmentRecoveryAction, rematching: Boolean): AlignmentActionState =
        when (id) {
            AlignmentRecoveryAction.RECHECK ->
                AlignmentActionState(id, "Check again", Emphasis.PRIMARY, !rematching)
            AlignmentRecoveryAction.REMATCH_PROFILE -> AlignmentActionState(
                id,
                if (rematching) "Re-matching…" else "Re-match profile to this connection",
                Emphasis.SECONDARY,
                !rematching,
            )
            AlignmentRecoveryAction.CONTINUE_WITH_WARNING ->
                AlignmentActionState(id, "Continue anyway (not aligned)", Emphasis.TEXT, !rematching)
            AlignmentRecoveryAction.LEAVE_BROWSER ->
                AlignmentActionState(id, "Leave the browser", Emphasis.TEXT, !rematching)
        }

    private fun title(status: MonitorStatus): String = when (status) {
        MonitorStatus.VPN_DISCONNECTED -> "Your VPN is no longer connected"
        MonitorStatus.EXIT_IP_CHANGED -> "Your connection moved to a different exit"
        MonitorStatus.PROFILE_MISMATCH -> "This profile no longer matches your connection"
        MonitorStatus.UNABLE_TO_VERIFY -> "Alignment could not be verified"
        MonitorStatus.ALIGNED, MonitorStatus.RECHECKING -> "Alignment"
    }

    private fun body(status: MonitorStatus): String {
        val tail = "The page you are on is untouched — only new pages are paused."
        return when (status) {
            MonitorStatus.VPN_DISCONNECTED ->
                "Android no longer reports a VPN on this connection, so a new page would load " +
                    "from your real network. $tail"
            MonitorStatus.EXIT_IP_CHANGED ->
                "Your VPN is exiting from a different address than it was. The location a site " +
                    "sees may no longer be the one this profile describes. $tail"
            MonitorStatus.PROFILE_MISMATCH ->
                "The saved location profile contradicts where this connection is exiting, so a " +
                    "site could see two different places at once. $tail"
            MonitorStatus.UNABLE_TO_VERIFY ->
                "The alignment check could not be completed, so nothing here is confirmed. A " +
                    "check that failed is not a check that passed. $tail"
            MonitorStatus.ALIGNED, MonitorStatus.RECHECKING -> tail
        }
    }

    private fun problem(status: MonitorStatus): String = when (status) {
        MonitorStatus.VPN_DISCONNECTED -> "no VPN is connected."
        MonitorStatus.EXIT_IP_CHANGED -> "the connection moved to a different exit."
        MonitorStatus.PROFILE_MISMATCH -> "the profile contradicts this connection."
        MonitorStatus.UNABLE_TO_VERIFY -> "the last check could not be completed."
        MonitorStatus.ALIGNED, MonitorStatus.RECHECKING -> "alignment is being re-checked."
    }

    private fun shortProblem(status: MonitorStatus): String = when (status) {
        MonitorStatus.VPN_DISCONNECTED -> "No VPN"
        MonitorStatus.EXIT_IP_CHANGED -> "Exit changed"
        MonitorStatus.PROFILE_MISMATCH -> "Profile mismatch"
        MonitorStatus.UNABLE_TO_VERIFY -> "Unverified"
        MonitorStatus.ALIGNED, MonitorStatus.RECHECKING -> "Checking"
    }
}
