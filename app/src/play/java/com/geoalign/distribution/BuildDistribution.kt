package com.geoalign.distribution

import com.geoalign.core.distribution.DistributionCapabilities

/**
 * The `play` edition's identity and capability set.
 *
 * Declared with the same fully-qualified name in `app/src/community`, so `AppGraph` reads
 * `BuildDistribution.CAPABILITIES` with no idea which edition it linked against — which is the
 * point. This object is the *only* place in the Play variant that states Play's policy.
 */
internal object BuildDistribution {

    /** Human-readable edition name. For display and diagnostics; never branched on. */
    const val EDITION = "play"

    /**
     * Conservative posture (README `## Editions`):
     * - no spoof device presets — the preset source file is not in this variant at all;
     * - no developer diagnostics surface — user-facing readiness only;
     * - no partner directory yet — planned for a later release.
     */
    val CAPABILITIES = DistributionCapabilities(
        experimentalDeviceProfiles = false,
        developerDiagnostics = false,
        partnerDirectory = false,
    )
}
