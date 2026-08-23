package com.geoalign.core.device

/**
 * Experimental device-emulation presets — **`community` edition only**.
 *
 * This file has no counterpart in `app/src/play`. That absence *is* the Play device-identity
 * policy (issue #4, README `## Editions`): presenting a hardware identity other than the real one is
 * exactly what an app-store reviewer scrutinises, so the Play artifact is built without the code
 * that can do it, rather than shipping it behind a flag. `testPlayDebugUnitTest` asserts this class
 * is not on the Play classpath and that none of the preset identifiers below appear anywhere in the
 * Play variant's compiled bytecode.
 *
 * Content is unchanged from the pre-flavor `DeviceProfiles` object; only its home moved.
 *
 * Honesty note (spec §1): these change what pages *see*, not what the hardware *is*. Some surfaces
 * — notably Sec-CH-UA request headers on the non-Chromium presets — remain imperfect.
 */
internal object ExperimentalDeviceProfiles {

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
        brands = UaBrands.CHROME_126,
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
        brands = UaBrands.CHROME_126,
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
        brands = UaBrands.CHROME_126,
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
        brands = UaBrands.CHROME_126,
        screenWidth = 360,
        screenHeight = 780,
        devicePixelRatio = 3.0,
        maxTouchPoints = 5,
        emitsClientHints = true,
    )

    /**
     * Picker order after "This device". Unchanged from the pre-flavor `DeviceProfiles.ALL` tail:
     * Android first (closest to the real hardware), then iOS, then desktop.
     */
    val ALL: List<DeviceProfile> = listOf(
        PIXEL_8, GALAXY_S24, IPHONE_15_PRO, IPHONE_SE, DESKTOP_MAC_CHROME, DESKTOP_WIN_CHROME,
    )
}
