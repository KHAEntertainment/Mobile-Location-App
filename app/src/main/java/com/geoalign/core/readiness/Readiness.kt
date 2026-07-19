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

data class ReadinessState(
    val level: ReadinessLevel,
    val warnings: List<String>,
    val canOpenBrowser: Boolean,
)

object ReadinessReducer {

    fun reduce(i: ReadinessInputs): ReadinessState {
        val warnings = mutableListOf<String>()

        if (i.vpn == VpnTransport.CHECKING || i.internetReachable == StepState.UNKNOWN) {
            return ReadinessState(ReadinessLevel.CHECKING, emptyList(), canOpenBrowser = false)
        }

        if (i.vpn == VpnTransport.NETWORK_UNAVAILABLE) {
            return ReadinessState(
                ReadinessLevel.BLOCKED_NO_VPN,
                listOf("No network is available. Connect to the internet and check again."),
                canOpenBrowser = false,
            )
        }

        val noVpn = i.vpn == VpnTransport.NOT_DETECTED || i.vpn == VpnTransport.ERROR
        if (noVpn && !i.userAcceptedNoVpn) {
            // Blocking readiness screen (spec §3 No-VPN Behavior).
            return ReadinessState(
                ReadinessLevel.BLOCKED_NO_VPN,
                listOf(
                    if (i.vpn == VpnTransport.ERROR)
                        "Could not confirm a VPN transport. Your real public network address may be visible."
                    else
                        "No VPN transport detected. Your real public network address may be visible.",
                ),
                canOpenBrowser = false,
            )
        }

        if (noVpn && i.userAcceptedNoVpn) {
            warnings += "Continuing without a detected VPN — your real public IP may be exposed."
        }

        if (i.internetReachable == StepState.FAILED) {
            warnings += "Internet does not appear reachable through the current network."
        }
        if (i.effectiveIp == StepState.FAILED) {
            warnings += "Could not verify the effective public IP. Location suggestions may be inaccurate."
        }
        if (i.geolocation == StepState.FAILED) {
            warnings += "IP geolocation lookup failed. Enter or pick a location manually."
        }
        if (i.ipStackDivergence) {
            warnings += "IPv4 and IPv6 appear to exit in different locations — a possible leak path."
        }
        if (!i.profileSelected) {
            warnings += "No browser location profile is selected yet."
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
