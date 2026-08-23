package com.geoalign.web.config

import com.geoalign.core.browser.BrowserCapability
import com.geoalign.core.browser.BrowserCapabilityGate
import com.geoalign.core.browser.BrowserGateDecision

/**
 * The one translation between the probe's answers and the pure decision types in `core/`.
 *
 * `core/` is plain Kotlin and does not know `web/` exists, so the gate and the Site & privacy
 * generator take a `Set<BrowserCapability>` rather than a [WebViewCapabilities]. That set is
 * produced here, once, from the single probe result — which is what keeps the project's rule intact:
 * capability facts are produced once and shared, and no surface re-queries `WebViewFeature` on its
 * own.
 */
fun WebViewCapabilities.supportedBrowserCapabilities(): Set<BrowserCapability> = buildSet {
    if (documentStartScript) add(BrowserCapability.DOCUMENT_START_SCRIPT)
    if (userAgentMetadata) add(BrowserCapability.USER_AGENT_METADATA)
    if (safeBrowsing) add(BrowserCapability.SAFE_BROWSING)
    if (serviceWorkerControl) add(BrowserCapability.SERVICE_WORKER_CONTROL)
    if (errorDescription) add(BrowserCapability.ERROR_DESCRIPTION)
}

/**
 * The gate decision for a probe result, carrying the WebView package identity along so the block
 * screen can name the build the user is being asked to replace.
 */
fun WebViewCapabilities.gateDecision(): BrowserGateDecision = BrowserCapabilityGate.decide(
    supported = supportedBrowserCapabilities(),
    webViewPackageName = packageName,
    webViewPackageVersion = packageVersion,
)
