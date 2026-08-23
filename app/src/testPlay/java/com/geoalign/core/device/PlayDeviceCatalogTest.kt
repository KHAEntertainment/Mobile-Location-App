package com.geoalign.core.device

import com.geoalign.core.model.LocationProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The `play` half of issue #4's central acceptance criterion: *the experimental device profiles are
 * absent from the `play` source set, not hidden in its UI.*
 *
 * Two of these tests are about the source set and one is about the bytecode. Note what none of them
 * do: read `DistributionCapabilities`. A capability flag is a description of the build, not evidence
 * about it — every assertion here would still pass if the flag were deleted, and none would pass on
 * a build that shipped the presets and filtered them at render time.
 *
 * The mirror-image assertions live in `src/testCommunity/.../CommunityDeviceCatalogTest.kt`. They
 * matter: without them, a broken scanner or a stale marker list would let this file pass while
 * proving nothing.
 */
class PlayDeviceCatalogTest {

    @Test
    fun `the experimental profile class is not on the play classpath at all`() {
        try {
            val found = Class.forName("com.geoalign.core.device.ExperimentalDeviceProfiles")
            fail(
                "ExperimentalDeviceProfiles was loaded in the play variant as $found. The Play " +
                    "edition must be built without that file — it lives only in app/src/community. " +
                    "If it moved to main or to src/play, the device-identity policy is gone.",
            )
        } catch (expected: ClassNotFoundException) {
            // The file is not in this variant's source sets, so there is no class to load.
        }
    }

    @Test
    fun `no preset identifier survives anywhere in the play variant's compiled code`() {
        val scan = VariantBytecode.scanProductionClasses(EXPERIMENTAL_PROFILE_MARKERS)

        assertTrue(
            "the bytecode scan read no class files, so it proves nothing — check VariantBytecode",
            scan.classFilesScanned > 0,
        )
        assertEquals(
            "these experimental-device-profile strings are compiled into the play variant " +
                "(${scan.classFilesScanned} class files scanned); the Play artifact must not " +
                "contain the preset data in any form",
            emptySet<String>(),
            scan.markersFound,
        )
    }

    @Test
    fun `the play catalog offers this device and nothing else`() {
        assertEquals(listOf("native"), DeviceProfiles.ALL.map { it.id })
        assertTrue(DeviceProfiles.ALL.single().native)
    }

    @Test
    fun `with no preset to fall back on, both defaults resolve to this device`() {
        assertSame(DeviceProfiles.NATIVE, DeviceProfiles.DEFAULT_MOBILE)
        assertSame(DeviceProfiles.NATIVE, DeviceProfiles.DEFAULT_DESKTOP)
    }

    @Test
    fun `a profile carried over from the community edition degrades to this device`() {
        // The profile store is JSON on disk and both editions read the same shape. A profile
        // authored in community can name a preset id play has never heard of; byId misses and
        // forProfile falls through rather than throwing.
        val fromCommunity = profile(userAgentProfileId = "pixel_8", desktopMode = true)
        assertSame(DeviceProfiles.NATIVE, DeviceProfiles.forProfile(fromCommunity))
    }

    private fun profile(
        desktopMode: Boolean = false,
        userAgentProfileId: String? = null,
    ) = LocationProfile(
        id = "1", name = "X",
        latitude = 0.0, longitude = 0.0,
        timezone = "UTC", primaryLocale = "en", languages = listOf("en"),
        desktopMode = desktopMode, userAgentProfileId = userAgentProfileId,
        createdAtMillis = 0, updatedAtMillis = 0,
    )
}
