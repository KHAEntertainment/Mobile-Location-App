package com.geoalign.core.device

import com.geoalign.core.model.LocationProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfilesTest {

    private fun baseProfile(
        desktopMode: Boolean = false,
        userAgentProfileId: String? = null,
    ) = LocationProfile(
        id = "1", name = "X",
        latitude = 0.0, longitude = 0.0,
        timezone = "UTC", primaryLocale = "en", languages = listOf("en"),
        desktopMode = desktopMode, userAgentProfileId = userAgentProfileId,
        createdAtMillis = 0, updatedAtMillis = 0,
    )

    @Test fun idsAreUnique() {
        val ids = DeviceProfiles.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun everyPresetHasNonBlankUserAgent() {
        assertTrue(DeviceProfiles.ALL.all { it.userAgent.isNotBlank() })
    }

    @Test fun mobileFlagMatchesDeviceClass() {
        DeviceProfiles.ALL.forEach { d ->
            val expected = d.deviceClass != DeviceClass.DESKTOP
            assertEquals("mobile flag for ${d.id}", expected, d.mobile)
        }
    }

    @Test fun coversDesktopIosAndAndroid() {
        val classes = DeviceProfiles.ALL.map { it.deviceClass }.toSet()
        assertTrue(classes.containsAll(listOf(DeviceClass.DESKTOP, DeviceClass.IOS, DeviceClass.ANDROID)))
    }

    @Test fun chromiumPresetsHaveBrandsAndIosDoesNot() {
        DeviceProfiles.ALL.forEach { d ->
            if (d.emitsClientHints) assertTrue("${d.id} should have brands", d.brands.isNotEmpty())
            else assertTrue("${d.id} should have no brands", d.brands.isEmpty())
        }
        // iOS presets must not claim client hints.
        assertTrue(DeviceProfiles.ALL.filter { it.deviceClass == DeviceClass.IOS }.none { it.emitsClientHints })
    }

    @Test fun byIdFindsAndMissesCorrectly() {
        assertSame(DeviceProfiles.PIXEL_8, DeviceProfiles.byId("pixel_8"))
        assertNull(DeviceProfiles.byId("nope"))
        assertNull(DeviceProfiles.byId(null))
    }

    @Test fun forProfilePrefersExplicitId() {
        val d = DeviceProfiles.forProfile(baseProfile(userAgentProfileId = "iphone_15_pro"))
        assertSame(DeviceProfiles.IPHONE_15_PRO, d)
    }

    @Test fun forProfileUnknownIdFallsBackToDesktopToggle() {
        val desktop = DeviceProfiles.forProfile(baseProfile(userAgentProfileId = "bogus", desktopMode = true))
        assertSame(DeviceProfiles.DEFAULT_DESKTOP, desktop)
        val mobile = DeviceProfiles.forProfile(baseProfile(userAgentProfileId = "bogus", desktopMode = false))
        assertSame(DeviceProfiles.DEFAULT_MOBILE, mobile)
    }

    @Test fun forProfileHonorsDesktopToggleWhenNoId() {
        assertSame(DeviceProfiles.DEFAULT_DESKTOP, DeviceProfiles.forProfile(baseProfile(desktopMode = true)))
        assertSame(DeviceProfiles.DEFAULT_MOBILE, DeviceProfiles.forProfile(baseProfile(desktopMode = false)))
    }

    @Test fun desktopPresetsAreNonTouch() {
        assertFalse(DeviceProfiles.DESKTOP_MAC_CHROME.maxTouchPoints > 0)
        assertTrue(DeviceProfiles.PIXEL_8.maxTouchPoints > 0)
    }
}
