package com.geoalign.core.diagnostics

/**
 * The outcome of one diagnostic check.
 *
 * Four states rather than a boolean, because "not true" has three different meanings here and
 * collapsing them is how the old POC screen managed to look reassuring while proving nothing:
 * something the installed WebView cannot do is not the same as something that is missing, and
 * neither is the same as something this browser has never been able to offer on any device.
 */
enum class CheckStatus(
    /** The token used in the copyable report. Padded to a fixed width so the paste stays a column. */
    val marker: String,
) {
    /** Verified true on this device, now. */
    PASS("PASS"),

    /** True enough to browse with, with a named gap the reader is told about in the same line. */
    WARNING("WARN"),

    /** Not offered on this device, or not offered by this browser at all. Never a fault to fix. */
    UNSUPPORTED("N/A"),

    /** Should have been true and is not. The only status that means something is wrong. */
    FAILED("FAIL"),
    ;

    /** Fixed-width form, so the copied report's status column lines up in a plain-text issue. */
    val paddedMarker: String get() = marker.padEnd(4)
}

/**
 * One line of the diagnostics report.
 *
 * [detail] is written to stand on its own after the label, and — like `ProtectionClaim.detail` —
 * says what *is* the case, never what was attempted. Nothing here may carry an unredacted address:
 * the whole report is built to be copied into a bug report to a stranger.
 */
data class DiagnosticCheck(
    val label: String,
    val status: CheckStatus,
    val detail: String,
) {
    val line: String get() = "  [${status.paddedMarker}] $label — $detail"
}

data class DiagnosticsSection(val title: String, val checks: List<DiagnosticCheck>)

/**
 * The whole compatibility report, as data.
 *
 * This is the thing a user pastes into an issue about a site that refused to work, so it is
 * generated where a test can read it rather than assembled in a composable. [text] is the exact
 * string the copy action puts on the clipboard — `DiagnosticsReportBuilderTest` asserts against
 * this property, not against a UI, which is what makes the "no raw IP" invariant an invariant.
 */
data class DiagnosticsReport(
    val title: String,
    /** Context lines above the sections: which profile and device the rest of this describes. */
    val summary: List<String>,
    val sections: List<DiagnosticsSection>,
    val disclaimer: String,
) {

    val checks: List<DiagnosticCheck> get() = sections.flatMap { it.checks }

    /** True when at least one check failed — the screen's headline is derived from this, not from copy. */
    val hasFailures: Boolean get() = checks.any { it.status == CheckStatus.FAILED }

    val hasWarnings: Boolean get() = checks.any { it.status == CheckStatus.WARNING }

    val text: String
        get() = buildString {
            append(title)
            append("\n\n")
            summary.forEach { append(it).append('\n') }
            sections.forEach { section ->
                append('\n')
                append(section.title)
                append('\n')
                section.checks.forEach { append(it.line).append('\n') }
            }
            append('\n')
            append(disclaimer)
        }
}
