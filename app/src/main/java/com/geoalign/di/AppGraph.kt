package com.geoalign.di

import android.content.Context
import com.geoalign.data.geolocation.IpWhoIsProvider
import com.geoalign.data.net.OkHttpEffectiveIpRepository
import com.geoalign.data.profiles.JsonFileProfileStore
import com.geoalign.data.profiles.ProfileStore
import com.geoalign.data.readiness.ReadinessService
import com.geoalign.data.settings.AndroidKeystoreSecureKeyStore
import com.geoalign.data.settings.SecureKeyStore
import com.geoalign.data.vpn.AndroidVpnStatusRepository
import java.io.File

/**
 * Minimal manual dependency wiring for the MVP (no DI framework). Each factory builds a small,
 * stateless graph from an application [Context]. Kept deliberately simple; can be swapped for
 * Hilt later without touching call sites much.
 */
object AppGraph {

    fun readinessService(context: Context): ReadinessService = ReadinessService(
        vpn = AndroidVpnStatusRepository(context),
        effectiveIp = OkHttpEffectiveIpRepository(),
        geolocation = IpWhoIsProvider(),
    )

    fun profileStore(context: Context): ProfileStore =
        JsonFileProfileStore(File(context.applicationContext.filesDir, "profiles.json"))

    fun secureKeyStore(context: Context): SecureKeyStore =
        AndroidKeystoreSecureKeyStore(context)
}
