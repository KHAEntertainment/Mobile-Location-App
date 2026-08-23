package com.geoalign.ui.state

import com.geoalign.core.readiness.VpnTransport
import com.geoalign.data.monitor.AlignmentSnapshot

/**
 * Turns a live [AlignmentSnapshot] into the presenter's input.
 *
 * This is the one piece of glue between the monitor and the screen, and it lives here — pure, JVM
 * tested — rather than inside the composable. A `when` over load phases written into a `@Composable`
 * is exactly the shape of the stale-profile bug in `3d3108b`: no test could reach it.
 *
 * @param pendingAction true while the screen is running work of its own that the monitor knows
 *   nothing about — "Re-match", which deliberately re-fetches its own estimate so the profile it
 *   saves and the exit it displays come from the same observation.
 * @param actionError the failure from that work, if any.
 * @param developerDiagnostics `DistributionCapabilities.developerDiagnostics` for this edition. It
 *   arrives as a parameter rather than being read here, because `ui/state` is pure and the value
 *   comes from `AppGraph` — and because a test has to be able to present both editions.
 */
object LiveReadiness {

    fun input(
        snapshot: AlignmentSnapshot,
        nowMillis: Long,
        userAcceptedNoVpn: Boolean = false,
        pendingAction: Boolean = false,
        actionError: String? = null,
        developerDiagnostics: Boolean = false,
    ): ReadinessPresentationInput = ReadinessPresentationInput(
        phase = phase(snapshot, pendingAction, actionError),
        evaluation = snapshot.evaluation,
        profile = snapshot.profile,
        errorMessage = actionError ?: snapshot.errorMessage,
        checkedAtMillis = snapshot.checkedAtMillis,
        nowMillis = nowMillis,
        userAcceptedNoVpn = userAcceptedNoVpn,
        // CHECKING is the monitor's "nothing observed yet" placeholder, not an observation. Passing
        // it as a live signal would let the presenter compare a real cached transport against a
        // value that means nothing.
        liveVpn = snapshot.monitor.transport.takeIf { it != VpnTransport.CHECKING },
        liveMonitor = snapshot.monitor,
        developerDiagnostics = developerDiagnostics,
    )

    /**
     * ERROR outranks everything: a failed check is the most important thing on the screen and must
     * not be smoothed over by a spinner. INITIAL is keyed on "no check has ever completed" rather
     * than on a flag, so a first check that fails still lands in ERROR and not in a permanent
     * loading state.
     */
    private fun phase(
        snapshot: AlignmentSnapshot,
        pendingAction: Boolean,
        actionError: String?,
    ): LoadPhase = when {
        actionError != null || snapshot.errorMessage != null -> LoadPhase.ERROR
        snapshot.checkedAtMillis == null -> LoadPhase.INITIAL
        pendingAction || snapshot.checking -> LoadPhase.REFRESHING
        else -> LoadPhase.LOADED
    }
}
