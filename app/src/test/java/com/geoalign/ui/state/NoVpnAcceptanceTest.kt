package com.geoalign.ui.state

import com.geoalign.core.readiness.VpnTransport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoVpnAcceptanceTest {

    @Test fun aDetectedVpnClearsTheOptIn() {
        assertFalse(NoVpnAcceptance.next(current = true, transport = VpnTransport.DETECTED))
    }

    @Test fun theOptInStandsWhileNoVpnIsPresent() {
        assertTrue(NoVpnAcceptance.next(current = true, transport = VpnTransport.NOT_DETECTED))
        assertTrue(NoVpnAcceptance.next(current = true, transport = VpnTransport.ERROR))
        assertTrue(NoVpnAcceptance.next(current = true, transport = VpnTransport.NETWORK_UNAVAILABLE))
    }

    @Test fun nothingIsAcceptedWithoutAnExplicitOptIn() {
        for (t in VpnTransport.entries) {
            assertFalse(NoVpnAcceptance.next(current = false, transport = t))
        }
    }

    /**
     * The sequence that matters: accept, connect a VPN, then lose it. The screen must block again
     * rather than inherit the earlier opt-in for a situation the user never agreed to.
     */
    @Test fun aLaterDropReBlocksAfterTheVpnCameUp() {
        var accepted = true
        accepted = NoVpnAcceptance.next(accepted, VpnTransport.DETECTED)
        accepted = NoVpnAcceptance.next(accepted, VpnTransport.NOT_DETECTED)
        assertFalse(accepted)
    }
}
