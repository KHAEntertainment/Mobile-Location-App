package com.geoalign.core.browser

/** How much of a claimed protection is actually in force right now. */
enum class ProtectionState {
    /** In force. Reachable only when every capability the claim names is present. */
    ACTIVE,

    /** Partly in force, with a named gap the user is told about in the same breath. */
    DEGRADED,

    /** Not in force at all. */
    UNAVAILABLE,
}

/**
 * One line of the Site & privacy sheet.
 *
 * [requiresAll] is the honesty mechanism: it names the capabilities that have to be present for this
 * claim to be [ProtectionState.ACTIVE]. A claim with an empty set is one this app enforces itself —
 * denying camera and microphone, refusing invalid certificates — and therefore does not depend on
 * which WebView build happens to be installed. Everything else has to name its dependency, and
 * `SitePrivacyReportTest` asserts across every capability subset that no claim reaches ACTIVE
 * without them.
 */
data class ProtectionClaim(
    val title: String,
    val state: ProtectionState,
    /** Written to stand on its own after "title: ". Says what is true, never what was attempted. */
    val detail: String,
    val requiresAll: Set<BrowserCapability> = emptySet(),
) {
    val line: String get() = "• $title: $detail"
}

/**
 * The Site & privacy sheet, as data.
 *
 * Previously this was a single interpolated string in `BrowserScreen` that asserted "Location:
 * virtual" on every device, including one whose WebView cannot install a document-start script and
 * therefore never received a virtual environment at all. No protection may be reported active
 * merely because it was requested, so the sheet is now derived from the capability facts the probe
 * produced, and its text is generated where a test can read it.
 */
data class SitePrivacyReport(
    val host: String,
    val claims: List<ProtectionClaim>,
    val disclaimer: String,
) {

    /** Only the claims actually in force — the ones a reader is entitled to rely on. */
    val activeClaims: List<ProtectionClaim> get() = claims.filter { it.state == ProtectionState.ACTIVE }

    val siteLine: String get() = "Site: ${host.ifBlank { "—" }}"

    /** The whole sheet, ready to render. The only string the composable puts on screen. */
    val text: String
        get() = buildString {
            append(siteLine)
            append("\n\n")
            claims.joinTo(this, separator = "\n") { it.line }
            append("\n\n")
            append(disclaimer)
        }
}

/**
 * Generates the Site & privacy sheet from runtime state (spec §10, §17–19, §21).
 *
 * Pure, and deliberately reachable from a JVM test with an arbitrary capability set: the sheet's
 * wrongest possible state — claiming a virtual location on a WebView that cannot inject one — is
 * one that the shipped UI can no longer reach, because [BrowserCapabilityGate] blocks the browser
 * first. Keeping the generator independent of the gate is what lets the test prove the claim is
 * still correct in that state rather than merely unreachable.
 */
object SitePrivacySheet {

    const val DISCLAIMER: String = "This app does not operate the VPN and does not promise anonymity."

    /**
     * [host] is the current page's host (blank is fine — it renders as an em dash), [deviceLabel]
     * the emulated device's display name, and [supported] the capability set from the single probe.
     */
    fun forSession(
        host: String,
        deviceLabel: String,
        supported: Set<BrowserCapability>,
    ): SitePrivacyReport {
        val documentStart = BrowserCapability.DOCUMENT_START_SCRIPT in supported
        val clientHints = BrowserCapability.USER_AGENT_METADATA in supported
        val safeBrowsing = BrowserCapability.SAFE_BROWSING in supported
        val serviceWorkers = BrowserCapability.SERVICE_WORKER_CONTROL in supported

        return SitePrivacyReport(
            host = host,
            disclaimer = DISCLAIMER,
            claims = listOf(
                locationClaim(documentStart),
                captureClaim(),
                deviceClaim(deviceLabel, documentStart, clientHints),
                connectionClaim(),
                safeBrowsingClaim(safeBrowsing),
                serviceWorkerClaim(serviceWorkers),
            ),
        )
    }

    /**
     * The claim the bug was in. "virtual" appears in exactly one branch, and only when the
     * document-start script that installs the virtual environment can actually be registered.
     */
    private fun locationClaim(documentStart: Boolean): ProtectionClaim =
        if (documentStart) {
            ProtectionClaim(
                title = "Location",
                state = ProtectionState.ACTIVE,
                detail = "virtual — pages see your profile's coordinates, not this device's GPS.",
                requiresAll = setOf(BrowserCapability.DOCUMENT_START_SCRIPT),
            )
        } else {
            ProtectionClaim(
                title = "Location",
                state = ProtectionState.UNAVAILABLE,
                detail = "not replaced — this WebView cannot run a script before a page's own, so " +
                    "pages would read this device's real location APIs.",
            )
        }

    /** Enforced in `BrowserWebChromeClient`, so no WebView capability can take it away. */
    private fun captureClaim(): ProtectionClaim = ProtectionClaim(
        title = "Camera & microphone",
        state = ProtectionState.ACTIVE,
        detail = "blocked for every site. Refused by this app rather than by the WebView, so it " +
            "does not depend on which WebView is installed.",
    )

    /**
     * Device emulation has two halves and they fail separately: the user-agent *string* is a plain
     * `WebSettings` field that always applies, while the client hints behind `Sec-CH-UA` need a
     * capability JavaScript cannot substitute for. Saying "presenting as X" flat was the second,
     * quieter version of the same overclaim.
     */
    private fun deviceClaim(
        deviceLabel: String,
        documentStart: Boolean,
        clientHints: Boolean,
    ): ProtectionClaim = when {
        !documentStart -> ProtectionClaim(
            title = "Device",
            state = ProtectionState.UNAVAILABLE,
            detail = "the user-agent string says $deviceLabel, but the device bundle could not be " +
                "installed, so everything JavaScript reports is this device's own.",
        )
        clientHints -> ProtectionClaim(
            title = "Device",
            state = ProtectionState.ACTIVE,
            detail = "presenting as $deviceLabel, and the client hints match it, so the Sec-CH-UA " +
                "headers agree with the user-agent string.",
            requiresAll = setOf(
                BrowserCapability.DOCUMENT_START_SCRIPT,
                BrowserCapability.USER_AGENT_METADATA,
            ),
        )
        else -> ProtectionClaim(
            title = "Device",
            state = ProtectionState.DEGRADED,
            detail = "presenting as $deviceLabel in the user-agent string and to page scripts. This " +
                "WebView cannot set client hints, so Sec-CH-UA request headers still identify it.",
            requiresAll = setOf(BrowserCapability.DOCUMENT_START_SCRIPT),
        )
    }

    /** Certificate refusal and mixed-content blocking are this app's own settings and client. */
    private fun connectionClaim(): ProtectionClaim = ProtectionClaim(
        title = "Connections",
        state = ProtectionState.ACTIVE,
        detail = "HTTPS only; invalid certificates are refused with no way to proceed, and mixed " +
            "content is blocked.",
    )

    private fun safeBrowsingClaim(safeBrowsing: Boolean): ProtectionClaim =
        if (safeBrowsing) {
            ProtectionClaim(
                title = "Malicious sites",
                state = ProtectionState.ACTIVE,
                detail = "Safe Browsing is on, so known malicious sites are flagged.",
                requiresAll = setOf(BrowserCapability.SAFE_BROWSING),
            )
        } else {
            ProtectionClaim(
                title = "Malicious sites",
                state = ProtectionState.UNAVAILABLE,
                detail = "this WebView cannot enable Safe Browsing, so known malicious sites are " +
                    "not flagged.",
            )
        }

    /**
     * Never ACTIVE, on any device. `serviceWorkerControl` is carried as a fact and nothing consumes
     * it yet, so a supported WebView buys the user nothing today — reporting it as a working filter
     * would be the same overclaim in a new place. The two branches differ only in *why*.
     */
    private fun serviceWorkerClaim(serviceWorkers: Boolean): ProtectionClaim =
        if (serviceWorkers) {
            ProtectionClaim(
                title = "Local-network filtering",
                state = ProtectionState.DEGRADED,
                detail = "applies to requests made by pages. Requests made by service workers are " +
                    "not filtered yet, though this WebView would allow it.",
            )
        } else {
            ProtectionClaim(
                title = "Local-network filtering",
                state = ProtectionState.UNAVAILABLE,
                detail = "applies to requests made by pages. This WebView offers no service-worker " +
                    "control, so requests made by service workers cannot be filtered at all.",
            )
        }
}
