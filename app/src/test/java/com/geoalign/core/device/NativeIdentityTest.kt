package com.geoalign.core.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures are real captures from a Galaxy Z Fold 6 (SM-F956U1, Android 16, WebView/Chrome 151),
 * taken over the DevTools protocol from both the embedded WebView and the device's own Chrome.
 * [REAL_CHROME_UA] is therefore the exact string native mode must reproduce.
 */
class NativeIdentityTest {

    private companion object {
        const val WEBVIEW_UA =
            "Mozilla/5.0 (Linux; Android 16; SM-F956U1 Build/BP4A.251205.006; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/151.0.7922.169 Safari/537.36"

        const val REAL_CHROME_UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/151.0.0.0 Safari/537.36"
    }

    @Test
    fun `reduced UA matches the device's real Chrome exactly`() {
        assertEquals(REAL_CHROME_UA, NativeIdentity.reduceUserAgent(WEBVIEW_UA))
    }

    @Test
    fun `reduced UA leaks neither the real OS version nor the model`() {
        val reduced = NativeIdentity.reduceUserAgent(WEBVIEW_UA)
        assertFalse("model must not survive UA reduction", reduced.contains("SM-F956U1"))
        assertFalse("real OS version must not survive UA reduction", reduced.contains("Android 16"))
        assertFalse("patch version must not survive UA reduction", reduced.contains("7922"))
        assertFalse("WebView marker must not survive", reduced.contains("wv"))
        assertFalse("WebView Version token must not survive", reduced.contains("Version/4.0"))
    }

    @Test
    fun `Mobile token is preserved when the source UA carries it`() {
        val phoneUa = WEBVIEW_UA.replace("Chrome/151.0.7922.169 Safari", "Chrome/151.0.7922.169 Mobile Safari")
        assertTrue(NativeIdentity.reduceUserAgent(phoneUa).contains(" Mobile Safari/537.36"))
    }

    @Test
    fun `Mobile token is absent when the source UA omits it`() {
        assertFalse(NativeIdentity.reduceUserAgent(WEBVIEW_UA).contains("Mobile"))
    }

    @Test
    fun `unparseable UA falls back to stripping WebView markers`() {
        val odd = "Mozilla/5.0 (Linux; Android 16; SM-F956U1 Build/XYZ; wv) AppleWebKit/537.36"
        val out = NativeIdentity.reduceUserAgent(odd)
        assertFalse(out.contains("; wv)"))
        assertFalse(out.contains("Build/XYZ"))
    }

    @Test
    fun `chrome version parsing`() {
        assertEquals("151", NativeIdentity.chromeMajor(WEBVIEW_UA))
        assertEquals("151.0.7922.169", NativeIdentity.chromeFullVersion(WEBVIEW_UA))
        assertNull(NativeIdentity.chromeMajor("Mozilla/5.0 (Linux; Android 16)"))
        assertNull(NativeIdentity.chromeFullVersion("... Chrome/151 ..."))
    }

    @Test
    fun `platform version is padded to three components`() {
        assertEquals("16.0.0", NativeIdentity.platformVersion("16"))
        assertEquals("16.1.0", NativeIdentity.platformVersion("16.1"))
        assertEquals("14.2.3", NativeIdentity.platformVersion("14.2.3"))
        assertEquals("0.0.0", NativeIdentity.platformVersion(""))
    }

    @Test
    fun `WebView brand becomes Google Chrome and full versions are attached`() {
        // Exactly what the WebView reported for its own userAgentData.brands.
        val webViewDefaults = listOf(
            Brand("Not=A?Brand", "99", ""),
            Brand("Android WebView", "151", ""),
            Brand("Chromium", "151", ""),
        )

        val out = NativeIdentity.chromeBrands(webViewDefaults, "151.0.7922.169")

        // Matches the real Chrome capture, entry for entry.
        assertEquals(
            listOf(
                Brand("Not=A?Brand", "99", "99.0.0.0"),
                Brand("Google Chrome", "151", "151.0.7922.169"),
                Brand("Chromium", "151", "151.0.7922.169"),
            ),
            out,
        )
        assertFalse("must not advertise WebView", out.any { it.brand == NativeIdentity.WEBVIEW_BRAND })
    }

    @Test
    fun `grease brands are identified by exclusion`() {
        assertTrue(NativeIdentity.isGreaseBrand("Not=A?Brand"))
        assertTrue(NativeIdentity.isGreaseBrand("Not/A)Brand"))
        assertFalse(NativeIdentity.isGreaseBrand("Chromium"))
        assertFalse(NativeIdentity.isGreaseBrand("Google Chrome"))
        assertFalse(NativeIdentity.isGreaseBrand("Android WebView"))
    }
}
