package com.geoalign.core.readiness

/**
 * Pure readiness model (spec §7, §25). Readiness is NOT "VPN transport present" alone — it is the
 * conjunction of transport, reachability, effective-IP retrieval, geolocation, and a selected
 * browser profile. This reducer is dependency-free and unit-tested; the repositories feed it
 * observed facts and it folds them into a single state plus a user-facing warning set.
 */

enum class VpnTransport { CHECKING, DETECTED, NOT_DETECTED, NETWORK_UNAVAILABLE, ERROR }

enum class StepState { UNKNOWN, OK, FAILED }

data class ReadinessInputs(
    val vpn: VpnTransport = VpnTransport.CHECKING,
    val internetReachable: StepState = StepState.UNKNOWN,
    val effectiveIp: StepState = StepState.UNKNOWN,
    val geolocation: StepState = StepState.UNKNOWN,
    val profileSelected: Boolean = false,
    /** True only if the user explicitly chose to proceed without a VPN (spec §3). */
    val userAcceptedNoVpn: Boolean = false,
    /** True if IPv4 and IPv6 appear to geolocate to different places (spec §7). */
    val ipStackDivergence: Boolean = false,
)

enum class ReadinessLevel {
    /** Still determining state; show a spinner, imply nothing. */
    CHECKING,
    /** Hard block: no VPN and the user has not accepted continuing. */
    BLOCKED_NO_VPN,
    /** Usable but degraded; warnings are present. */
    READY_WITH_WARNINGS,
    /** All checks green. */
    READY,
}

/**
 * Stable identity for each warning the reducer can emit.
 *
 * The id exists so a presentation layer can re-tone, re-word or suppress a warning in context
 * without string-matching. [NO_PROFILE], for example, is redundant next to a screen whose headline
 * already says there is no profile and whose button already says "Match browser to VPN".
 */
enum class WarningId {
    NO_NETWORK,
    NO_VPN_BLOCKED,
    NO_VPN_ACCEPTED,
    INTERNET_UNREACHABLE,
    EFFECTIVE_IP_FAILED,
    GEOLOCATION_FAILED,
    IP_STACK_DIVERGENCE,
    NO_PROFILE,
}

/**
 * [message] is a reasonable default rendering, kept so any surface can show something sensible
 * without a copy table. Presentation layers are free to substitute their own wording keyed on [id].
 */
data class ReadinessWarning(val id: WarningId, val message: String)

data class ReadinessState(
    val level: ReadinessLevel,
    val warnings: List<ReadinessWarning>,
    val canOpenBrowser: Boolean,
) {
    /** Convenience for callers that only care whether a particular condition fired. */
    fun has(id: WarningId): Boolean = warnings.any { it.id == id }
}

object ReadinessReducer {

    fun reduce(i: ReadinessInputs): ReadinessState {
        val warnings = mutableListOf<ReadinessWarning>()

        if (i.vpn == VpnTransport.CHECKING || i.internetReachable == StepState.UNKNOWN) {
            return ReadinessState(ReadinessLevel.CHECKING, emptyList(), canOpenBrowser = false)
        }

        if (i.vpn == VpnTransport.NETWORK_UNAVAILABLE) {
            return ReadinessState(
                ReadinessLevel.BLOCKED_NO_VPN,
                listOf(
                    ReadinessWarning(
                        WarningId.NO_NETWORK,
                        "No network is available. Connect to the internet and check again.",
                    ),
                ),
                canOpenBrowser = false,
            )
        }

        val noVpn = i.vpn == VpnTransport.NOT_DETECTED || i.vpn == VpnTransport.ERROR
        if (noVpn && !i.userAcceptedNoVpn) {
            // Blocking readiness screen (spec §3 No-VPN Behavior).
            return ReadinessState(
                ReadinessLevel.BLOCKED_NO_VPN,
                listOf(
                    ReadinessWarning(
                        WarningId.NO_VPN_BLOCKED,
                        if (i.vpn == VpnTransport.ERROR)
                            "Could not confirm a VPN transport. Your real public network address may be visible."
                        else
                            "No VPN transport detected. Your real public network address may be visible.",
                    ),
                ),
                canOpenBrowser = false,
            )
        }

        if (noVpn && i.userAcceptedNoVpn) {
            warnings += ReadinessWarning(
                WarningId.NO_VPN_ACCEPTED,
                "Continuing without a detected VPN — your real public IP may be exposed.",
            )
        }

        if (i.internetReachable == StepState.FAILED) {
            warnings += ReadinessWarning(
                WarningId.INTERNET_UNREACHABLE,
                "Internet does not appear reachable through the current network.",
            )
        }
        if (i.effectiveIp == StepState.FAILED) {
            warnings += ReadinessWarning(
                WarningId.EFFECTIVE_IP_FAILED,
                "Could not verify the effective public IP. Location suggestions may be inaccurate.",
            )
        }
        if (i.geolocation == StepState.FAILED) {
            warnings += ReadinessWarning(
                WarningId.GEOLOCATION_FAILED,
                "IP geolocation lookup failed. Enter or pick a location manually.",
            )
        }
        if (i.ipStackDivergence) {
            warnings += ReadinessWarning(
                WarningId.IP_STACK_DIVERGENCE,
                "IPv4 and IPv6 appear to exit in different locations — a possible leak path.",
            )
        }
        if (!i.profileSelected) {
            warnings += ReadinessWarning(
                WarningId.NO_PROFILE,
                "No browser profile yet — tap \"Match browser to VPN\" to create one.",
            )
        }

        val allCoreOk = i.internetReachable == StepState.OK &&
            i.effectiveIp == StepState.OK &&
            i.geolocation == StepState.OK &&
            i.profileSelected &&
            !i.ipStackDivergence

        val vpnClean = i.vpn == VpnTransport.DETECTED

        return when {
            allCoreOk && vpnClean ->
                ReadinessState(ReadinessLevel.READY, emptyList(), canOpenBrowser = true)
            else ->
                ReadinessState(
                    ReadinessLevel.READY_WITH_WARNINGS,
                    warnings,
                    // A profile is the minimum needed to inject an environment; browsing is
                    // permitted with warnings, never silently implied as protected.
                    canOpenBrowser = i.profileSelected,
                )
        }
    }
}
