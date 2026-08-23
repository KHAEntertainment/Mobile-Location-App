package com.geoalign.web.config

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.geoalign.core.browser.WebViewUpdateOutcome
import com.geoalign.core.browser.WebViewUpdatePlan

/**
 * Takes the user to a newer WebView, or admits it cannot.
 *
 * The ordering and the wording are [WebViewUpdatePlan]'s; the only thing here is trying the intents.
 * Each attempt is wrapped, because a `market://` intent on a device with no Play Store throws
 * `ActivityNotFoundException` — and this launcher is only ever invoked from the screen that is
 * already explaining a problem, so crashing there is the one outcome that must be impossible. This
 * app is sideloadable and plenty of its installs will be on devices with no Play Store at all, so
 * "no handler" is an expected path, not an error.
 *
 * Resolution is attempted rather than queried: `resolveActivity` is subject to package visibility
 * from API 30, which would need a `<queries>` entry to answer honestly, and a false negative there
 * would send a user with a perfectly good Play Store to the manual instructions.
 */
class AndroidWebViewUpdateLauncher(private val context: Context) {

    /**
     * Offer an update for [probedPackageName] — the WebView implementation the probe actually found,
     * or null to fall back to the stock package. Returns what happened; never throws.
     */
    fun launch(probedPackageName: String?): WebViewUpdateOutcome {
        val packageName = WebViewUpdatePlan.packageToUpdate(probedPackageName)
        for (step in WebViewUpdatePlan.steps) {
            if (start(WebViewUpdatePlan.uriFor(step, packageName))) return step.outcome
        }
        return WebViewUpdateOutcome.UNAVAILABLE
    }

    /** True if something handled the URI. NEW_TASK because this context need not be an Activity. */
    private fun start(uri: String): Boolean = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess
}
