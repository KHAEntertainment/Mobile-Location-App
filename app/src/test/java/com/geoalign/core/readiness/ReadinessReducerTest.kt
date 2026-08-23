package com.geoalign.core.readiness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessReducerTest {

    @Test fun checkingWhileVpnUnknown() {
        val s = ReadinessReducer.reduce(ReadinessInputs(vpn = VpnTransport.CHECKING))
        assertEquals(ReadinessLevel.CHECKING, s.level)
        assertFalse(s.canOpenBrowser)
    }

    @Test fun blockedWhenNoVpnAndNotAccepted() {
        val s = ReadinessReducer.reduce(
            ReadinessInputs(vpn = VpnTransport.NOT_DETECTED, internetReachable = StepState.OK)
        )
        assertEquals(ReadinessLevel.BLOCKED_NO_VPN, s.level)
        assertFalse(s.canOpenBrowser)
        assertTrue(s.has(WarningId.NO_VPN_BLOCKED))
    }

    @Test fun networkUnavailableBlocks() {
        val s = ReadinessReducer.reduce(
            ReadinessInputs(vpn = VpnTransport.NETWORK_UNAVAILABLE, internetReachable = StepState.FAILED)
        )
        assertEquals(ReadinessLevel.BLOCKED_NO_VPN, s.level)
    }

    @Test fun noVpnAcceptedProducesPersistentWarning() {
        val s = ReadinessReducer.reduce(
            ReadinessInputs(
                vpn = VpnTransport.NOT_DETECTED,
                internetReachable = StepState.OK,
                effectiveIp = StepState.OK,
                geolocation = StepState.OK,
                profileSelected = true,
                userAcceptedNoVpn = true,
            )
        )
        assertEquals(ReadinessLevel.READY_WITH_WARNINGS, s.level)
        assertTrue(s.canOpenBrowser)
        assertTrue(s.has(WarningId.NO_VPN_ACCEPTED))
    }

    @Test fun fullyReadyWhenEverythingGreen() {
        val s = ReadinessReducer.reduce(
            ReadinessInputs(
                vpn = VpnTransport.DETECTED,
                internetReachable = StepState.OK,
                effectiveIp = StepState.OK,
                geolocation = StepState.OK,
                profileSelected = true,
            )
        )
        assertEquals(ReadinessLevel.READY, s.level)
        assertTrue(s.canOpenBrowser)
        assertTrue(s.warnings.isEmpty())
    }

    @Test fun ipStackDivergenceDowngradesFromReady() {
        val s = ReadinessReducer.reduce(
            ReadinessInputs(
                vpn = VpnTransport.DETECTED,
                internetReachable = StepState.OK,
                effectiveIp = StepState.OK,
                geolocation = StepState.OK,
                profileSelected = true,
                ipStackDivergence = true,
            )
        )
        assertEquals(ReadinessLevel.READY_WITH_WARNINGS, s.level)
        assertTrue(s.has(WarningId.IP_STACK_DIVERGENCE))
    }

    @Test fun missingProfileBlocksBrowserButNotReadiness() {
        val s = ReadinessReducer.reduce(
            ReadinessInputs(
                vpn = VpnTransport.DETECTED,
                internetReachable = StepState.OK,
                effectiveIp = StepState.OK,
                geolocation = StepState.OK,
                profileSelected = false,
            )
        )
        assertEquals(ReadinessLevel.READY_WITH_WARNINGS, s.level)
        assertFalse(s.canOpenBrowser)
    }
}
