package com.geoalign.web.config

import com.geoalign.core.device.DeviceProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The spoof-preset half of `BrowserSettingsSpecTest`, which can only exist where spoof presets do.
 *
 * Selecting a preset must replace the UA outright — if any part of the real device survived into the
 * header, the preset would be worse than useless: a page would see a device claiming to be a Pixel
 * while carrying a Galaxy Z Fold 6 build fingerprint.
 */
class CommunityBrowserSettingsSpecTest {

    private companion object {
        const val WEBVIEW_UA =
            "Mozilla/5.0 (Linux; Android 16; SM-F956U1 Build/BP4A.251205.006; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/151.0.7922.169 Safari/537.36"
    }

    @Test
    fun `a spoof preset presents its own UA and never consults the real one`() {
        val preset = DeviceProfiles.ALL.first { !it.native }
        val spec = BrowserSettingsSpec.hardened(preset, WEBVIEW_UA)
        assertEquals(preset.userAgent, spec.userAgentString)
        assertFalse("the real device must not leak through a preset", spec.userAgentString.contains("SM-F956U1"))
    }

    @Test
    fun `no preset leaks the host device into its user agent`() {
        DeviceProfiles.ALL.filter { !it.native }.forEach { preset ->
            val ua = BrowserSettingsSpec.hardened(preset, WEBVIEW_UA).userAgentString
            assertFalse("${preset.id} leaked the real model", ua.contains("SM-F956U1"))
            assertFalse("${preset.id} leaked the WebView marker", ua.contains("; wv"))
        }
    }
}
