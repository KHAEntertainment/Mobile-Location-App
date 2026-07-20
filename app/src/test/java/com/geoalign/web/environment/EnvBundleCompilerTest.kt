package com.geoalign.web.environment

import com.geoalign.core.model.LocationProfile
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class EnvBundleCompilerTest {

    private val template =
        "var LAT=__LAT__;var LNG=__LNG__;var ACC=__ACC__;var TZ=\"__TZ__\";" +
            "var L=\"__LANG__\";var LS=__LANGS__;"

    private fun profile() = LocationProfile(
        id = "1", name = "Tokyo",
        latitude = 35.6762, longitude = 139.6503,
        accuracyMeters = 1500.0,
        timezone = "Asia/Tokyo",
        primaryLocale = "en-JP",
        languages = listOf("en-JP", "en"),
        createdAtMillis = 0, updatedAtMillis = 0,
    )

    @Test fun substitutesAllTokens() {
        val out = EnvBundleCompiler.compile(template, profile())
        assertTrue(out.contains("var LAT=35.6762;"))
        assertTrue(out.contains("var LNG=139.6503;"))
        assertTrue(out.contains("var ACC=1500.0;"))
        assertTrue(out.contains("var TZ=\"Asia/Tokyo\";"))
        assertTrue(out.contains("var L=\"en-JP\";"))
        assertTrue(out.contains("var LS=[\"en-JP\",\"en\"];"))
        assertFalse(out.contains("__"))
    }

    @Test fun emptyLanguagesFallsBackToPrimaryLocale() {
        val out = EnvBundleCompiler.compile(template, profile().copy(languages = emptyList()))
        assertTrue(out.contains("var LS=[\"en-JP\"];"))
    }
}
