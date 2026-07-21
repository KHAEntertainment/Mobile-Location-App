package com.geoalign.core.net

/**
 * Decides how a navigation URL should be handled based on its scheme (spec §16, §19). Web schemes
 * load in the WebView (subject to the local-network host policy); a small allowlist of safe schemes
 * hands off to the system (dialer, mail, maps); everything else — javascript:, data:, file:,
 * intent:, and unknown schemes — is blocked to avoid redirection and local-file leaks. Pure and
 * unit-tested.
 */
object ExternalSchemePolicy {

    enum class Action { LOAD_IN_WEBVIEW, OPEN_EXTERNALLY, BLOCK }

    private val WEB_SCHEMES = setOf("http", "https")

    /** Safe, user-expected external handlers. Deliberately excludes intent:/android-app:/javascript:. */
    private val EXTERNAL_SCHEMES = setOf("tel", "mailto", "sms", "smsto", "mms", "geo", "market")

    fun classify(scheme: String?): Action {
        val s = scheme?.lowercase()?.trim() ?: return Action.BLOCK
        return when {
            s in WEB_SCHEMES -> Action.LOAD_IN_WEBVIEW
            s in EXTERNAL_SCHEMES -> Action.OPEN_EXTERNALLY
            else -> Action.BLOCK
        }
    }
}
