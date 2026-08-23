package com.geoalign.web.environment

import com.geoalign.core.device.DeviceProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceBundleCompilerTest {

    private val template = "GEO[__GEO_BLOCK__]UAD[__UAD_BLOCK__]"

    @Test fun spoofPresetEmitsGeometry() {
        val geo = DeviceBundleCompiler.geometryBlock(DeviceProfiles.PIXEL_8)
        assertTrue(geo.contains("\"platform\", \"Linux armv8l\""))
        assertTrue(geo.contains("\"maxTouchPoints\", 5"))
        assertTrue(geo.contains("\"devicePixelRatio\", 2.625"))
        assertTrue(geo.contains("\"width\", 412"))
        assertTrue(geo.contains("\"height\", 915"))
    }

    @Test fun nativeEmitsNoGeometry() {
        assertEquals("", DeviceBundleCompiler.geometryBlock(DeviceProfiles.NATIVE))
    }

    /**
     * Native mode deliberately emits **no** userAgentData shim: its client hints come from
     * WebSettingsCompat.setUserAgentMetadata, which also fixes the Sec-CH-UA request headers.
     *
     * This inverts an earlier expectation. The shim used to inject this profile's placeholder
     * values (Chrome 126 / Android 14.0.0 / empty model) over a UA string reporting the real
     * Chrome 151 on Android 16 — a self-contradiction that reads as active spoofing to any site
     * that cross-checks the two.
     */
    @Test fun nativeEmitsNoUserAgentDataShim() {
        assertEquals("", DeviceBundleCompiler.userAgentDataBlock(DeviceProfiles.NATIVE))
    }

    @Test fun noUnsubstitutedPlaceholdersRemain() {
        val out = DeviceBundleCompiler.compile(template, DeviceProfiles.DESKTOP_MAC_CHROME)
        assertFalse(out.contains("__"))
        val nativeOut = DeviceBundleCompiler.compile(template, DeviceProfiles.NATIVE)
        assertFalse(nativeOut.contains("__"))
    }

    @Test fun chromiumEmitsUserAgentDataShimWithBrands() {
        val block = DeviceBundleCompiler.userAgentDataBlock(DeviceProfiles.PIXEL_8)
        assertTrue(block.contains("getHighEntropyValues"))
        assertTrue(block.contains("\"brand\":\"Google Chrome\""))
        assertTrue(block.contains("\"platform\":\"Android\""))
        assertTrue(block.contains("\"model\":\"Pixel 8\""))
        assertTrue(block.contains("mobile:true"))
    }

    @Test fun iosHidesUserAgentData() {
        val block = DeviceBundleCompiler.userAgentDataBlock(DeviceProfiles.IPHONE_15_PRO)
        assertTrue(block.contains("\"userAgentData\", undefined"))
        assertFalse(block.contains("getHighEntropyValues"))
    }

    @Test fun desktopIsNotMobile() {
        val block = DeviceBundleCompiler.userAgentDataBlock(DeviceProfiles.DESKTOP_WIN_CHROME)
        assertTrue(block.contains("mobile:false"))
        assertTrue(block.contains("\"platform\":\"Windows\""))
    }
}
