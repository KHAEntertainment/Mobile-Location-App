package com.geoalign.core.i18n

/**
 * Suggests a regional locale for a VPN-exit country WITHOUT changing the user's content language
 * (spec §13). A US exit does not mean the user wants US English content — we keep their language
 * and only align the *region*. Pure and unit-tested.
 */
object LocaleSuggester {

    data class Suggestion(
        /** The user's preserved content language (unchanged), e.g. "en". */
        val contentLanguage: String,
        /** Suggested regional locale combining content language + exit region, e.g. "en-US". */
        val regionalLocale: String,
        /** Ordered navigator.languages proposal. */
        val languages: List<String>,
        val measurementSystem: com.geoalign.core.model.MeasurementSystem,
    )

    // Countries that customarily use US-style imperial units for everyday measures.
    private val IMPERIAL_COUNTRIES = setOf("US", "LR", "MM")

    /**
     * @param contentLanguage the user's chosen language tag ("en", "fr", "en-GB"); its language
     *   subtag is preserved. @param exitCountryCode ISO-3166 alpha-2 of the VPN exit (nullable).
     */
    fun suggest(contentLanguage: String, exitCountryCode: String?): Suggestion {
        val lang = contentLanguage.substringBefore('-').lowercase().ifBlank { "en" }
        val country = exitCountryCode?.trim()?.uppercase()?.takeIf { it.length == 2 }

        val regional = if (country != null) "$lang-$country" else lang
        // languages: regional first, then bare language, de-duplicated preserving order.
        val languages = linkedSetOf(regional, lang).toList()
        val measurement = if (country in IMPERIAL_COUNTRIES)
            com.geoalign.core.model.MeasurementSystem.IMPERIAL
        else
            com.geoalign.core.model.MeasurementSystem.METRIC

        return Suggestion(
            contentLanguage = lang,
            regionalLocale = regional,
            languages = languages,
            measurementSystem = measurement,
        )
    }
}
