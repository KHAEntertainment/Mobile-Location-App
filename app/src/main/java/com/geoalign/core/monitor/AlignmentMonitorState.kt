package com.geoalign.core.monitor

import com.geoalign.core.alignment.AlignmentChecker
import com.geoalign.core.alignment.AlignmentReason
import com.geoalign.core.alignment.AlignmentResult
import com.geoalign.core.alignment.AlignmentThresholds
import com.geoalign.core.alignment.AlignmentVerdict
import com.geoalign.core.model.IpGeolocation
import com.geoalign.core.model.LocationProfile
import com.geoalign.core.readiness.VpnTransport

/**
 * The live half of readiness (spec §6, §7). Readiness answers "is this connection aligned *right
 * now*"; until this existed the answer was computed once at composition and then never revisited,
 * so a VPN that dropped or silently moved to a different exit mid-session left a green screen
 * standing over a connection that no longer matched it.
 *
 * Everything here is pure Kotlin — no Android, no coroutines, no clock of its own. Time and every
 * observed fact arrive as events, so the whole machine is reachable from a JVM unit test. The
 * Android-scoped collector that feeds it holds no decisions at all.
 */
enum class MonitorStatus {
    /**
     * A check is owed. Carries whatever the last check established, but claims nothing new — this
     * is a question mark, not a pass.
     */
    RECHECKING,

    /** Verified: VPN transport present, exit resolved, profile matches, exit unchanged. */
    ALIGNED,

    /** Android no longer reports a VPN transport on the default network. */
    VPN_DISCONNECTED,

    /** Still aligned, but the connection is exiting from a different address than it was. */
    EXIT_IP_CHANGED,

    /** The saved profile contradicts the live exit, or there is no profile to compare at all. */
    PROFILE_MISMATCH,

    /**
     * The check could not be completed — provider unreachable, no exit estimate. Deliberately its
     * own state: a check that failed is not a check that passed, and it must never be rendered,
     * folded or defaulted into [ALIGNED].
     */
    UNABLE_TO_VERIFY,
}

/** Why the monitor is in its current [MonitorStatus]. Stable ids so copy can change freely. */
enum class MonitorReason {
    /** Nothing observed yet. */
    INITIAL,
    TRANSPORT_LOST,
    TRANSPORT_RESTORED,
    MANUAL_REFRESH,
    LIFECYCLE_RESUMED,
    PROFILE_ABSENT,
    PROFILE_DRIFTED,
    PROFILE_STALE_CAPTURE,
    EXIT_UNKNOWN,
    CHECK_FAILED,
    EXIT_IP_CHANGED,
    VERIFIED,
}

/**
 * @param verifiedExitIp the exit address established by the most recent *successful* verification.
 *   Retained across a transport drop on purpose: comparing it after the VPN returns is exactly how
 *   "the tunnel came back on a different exit" becomes visible.
 * @param alignment the last [AlignmentResult] computed, kept for display. Its presence never
 *   implies the current status is good — [status] is the only thing that says that.
 * @param lastCheckedAtMillis when a check last *completed*, successfully or not.
 * @param lastVerifiedAtMillis when a check last completed with a usable exit estimate.
 */
data class AlignmentMonitorState(
    val status: MonitorStatus = MonitorStatus.RECHECKING,
    val reason: MonitorReason = MonitorReason.INITIAL,
    val transport: VpnTransport = VpnTransport.CHECKING,
    val verifiedExitIp: String? = null,
    val alignment: AlignmentResult? = null,
    val lastCheckedAtMillis: Long? = null,
    val lastVerifiedAtMillis: Long? = null,
) {
    /**
     * The single green predicate. Written as an equality against one status rather than as a set of
     * exclusions, so a status added later cannot accidentally inherit "aligned".
     */
    val isAligned: Boolean get() = status == MonitorStatus.ALIGNED

    /** True while a check is owed. The collector reads this; it decides nothing else. */
    val needsCheck: Boolean get() = status == MonitorStatus.RECHECKING

    /** True when the current status is a live reason not to trust the browser environment. */
    val isActionable: Boolean get() = when (status) {
        MonitorStatus.VPN_DISCONNECTED,
        MonitorStatus.EXIT_IP_CHANGED,
        MonitorStatus.PROFILE_MISMATCH,
        MonitorStatus.UNABLE_TO_VERIFY,
        -> true
        MonitorStatus.ALIGNED, MonitorStatus.RECHECKING -> false
    }
}

sealed interface AlignmentMonitorEvent {

    /** A transport reading from the platform network callback. */
    data class TransportObserved(
        val transport: VpnTransport,
        val atMillis: Long,
    ) : AlignmentMonitorEvent

    /** Something outside the network asked for a re-check (manual button, lifecycle RESUMED). */
    data class CheckRequested(
        val reason: MonitorReason,
        val atMillis: Long,
    ) : AlignmentMonitorEvent

    /**
     * A check completed. [exit] null means the provider gave us nothing usable, which is a failure
     * to verify — never an implicit pass.
     */
    data class CheckCompleted(
        val transport: VpnTransport,
        val exitIp: String?,
        val exit: IpGeolocation?,
        val profile: LocationProfile?,
        val atMillis: Long,
    ) : AlignmentMonitorEvent

    /** A check threw, timed out, or the provider was unreachable. */
    data class CheckFailed(
        val atMillis: Long,
        val message: String? = null,
    ) : AlignmentMonitorEvent
}

/**
 * Folds observed facts into [AlignmentMonitorState].
 *
 * Profile-vs-exit judgement is delegated wholesale to [AlignmentChecker] — country, city with
 * diacritic folding, haversine distance and stale-capture provenance all already live there and are
 * tested there. Duplicating any of it here would create a second definition of "aligned" that could
 * drift from the first.
 */
object AlignmentMonitorReducer {

    fun reduce(
        state: AlignmentMonitorState,
        event: AlignmentMonitorEvent,
        thresholds: AlignmentThresholds = AlignmentThresholds.DEFAULT,
    ): AlignmentMonitorState = when (event) {
        is AlignmentMonitorEvent.TransportObserved -> onTransport(state, event)
        is AlignmentMonitorEvent.CheckRequested ->
            state.copy(status = MonitorStatus.RECHECKING, reason = event.reason)
        is AlignmentMonitorEvent.CheckCompleted -> onCompleted(state, event, thresholds)
        is AlignmentMonitorEvent.CheckFailed -> state.copy(
            status = MonitorStatus.UNABLE_TO_VERIFY,
            reason = MonitorReason.CHECK_FAILED,
            lastCheckedAtMillis = event.atMillis,
        )
    }

    /**
     * `registerDefaultNetworkCallback` re-delivers the same capabilities repeatedly. Reducing an
     * unchanged transport to a fresh RECHECKING would put the screen in a permanent re-check loop,
     * so an identical reading is a no-op.
     */
    private fun onTransport(
        state: AlignmentMonitorState,
        event: AlignmentMonitorEvent.TransportObserved,
    ): AlignmentMonitorState {
        if (event.transport == state.transport) return state

        return when (event.transport) {
            VpnTransport.DETECTED -> state.copy(
                status = MonitorStatus.RECHECKING,
                reason = if (state.transport == VpnTransport.CHECKING) MonitorReason.INITIAL
                else MonitorReason.TRANSPORT_RESTORED,
                transport = event.transport,
            )
            // Losing the tunnel is knowable without a network round trip, so it is reported at once
            // rather than waiting for a check that would have to travel through the very connection
            // that just went away.
            VpnTransport.NOT_DETECTED,
            VpnTransport.NETWORK_UNAVAILABLE,
            VpnTransport.ERROR,
            -> state.copy(
                status = MonitorStatus.VPN_DISCONNECTED,
                reason = MonitorReason.TRANSPORT_LOST,
                transport = event.transport,
            )
            VpnTransport.CHECKING -> state.copy(
                status = MonitorStatus.RECHECKING,
                reason = MonitorReason.INITIAL,
                transport = event.transport,
            )
        }
    }

    private fun onCompleted(
        state: AlignmentMonitorState,
        event: AlignmentMonitorEvent.CheckCompleted,
        thresholds: AlignmentThresholds,
    ): AlignmentMonitorState {
        val checked = state.copy(
            transport = event.transport,
            lastCheckedAtMillis = event.atMillis,
        )

        // A check that finds no tunnel reports the tunnel, whatever else it found. Any other order
        // could describe a profile as matching an exit the user is no longer protected behind.
        if (event.transport != VpnTransport.DETECTED) {
            return checked.copy(
                status = MonitorStatus.VPN_DISCONNECTED,
                reason = MonitorReason.TRANSPORT_LOST,
            )
        }

        val alignment = AlignmentChecker.check(
            profile = event.profile,
            exit = event.exit,
            nowMillis = event.atMillis,
            thresholds = thresholds,
        )

        // No exit estimate is a failure to verify. The previous verifiedExitIp is deliberately kept
        // rather than cleared: forgetting it would make the next successful check look like a first
        // observation and silently swallow an exit change that happened across the gap.
        if (event.exit == null || event.exitIp == null) {
            return checked.copy(
                status = MonitorStatus.UNABLE_TO_VERIFY,
                reason = MonitorReason.EXIT_UNKNOWN,
                alignment = alignment,
            )
        }

        val verified = checked.copy(
            alignment = alignment,
            verifiedExitIp = event.exitIp,
            lastVerifiedAtMillis = event.atMillis,
        )

        return when (alignment.verdict) {
            AlignmentVerdict.UNKNOWN -> verified.copy(
                status = MonitorStatus.UNABLE_TO_VERIFY,
                reason = MonitorReason.EXIT_UNKNOWN,
            )
            AlignmentVerdict.NO_PROFILE -> verified.copy(
                status = MonitorStatus.PROFILE_MISMATCH,
                reason = MonitorReason.PROFILE_ABSENT,
            )
            AlignmentVerdict.DRIFTED_COUNTRY,
            AlignmentVerdict.DRIFTED_CITY,
            AlignmentVerdict.DRIFTED_DISTANCE,
            -> verified.copy(
                status = MonitorStatus.PROFILE_MISMATCH,
                reason = MonitorReason.PROFILE_DRIFTED,
            )
            AlignmentVerdict.STALE_CAPTURE -> verified.copy(
                status = MonitorStatus.PROFILE_MISMATCH,
                reason = MonitorReason.PROFILE_STALE_CAPTURE,
            )
            // Labels agree. A moved exit is reported *after* mismatch, not before: a contradiction
            // a site can see right now outranks the news that the address changed.
            AlignmentVerdict.ALIGNED ->
                if (comparedNothing(alignment)) {
                    verified.copy(
                        status = MonitorStatus.UNABLE_TO_VERIFY,
                        reason = MonitorReason.EXIT_UNKNOWN,
                    )
                } else if (state.verifiedExitIp != null && state.verifiedExitIp != event.exitIp) {
                    verified.copy(
                        status = MonitorStatus.EXIT_IP_CHANGED,
                        reason = MonitorReason.EXIT_IP_CHANGED,
                    )
                } else {
                    verified.copy(
                        status = MonitorStatus.ALIGNED,
                        reason = MonitorReason.VERIFIED,
                    )
                }
        }
    }

    /**
     * True when an ALIGNED verdict rests on nothing: the estimate carried neither a country nor a
     * city, so no comparison actually ran and the profile agreed with an empty record.
     *
     * [AlignmentChecker] treats a missing label as unverified rather than as a mismatch — correct
     * for it, since a provider that omits a city must not be reported as a contradiction. But a
     * live monitor claiming "aligned" wants evidence, not the absence of a counter-example, so the
     * one case where *every* comparison was skipped is reported as a failure to verify. The
     * checker's own reasons are read here; none of its logic is restated.
     */
    private fun comparedNothing(alignment: AlignmentResult): Boolean =
        alignment.hasReason(AlignmentReason.COUNTRY_UNVERIFIED) &&
            alignment.hasReason(AlignmentReason.CITY_UNVERIFIED)
}
