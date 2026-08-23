package com.geoalign.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `WebView.evaluateJavascript` hands back a JSON-*encoded* value, so the collector's JSON string
 * arrives double-encoded and a collector that has not finished arrives as the four characters
 * `null`. Getting that unwrapping wrong would not throw — it would produce an empty observation,
 * which the report would then describe as a browser that installed nothing. Hence a test.
 */
class ObservedEnvironmentParserTest {

    private val payload = """
        {"geolocationShimmed":true,"latitude":-33.8688,"longitude":151.2093,"accuracy":1500,
         "timezone":"Australia/Sydney","timezoneOffsetMinutes":-600,"language":"en-AU",
         "languages":["en-AU","en"],"userAgent":"Mozilla/5.0","platform":"Linux armv8l",
         "userAgentDataPresent":true,"userAgentDataPlatform":"Android","userAgentDataMobile":true,
         "screenWidth":412,"screenHeight":915,"devicePixelRatio":2.625,"maxTouchPoints":5}
    """.trimIndent()

    /** The shape evaluateJavascript actually delivers: the JSON string, re-encoded as JSON. */
    private fun doubleEncoded(json: String): String =
        "\"" + json.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    @Test fun readsTheDoubleEncodedFormEvaluateJavascriptReturns() {
        val parsed = ObservedEnvironmentParser.parse(doubleEncoded(payload))!!
        assertEquals(-33.8688, parsed.latitude!!, 1e-9)
        assertEquals("Australia/Sydney", parsed.timezone)
        assertEquals(listOf("en-AU", "en"), parsed.languages)
        assertEquals(412, parsed.screenWidth)
        assertTrue(parsed.geolocationShimmed)
    }

    @Test fun readsAPlainJsonObjectToo() {
        assertEquals("en-AU", ObservedEnvironmentParser.parse(payload)!!.language)
    }

    @Test fun treatsAnUnfinishedCollectorAsNoObservationRatherThanAnEmptyOne() {
        assertNull(ObservedEnvironmentParser.parse("null"))
        assertNull(ObservedEnvironmentParser.parse("undefined"))
        assertNull(ObservedEnvironmentParser.parse(""))
        assertNull(ObservedEnvironmentParser.parse(null))
        assertNull(ObservedEnvironmentParser.parse("   "))
    }

    @Test fun refusesAnythingThatIsNotAnObservationObject() {
        assertNull(ObservedEnvironmentParser.parse("\"not json\""))
        assertNull(ObservedEnvironmentParser.parse("[1,2,3]"))
        assertNull(ObservedEnvironmentParser.parse("{"))
    }

    /** A collector that only got half its answers is still an observation — of a half-broken WebView. */
    @Test fun acceptsAPartialObservation() {
        val parsed = ObservedEnvironmentParser.parse("""{"geolocationError":"no position within 2.5s"}""")!!
        assertEquals("no position within 2.5s", parsed.geolocationError)
        assertNull(parsed.latitude)
        assertEquals(false, parsed.geolocationShimmed)
    }

    /** Fields this Kotlin version does not know about must not sink the whole observation. */
    @Test fun ignoresFieldsItDoesNotKnow() {
        val parsed = ObservedEnvironmentParser.parse("""{"timezone":"UTC","somethingNew":42}""")!!
        assertEquals("UTC", parsed.timezone)
    }
}
