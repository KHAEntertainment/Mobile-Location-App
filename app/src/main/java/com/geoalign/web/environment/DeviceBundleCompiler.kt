package com.geoalign.web.environment

import android.content.Context
import com.geoalign.core.device.DeviceProfile

/**
 * Compiles the document-start device-emulation bundle for a [DeviceProfile] (spec §14). The
 * token-substitution + userAgentData block construction are pure and unit-tested; loading the asset
 * template needs a Context.
 */
object DeviceBundleCompiler {

    /** Pure substitution of device values into the JS template. */
    fun compile(template: String, profile: DeviceProfile): String = template
        .replace("__GEO_BLOCK__", geometryBlock(profile))
        .replace("__UAD_BLOCK__", userAgentDataBlock(profile))

    /** Load the bundled template and compile it for [profile]. */
    fun compileFromAssets(context: Context, profile: DeviceProfile): String {
        val template = context.assets.open("device_bundle.js").bufferedReader().use { it.readText() }
        return compile(template, profile)
    }

    /**
     * The geometry section: navigator.platform, touch points, devicePixelRatio and screen size.
     * Emitted only for spoof presets — "This device" ([DeviceProfile.native]) keeps the real values
     * so the page sees genuine, self-consistent hardware.
     */
    internal fun geometryBlock(profile: DeviceProfile): String {
        if (profile.native) return ""
        return buildString {
            append("def(navigator, \"platform\", ").append(jsQuote(profile.navPlatform)).append(");")
            append("def(navigator, \"maxTouchPoints\", ").append(profile.maxTouchPoints).append(");")
            append("def(window, \"devicePixelRatio\", ").append(profile.devicePixelRatio).append(");")
            append("def(screen, \"width\", ").append(profile.screenWidth).append(");")
            append("def(screen, \"height\", ").append(profile.screenHeight).append(");")
            append("def(screen, \"availWidth\", ").append(profile.screenWidth).append(");")
            append("def(screen, \"availHeight\", ").append(profile.screenHeight).append(");")
        }
    }

    /**
     * The navigator.userAgentData section. Chromium presets get a shim exposing brands + a
     * getHighEntropyValues() resolving the emulated values; non-Chromium (iOS Safari) hides the
     * property entirely to match a real Safari, where it does not exist.
     */
    internal fun userAgentDataBlock(profile: DeviceProfile): String {
        if (!profile.emitsClientHints) {
            return "def(navigator, \"userAgentData\", undefined);"
        }
        val brands = jsonBrandList(profile.brands.map { it.brand to it.major })
        val fullList = jsonBrandList(profile.brands.map { it.brand to it.full })
        val mobile = profile.mobile.toString()
        val platform = jsQuote(profile.uachPlatform)
        val high = buildString {
            append("{")
            append("\"brands\":").append(brands).append(",")
            append("\"mobile\":").append(mobile).append(",")
            append("\"platform\":").append(platform).append(",")
            append("\"platformVersion\":").append(jsQuote(profile.platformVersion)).append(",")
            append("\"architecture\":").append(jsQuote(profile.architecture)).append(",")
            append("\"bitness\":").append(jsQuote(profile.bitness)).append(",")
            append("\"model\":").append(jsQuote(profile.model)).append(",")
            append("\"uaFullVersion\":").append(jsQuote(profile.browserFullVersion)).append(",")
            append("\"fullVersionList\":").append(fullList)
            append("}")
        }
        return buildString {
            append("def(navigator, \"userAgentData\", {")
            append("brands:").append(brands).append(",")
            append("mobile:").append(mobile).append(",")
            append("platform:").append(platform).append(",")
            append("getHighEntropyValues:function(h){return Promise.resolve(").append(high).append(");},")
            append("toJSON:function(){return {brands:").append(brands)
            append(",mobile:").append(mobile).append(",platform:").append(platform).append("};}")
            append("});")
        }
    }

    private fun jsonBrandList(pairs: List<Pair<String, String>>): String =
        pairs.joinToString(prefix = "[", postfix = "]", separator = ",") { (brand, version) ->
            "{\"brand\":${jsQuote(brand)},\"version\":${jsQuote(version)}}"
        }

    private fun jsQuote(s: String): String = "\"" + jsEscape(s) + "\""

    private fun jsEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
}
