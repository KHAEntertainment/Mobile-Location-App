package com.geoalign.core.distribution

/**
 * What this *edition* of the app is allowed to do (issue #4, README `## Editions`).
 *
 * Two editions ship from one tree: `play` (conservative — only what survives store review and is
 * safe for a first-time user) and `community` (sideloaded, complete, including the sharp edges).
 *
 * The load-bearing rule, from `CONTRIBUTING.md` §5: **edition differences are read from this
 * injected value and never from `BuildConfig.FLAVOR` at a call site.** A flavor string compared at
 * the point of use is untestable — a JVM unit test cannot make it take the other branch — and it
 * scatters the distribution policy across every screen that happens to care. One value, obtained
 * from `AppGraph.distributionCapabilities()`, can be substituted in a test and read in one place.
 *
 * A capability being `false` here does **not** by itself mean the corresponding code is absent from
 * the artifact. Where absence is the actual requirement — the experimental device profiles — it is
 * enforced by source-set exclusion (`core/device/ExperimentalDeviceProfiles.kt` exists only in
 * `app/src/community`) and this flag merely describes that fact so UI can be built without
 * reflection. `DistributionCapabilitiesTest` asserts the two never disagree.
 */
data class DistributionCapabilities(
    /**
     * Whether spoof device presets (Pixel, Galaxy, iPhone, desktop Chrome) are part of this build.
     * `false` on `play`, where `DeviceProfiles.ALL` contains only "This device" because the preset
     * source file is not compiled into the variant.
     */
    val experimentalDeviceProfiles: Boolean,
    /**
     * Whether the full developer diagnostics surface — per-signal pass/fail and the
     * injection-verification WebView — is available. `play` gets user-facing readiness only.
     *
     * Consumed by issue #8, which gates the Diagnostics screen on this flag. Nothing reads it yet.
     */
    val developerDiagnostics: Boolean,
    /**
     * Whether the curated VPN-provider directory is present. `false` in both editions today: it is
     * a planned later addition for `play` and not applicable to `community` (README `## Editions`).
     * The M9 lane flips it when the directory actually exists.
     */
    val partnerDirectory: Boolean,
)
