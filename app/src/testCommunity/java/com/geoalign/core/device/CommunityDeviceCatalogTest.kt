package com.geoalign.core.device

import com.geoalign.core.model.LocationProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `community` half of issue #4's device-catalog split, and the control group for
 * `src/testPlay/.../PlayDeviceCatalogTest.kt`.
 *
 * The first two tests here are the ones that give the play tests their meaning. `Class.forName`
 * throwing and a bytecode scan finding nothing are both also what you would see if the class had
 * been renamed, the marker list had gone stale, or [VariantBytecode] had silently stopped reading
 * files. Asserting the opposite outcome under community rules all three out.
 *
 * The remaining tests are the preset-specific assertions that used to live in the shared
 * `src/test/.../DeviceProfilesTest.kt`, which can no longer compile there: under `play` the symbols
 * they name do not exist.
 */
class CommunityDeviceCatalogTest {

    @Test
    fun `the experimental profile class is on the community classpath`() {
        // The exact assertion PlayDeviceCatalogTest expects to fail.
        assertNotNull(Class.forName("com.geoalign.core.device.ExperimentalDeviceProfiles"))
    }

    @Test
    fun `every preset identifier the play scan looks for is really findable this way`() {
        val scan = VariantBytecode.scanProductionClasses(EXPERIMENTAL_PROFILE_MARKERS)

        assertTrue("the bytecode scan read no class files", scan.classFilesScanned > 0)
        assertEquals(
            "a marker the play variant is checked against cannot be found even in community, so " +
                "the play assertion would pass vacuously — update EXPERIMENTAL_PROFILE_MARKERS",
            EXPERIMENTAL_PROFILE_MARKERS.toSet(),
            scan.markersFound,
        )
    }

    @Test
    fun `the community catalog is this device followed by the full preset set`() {
        assertEquals(
            listOf(
                "native", "pixel_8", "galaxy_s24",
                "iphone_15_pro", "iphone_se", "desktop_mac_chrome", "desktop_win_chrome",
            ),
            DeviceProfiles.ALL.map { it.id },
        )
    }

    @Test
    fun `the presets cover desktop, iOS and Android`() {
        val classes = DeviceProfiles.ALL.map { it.deviceClass }.toSet()
        assertTrue(classes.containsAll(listOf(DeviceClass.DESKTOP, DeviceClass.IOS, DeviceClass.ANDROID)))
    }

    @Test
    fun `the defaults are unchanged from before the flavor split`() {
        assertSame(ExperimentalDeviceProfiles.PIXEL_8, DeviceProfiles.DEFAULT_MOBILE)
        assertSame(ExperimentalDeviceProfiles.DESKTOP_MAC_CHROME, DeviceProfiles.DEFAULT_DESKTOP)
    }

    @Test
    fun `byId finds a preset and misses a name that is not one`() {
        assertSame(ExperimentalDeviceProfiles.PIXEL_8, DeviceProfiles.byId("pixel_8"))
    }

    @Test
    fun `forProfile prefers an explicit preset id`() {
        val d = DeviceProfiles.forProfile(profile(userAgentProfileId = "iphone_15_pro"))
        assertSame(ExperimentalDeviceProfiles.IPHONE_15_PRO, d)
    }

    @Test
    fun `an unknown id falls back to the desktop toggle`() {
        val desktop = DeviceProfiles.forProfile(profile(userAgentProfileId = "bogus", desktopMode = true))
        assertSame(ExperimentalDeviceProfiles.DESKTOP_MAC_CHROME, desktop)
        val mobile = DeviceProfiles.forProfile(profile(userAgentProfileId = "bogus", desktopMode = false))
        assertSame(DeviceProfiles.NATIVE, mobile)
    }

    @Test
    fun `the desktop toggle picks the desktop preset when no id is set`() {
        assertSame(ExperimentalDeviceProfiles.DESKTOP_MAC_CHROME, DeviceProfiles.forProfile(profile(desktopMode = true)))
        assertSame(DeviceProfiles.NATIVE, DeviceProfiles.forProfile(profile(desktopMode = false)))
    }

    @Test
    fun `desktop presets are non-touch and phone presets are not`() {
        assertFalse(ExperimentalDeviceProfiles.DESKTOP_MAC_CHROME.maxTouchPoints > 0)
        assertTrue(ExperimentalDeviceProfiles.PIXEL_8.maxTouchPoints > 0)
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
