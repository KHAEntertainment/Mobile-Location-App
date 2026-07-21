package com.geoalign.web.environment

import com.geoalign.core.device.DeviceProfiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceBundleCompilerTest {

    private val template =
        "P=\"__NAV_PLATFORM__\";T=__MAXTOUCH__;D=__DPR__;W=__SCREEN_W__;H=__SCREEN_H__;__UAD_BLOCK__"

    @Test fun substitutesScalarTokens() {
        val out = DeviceBundleCompiler.compile(template, DeviceProfiles.PIXEL_8)
        assertTrue(out.contains("P=\"Linux armv8l\";"))
        assertTrue(out.contains("T=5;"))
        assertTrue(out.contains("D=2.625;"))
        assertTrue(out.contains("W=412;"))
        assertTrue(out.contains("H=915;"))
    }

    @Test fun noUnsubstitutedPlaceholdersRemain() {
        val out = DeviceBundleCompiler.compile(template, DeviceProfiles.DESKTOP_MAC_CHROME)
        assertFalse(out.contains("__"))
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
