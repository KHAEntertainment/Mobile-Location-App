package com.geoalign.core.device

/**
 * `community` edition device catalog: the full experimental preset set.
 *
 * Same fully-qualified name as the `play` copy in `app/src/play`, so `DeviceProfiles` in `main`
 * links against whichever one the variant was built with and needs no flavor branch.
 *
 * Kept in lockstep with `DistributionCapabilities.experimentalDeviceProfiles = true`; the shared
 * `DistributionCapabilitiesTest` fails the build if the two ever disagree.
 */
internal object DeviceProfileCatalog {

    val EXPERIMENTAL: List<DeviceProfile> = ExperimentalDeviceProfiles.ALL

    val DEFAULT_MOBILE: DeviceProfile? = ExperimentalDeviceProfiles.PIXEL_8

    val DEFAULT_DESKTOP: DeviceProfile? = ExperimentalDeviceProfiles.DESKTOP_MAC_CHROME
}
