package com.geoalign.core.distribution

import com.geoalign.core.device.DeviceProfiles
import com.geoalign.distribution.BuildDistribution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs under **both** flavors from the shared `src/test` source set, so each assertion is checked
 * twice against two different `BuildDistribution` objects.
 *
 * The load-bearing one is [experimentalDeviceProfilesFlagMatchesTheCompiledCatalog]. The edition
 * boundary is stated in two independent places — `BuildDistribution.CAPABILITIES` and
 * `DeviceProfileCatalog` — and nothing in the type system ties them together, so a future edit could
 * leave `experimentalDeviceProfiles = false` while the catalog still ships presets. That build would
 * be a Play submission whose UI claims to have no spoof presets while carrying them. This test turns
 * that drift into a build failure on whichever flavor drifted.
 *
 * The per-edition literals themselves are asserted in the flavor test source sets
 * (`testPlay`/`testCommunity`), so nothing here has to branch on which edition it is running as.
 */
class DistributionCapabilitiesTest {

    private val capabilities = BuildDistribution.CAPABILITIES

    @Test
    fun experimentalDeviceProfilesFlagMatchesTheCompiledCatalog() {
        val catalogHasPresets = DeviceProfiles.ALL.any { !it.native }
        assertEquals(
            "BuildDistribution.CAPABILITIES.experimentalDeviceProfiles disagrees with what the " +
                "${BuildDistribution.EDITION} edition actually compiled into DeviceProfiles.ALL " +
                "(${DeviceProfiles.ALL.map { it.id }})",
            capabilities.experimentalDeviceProfiles,
            catalogHasPresets,
        )
    }

    @Test
    fun everyEditionAlwaysOffersThisDevice() {
        // Whatever else an edition drops, the real-hardware identity is not optional: it is the
        // fallback forProfile() resolves to and the only entry play has.
        assertTrue(DeviceProfiles.NATIVE in DeviceProfiles.ALL)
    }

    @Test
    fun theEditionNameIsOneOfTheTwoDeclaredFlavors() {
        assertTrue(
            "unexpected edition '${BuildDistribution.EDITION}'",
            BuildDistribution.EDITION in setOf("play", "community"),
        )
    }

    @Test
    fun partnerDirectoryIsOffInBothEditionsForNow() {
        // README `## Editions`: a planned later addition for play, not applicable to community.
        // Asserted rather than left implicit so the M9 lane has to come here and say so on purpose.
        assertFalse(capabilities.partnerDirectory)
    }
}
