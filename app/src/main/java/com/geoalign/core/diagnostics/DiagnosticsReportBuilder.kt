package com.geoalign.core.diagnostics

import com.geoalign.core.browser.BrowserCapability
import com.geoalign.core.browser.BrowserGateDecision
import com.geoalign.core.device.DeviceProfile
import com.geoalign.core.model.IpGeolocation
import com.geoalign.core.model.LocationProfile
import com.geoalign.core.net.IpRedaction
import com.geoalign.core.readiness.VpnTransport
import java.util.Locale

/**
 * Everything the report is derived from, gathered by the screen and handed over in one value.
 *
 * Note what is *not* here: no probe, no `WebViewFeature`, no context. [gate] is the single probe's
 * decision, produced once by `WebViewCapabilities.gateDecision()` and shared with the browser and
 * the Site & privacy sheet, and [observation] is what the production-configured WebView reported
 * back. This type holding only values is what lets every branch below be reached from a JVM test.
 *
 * [expectedUserAgent] is computed by the caller with `BrowserSettingsSpec.userAgentFor`, the same
 * function the configurator uses — so the comparison is against what production would have set, not
 * against a second opinion assembled here.
 */
data class DiagnosticsInput(
    val gate: BrowserGateDecision,
    val profile: LocationProfile?,
    val device: DeviceProfile,
    val expectedUserAgent: String?,
    val observation: ObservationOutcome,
    val vpn: VpnTransport?,
    /** Raw effective address. Redacted before it reaches the report; never rendered as given. */
    val effectiveIp: String?,
    /** The exit estimate. Its `ip` field is never rendered — the redacted [effectiveIp] is. */
    val exit: IpGeolocation?,
)

/**
 * Builds the compatibility report (issue #8, spec §10–§14, §21).
 *
 * Pure, in `core/`, for the same reason `SitePrivacySheet` is: the states worth getting right are
 * the ugly ones — a WebView that installed nothing, coordinates that came back as the device's own,
 * a locale the bundle failed to set — and none of them can be produced on demand from a real device.
 * Here they are three lines of test setup.
 *
 * Two rules run through all of it. Nothing is reported as passing because it was *requested*: every
 * PASS below is a comparison against something the page actually reported. And no full address ever
 * reaches a line — the whole point of this report is that it can be pasted into a public issue.
 */
object DiagnosticsReportBuilder {

    const val TITLE: String = "GeoAlign compatibility report"

    const val DISCLAIMER: String =
        "This report describes what web pages see in this browser, on this device, with the " +
            "profile named above. IP addresses are redacted to their first two components. " +
            "GeoAlign does not operate the VPN and does not promise anonymity."

    fun build(input: DiagnosticsInput): DiagnosticsReport = DiagnosticsReport(
        title = TITLE,
        summary = summary(input),
        sections = listOf(
            DiagnosticsSection("Installed WebView", webViewChecks(input.gate)),
            DiagnosticsSection("Environment seen by pages", environmentChecks(input)),
            DiagnosticsSection("Connection", connectionChecks(input)),
            DiagnosticsSection("Known gaps", KNOWN_GAPS),
        ),
        disclaimer = DISCLAIMER,
    )

    private fun summary(input: DiagnosticsInput): List<String> = buildList {
        val profile = input.profile
        add(
            if (profile == null) {
                "Profile: none selected"
            } else {
                "Profile: ${profile.name} (${coordinates(profile.latitude, profile.longitude)}, " +
                    "${profile.timezone}, ${profile.primaryLocale})"
            },
        )
        add("Device mode: ${input.device.displayName}")
        add(input.gate.installedWebViewLabel)
    }

    // --- installed WebView ------------------------------------------------------------------

    /**
     * One line per capability, from the gate's own answers and each capability's own
     * [BrowserCapability.consequence]. `ERROR_DESCRIPTION` appears here and deliberately not in the
     * Site & privacy sheet: it changes what a *developer* can see about a failed load and protects
     * nobody, so a user-facing protection sheet listing it would be padding.
     */
    private fun webViewChecks(gate: BrowserGateDecision): List<DiagnosticCheck> = buildList {
        add(
            DiagnosticCheck(
                label = "WebView package",
                status = when {
                    gate.webViewPackageName == null -> CheckStatus.WARNING
                    gate.webViewPackageVersion == null -> CheckStatus.WARNING
                    else -> CheckStatus.PASS
                },
                detail = gate.installedWebViewLabel,
            ),
        )
        BrowserCapability.entries.forEach { capability ->
            val supported = capability in gate.supported
            add(
                DiagnosticCheck(
                    label = capability.displayName,
                    status = when {
                        supported -> CheckStatus.PASS
                        capability.required -> CheckStatus.FAILED
                        else -> CheckStatus.WARNING
                    },
                    detail = if (supported) {
                        "Supported by the installed WebView."
                    } else {
                        capability.consequence
                    },
                ),
            )
        }
    }

    // --- what the page saw ------------------------------------------------------------------

    private fun environmentChecks(input: DiagnosticsInput): List<DiagnosticCheck> =
        when (val outcome = input.observation) {
            ObservationOutcome.Pending -> listOf(
                DiagnosticCheck(
                    label = "Observation",
                    status = CheckStatus.WARNING,
                    detail = "The configured browser has not reported back yet.",
                ),
            )
            ObservationOutcome.NotInstalled -> listOf(
                DiagnosticCheck(
                    label = "Observation",
                    status = CheckStatus.UNSUPPORTED,
                    detail = "This WebView cannot run a script before a page's own, so no virtual " +
                        "environment was installed and there is nothing to observe.",
                ),
            )
            is ObservationOutcome.Failed -> listOf(
                DiagnosticCheck(
                    label = "Observation",
                    status = CheckStatus.FAILED,
                    detail = "The configured browser could not be read back: ${outcome.reason}",
                ),
            )
            is ObservationOutcome.Completed -> completedChecks(input, outcome.environment)
        }

    private fun completedChecks(
        input: DiagnosticsInput,
        env: ObservedEnvironment,
    ): List<DiagnosticCheck> = buildList {
        env.error?.let {
            add(
                DiagnosticCheck(
                    label = "Observation",
                    status = CheckStatus.FAILED,
                    detail = "The page reported an error while reading its environment: $it",
                ),
            )
        }
        add(injectionCheck(env))
        add(coordinatesCheck(input.profile, env))
        add(timezoneCheck(input.profile, env))
        add(localeCheck(input.profile, env))
        add(userAgentCheck(input.expectedUserAgent, env))
        add(clientHintsCheck(input, env))
        add(geometryCheck(input.device, env))
    }

    private fun injectionCheck(env: ObservedEnvironment): DiagnosticCheck = DiagnosticCheck(
        label = "Document-start injection",
        status = if (env.geolocationShimmed) CheckStatus.PASS else CheckStatus.FAILED,
        detail = if (env.geolocationShimmed) {
            "navigator.geolocation was replaced before the page's own scripts ran."
        } else {
            "navigator.geolocation is the platform's own, so the page was reading this device's " +
                "location APIs rather than the profile."
        },
    )

    private fun coordinatesCheck(
        profile: LocationProfile?,
        env: ObservedEnvironment,
    ): DiagnosticCheck {
        val label = "Coordinates"
        if (profile == null) {
            return DiagnosticCheck(
                label = label,
                status = CheckStatus.WARNING,
                detail = "No profile is selected, so there is nothing the page should have seen.",
            )
        }
        val lat = env.latitude
        val lng = env.longitude
        if (lat == null || lng == null) {
            return DiagnosticCheck(
                label = label,
                status = CheckStatus.FAILED,
                detail = "The page asked for a position and got none" +
                    (env.geolocationError?.let { " ($it)" } ?: "") +
                    ". Expected ${coordinates(profile.latitude, profile.longitude)}.",
            )
        }
        val matches = close(lat, profile.latitude) && close(lng, profile.longitude)
        return DiagnosticCheck(
            label = label,
            status = if (matches) CheckStatus.PASS else CheckStatus.FAILED,
            detail = if (matches) {
                "The page was given ${coordinates(lat, lng)}, the profile's position."
            } else {
                "The page was given ${coordinates(lat, lng)}, but the profile is " +
                    "${coordinates(profile.latitude, profile.longitude)}."
            },
        )
    }

    private fun timezoneCheck(profile: LocationProfile?, env: ObservedEnvironment): DiagnosticCheck {
        val label = "Timezone"
        val observed = env.timezone?.trim()?.ifBlank { null }
        if (profile == null) {
            return DiagnosticCheck(
                label = label,
                status = CheckStatus.WARNING,
                detail = "No profile is selected. The page resolved ${observed ?: "nothing"}.",
            )
        }
        if (observed == null) {
            return DiagnosticCheck(
                label = label,
                status = CheckStatus.FAILED,
                detail = "The page could not resolve a timezone at all. Expected ${profile.timezone}.",
            )
        }
        val matches = observed.equals(profile.timezone, ignoreCase = true)
        return DiagnosticCheck(
            label = label,
            status = if (matches) CheckStatus.PASS else CheckStatus.FAILED,
            detail = if (matches) {
                "Intl resolved $observed, the profile's zone" +
                    (env.timezoneOffsetMinutes?.let { " (offset ${offsetLabel(it)})" } ?: "") + "."
            } else {
                "Intl resolved $observed, but the profile is ${profile.timezone}."
            },
        )
    }

    private fun localeCheck(profile: LocationProfile?, env: ObservedEnvironment): DiagnosticCheck {
        val label = "Language"
        val observed = env.language?.trim()?.ifBlank { null }
        val list = env.languages.joinToString(", ").ifBlank { "—" }
        if (profile == null) {
            return DiagnosticCheck(
                label = label,
                status = CheckStatus.WARNING,
                detail = "No profile is selected. The page read ${observed ?: "nothing"}.",
            )
        }
        if (observed == null) {
            return DiagnosticCheck(
                label = label,
                status = CheckStatus.FAILED,
                detail = "navigator.language was empty. Expected ${profile.primaryLocale}.",
            )
        }
        val matches = observed.equals(profile.primaryLocale, ignoreCase = true)
        return DiagnosticCheck(
            label = label,
            status = if (matches) CheckStatus.PASS else CheckStatus.FAILED,
            detail = if (matches) {
                "navigator.language is $observed; navigator.languages is [$list]."
            } else {
                "navigator.language is $observed, but the profile is ${profile.primaryLocale}. " +
                    "navigator.languages is [$list]."
            },
        )
    }

    private fun userAgentCheck(expected: String?, env: ObservedEnvironment): DiagnosticCheck {
        val label = "User-agent string"
        val observed = env.userAgent?.trim()?.ifBlank { null }
            ?: return DiagnosticCheck(
                label = label,
                status = CheckStatus.FAILED,
                detail = "The page reported no user-agent at all.",
            )
        // A UA still carrying the WebView marker is the failure this browser exists to avoid: it
        // tells every site it is an embedded WebView regardless of which device is being presented.
        if (observed.contains("; wv")) {
            return DiagnosticCheck(
                label = label,
                status = CheckStatus.FAILED,
                detail = "The page still sees the WebView marker (\"; wv\"): $observed",
            )
        }
        if (expected == null) {
            return DiagnosticCheck(
                label = label,
                status = CheckStatus.WARNING,
                detail = "Nothing to compare against on this run. The page sees: $observed",
            )
        }
        val matches = observed == expected
        return DiagnosticCheck(
            label = label,
            status = if (matches) CheckStatus.PASS else CheckStatus.FAILED,
            detail = if (matches) {
                "The page sees exactly the string this device mode sets: $observed"
            } else {
                "The page sees $observed, but this device mode sets $expected."
            },
        )
    }

    /**
     * Client hints have two failure modes that must not be confused: a WebView that cannot set them
     * (a gap, already reported above, so a warning here) and a WebView that set them to something
     * the presented device contradicts (a fault). A preset whose engine does not expose
     * `navigator.userAgentData` at all — iOS Safari — passes precisely by its absence.
     */
    private fun clientHintsCheck(
        input: DiagnosticsInput,
        env: ObservedEnvironment,
    ): DiagnosticCheck {
        val label = "Client hints"
        val device = input.device
        if (!device.emitsClientHints) {
            return DiagnosticCheck(
                label = label,
                status = if (env.userAgentDataPresent) CheckStatus.FAILED else CheckStatus.PASS,
                detail = if (env.userAgentDataPresent) {
                    "navigator.userAgentData is exposed, but ${device.displayName} does not have it, " +
                        "so the page can tell the two apart."
                } else {
                    "navigator.userAgentData is absent, as it is on ${device.displayName}."
                },
            )
        }
        if (BrowserCapability.USER_AGENT_METADATA !in input.gate.supported) {
            return DiagnosticCheck(
                label = label,
                status = CheckStatus.WARNING,
                detail = BrowserCapability.USER_AGENT_METADATA.consequence,
            )
        }
        if (!env.userAgentDataPresent) {
            return DiagnosticCheck(
                label = label,
                status = CheckStatus.FAILED,
                detail = "navigator.userAgentData is missing, though ${device.displayName} should " +
                    "expose it and this WebView can set it.",
            )
        }
        val platform = env.userAgentDataPlatform?.trim().orEmpty()
        val platformMatches = platform.equals(device.uachPlatform, ignoreCase = true)
        val mobileMatches = env.userAgentDataMobile == null || env.userAgentDataMobile == device.mobile
        return DiagnosticCheck(
            label = label,
            status = if (platformMatches && mobileMatches) CheckStatus.PASS else CheckStatus.FAILED,
            detail = if (platformMatches && mobileMatches) {
                "navigator.userAgentData reports platform $platform, mobile=${env.userAgentDataMobile}, " +
                    "matching ${device.displayName}. Sec-CH-UA headers are set from the same source."
            } else {
                "navigator.userAgentData reports platform ${platform.ifBlank { "—" }}, " +
                    "mobile=${env.userAgentDataMobile}, but ${device.displayName} is " +
                    "${device.uachPlatform}, mobile=${device.mobile}."
            },
        )
    }

    /**
     * Screen geometry and touch points. "This device" mode deliberately overrides none of them — the
     * real hardware is the consistent answer there — so the check reports the observed values and
     * only compares when a preset claimed specific ones.
     */
    private fun geometryCheck(device: DeviceProfile, env: ObservedEnvironment): DiagnosticCheck {
        val label = "Screen & touch"
        val observed = "${env.screenWidth ?: "?"}×${env.screenHeight ?: "?"}, dpr " +
            "${env.devicePixelRatio ?: "?"}, ${env.maxTouchPoints ?: "?"} touch points"
        if (device.native) {
            return DiagnosticCheck(
                label = label,
                status = CheckStatus.PASS,
                detail = "This device's own values, as ${device.displayName} intends: $observed.",
            )
        }
        val matches = env.screenWidth == device.screenWidth && env.screenHeight == device.screenHeight
        return DiagnosticCheck(
            label = label,
            status = if (matches) CheckStatus.PASS else CheckStatus.FAILED,
            detail = if (matches) {
                "The page sees $observed, the values ${device.displayName} declares."
            } else {
                "The page sees $observed, but ${device.displayName} declares " +
                    "${device.screenWidth}×${device.screenHeight}."
            },
        )
    }

    // --- connection -------------------------------------------------------------------------

    private fun connectionChecks(input: DiagnosticsInput): List<DiagnosticCheck> = buildList {
        add(
            DiagnosticCheck(
                label = "VPN transport",
                status = when (input.vpn) {
                    VpnTransport.DETECTED -> CheckStatus.PASS
                    VpnTransport.NOT_DETECTED, VpnTransport.NETWORK_UNAVAILABLE -> CheckStatus.FAILED
                    VpnTransport.ERROR, VpnTransport.CHECKING, null -> CheckStatus.WARNING
                },
                detail = when (input.vpn) {
                    VpnTransport.DETECTED ->
                        "A VPN transport is active on the network this browser is using."
                    VpnTransport.NOT_DETECTED ->
                        "No VPN transport is active, so the profile is not aligned with anything."
                    VpnTransport.NETWORK_UNAVAILABLE ->
                        "No network is available, so nothing about the exit can be established."
                    VpnTransport.ERROR ->
                        "The transport could not be read, so a VPN can be neither confirmed nor ruled out."
                    VpnTransport.CHECKING, null ->
                        "The transport has not been established yet."
                },
            ),
        )
        // The redaction that makes this report safe to paste. The raw address is in the input and
        // must not survive into any line here.
        val redacted = input.effectiveIp?.trim()?.ifBlank { null }?.let { IpRedaction.redact(it) }
        add(
            DiagnosticCheck(
                label = "Effective IP",
                status = if (redacted == null) CheckStatus.WARNING else CheckStatus.PASS,
                detail = redacted?.let { "Redacted: $it" }
                    ?: "The effective public address could not be verified on this connection.",
            ),
        )
        val exit = input.exit
        val place = listOfNotNull(
            exit?.city?.trim()?.ifBlank { null },
            exit?.countryName?.trim()?.ifBlank { null } ?: exit?.countryCode?.trim()?.ifBlank { null },
        ).joinToString(", ").ifBlank { null }
        add(
            DiagnosticCheck(
                label = "Exit estimate",
                status = if (place == null) CheckStatus.WARNING else CheckStatus.PASS,
                detail = if (place == null) {
                    "No location estimate is available for the current exit."
                } else {
                    "$place, per ${exit?.providerName?.trim()?.ifBlank { null } ?: "the estimate provider"}" +
                        (exit?.timezone?.trim()?.ifBlank { null }?.let { " ($it)" } ?: "") + "."
                },
            ),
        )
    }

    // --- what this browser cannot do, on any device -------------------------------------------

    /**
     * Not device-dependent and not a fault: these are limits of an app-level WebView, and stating
     * them is the difference between a report a site owner can act on and one that implies total
     * isolation (`docs/ARCHITECTURE_PLAN.md` §11, §21).
     */
    private val KNOWN_GAPS: List<DiagnosticCheck> = listOf(
        DiagnosticCheck(
            label = "WebSocket traffic",
            status = CheckStatus.UNSUPPORTED,
            detail = "WebSocket connections do not pass through the WebView's request callback, so " +
                "the local-network policy cannot see or block them on any device.",
        ),
        DiagnosticCheck(
            label = "DNS rebinding",
            status = CheckStatus.UNSUPPORTED,
            detail = "The local-network policy classifies the address written in a URL. A hostname " +
                "that resolves to a private address at connection time is not caught.",
        ),
        DiagnosticCheck(
            label = "Service-worker requests",
            status = CheckStatus.UNSUPPORTED,
            detail = "Requests issued by service workers bypass the request callback entirely and " +
                "are not filtered, whatever this WebView supports.",
        ),
    )

    // --- formatting ---------------------------------------------------------------------------

    /** Four decimals, always in the root locale — a report copied on a German phone must not use commas. */
    private fun coordinates(lat: Double, lng: Double): String =
        String.format(Locale.ROOT, "%.4f, %.4f", lat, lng)

    private fun offsetLabel(minutes: Int): String {
        // JS getTimezoneOffset() is inverted: minutes *behind* UTC are positive.
        val total = -minutes
        val sign = if (total < 0) "-" else "+"
        val abs = kotlin.math.abs(total)
        return String.format(Locale.ROOT, "UTC%s%02d:%02d", sign, abs / 60, abs % 60)
    }

    /** ~11 m at the equator: closer than any profile edit, looser than float round-tripping. */
    private fun close(a: Double, b: Double): Boolean = kotlin.math.abs(a - b) < 0.0001
}
