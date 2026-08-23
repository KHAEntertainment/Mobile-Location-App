package com.geoalign.core.device

/**
 * `play` edition device catalog: **empty**.
 *
 * The Play edition ships "This device" only — the real hardware identity, no spoof presets (see
 * README `## Editions`). That is enforced structurally, not at runtime: the preset data lives in
 * `ExperimentalDeviceProfiles`, a file that exists only in `app/src/community`. This variant is
 * never compiled with it, so there is no preset bytecode, no preset strings, and nothing for the UI
 * to filter. `DeviceProfiles.ALL` is therefore `[NATIVE]` by construction.
 *
 * If a future change wants a preset in Play, it has to *move a file into a source set* — a visible,
 * reviewable act — rather than flip a boolean.
 *
 * Kept in lockstep with `DistributionCapabilities.experimentalDeviceProfiles = false`; the shared
 * `DistributionCapabilitiesTest` fails the build if the two ever disagree.
 */
internal object DeviceProfileCatalog {

    val EXPERIMENTAL: List<DeviceProfile> = emptyList()

    /** No non-native mobile preset exists here, so `DeviceProfiles` falls back to `NATIVE`. */
    val DEFAULT_MOBILE: DeviceProfile? = null

    /** No desktop preset exists here, so a `desktopMode` profile resolves to `NATIVE`. */
    val DEFAULT_DESKTOP: DeviceProfile? = null
}
