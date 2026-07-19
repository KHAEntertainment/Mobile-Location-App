package com.geoalign.data.readiness

import com.geoalign.core.model.IpGeolocation
import com.geoalign.core.readiness.ReadinessInputs
import com.geoalign.core.readiness.ReadinessReducer
import com.geoalign.core.readiness.ReadinessState
import com.geoalign.core.readiness.StepState
import com.geoalign.core.readiness.VpnTransport
import com.geoalign.data.geolocation.IpGeolocationProvider
import com.geoalign.data.net.EffectiveIp
import com.geoalign.data.net.EffectiveIpRepository
import com.geoalign.data.vpn.VpnStatusRepository

/**
 * Composes the VPN / effective-IP / geolocation repositories into a single readiness evaluation
 * (spec §7). It gathers observed facts, folds them through the pure ReadinessReducer, and returns
 * both the raw inputs (for diagnostics) and the derived state. Depends only on interfaces, so it is
 * unit-tested with fakes — no Android, no network.
 */
class ReadinessService(
    private val vpn: VpnStatusRepository,
    private val effectiveIp: EffectiveIpRepository,
    private val geolocation: IpGeolocationProvider,
) {
    data class Evaluation(
        val inputs: ReadinessInputs,
        val state: ReadinessState,
        val effectiveIp: EffectiveIp? = null,
        val geolocation: IpGeolocation? = null,
    )

    /**
     * @param profileSelected whether the user already has a browser profile chosen.
     * @param userAcceptedNoVpn whether the user explicitly opted to continue without a VPN.
     */
    suspend fun evaluate(
        profileSelected: Boolean,
        userAcceptedNoVpn: Boolean = false,
    ): Evaluation {
        val transport = vpn.currentTransport()

        // If there is no network at all, short-circuit — don't hammer the IP providers.
        if (transport == VpnTransport.NETWORK_UNAVAILABLE) {
            val inputs = ReadinessInputs(
                vpn = transport,
                internetReachable = StepState.FAILED,
                userAcceptedNoVpn = userAcceptedNoVpn,
                profileSelected = profileSelected,
            )
            return Evaluation(inputs, ReadinessReducer.reduce(inputs))
        }

        // Effective IP retrieval doubles as our internet-reachability probe.
        val ipResult = effectiveIp.currentIp()
        val ip = ipResult.getOrNull()
        val internet = if (ipResult.isSuccess) StepState.OK else StepState.FAILED
        val ipStep = if (ipResult.isSuccess) StepState.OK else StepState.FAILED

        // Only attempt geolocation if we have an IP.
        val geoResult = if (ip != null) geolocation.locate(ip.ip) else null
        val geo = geoResult?.getOrNull()
        val geoStep = when {
            geoResult == null -> StepState.UNKNOWN
            geoResult.isSuccess -> StepState.OK
            else -> StepState.FAILED
        }

        val inputs = ReadinessInputs(
            vpn = transport,
            internetReachable = internet,
            effectiveIp = ipStep,
            geolocation = geoStep,
            profileSelected = profileSelected,
            userAcceptedNoVpn = userAcceptedNoVpn,
            // ipStackDivergence needs a dual-stack v4/v6 comparison — tracked for a follow-up.
            ipStackDivergence = false,
        )
        return Evaluation(inputs, ReadinessReducer.reduce(inputs), ip, geo)
    }
}
