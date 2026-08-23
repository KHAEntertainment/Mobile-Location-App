package com.geoalign.core.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The WebView-update offer, including the case that matters most for a sideloaded app: a device
 * with no Play Store at all. The launcher that fires the intents is Android; everything it decides
 * is here, so the no-Play path is exercised without one.
 */
class WebViewUpdatePlanTest {

    @Test fun theProbedPackageAlwaysWinsOverTheStockOne() {
        // Sending a user on a Bromite or OEM WebView to update Google's would be wrong.
        assertEquals("com.android.webview", WebViewUpdatePlan.packageToUpdate("com.android.webview"))
    }

    @Test fun anUnresolvablePackageFallsBackToTheStockWebView() {
        assertEquals(WebViewUpdatePlan.DEFAULT_PACKAGE, WebViewUpdatePlan.packageToUpdate(null))
        assertEquals(WebViewUpdatePlan.DEFAULT_PACKAGE, WebViewUpdatePlan.packageToUpdate(""))
        assertEquals(WebViewUpdatePlan.DEFAULT_PACKAGE, WebViewUpdatePlan.packageToUpdate("   "))
    }

    @Test fun theStoreAppIsTriedBeforeTheWebListing() {
        assertEquals(
            listOf(WebViewUpdateStep.PLAY_STORE_APP, WebViewUpdateStep.PLAY_STORE_WEB),
            WebViewUpdatePlan.steps,
        )
    }

    @Test fun eachStepHasAUriForTheChosenPackage() {
        val pkg = "com.google.android.webview"
        assertEquals(
            "market://details?id=com.google.android.webview",
            WebViewUpdatePlan.uriFor(WebViewUpdateStep.PLAY_STORE_APP, pkg),
        )
        assertEquals(
            "https://play.google.com/store/apps/details?id=com.google.android.webview",
            WebViewUpdatePlan.uriFor(WebViewUpdateStep.PLAY_STORE_WEB, pkg),
        )
    }

    @Test fun theWebFallbackIsHttpsSoItSurvivesThisAppsCleartextBan() {
        assertTrue(WebViewUpdatePlan.webUri("x").startsWith("https://"))
    }

    @Test fun everyStepMapsToASuccessOutcome() {
        assertEquals(WebViewUpdateOutcome.OPENED_STORE, WebViewUpdateStep.PLAY_STORE_APP.outcome)
        assertEquals(WebViewUpdateOutcome.OPENED_WEB, WebViewUpdateStep.PLAY_STORE_WEB.outcome)
    }

    @Test fun withNothingAbleToOpenTheListingTheUserGetsInstructionsInstead() {
        // The graceful-degradation criterion: no Play Store is an expected configuration for a
        // sideloadable app, so it produces text — not an ActivityNotFoundException, and not silence.
        val message = WebViewUpdatePlan.outcomeMessage(
            WebViewUpdateOutcome.UNAVAILABLE,
            "com.google.android.webview",
        )
        assertNotNull(message)
        assertTrue(message!!.contains("com.google.android.webview"))
        assertTrue(message.contains("system or app updater"))
    }

    @Test fun aSuccessfulLaunchProducesNoFallbackMessage() {
        assertNull(WebViewUpdatePlan.outcomeMessage(WebViewUpdateOutcome.OPENED_STORE, "x"))
        assertNull(WebViewUpdatePlan.outcomeMessage(WebViewUpdateOutcome.OPENED_WEB, "x"))
    }

    @Test fun theOfferLabelReadsTheSameWhetherOrNotAStoreExists() {
        // The button cannot promise the Play Store, because whether one exists is only discovered
        // after it is pressed.
        assertEquals("Update Android System WebView", WebViewUpdatePlan.OFFER_LABEL)
        assertTrue(!WebViewUpdatePlan.OFFER_LABEL.contains("Play"))
    }
}
