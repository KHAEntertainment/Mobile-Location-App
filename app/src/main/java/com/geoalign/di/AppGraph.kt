package com.geoalign.di

import android.content.Context
import com.geoalign.core.distribution.DistributionCapabilities
import com.geoalign.data.geolocation.IpWhoIsProvider
import com.geoalign.data.monitor.AlignmentMonitor
import com.geoalign.data.net.OkHttpEffectiveIpRepository
import com.geoalign.data.profiles.JsonFileProfileStore
import com.geoalign.data.profiles.ProfileStore
import com.geoalign.data.readiness.ReadinessService
import com.geoalign.data.settings.AndroidKeystoreSecureKeyStore
import com.geoalign.data.settings.SecureKeyStore
import com.geoalign.data.vpn.AndroidVpnStatusRepository
import com.geoalign.data.vpn.VpnStatusRepository
import com.geoalign.distribution.BuildDistribution
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Minimal manual dependency wiring for the MVP (no DI framework). Each factory builds a small,
 * stateless graph from an application [Context]. Kept deliberately simple; can be swapped for
 * Hilt later without touching call sites much.
 */
object AppGraph {

    fun vpnStatusRepository(context: Context): VpnStatusRepository =
        AndroidVpnStatusRepository(context)

    fun readinessService(context: Context): ReadinessService = ReadinessService(
        vpn = vpnStatusRepository(context),
        effectiveIp = OkHttpEffectiveIpRepository(),
        geolocation = IpWhoIsProvider(),
    )

    /**
     * Process-lifetime live monitor. Deliberately a singleton: it holds the network callback
     * registration and the last verified exit, and a per-screen instance would re-register on every
     * navigation and forget the exit address it needs in order to notice that the exit changed.
     */
    @Volatile
    private var monitor: AlignmentMonitor? = null

    fun alignmentMonitor(context: Context): AlignmentMonitor {
        monitor?.let { return it }
        return synchronized(this) {
            monitor ?: AlignmentMonitor(
                readiness = readinessService(context),
                profiles = profileStore(context),
                vpn = vpnStatusRepository(context),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            ).also {
                monitor = it
                it.start()
            }
        }
    }

    fun profileStore(context: Context): ProfileStore =
        JsonFileProfileStore(File(context.applicationContext.filesDir, "profiles.json"))

    fun secureKeyStore(context: Context): SecureKeyStore =
        AndroidKeystoreSecureKeyStore(context)

    /**
     * What this edition of the app is allowed to do. Resolved from the flavor-supplied
     * `BuildDistribution`, so call sites read a value instead of comparing `BuildConfig.FLAVOR`
     * (`CONTRIBUTING.md` §5). No context needed — it is a compile-time constant of the variant.
     */
    fun distributionCapabilities(): DistributionCapabilities = BuildDistribution.CAPABILITIES
}
