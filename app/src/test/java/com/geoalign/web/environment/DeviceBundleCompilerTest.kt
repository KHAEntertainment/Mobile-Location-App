package com.geoalign.web.environment

import com.geoalign.core.device.DeviceProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Native-mode compilation, which every edition has. The preset cases moved to
 * `src/testCommunity/.../CommunityDeviceBundleCompilerTest.kt` — under `play` the presets they name
 * are not compiled into the variant at all (issue #4).
 */
class DeviceBundleCompilerTest {

    private val template = "GEO[__GEO_BLOCK__]UAD[__UAD_BLOCK__]"

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

    @Test fun noUnsubstitutedPlaceholdersRemainForNative() {
        val nativeOut = DeviceBundleCompiler.compile(template, DeviceProfiles.NATIVE)
        assertFalse(nativeOut.contains("__"))
    }

    @Test fun noUnsubstitutedPlaceholdersRemainForAnyProfileThisEditionHas() {
        DeviceProfiles.ALL.forEach { device ->
            val out = DeviceBundleCompiler.compile(template, device)
            assertFalse("${device.id} left a placeholder unsubstituted", out.contains("__"))
        }
    }
}
