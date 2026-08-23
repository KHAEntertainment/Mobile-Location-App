package com.geoalign.distribution

import com.geoalign.core.distribution.DistributionCapabilities

/**
 * The `community` edition's identity and capability set.
 *
 * Same fully-qualified name as the `play` copy in `app/src/play`; `AppGraph` links against whichever
 * one the variant was built with.
 */
internal object BuildDistribution {

    /** Human-readable edition name. For display and diagnostics; never branched on. */
    const val EDITION = "community"

    /**
     * Complete posture (README `## Editions`):
     * - the full experimental device preset set;
     * - full developer diagnostics — per-signal pass/fail and injection verification;
     * - no partner directory: not applicable to the sideloaded edition.
     */
    val CAPABILITIES = DistributionCapabilities(
        experimentalDeviceProfiles = true,
        developerDiagnostics = true,
        partnerDirectory = false,
    )
}
