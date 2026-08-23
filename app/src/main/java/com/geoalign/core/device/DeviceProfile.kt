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
 *
 * **Distribution split (issue #4).** Only [DeviceProfiles.NATIVE] lives here. The experimental spoof
 * presets are declared in `ExperimentalDeviceProfiles`, which exists **only in the `community`
 * source set** — the `play` variant is not compiled with that file at all, so it has no preset
 * bytecode to hide. Both flavors supply a `DeviceProfileCatalog` with the same fully-qualified name;
 * `play`'s is empty. See `core/distribution/DistributionCapabilities.kt`.
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

/**
 * Shared UA Client-Hints brand lists. Deliberately a standalone object rather than a member of
 * [DeviceProfiles]: the flavor-supplied catalog reads it while [DeviceProfiles] is still
 * initialising its own `ALL`, and routing that through [DeviceProfiles] would make the two objects
 * mutually dependent at class-init time.
 */
internal object UaBrands {
    val CHROME_126 = listOf(
        Brand("Not/A)Brand", "8", "8.0.0.0"),
        Brand("Chromium", "126", "126.0.6478.0"),
        Brand("Google Chrome", "126", "126.0.6478.0"),
    )
}

/**
 * The device catalog as the rest of the app sees it. Shape is fixed in `main` so call sites never
 * need to know which edition they are running in; the *contents* beyond [NATIVE] come from the
 * flavor-supplied `DeviceProfileCatalog`.
 */
object DeviceProfiles {

    /**
     * Default, most-compatible mode: present the real device. No geometry overrides; the browser
     * supplies a cleaned Chrome UA and Chrome client-hints so the page sees a genuine mobile Chrome
     * rather than an embedded WebView.
     *
     * This is the one profile present in **every** edition, and on `play` it is the only one.
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
        brands = UaBrands.CHROME_126,
        screenWidth = 0,
        screenHeight = 0,
        devicePixelRatio = 0.0,
        maxTouchPoints = 0,
        emitsClientHints = true,
        native = true,
    )

    /**
     * Presentation order for the picker: native first (recommended), then whatever spoof presets
     * this edition was built with. On `play` that tail is empty, because the presets are not in the
     * source set — not because they were filtered out here.
     */
    val ALL: List<DeviceProfile> = listOf(NATIVE) + DeviceProfileCatalog.EXPERIMENTAL

    /**
     * Fallbacks for the legacy `desktopMode` toggle. An edition without spoof presets has no
     * desktop or non-native mobile preset to fall back to, so both resolve to [NATIVE].
     */
    val DEFAULT_MOBILE: DeviceProfile = DeviceProfileCatalog.DEFAULT_MOBILE ?: NATIVE
    val DEFAULT_DESKTOP: DeviceProfile = DeviceProfileCatalog.DEFAULT_DESKTOP ?: NATIVE

    fun byId(id: String?): DeviceProfile? = ALL.firstOrNull { it.id == id }

    /**
     * Resolve the device a location profile should present. Explicit [LocationProfile.userAgentProfileId]
     * wins; otherwise the legacy [LocationProfile.desktopMode] toggle picks desktop, and the default
     * is the most-compatible [NATIVE] mode.
     *
     * A profile created on `community` and carried to `play` (same JSON store, different edition)
     * names a preset id that this edition does not have; [byId] misses and it degrades to [NATIVE]
     * rather than crashing.
     */
    fun forProfile(profile: LocationProfile): DeviceProfile =
        byId(profile.userAgentProfileId)
            ?: if (profile.desktopMode) DEFAULT_DESKTOP else NATIVE
}
