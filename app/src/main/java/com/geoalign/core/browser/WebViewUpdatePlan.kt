package com.geoalign.core.browser

/** One way of getting the user to a newer WebView, in the order they should be tried. */
enum class WebViewUpdateStep {
    /** `market://details?id=…` — the Play Store app, if one is installed and enabled. */
    PLAY_STORE_APP,

    /** `https://play.google.com/store/apps/details?id=…` — the web listing, via any browser. */
    PLAY_STORE_WEB,
    ;

    /** What taking this step successfully amounts to, so the launcher owns no mapping of its own. */
    val outcome: WebViewUpdateOutcome
        get() = when (this) {
            PLAY_STORE_APP -> WebViewUpdateOutcome.OPENED_STORE
            PLAY_STORE_WEB -> WebViewUpdateOutcome.OPENED_WEB
        }
}

/** What actually happened when the offer was taken up. */
enum class WebViewUpdateOutcome {
    /** The Play Store app opened on the WebView listing. */
    OPENED_STORE,

    /** No Play Store app, but a browser took the web listing. */
    OPENED_WEB,

    /** Nothing on this device could handle either. The user gets instructions instead. */
    UNAVAILABLE,
}

/**
 * Where to send a user whose WebView is too old, as pure data (spec §14).
 *
 * GeoAlign is sideloadable and a large share of installs will be on devices with no Play Store at
 * all — de-Googled ROMs, some Amazon and Chinese OEM builds, work profiles with Play disabled. So
 * "open the Play listing" cannot be the plan; it is only the first of several, and the last one has
 * to be text. Firing a `market://` intent at a device with no handler throws
 * `ActivityNotFoundException`, which on the block screen would mean the app crashing precisely
 * where it was explaining a problem.
 *
 * Every decision here is a string or a list, so the fallback ordering is unit-testable; only the
 * intent resolution itself is Android, in `AndroidWebViewUpdateLauncher`.
 */
object WebViewUpdatePlan {

    /**
     * The stock Google WebView package. Used only when the probe could not name the installed
     * implementation — sending the user to update Google's WebView on a device running Bromite's or
     * an OEM's would be wrong, so the probed name always wins.
     */
    const val DEFAULT_PACKAGE = "com.google.android.webview"

    /** The package to offer an update for: whatever is actually installed, else the stock one. */
    fun packageToUpdate(probedPackageName: String?): String =
        probedPackageName?.takeIf { it.isNotBlank() } ?: DEFAULT_PACKAGE

    fun marketUri(packageName: String): String = "market://details?id=$packageName"

    fun webUri(packageName: String): String = "https://play.google.com/store/apps/details?id=$packageName"

    /** The URI for a step, so the launcher walks [steps] without a `when` of its own. */
    fun uriFor(step: WebViewUpdateStep, packageName: String): String = when (step) {
        WebViewUpdateStep.PLAY_STORE_APP -> marketUri(packageName)
        WebViewUpdateStep.PLAY_STORE_WEB -> webUri(packageName)
    }

    /** Ordered, most-direct first. The launcher takes the first that resolves. */
    val steps: List<WebViewUpdateStep> = listOf(
        WebViewUpdateStep.PLAY_STORE_APP,
        WebViewUpdateStep.PLAY_STORE_WEB,
    )

    /**
     * What to show when neither step resolved. Names the package rather than assuming a store, and
     * offers the two routes that exist without one: the OEM's own updater, or a sideloaded build.
     */
    fun manualInstruction(packageName: String): String =
        "No app on this device can open the Play listing. Update \"$packageName\" through your " +
            "device's own system or app updater, or install a current WebView build yourself, then " +
            "reopen the browser."

    /** The button's label. Reads the same whether or not a store turns out to exist. */
    const val OFFER_LABEL: String = "Update Android System WebView"

    /** Result text for an outcome, so the block screen never has to guess what happened. */
    fun outcomeMessage(outcome: WebViewUpdateOutcome, packageName: String): String? = when (outcome) {
        WebViewUpdateOutcome.OPENED_STORE, WebViewUpdateOutcome.OPENED_WEB -> null
        WebViewUpdateOutcome.UNAVAILABLE -> manualInstruction(packageName)
    }
}
