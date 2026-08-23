package com.geoalign.web.environment

import com.geoalign.core.device.ExperimentalDeviceProfiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spoof-preset cases from `DeviceBundleCompilerTest`, unchanged apart from now naming
 * `ExperimentalDeviceProfiles` instead of `DeviceProfiles` — the presets moved to a community-only
 * source file so the `play` variant is not compiled with them (issue #4).
 */
class CommunityDeviceBundleCompilerTest {

    private val template = "GEO[__GEO_BLOCK__]UAD[__UAD_BLOCK__]"

    @Test fun spoofPresetEmitsGeometry() {
        val geo = DeviceBundleCompiler.geometryBlock(ExperimentalDeviceProfiles.PIXEL_8)
        assertTrue(geo.contains("\"platform\", \"Linux armv8l\""))
        assertTrue(geo.contains("\"maxTouchPoints\", 5"))
        assertTrue(geo.contains("\"devicePixelRatio\", 2.625"))
        assertTrue(geo.contains("\"width\", 412"))
        assertTrue(geo.contains("\"height\", 915"))
    }

    @Test fun noUnsubstitutedPlaceholdersRemain() {
        val out = DeviceBundleCompiler.compile(template, ExperimentalDeviceProfiles.DESKTOP_MAC_CHROME)
        assertFalse(out.contains("__"))
    }

    @Test fun chromiumEmitsUserAgentDataShimWithBrands() {
        val block = DeviceBundleCompiler.userAgentDataBlock(ExperimentalDeviceProfiles.PIXEL_8)
        assertTrue(block.contains("getHighEntropyValues"))
        assertTrue(block.contains("\"brand\":\"Google Chrome\""))
        assertTrue(block.contains("\"platform\":\"Android\""))
        assertTrue(block.contains("\"model\":\"Pixel 8\""))
        assertTrue(block.contains("mobile:true"))
    }

    @Test fun iosHidesUserAgentData() {
        val block = DeviceBundleCompiler.userAgentDataBlock(ExperimentalDeviceProfiles.IPHONE_15_PRO)
        assertTrue(block.contains("\"userAgentData\", undefined"))
        assertFalse(block.contains("getHighEntropyValues"))
    }

    @Test fun desktopIsNotMobile() {
        val block = DeviceBundleCompiler.userAgentDataBlock(ExperimentalDeviceProfiles.DESKTOP_WIN_CHROME)
        assertTrue(block.contains("mobile:false"))
        assertTrue(block.contains("\"platform\":\"Windows\""))
    }
}
