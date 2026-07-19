package com.geoalign.core.i18n

import com.geoalign.core.model.MeasurementSystem
import org.junit.Assert.assertEquals
import org.junit.Test

class LocaleSuggesterTest {

    @Test fun preservesContentLanguageWhileAligningRegion() {
        val s = LocaleSuggester.suggest("en", "DE")
        assertEquals("en", s.contentLanguage)          // NOT changed to German
        assertEquals("en-DE", s.regionalLocale)
        assertEquals(listOf("en-DE", "en"), s.languages)
        assertEquals(MeasurementSystem.METRIC, s.measurementSystem)
    }

    @Test fun usExitYieldsImperial() {
        val s = LocaleSuggester.suggest("en", "US")
        assertEquals("en-US", s.regionalLocale)
        assertEquals(MeasurementSystem.IMPERIAL, s.measurementSystem)
    }

    @Test fun stripsRegionFromInputLanguageTag() {
        val s = LocaleSuggester.suggest("en-GB", "FR")
        assertEquals("en", s.contentLanguage)
        assertEquals("en-FR", s.regionalLocale)
    }

    @Test fun noCountryFallsBackToBareLanguage() {
        val s = LocaleSuggester.suggest("fr", null)
        assertEquals("fr", s.regionalLocale)
        assertEquals(listOf("fr"), s.languages)
    }

    @Test fun blankLanguageDefaultsToEnglish() {
        val s = LocaleSuggester.suggest("", "JP")
        assertEquals("en-JP", s.regionalLocale)
    }

    @Test fun invalidCountryCodeIgnored() {
        val s = LocaleSuggester.suggest("de", "Germany") // not 2-letter
        assertEquals("de", s.regionalLocale)
    }
}
