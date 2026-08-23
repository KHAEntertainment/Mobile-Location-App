package com.geoalign.core.browser

/**
 * A WebView capability the browser has an opinion about, and what its absence actually costs.
 *
 * The implementation behind `android.webkit.WebView` is a separately updatable system package, so
 * this list is a *runtime* question on every device. Splitting it into required and optional is the
 * whole point: one of these is load-bearing for the product's central claim and the rest degrade
 * something the user should still be told about, and until now both kinds were handled the same way
 * — by carrying on regardless and describing the browser as if nothing were missing.
 *
 * [consequence] is written to be shown to a user verbatim. It says what *is* true without the
 * capability, never what was attempted.
 */
enum class BrowserCapability(
    /** False means the browser still opens; the user is told what is degraded. */
    val required: Boolean,
    /** Short label, for a list of capability facts. */
    val displayName: String,
    /** What is actually the case on a device that lacks it. */
    val consequence: String,
) {
    /**
     * `WebViewCompat.addDocumentStartJavaScript`. The one required capability: without it neither
     * the location environment nor the device bundle can be installed *before* page script runs,
     * and a late injection loses every race against a page's own bootstrap. A browser in that state
     * is not aligned with anything — it is an ordinary WebView showing the device's real geolocation
     * API, which is the opposite of what this app exists to do.
     */
    DOCUMENT_START_SCRIPT(
        required = true,
        displayName = "Document-start script injection",
        consequence = "The virtual location and device environment cannot be installed before a " +
            "page's own scripts run, so pages would see this device's real environment.",
    ),

    /**
     * `WebSettingsCompat.setUserAgentMetadata`. Optional: the user-agent *string* is set regardless,
     * so device emulation still works client-side. What is lost is the `Sec-CH-UA` request headers,
     * which JavaScript cannot reach.
     */
    USER_AGENT_METADATA(
        required = false,
        displayName = "User-agent client hints",
        consequence = "Sec-CH-UA request headers keep identifying this WebView, even though the " +
            "user-agent string presents the selected device.",
    ),

    /** `WebSettingsCompat.setSafeBrowsingEnabled`. Optional: no malicious-site warnings without it. */
    SAFE_BROWSING(
        required = false,
        displayName = "Safe Browsing",
        consequence = "Google Safe Browsing cannot be switched on, so known malicious sites are " +
            "not flagged.",
    ),

    /**
     * `ServiceWorkerControllerCompat`. Optional, and deliberately modest: nothing consumes it yet,
     * so its presence is not a protection either. Its *absence* is still a fact worth stating,
     * because service-worker requests bypass `WebViewClient` entirely.
     */
    SERVICE_WORKER_CONTROL(
        required = false,
        displayName = "Service worker control",
        consequence = "Requests issued by service workers cannot be reached at all, so the " +
            "local-network policy has no way to see them.",
    ),

    /**
     * `WebViewFeature.WEB_RESOURCE_ERROR_GET_DESCRIPTION`. Optional and cosmetic: a failed load is
     * still reported, just without the platform's description of why.
     */
    ERROR_DESCRIPTION(
        required = false,
        displayName = "Load-error descriptions",
        consequence = "Failed loads are reported without the platform's description of the failure.",
    ),
    ;

    companion object {
        val REQUIRED: List<BrowserCapability> = entries.filter { it.required }
        val OPTIONAL: List<BrowserCapability> = entries.filter { !it.required }
    }
}

/** Whether aligned browsing may start at all. */
enum class GateVerdict {
    /** Every required capability is present. Optional gaps may still be reported. */
    ALLOWED,

    /** At least one required capability is missing; the browser must not open in aligned mode. */
    BLOCKED,
}

/**
 * The gate's answer: what the installed WebView can do, what it cannot, and whether that is fatal.
 *
 * Carries the whole fact set rather than a bare boolean, because two other surfaces need the same
 * answers — the Site & privacy sheet ([SitePrivacySheet]) and diagnostics — and the project rule is
 * that capability facts are produced once and shared, never re-derived per surface.
 */
data class BrowserGateDecision(
    val verdict: GateVerdict,
    /** Everything the installed WebView supports, of the set this browser cares about. */
    val supported: Set<BrowserCapability>,
    /** Missing capabilities that block aligned browsing. Empty when [verdict] is ALLOWED. */
    val missingRequired: List<BrowserCapability>,
    /** Missing capabilities that only degrade it. Reported separately, never mixed into the block. */
    val missingOptional: List<BrowserCapability>,
    /** Package name of the WebView implementation, or null if none could be resolved. */
    val webViewPackageName: String?,
    /** Version of that package, or null if none could be resolved. */
    val webViewPackageVersion: String?,
) {

    val allowsAlignedBrowsing: Boolean get() = verdict == GateVerdict.ALLOWED

    /** True when the browser opens but with something to disclose. */
    val hasOptionalGaps: Boolean get() = missingOptional.isNotEmpty()

    val headline: String
        get() = if (allowsAlignedBrowsing) {
            "Aligned browsing is available"
        } else {
            "This device's WebView can't run aligned browsing"
        }

    /**
     * Why the browser refuses, in the user's terms. Empty when nothing is blocked — a caller that
     * renders it unconditionally shows nothing rather than a reassuring falsehood.
     */
    val reason: String
        get() = if (allowsAlignedBrowsing) {
            ""
        } else {
            missingRequired.joinToString(separator = "\n\n") { "${it.displayName} is unavailable. ${it.consequence}" }
        }

    /**
     * The installed WebView, for the block screen and for diagnostics. Never invents a build: an
     * unresolvable package says so.
     */
    val installedWebViewLabel: String
        get() = when {
            webViewPackageName == null -> "No WebView implementation could be identified on this device."
            webViewPackageVersion == null -> "Installed WebView: $webViewPackageName (version unknown)."
            else -> "Installed WebView: $webViewPackageName $webViewPackageVersion."
        }

    /** Optional gaps as user-facing lines, or null when there are none. Never merged into [reason]. */
    val optionalNotice: String?
        get() = missingOptional
            .takeIf { it.isNotEmpty() }
            ?.joinToString(separator = "\n") { "• ${it.displayName}: ${it.consequence}" }
}

/**
 * The capability gate (spec §10, §14, §21).
 *
 * `DOCUMENT_START_SCRIPT` was already checked before installing scripts, but an unsupported device
 * simply carried on without the virtual environment while every surface kept describing the browser
 * as aligned. This decision table is the single place that says otherwise, and it is pure so that
 * the "document-start absent" path is reachable from a JVM test by stubbing the capability set —
 * this repo has no Robolectric and no Mockito, so a decision that could only be exercised against a
 * real WebView would not be exercised at all.
 */
object BrowserCapabilityGate {

    /**
     * Decide from the capabilities the installed WebView actually supports.
     *
     * [supported] is produced once by the probe (see `WebViewCapabilities.supportedBrowserCapabilities`)
     * and passed in; nothing here asks the platform anything.
     */
    fun decide(
        supported: Set<BrowserCapability>,
        webViewPackageName: String? = null,
        webViewPackageVersion: String? = null,
    ): BrowserGateDecision {
        val missingRequired = BrowserCapability.REQUIRED.filterNot { it in supported }
        val missingOptional = BrowserCapability.OPTIONAL.filterNot { it in supported }
        return BrowserGateDecision(
            verdict = if (missingRequired.isEmpty()) GateVerdict.ALLOWED else GateVerdict.BLOCKED,
            supported = supported,
            missingRequired = missingRequired,
            missingOptional = missingOptional,
            webViewPackageName = webViewPackageName,
            webViewPackageVersion = webViewPackageVersion,
        )
    }
}
