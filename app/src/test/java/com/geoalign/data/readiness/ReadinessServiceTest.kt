package com.geoalign.data.readiness

import com.geoalign.core.model.IpGeolocation
import com.geoalign.core.readiness.ReadinessLevel
import com.geoalign.core.readiness.VpnTransport
import com.geoalign.core.readiness.WarningId
import com.geoalign.data.geolocation.IpGeolocationProvider
import com.geoalign.data.net.EffectiveIp
import com.geoalign.data.net.EffectiveIpRepository
import com.geoalign.data.net.IpVersion
import com.geoalign.data.vpn.VpnStatusRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessServiceTest {

    private class FakeVpn(private val t: VpnTransport) : VpnStatusRepository {
        override fun currentTransport() = t
        override fun transportUpdates(): Flow<VpnTransport> = flowOf(t)
    }

    private class FakeIp(private val result: Result<EffectiveIp>) : EffectiveIpRepository {
        override suspend fun currentIp() = result
    }

    private class FakeGeo(private val result: Result<IpGeolocation>) : IpGeolocationProvider {
        override val name = "fake"
        override suspend fun locate(ip: String?) = result
    }

    private val goodIp = EffectiveIp("203.0.113.7", IpVersion.V4)
    private val goodGeo = IpGeolocation(
        ip = "203.0.113.7", countryCode = "US", city = "NYC",
        latitude = 40.7, longitude = -74.0, timezone = "America/New_York",
        providerName = "fake", timestampMillis = 1L,
    )

    @Test fun fullyReadyWhenVpnIpGeoAndProfileAllGood() = runBlocking {
        val svc = ReadinessService(
            FakeVpn(VpnTransport.DETECTED),
            FakeIp(Result.success(goodIp)),
            FakeGeo(Result.success(goodGeo)),
        )
        val e = svc.evaluate(profileSelected = true)
        assertEquals(ReadinessLevel.READY, e.state.level)
        assertTrue(e.state.canOpenBrowser)
        assertEquals(goodIp, e.effectiveIp)
        assertEquals(goodGeo, e.geolocation)
    }

    @Test fun networkUnavailableShortCircuitsWithoutIpLookup() = runBlocking {
        var ipCalled = false
        val ipRepo = object : EffectiveIpRepository {
            override suspend fun currentIp(): Result<EffectiveIp> {
                ipCalled = true; return Result.success(goodIp)
            }
        }
        val svc = ReadinessService(FakeVpn(VpnTransport.NETWORK_UNAVAILABLE), ipRepo, FakeGeo(Result.success(goodGeo)))
        val e = svc.evaluate(profileSelected = true)
        assertEquals(ReadinessLevel.BLOCKED_NO_VPN, e.state.level)
        assertEquals(false, ipCalled) // did not hammer IP providers
        assertNull(e.effectiveIp)
    }

    @Test fun noVpnNotAcceptedIsBlocked() = runBlocking {
        val svc = ReadinessService(
            FakeVpn(VpnTransport.NOT_DETECTED),
            FakeIp(Result.success(goodIp)),
            FakeGeo(Result.success(goodGeo)),
        )
        val e = svc.evaluate(profileSelected = true, userAcceptedNoVpn = false)
        assertEquals(ReadinessLevel.BLOCKED_NO_VPN, e.state.level)
    }

    @Test fun ipFailureDegradesToWarnings() = runBlocking {
        val svc = ReadinessService(
            FakeVpn(VpnTransport.DETECTED),
            FakeIp(Result.failure(RuntimeException("timeout"))),
            FakeGeo(Result.success(goodGeo)),
        )
        val e = svc.evaluate(profileSelected = true)
        assertEquals(ReadinessLevel.READY_WITH_WARNINGS, e.state.level)
        assertTrue(e.state.has(WarningId.EFFECTIVE_IP_FAILED))
        assertNull(e.geolocation) // geo not attempted without an IP
    }

    @Test fun geoFailureAfterGoodIpWarns() = runBlocking {
        val svc = ReadinessService(
            FakeVpn(VpnTransport.DETECTED),
            FakeIp(Result.success(goodIp)),
            FakeGeo(Result.failure(RuntimeException("provider 500"))),
        )
        val e = svc.evaluate(profileSelected = true)
        assertEquals(ReadinessLevel.READY_WITH_WARNINGS, e.state.level)
        assertTrue(e.state.has(WarningId.GEOLOCATION_FAILED))
    }
}
