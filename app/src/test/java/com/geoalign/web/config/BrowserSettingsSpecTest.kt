package com.geoalign.web.config

import com.geoalign.core.device.DeviceProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hardened settings matrix, asserted as data. These are the settings that used to be a run of
 * assignments inside a composable, where nothing could see them; each `assertFalse` below is a
 * hardening choice that a stray edit would otherwise flip silently.
 *
 * [WEBVIEW_UA] is a real capture from a Galaxy Z Fold 6 (SM-F956U1, Android 16, WebView 151), the
 * same fixture `NativeIdentityTest` uses.
 */
class BrowserSettingsSpecTest {

    private companion object {
        const val WEBVIEW_UA =
            "Mozilla/5.0 (Linux; Android 16; SM-F956U1 Build/BP4A.251205.006; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/151.0.7922.169 Safari/537.36"

        const val REAL_CHROME_UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/151.0.0.0 Safari/537.36"
    }

    private val hardened = BrowserSettingsSpec.hardened(DeviceProfiles.NATIVE, WEBVIEW_UA)

    @Test
    fun `file and content access stay off in every direction`() {
        assertFalse("a page must not read the APK's assets", hardened.allowFileAccess)
        assertFalse("a page must not reach content providers", hardened.allowContentAccess)
        assertFalse("a file url must not read sibling files", hardened.allowFileAccessFromFileURLs)
        assertFalse("a file url must not get universal access", hardened.allowUniversalAccessFromFileURLs)
    }

    @Test
    fun `mixed content is refused`() {
        assertTrue(hardened.blockMixedContent)
    }

    @Test
    fun `the viewport pair from c8f9b55 is still set`() {
        // Both, or neither works: without useWideViewPort the page is laid out at the raw view
        // width and (orientation: landscape) can match on a portrait phone.
        assertTrue("useWideViewPort", hardened.useWideViewPort)
        assertTrue("loadWithOverviewMode", hardened.loadWithOverviewMode)
    }

    @Test
    fun `script and storage stay on, because the injected environment is script`() {
        assertTrue(hardened.javaScriptEnabled)
        assertTrue(hardened.domStorageEnabled)
    }

    @Test
    fun `native mode presents the reduced Chrome UA, not the WebView's own`() {
        assertEquals(REAL_CHROME_UA, hardened.userAgentString)
    }

    @Test
    fun `native mode's UA leaks neither the model, the OS version, nor the WebView markers`() {
        val ua = hardened.userAgentString
        assertFalse("model must not survive", ua.contains("SM-F956U1"))
        assertFalse("real OS version must not survive", ua.contains("Android 16"))
        assertFalse("patch version must not survive", ua.contains("7922"))
        assertFalse("WebView marker must not survive", ua.contains("wv"))
        assertFalse("WebView Version token must not survive", ua.contains("Version/4.0"))
        assertFalse("build fingerprint must not survive", ua.contains("Build/"))
    }

    @Test
    fun `a spoof preset presents its own UA and never consults the real one`() {
        val preset = DeviceProfiles.ALL.first { !it.native }
        val spec = BrowserSettingsSpec.hardened(preset, WEBVIEW_UA)
        assertEquals(preset.userAgent, spec.userAgentString)
        assertFalse("the real device must not leak through a preset", spec.userAgentString.contains("SM-F956U1"))
    }

    @Test
    fun `every preset yields a non-empty user agent`() {
        // Native profiles carry an empty DeviceProfile.userAgent by construction, so the selection
        // rule is the only thing standing between "This device" and a blank UA header.
        DeviceProfiles.ALL.forEach { device ->
            val ua = BrowserSettingsSpec.userAgentFor(device, WEBVIEW_UA)
            assertTrue("${device.id} produced a blank UA", ua.isNotBlank())
        }
    }

    @Test
    fun `the hardening is identical whichever device is presented`() {
        // Only the UA may vary with the device; a preset must not relax the security matrix.
        DeviceProfiles.ALL.forEach { device ->
            val spec = BrowserSettingsSpec.hardened(device, WEBVIEW_UA)
            assertEquals(
                "${device.id} altered more than the user agent",
                hardened.copy(userAgentString = spec.userAgentString),
                spec,
            )
        }
    }
}
