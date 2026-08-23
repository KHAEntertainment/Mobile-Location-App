package com.geoalign.core.browser

import com.geoalign.core.monitor.AlignmentMonitorState
import com.geoalign.core.monitor.MonitorStatus

/**
 * The four ways out of a mid-session alignment problem (spec §6, §7; issue #6).
 *
 * They are an enum rather than four booleans on a UI state so that the set is closed: a screen
 * cannot offer three of them, and a `when` over this cannot silently gain a fifth path that nobody
 * wired up.
 */
enum class AlignmentRecoveryAction {
    /** Ask the monitor to look again. The cheapest answer to a transient drop. */
    RECHECK,

    /** Rebuild the location profile from wherever the connection is exiting *now*. */
    REMATCH_PROFILE,

    /**
     * Browse on, knowing the environment is not verified. Never a claim that anything is fine —
     * see [BrowserAlignmentGuardDecision.mayRenderVerified].
     */
    CONTINUE_WITH_WARNING,

    /** Close the browser and go back to readiness, where the problem can be fixed properly. */
    LEAVE_BROWSER,
}

/**
 * How bad the current alignment situation is, in the guard's own vocabulary.
 *
 * Deliberately not `StatusTone`: that is a *presentation* word owned by `ui/state`, and a policy in
 * `core/` that spoke it would be deciding what colour the screen paints. The presenter maps this to
 * a tone, and that mapping is what enforces "green means verified".
 */
enum class AlignmentSeverity {
    /** Verified alignment, right now. The only value that is allowed to become green. */
    VERIFIED,

    /** A check is owed or in flight. A question mark — neither a pass nor a problem. */
    CHECKING,

    /** A live reason not to trust the browser environment, and the user has not accepted it. */
    ACTIONABLE,

    /** The same live reason, still true, after the user chose to continue anyway. */
    ACCEPTED_RISK,
}

/**
 * What the browser should do about the monitor's current reading.
 *
 * @param mayRenderVerified the single green predicate for the browser surface, written the same way
 *   `AlignmentMonitorState.isAligned` is: an equality against one severity, never a set of
 *   exclusions. [AlignmentSeverity.ACCEPTED_RISK] can therefore never inherit it, which is the
 *   acceptance criterion that "continue with warning" must never render as aligned or verified.
 */
data class BrowserAlignmentGuardDecision(
    val severity: AlignmentSeverity,
    val status: MonitorStatus,
    /** True when a *new* navigation must be queued rather than issued. The page already loaded stays. */
    val navigationHeld: Boolean,
    /** True when the four-choice recovery prompt should be in front of the user. */
    val promptVisible: Boolean,
    val actions: List<AlignmentRecoveryAction>,
    /** Mirrors the user's standing no-VPN opt-in back to the monitor, so both surfaces agree. */
    val userAcceptedNoVpn: Boolean,
) {
    val mayRenderVerified: Boolean get() = severity == AlignmentSeverity.VERIFIED
}

/**
 * Decides whether browsing may continue, given what the live monitor last observed.
 *
 * **The pause predicate is [AlignmentMonitorState.isActionable], not `!isAligned`.** Those are two
 * different questions and conflating them is the specific bug this API was shaped to prevent:
 * `RECHECKING` is not aligned, but it is not a problem either. Every `CheckRequested` — a manual
 * refresh, a lifecycle resume, a transport callback that `registerDefaultNetworkCallback` re-fires
 * for its own reasons — puts the monitor into `RECHECKING`. Pausing there would interrupt the user
 * on every transient connectivity blip and, worse, would train them to dismiss the prompt without
 * reading it, which is exactly when the real one arrives.
 *
 * Pure, injected, no clock and no Android: the whole thing is reachable from a JVM unit test, per
 * the `BackPolicy` / `LoadErrorPolicy` / `BrowserCapabilityGate` precedent in this package.
 */
object BrowserAlignmentGuard {

    /**
     * The user's acceptance, keyed to *the problem they accepted*.
     *
     * Modelled as a nullable status rather than a boolean for the same reason
     * `ui/state/NoVpnAcceptance` clears on a detected VPN: consent is to one situation, not to a
     * mood. Accepting a dropped VPN is not consent to browse on later while the saved profile
     * contradicts the exit — a different problem is a new decision, and inheriting the old answer
     * would silently un-pause the browser at the moment it should pause.
     */
    fun nextAcknowledgement(
        current: MonitorStatus?,
        state: AlignmentMonitorState,
    ): MonitorStatus? = when {
        // Nothing to accept any more. Also covers ALIGNED, so a recovered connection resets consent.
        !state.isActionable -> null
        // A different problem than the one accepted. Re-ask.
        current != state.status -> null
        else -> current
    }

    /** Record the user's "continue with an explicit warning" choice for the problem now showing. */
    fun acknowledge(state: AlignmentMonitorState): MonitorStatus? =
        state.status.takeIf { state.isActionable }

    fun decide(
        state: AlignmentMonitorState,
        acknowledged: MonitorStatus? = null,
    ): BrowserAlignmentGuardDecision {
        // Re-derived rather than trusted: a caller holding a stale acknowledgement must not be able
        // to suppress a prompt for a problem it was never given for.
        val standing = nextAcknowledgement(acknowledged, state)
        val actionable = state.isActionable

        val severity = when {
            actionable && standing != null -> AlignmentSeverity.ACCEPTED_RISK
            actionable -> AlignmentSeverity.ACTIONABLE
            state.isAligned -> AlignmentSeverity.VERIFIED
            // RECHECKING. Not a problem, and emphatically not a pass.
            else -> AlignmentSeverity.CHECKING
        }

        val held = severity == AlignmentSeverity.ACTIONABLE

        return BrowserAlignmentGuardDecision(
            severity = severity,
            status = state.status,
            navigationHeld = held,
            promptVisible = held,
            actions = if (held) actionsFor(state.status) else emptyList(),
            // Continuing past a lost tunnel *is* the no-VPN opt-in. Saying so here keeps the browser
            // and the readiness screen from disagreeing about whether to warn.
            userAcceptedNoVpn = standing == MonitorStatus.VPN_DISCONNECTED,
        )
    }

    /**
     * All four choices are offered for every problem state, in the same order, on purpose.
     *
     * The temptation is to hide "Re-match profile" when the VPN is down, since there is nothing to
     * match against. But the monitor's status is a *reading*, not ground truth — a tunnel it thinks
     * is gone may be back by the time the user taps — and a recovery menu whose contents shift
     * under a live status flap is one the user cannot learn. Re-match fails loudly if there is
     * nothing to match; that is a better failure than a missing button.
     */
    private fun actionsFor(status: MonitorStatus): List<AlignmentRecoveryAction> = when (status) {
        MonitorStatus.VPN_DISCONNECTED,
        MonitorStatus.EXIT_IP_CHANGED,
        MonitorStatus.PROFILE_MISMATCH,
        MonitorStatus.UNABLE_TO_VERIFY,
        -> listOf(
            AlignmentRecoveryAction.RECHECK,
            AlignmentRecoveryAction.REMATCH_PROFILE,
            AlignmentRecoveryAction.CONTINUE_WITH_WARNING,
            AlignmentRecoveryAction.LEAVE_BROWSER,
        )
        // Unreachable while `held` gates the call, but stated rather than defaulted: a status added
        // later has to come here and say what it offers instead of inheriting four buttons.
        MonitorStatus.ALIGNED, MonitorStatus.RECHECKING -> emptyList()
    }
}
