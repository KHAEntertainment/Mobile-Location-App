package com.geoalign.web.environment

import android.content.Context
import com.geoalign.core.model.LocationProfile

/**
 * Compiles the document-start environment bundle for a specific [LocationProfile] (spec §11–13).
 * The token-substitution step is pure and unit-tested; loading the asset template needs a Context.
 */
object EnvBundleCompiler {

    /** Pure substitution of profile values into the JS template. */
    fun compile(template: String, profile: LocationProfile): String = template
        .replace("__LAT__", profile.latitude.toString())
        .replace("__LNG__", profile.longitude.toString())
        .replace("__ACC__", profile.accuracyMeters.toString())
        .replace("__TZ__", profile.timezone)
        .replace("__LANG__", profile.primaryLocale)
        .replace("__LANGS__", jsonStringArray(profile.languages.ifEmpty { listOf(profile.primaryLocale) }))

    /** Load the bundled template and compile it for [profile]. */
    fun compileFromAssets(context: Context, profile: LocationProfile): String {
        val template = context.assets.open("env_bundle.js").bufferedReader().use { it.readText() }
        return compile(template, profile)
    }

    /** Minimal JSON string-array builder (avoids pulling org.json into pure code). */
    private fun jsonStringArray(items: List<String>): String =
        items.joinToString(prefix = "[", postfix = "]", separator = ",") { item ->
            "\"" + item.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
}
