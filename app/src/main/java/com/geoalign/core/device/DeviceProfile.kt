package com.geoalign.core.device

import com.geoalign.core.model.LocationProfile

/**
 * Device-emulation presets (spec §14). A [DeviceProfile] bundles the browser-visible *hardware*
 * identity — User-Agent string, UA Client-Hints, and the JS-visible screen/platform/touch signals —
 * so the embedded browser can present as a common phone or desktop instead of the host WebView.
 *
 * This is pure, dependency-free data + a registry. The Android layer applies a chosen profile to the
 * WebView (UA string + injected device bundle); this file never touches WebView internals.
 *
 * Honesty note (spec §1): emulating a device changes what pages *see*, not what the hardware *is*.
 * It is a consistency tool, not an anonymity guarantee, and some surfaces (e.g. Sec-CH-UA request
 * headers on non-Chromium presets) remain imperfect.
 */

enum class DeviceClass { DESKTOP, IOS, ANDROID }

/** A single UA Client-Hints brand entry. [major] feeds low-entropy `brands`; [full] feeds `fullVersionList`. */
data class Brand(val brand: String, val major: String, val full: String)

data class DeviceProfile(
    val id: String,
    val displayName: String,
    val deviceClass: DeviceClass,
    val userAgent: String,
    val mobile: Boolean,
    /** navigator.platform value, e.g. "iPhone", "MacIntel", "Win32", "Linux armv8l". */
    val navPlatform: String,
    /** UA-CH platform, e.g. "iOS", "macOS", "Windows", "Android". */
    val uachPlatform: String,
    val platformVersion: String,
    val architecture: String,
    val bitness: String,
    val model: String,
    val browserFullVersion: String,
    val brands: List<Brand>,
    val screenWidth: Int,
    val screenHeight: Int,
    val devicePixelRatio: Double,
    val maxTouchPoints: Int,
    /**
     * Whether this device's engine exposes UA Client-Hints (`navigator.userAgentData`). True for
     * Chromium-based presets (desktop Chrome, Android Chrome); false for iOS Safari, where the shim
     * hides `userAgentData` to match real Safari.
     */
    val emitsClientHints: Boolean,
    /**
     * "This device" mode: present the real hardware (no screen/DPR/touch overrides) with a clean
     * Chrome user-agent, rather than spoofing a specific device. Most compatible with sites that
     * refuse inconsistent/embedded-WebView fingerprints (spec §14). The Android layer supplies the
     * real UA (de-WebView-ified); [userAgent] is unused for native profiles.
     */
    val native: Boolean = false,
)

object DeviceProfiles {

    private val chrome = listOf(
        Brand("Not/A)Brand", "8", "8.0.0.0"),
        Brand("Chromium", "126", "126.0.6478.0"),
        Brand("Google Chrome", "126", "126.0.6478.0"),
    )

    /**
     * Default, most-compatible mode: present the real device. No geometry overrides; the browser
     * supplies a cleaned Chrome UA and Chrome client-hints so the page sees a genuine mobile Chrome
     * rather than an embedded WebView.
     */
    val NATIVE = DeviceProfile(
        id = "native",
        displayName = "This device (recommended)",
        deviceClass = DeviceClass.ANDROID,
        userAgent = "", // supplied at runtime from the real WebView UA
        mobile = true,
        navPlatform = "Linux armv8l",
        uachPlatform = "Android",
        platformVersion = "14.0.0",
        architecture = "",
        bitness = "",
        model = "",
        browserFullVersion = "126.0.6478.0",
        brands = chrome,
        screenWidth = 0,
        screenHeight = 0,
        devicePixelRatio = 0.0,
        maxTouchPoints = 0,
        emitsClientHints = true,
        native = true,
    )

    val DESKTOP_MAC_CHROME = DeviceProfile(
        id = "desktop_mac_chrome",
        displayName = "Desktop — Chrome (macOS)",
        deviceClass = DeviceClass.DESKTOP,
        userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        mobile = false,
        navPlatform = "MacIntel",
        uachPlatform = "macOS",
        platformVersion = "14.5.0",
        architecture = "arm",
        bitness = "64",
        model = "",
        browserFullVersion = "126.0.6478.0",
        brands = chrome,
        screenWidth = 1512,
        screenHeight = 982,
        devicePixelRatio = 2.0,
        maxTouchPoints = 0,
        emitsClientHints = true,
    )

    val DESKTOP_WIN_CHROME = DeviceProfile(
        id = "desktop_win_chrome",
        displayName = "Desktop — Chrome (Windows)",
        deviceClass = DeviceClass.DESKTOP,
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        mobile = false,
        navPlatform = "Win32",
        uachPlatform = "Windows",
        platformVersion = "15.0.0",
        architecture = "x86",
        bitness = "64",
        model = "",
        browserFullVersion = "126.0.6478.0",
        brands = chrome,
        screenWidth = 1920,
        screenHeight = 1080,
        devicePixelRatio = 1.0,
        maxTouchPoints = 0,
        emitsClientHints = true,
    )

    val IPHONE_15_PRO = DeviceProfile(
        id = "iphone_15_pro",
        displayName = "iPhone 15 Pro (Safari)",
        deviceClass = DeviceClass.IOS,
        userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1",
        mobile = true,
        navPlatform = "iPhone",
        uachPlatform = "iOS",
        platformVersion = "17.5.0",
        architecture = "",
        bitness = "",
        model = "iPhone",
        browserFullVersion = "17.5",
        brands = emptyList(),
        screenWidth = 393,
        screenHeight = 852,
        devicePixelRatio = 3.0,
        maxTouchPoints = 5,
        emitsClientHints = false,
    )

    val IPHONE_SE = DeviceProfile(
        id = "iphone_se",
        displayName = "iPhone SE (Safari)",
        deviceClass = DeviceClass.IOS,
        userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1",
        mobile = true,
        navPlatform = "iPhone",
        uachPlatform = "iOS",
        platformVersion = "17.5.0",
        architecture = "",
        bitness = "",
        model = "iPhone",
        browserFullVersion = "17.5",
        brands = emptyList(),
        screenWidth = 375,
        screenHeight = 667,
        devicePixelRatio = 2.0,
        maxTouchPoints = 5,
        emitsClientHints = false,
    )

    val PIXEL_8 = DeviceProfile(
        id = "pixel_8",
        displayName = "Pixel 8 (Chrome)",
        deviceClass = DeviceClass.ANDROID,
        userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
        mobile = true,
        navPlatform = "Linux armv8l",
        uachPlatform = "Android",
        platformVersion = "14.0.0",
        architecture = "",
        bitness = "",
        model = "Pixel 8",
        browserFullVersion = "126.0.6478.0",
        brands = chrome,
        screenWidth = 412,
        screenHeight = 915,
        devicePixelRatio = 2.625,
        maxTouchPoints = 5,
        emitsClientHints = true,
    )

    val GALAXY_S24 = DeviceProfile(
        id = "galaxy_s24",
        displayName = "Galaxy S24 (Chrome)",
        deviceClass = DeviceClass.ANDROID,
        userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S921B) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
        mobile = true,
        navPlatform = "Linux armv8l",
        uachPlatform = "Android",
        platformVersion = "14.0.0",
        architecture = "",
        bitness = "",
        model = "SM-S921B",
        browserFullVersion = "126.0.6478.0",
        brands = chrome,
        screenWidth = 360,
        screenHeight = 780,
        devicePixelRatio = 3.0,
        maxTouchPoints = 5,
        emitsClientHints = true,
    )

    /** Presentation order for the picker: native first (recommended), then spoof presets. */
    val ALL: List<DeviceProfile> = listOf(
        NATIVE, PIXEL_8, GALAXY_S24, IPHONE_15_PRO, IPHONE_SE, DESKTOP_MAC_CHROME, DESKTOP_WIN_CHROME,
    )

    val DEFAULT_MOBILE: DeviceProfile = PIXEL_8
    val DEFAULT_DESKTOP: DeviceProfile = DESKTOP_MAC_CHROME

    fun byId(id: String?): DeviceProfile? = ALL.firstOrNull { it.id == id }

    /**
     * Resolve the device a location profile should present. Explicit [LocationProfile.userAgentProfileId]
     * wins; otherwise the legacy [LocationProfile.desktopMode] toggle picks desktop, and the default
     * is the most-compatible [NATIVE] mode.
     */
    fun forProfile(profile: LocationProfile): DeviceProfile =
        byId(profile.userAgentProfileId)
            ?: if (profile.desktopMode) DEFAULT_DESKTOP else NATIVE
}
