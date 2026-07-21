package com.geoalign.core.net

import com.geoalign.core.net.ExternalSchemePolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalSchemePolicyTest {

    @Test fun webSchemesLoadInWebView() {
        assertEquals(Action.LOAD_IN_WEBVIEW, ExternalSchemePolicy.classify("http"))
        assertEquals(Action.LOAD_IN_WEBVIEW, ExternalSchemePolicy.classify("https"))
        assertEquals(Action.LOAD_IN_WEBVIEW, ExternalSchemePolicy.classify("HTTPS"))
    }

    @Test fun safeSchemesOpenExternally() {
        listOf("tel", "mailto", "sms", "smsto", "mms", "geo", "market").forEach {
            assertEquals("scheme $it", Action.OPEN_EXTERNALLY, ExternalSchemePolicy.classify(it))
        }
    }

    @Test fun dangerousAndUnknownSchemesAreBlocked() {
        listOf("javascript", "data", "file", "intent", "android-app", "content", "blob", "ftp", "unknownx")
            .forEach { assertEquals("scheme $it", Action.BLOCK, ExternalSchemePolicy.classify(it)) }
    }

    @Test fun nullOrBlankIsBlocked() {
        assertEquals(Action.BLOCK, ExternalSchemePolicy.classify(null))
        assertEquals(Action.BLOCK, ExternalSchemePolicy.classify(""))
    }
}
