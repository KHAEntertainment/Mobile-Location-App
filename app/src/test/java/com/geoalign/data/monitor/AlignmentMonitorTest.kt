package com.geoalign.data.monitor

import com.geoalign.core.model.IpGeolocation
import com.geoalign.core.model.LocationProfile
import com.geoalign.core.monitor.MonitorReason
import com.geoalign.core.monitor.MonitorStatus
import com.geoalign.core.readiness.VpnTransport
import com.geoalign.data.geolocation.IpGeolocationProvider
import com.geoalign.data.net.EffectiveIp
import com.geoalign.data.net.EffectiveIpRepository
import com.geoalign.data.net.IpVersion
import com.geoalign.data.profiles.InMemoryProfileStore
import com.geoalign.data.readiness.ReadinessService
import com.geoalign.data.vpn.VpnStatusRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android-scoped half of the monitor. It holds no verdicts — those are asserted in
 * `AlignmentMonitorReducerTest` — so what is tested here is the wiring: that the network callback
 * is actually consumed, that a check follows the events that should cause one, and that concurrent
 * checks are coalesced rather than raced.
 *
 * Fakes only. This project has no Robolectric and no Mockito, and the monitor depends solely on
 * interfaces so it needs neither.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlignmentMonitorTest {

    private val now = 1_700_000_000_000L
    private val lagosIp = "203.0.113.42"
    private val otherIp = "198.51.100.7"

    private class FakeVpn(initial: VpnTransport) : VpnStatusRepository {
        val transports = MutableStateFlow(initial)
        override fun currentTransport(): VpnTransport = transports.value
        override fun transportUpdates(): Flow<VpnTransport> = transports
    }

    /** Serves a scripted sequence, repeating the last entry once exhausted. */
    private class ScriptedIp(private val script: List<Result<EffectiveIp>>) : EffectiveIpRepository {
        var calls = 0
            private set
        override suspend fun currentIp(): Result<EffectiveIp> {
            val r = script[minOf(calls, script.lastIndex)]
            calls++
            return r
        }
    }

    private class ScriptedGeo(private val script: List<Result<IpGeolocation>>) : IpGeolocationProvider {
        var calls = 0
            private set
        override val name = "fake"
        override suspend fun locate(ip: String?): Result<IpGeolocation> {
            val r = script[minOf(calls, script.lastIndex)]
            calls++
            return r
        }
    }

    private fun geo(ip: String = lagosIp, country: String? = "NG", city: String? = "Lagos") =
        IpGeolocation(
            ip = ip,
            countryCode = country,
            countryName = "Nigeria",
            city = city,
            latitude = 6.5244,
            longitude = 3.3792,
            timezone = "Africa/Lagos",
            providerName = "fake",
            timestampMillis = now,
        )

    private fun profile() = LocationProfile(
        id = "p1",
        name = "Lagos",
        countryCode = "NG",
        city = "Lagos",
        latitude = 6.5244,
        longitude = 3.3792,
        timezone = "Africa/Lagos",
        primaryLocale = "en-NG",
        languages = listOf("en-NG", "en"),
        createdAtMillis = now,
        updatedAtMillis = now,
        generatedFromIp = true,
        sourceApproxTimestampMillis = now,
    )

    /**
     * The monitor gets a scope of its own rather than the test's. Its transport collector never
     * completes by design, and handing it the TestScope would leave `runTest` waiting forever for
     * a child that is supposed to run for the life of the process. Same scheduler, so
     * `advanceUntilIdle` still drives it.
     */
    private val scopes = mutableListOf<CoroutineScope>()

    @After fun cancelMonitorScopes() = scopes.forEach { it.cancel() }

    private fun TestScope.newMonitor(
        vpn: FakeVpn,
        ip: EffectiveIpRepository,
        geoProvider: IpGeolocationProvider,
        store: InMemoryProfileStore,
    ): AlignmentMonitor {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        scopes += scope
        return AlignmentMonitor(
            readiness = ReadinessService(vpn, ip, geoProvider),
            profiles = store,
            vpn = vpn,
            scope = scope,
            clock = { now },
        )
    }

    @Test fun startingConsumesTheTransportFlowAndVerifiesOnce() = runTest {
        val vpn = FakeVpn(VpnTransport.DETECTED)
        val ip = ScriptedIp(listOf(Result.success(EffectiveIp(lagosIp, IpVersion.V4))))
        val geoProvider = ScriptedGeo(listOf(Result.success(geo())))
        val m = newMonitor(vpn, ip, geoProvider, InMemoryProfileStore(listOf(profile())))

        m.start()
        advanceUntilIdle()

        val s = m.snapshots.value
        assertEquals(MonitorStatus.ALIGNED, s.monitor.status)
        assertEquals(lagosIp, s.monitor.verifiedExitIp)
        assertEquals(now, s.checkedAtMillis)
        assertFalse(s.checking)
        assertNull(s.errorMessage)
        assertNotNull(s.evaluation)
        assertNotNull(s.profile)
        assertEquals(1, ip.calls)
    }

    /** #5's headline: the VPN drops and the state reacts with no user action at all. */
    @Test fun aTransportDropIsObservedWithoutAnyUserAction() = runTest {
        val vpn = FakeVpn(VpnTransport.DETECTED)
        val ip = ScriptedIp(listOf(Result.success(EffectiveIp(lagosIp, IpVersion.V4))))
        val geoProvider = ScriptedGeo(listOf(Result.success(geo())))
        val m = newMonitor(vpn, ip, geoProvider, InMemoryProfileStore(listOf(profile())))

        m.start()
        advanceUntilIdle()
        assertEquals(MonitorStatus.ALIGNED, m.snapshots.value.monitor.status)

        vpn.transports.value = VpnTransport.NOT_DETECTED
        advanceUntilIdle()

        val dropped = m.snapshots.value
        assertEquals(MonitorStatus.VPN_DISCONNECTED, dropped.monitor.status)
        assertFalse(dropped.monitor.isAligned)
        // No round trip is attempted through a tunnel that just went away.
        assertEquals(1, ip.calls)
    }

    @Test fun theTransportReturningTriggersAFreshCheck() = runTest {
        val vpn = FakeVpn(VpnTransport.DETECTED)
        val ip = ScriptedIp(
            listOf(
                Result.success(EffectiveIp(lagosIp, IpVersion.V4)),
                Result.success(EffectiveIp(otherIp, IpVersion.V4)),
            ),
        )
        val geoProvider = ScriptedGeo(listOf(Result.success(geo()), Result.success(geo(ip = otherIp))))
        val m = newMonitor(vpn, ip, geoProvider, InMemoryProfileStore(listOf(profile())))

        m.start()
        advanceUntilIdle()

        vpn.transports.value = VpnTransport.NOT_DETECTED
        advanceUntilIdle()
        vpn.transports.value = VpnTransport.DETECTED
        advanceUntilIdle()

        val s = m.snapshots.value
        assertEquals(2, ip.calls)
        // Same tunnel provider, different exit after the reconnect.
        assertEquals(MonitorStatus.EXIT_IP_CHANGED, s.monitor.status)
        assertEquals(otherIp, s.monitor.verifiedExitIp)
    }

    @Test fun aGeolocationFailureLeavesUnableToVerify() = runTest {
        val vpn = FakeVpn(VpnTransport.DETECTED)
        val ip = ScriptedIp(listOf(Result.success(EffectiveIp(lagosIp, IpVersion.V4))))
        val geoProvider = ScriptedGeo(listOf(Result.failure(java.io.IOException("provider unreachable"))))
        val m = newMonitor(vpn, ip, geoProvider, InMemoryProfileStore(listOf(profile())))

        m.start()
        advanceUntilIdle()

        val s = m.snapshots.value
        // The service turns a failed lookup into a null geolocation rather than throwing, so the
        // reducer sees "no exit" — which is a failure to verify, not a pass.
        assertEquals(MonitorStatus.UNABLE_TO_VERIFY, s.monitor.status)
        assertEquals(MonitorReason.EXIT_UNKNOWN, s.monitor.reason)
        assertFalse(s.monitor.isAligned)
    }

    @Test fun aThrowingRepositoryIsReportedAsAFailedCheckNotAPass() = runTest {
        val vpn = FakeVpn(VpnTransport.DETECTED)
        val exploding = object : EffectiveIpRepository {
            override suspend fun currentIp(): Result<EffectiveIp> = throw IllegalStateException("boom")
        }
        val m = newMonitor(
            vpn,
            exploding,
            ScriptedGeo(listOf(Result.success(geo()))),
            InMemoryProfileStore(listOf(profile())),
        )

        m.start()
        advanceUntilIdle()

        val s = m.snapshots.value
        assertEquals(MonitorStatus.UNABLE_TO_VERIFY, s.monitor.status)
        assertEquals(MonitorReason.CHECK_FAILED, s.monitor.reason)
        assertEquals("boom", s.errorMessage)
        assertFalse(s.checking)
        assertFalse(s.monitor.isAligned)
    }

    @Test fun noStoredProfileIsAMismatchWithTheExitStillKnown() = runTest {
        val vpn = FakeVpn(VpnTransport.DETECTED)
        val ip = ScriptedIp(listOf(Result.success(EffectiveIp(lagosIp, IpVersion.V4))))
        val geoProvider = ScriptedGeo(listOf(Result.success(geo())))
        val m = newMonitor(vpn, ip, geoProvider, InMemoryProfileStore())

        m.start()
        advanceUntilIdle()

        val s = m.snapshots.value
        assertEquals(MonitorStatus.PROFILE_MISMATCH, s.monitor.status)
        assertEquals(MonitorReason.PROFILE_ABSENT, s.monitor.reason)
        assertNull(s.profile)
        assertEquals(lagosIp, s.monitor.verifiedExitIp)
    }

    @Test fun refreshNowRunsAnotherCheckAndPicksUpANewlySavedProfile() = runTest {
        val vpn = FakeVpn(VpnTransport.DETECTED)
        val ip = ScriptedIp(listOf(Result.success(EffectiveIp(lagosIp, IpVersion.V4))))
        val geoProvider = ScriptedGeo(listOf(Result.success(geo())))
        val store = InMemoryProfileStore()
        val m = newMonitor(vpn, ip, geoProvider, store)

        m.start()
        advanceUntilIdle()
        assertEquals(MonitorStatus.PROFILE_MISMATCH, m.snapshots.value.monitor.status)

        store.upsert(profile())
        m.refreshNow(MonitorReason.LIFECYCLE_RESUMED)
        advanceUntilIdle()

        assertEquals(MonitorStatus.ALIGNED, m.snapshots.value.monitor.status)
        assertEquals(2, ip.calls)
    }

    /**
     * A flap delivers several callbacks in quick succession. Each one opening its own IP and
     * geolocation request would let the *last to return* win rather than the last observed.
     */
    @Test fun overlappingChecksAreCoalescedIntoOne() = runTest {
        val vpn = FakeVpn(VpnTransport.DETECTED)
        val ip = ScriptedIp(listOf(Result.success(EffectiveIp(lagosIp, IpVersion.V4))))
        val geoProvider = ScriptedGeo(listOf(Result.success(geo())))
        val m = newMonitor(vpn, ip, geoProvider, InMemoryProfileStore(listOf(profile())))

        m.start()
        // Five requests before the dispatcher gets a chance to run any of them.
        repeat(5) { m.refreshNow() }
        advanceUntilIdle()

        assertTrue("expected requests to coalesce, ran ${ip.calls} checks", ip.calls <= 2)
        assertEquals(MonitorStatus.ALIGNED, m.snapshots.value.monitor.status)
    }

    @Test fun startIsIdempotent() = runTest {
        val vpn = FakeVpn(VpnTransport.DETECTED)
        val ip = ScriptedIp(listOf(Result.success(EffectiveIp(lagosIp, IpVersion.V4))))
        val geoProvider = ScriptedGeo(listOf(Result.success(geo())))
        val m = newMonitor(vpn, ip, geoProvider, InMemoryProfileStore(listOf(profile())))

        m.start()
        m.start()
        m.start()
        advanceUntilIdle()

        assertEquals(1, ip.calls)
    }
}
