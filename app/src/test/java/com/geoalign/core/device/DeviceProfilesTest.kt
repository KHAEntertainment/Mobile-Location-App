package com.geoalign.core.device

import com.geoalign.core.model.LocationProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariants that must hold for **whatever catalog this edition was built with**. Every assertion is
 * written against `DeviceProfiles.ALL` as a set, never against a named preset, so this class
 * compiles and runs under both `play` (catalog: `[NATIVE]`) and `community` (catalog: `NATIVE` plus
 * six presets).
 *
 * The assertions naming specific presets moved to `src/testCommunity/.../CommunityDeviceCatalogTest`
 * — not because they became less important, but because under `play` the symbols they name are not
 * compiled at all, which is the point of issue #4. `src/testPlay/.../PlayDeviceCatalogTest` asserts
 * that absence.
 */
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

    @Test fun everySpoofPresetHasNonBlankUserAgent() {
        // Native mode supplies its UA at runtime, so its userAgent field is intentionally blank.
        assertTrue(DeviceProfiles.ALL.filter { !it.native }.all { it.userAgent.isNotBlank() })
    }

    @Test fun nativeIsFirstAndDefault() {
        assertSame(DeviceProfiles.NATIVE, DeviceProfiles.ALL.first())
        assertTrue(DeviceProfiles.NATIVE.native)
        assertSame(DeviceProfiles.NATIVE, DeviceProfiles.forProfile(baseProfile()))
    }

    @Test fun exactlyOneProfileIsNative() {
        // "This device" is the one profile every edition has, and it must not be duplicated by a
        // catalog that accidentally re-adds it.
        assertEquals(1, DeviceProfiles.ALL.count { it.native })
    }

    @Test fun mobileFlagMatchesDeviceClass() {
        DeviceProfiles.ALL.forEach { d ->
            val expected = d.deviceClass != DeviceClass.DESKTOP
            assertEquals("mobile flag for ${d.id}", expected, d.mobile)
        }
    }

    @Test fun chromiumPresetsHaveBrandsAndIosDoesNot() {
        DeviceProfiles.ALL.forEach { d ->
            if (d.emitsClientHints) assertTrue("${d.id} should have brands", d.brands.isNotEmpty())
            else assertTrue("${d.id} should have no brands", d.brands.isEmpty())
        }
        // iOS presets must not claim client hints.
        assertTrue(DeviceProfiles.ALL.filter { it.deviceClass == DeviceClass.IOS }.none { it.emitsClientHints })
    }

    @Test fun byIdRoundTripsEveryEntryInTheCatalog() {
        DeviceProfiles.ALL.forEach { d ->
            assertSame("byId(${d.id})", d, DeviceProfiles.byId(d.id))
        }
    }

    @Test fun byIdMissesCorrectly() {
        assertNull(DeviceProfiles.byId("nope"))
        assertNull(DeviceProfiles.byId(null))
    }

    @Test fun defaultsAreAlwaysMembersOfTheCatalog() {
        // The desktopMode fallback must never hand back a profile the picker cannot show.
        assertTrue(DeviceProfiles.DEFAULT_MOBILE in DeviceProfiles.ALL)
        assertTrue(DeviceProfiles.DEFAULT_DESKTOP in DeviceProfiles.ALL)
    }

    @Test fun forProfilePrefersAnExplicitIdThatThisEditionHas() {
        DeviceProfiles.ALL.forEach { d ->
            assertSame(d, DeviceProfiles.forProfile(baseProfile(userAgentProfileId = d.id)))
        }
    }

    @Test fun forProfileUnknownIdFallsBackToDesktopToggle() {
        val desktop = DeviceProfiles.forProfile(baseProfile(userAgentProfileId = "bogus", desktopMode = true))
        assertSame(DeviceProfiles.DEFAULT_DESKTOP, desktop)
        val mobile = DeviceProfiles.forProfile(baseProfile(userAgentProfileId = "bogus", desktopMode = false))
        assertSame(DeviceProfiles.NATIVE, mobile)
    }

    @Test fun forProfileHonorsDesktopToggleWhenNoId() {
        assertSame(DeviceProfiles.DEFAULT_DESKTOP, DeviceProfiles.forProfile(baseProfile(desktopMode = true)))
        assertSame(DeviceProfiles.NATIVE, DeviceProfiles.forProfile(baseProfile(desktopMode = false)))
    }
}
