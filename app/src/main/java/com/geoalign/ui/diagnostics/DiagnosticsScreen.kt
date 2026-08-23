package com.geoalign.ui.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.geoalign.core.device.DeviceProfile
import com.geoalign.core.device.DeviceProfiles
import com.geoalign.core.diagnostics.CheckStatus
import com.geoalign.core.diagnostics.DiagnosticCheck
import com.geoalign.core.diagnostics.DiagnosticsInput
import com.geoalign.core.diagnostics.DiagnosticsReport
import com.geoalign.core.diagnostics.DiagnosticsReportBuilder
import com.geoalign.core.diagnostics.DiagnosticsSection
import com.geoalign.core.diagnostics.ObservationOutcome
import com.geoalign.core.model.LocationProfile
import com.geoalign.di.AppGraph
import com.geoalign.ui.components.AppScaffold
import com.geoalign.ui.components.SecondaryAction
import com.geoalign.ui.components.SecondaryActionRow
import com.geoalign.ui.components.statusContainerColor
import com.geoalign.ui.components.statusContentColor
import com.geoalign.ui.state.StatusTone
import com.geoalign.ui.theme.GeoShapeTokens
import com.geoalign.ui.theme.Spacing
import com.geoalign.web.config.AndroidWebViewCapabilityProbe
import com.geoalign.web.config.BrowserSettingsSpec
import com.geoalign.web.config.WebViewConfigurator
import com.geoalign.web.config.gateDecision
import com.geoalign.web.diagnostics.DiagnosticsCollectorScript
import com.geoalign.web.diagnostics.WebViewEnvironmentReader

/**
 * The diagnostics report (issue #8, spec §10–§14, §21).
 *
 * What it replaced was a second WebView with its own settings, its own bundle and a hardcoded London
 * profile, which meant every reassuring line on it was evidence about that harness and about nothing
 * the user was actually browsing with. Here the WebView is configured by the production
 * [WebViewConfigurator] with the **active** profile and the **active** device mode, using the same
 * document-start install path `BrowserScreen` uses, and the report is a readout of what a page
 * running inside that configuration reported back.
 *
 * The screen decides nothing: [DiagnosticsReportBuilder] is pure and unit-tested, and this renders
 * what it returns. The WebView below is one device pixel tall — it exists to be measured, not seen.
 */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val monitor = remember { AppGraph.alignmentMonitor(context) }
    val snapshot by monitor.snapshots.collectAsState()

    // The same three lines the browser starts with: one probe, one configurator, one gate decision.
    val deviceUa = remember { WebSettings.getDefaultUserAgent(context) }
    val capabilities = remember { AndroidWebViewCapabilityProbe(context).probe() }
    val configurator = remember(capabilities, deviceUa) { WebViewConfigurator(capabilities, deviceUa) }
    val gate = remember(capabilities) { capabilities.gateDecision() }

    val profile = snapshot.profile
    val device = remember(profile?.id, profile?.userAgentProfileId) {
        profile?.let { DeviceProfiles.forProfile(it) } ?: DeviceProfiles.NATIVE
    }

    var runId by remember { mutableIntStateOf(0) }
    var observation by remember { mutableStateOf<ObservationOutcome>(ObservationOutcome.Pending) }

    val report = DiagnosticsReportBuilder.build(
        DiagnosticsInput(
            gate = gate,
            profile = profile,
            device = device,
            // Computed by the production rule, from the production class, so the comparison is
            // against what the configurator actually set rather than a second opinion.
            expectedUserAgent = BrowserSettingsSpec.userAgentFor(device, deviceUa),
            observation = observation,
            vpn = snapshot.monitor.transport,
            effectiveIp = snapshot.evaluation?.effectiveIp?.ip,
            exit = snapshot.evaluation?.geolocation,
        ),
    )

    AppScaffold(
        title = "Diagnostics",
        onBack = onBack,
        contentSpacing = Spacing.md,
    ) {
        Text(
            headline(report, observation),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("diagnostics_headline"),
        )
        report.summary.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SecondaryActionRow {
            SecondaryAction(
                text = "Copy report",
                modifier = Modifier.weight(1f),
                testTag = "diagnostics_copy",
                onClick = { copyToClipboard(context, report.text) },
            )
            SecondaryAction(
                text = "Run again",
                modifier = Modifier.weight(1f),
                testTag = "diagnostics_rerun",
                onClick = {
                    observation = ObservationOutcome.Pending
                    runId += 1
                },
            )
        }

        report.sections.forEach { SectionBlock(it) }

        // The measured WebView. Only built when the capability that installs the bundles exists —
        // without it there is nothing to observe, and the report says exactly that instead of
        // rendering a browser that would prove nothing.
        if (capabilities.documentStartScript) {
            key(runId) {
                MeasuredWebView(
                    configurator = configurator,
                    profile = profile,
                    device = device,
                    onOutcome = { observation = it },
                )
            }
        } else {
            DisposableEffect(Unit) {
                observation = ObservationOutcome.NotInstalled
                onDispose { }
            }
        }
    }
}

private fun headline(report: DiagnosticsReport, observation: ObservationOutcome): String = when {
    observation == ObservationOutcome.Pending -> "Measuring this browser…"
    report.hasFailures -> "This browser is not presenting everything it should"
    report.hasWarnings -> "Working, with gaps worth knowing about"
    else -> "Everything this browser claims is in force"
}

/**
 * The WebView the report is measured from.
 *
 * It is the production configuration — [WebViewConfigurator.configure] applies the hardened settings
 * matrix, the device's user-agent and client hints, and registers both document-start bundles for
 * the active profile and device — and it is destroyed with the composition, so the diagnostics run
 * leaves nothing behind. There is deliberately no second settings block here; a WebView configured
 * anywhere but in the configurator would make this screen decorative again.
 */
@Composable
private fun MeasuredWebView(
    configurator: WebViewConfigurator,
    profile: LocationProfile?,
    device: DeviceProfile,
    onOutcome: (ObservationOutcome) -> Unit,
) {
    val context = LocalContext.current
    val collector = remember { runCatching { DiagnosticsCollectorScript.fromAssets(context) }.getOrNull() }
    if (profile == null) {
        DisposableEffect(Unit) {
            onOutcome(ObservationOutcome.Failed("no location profile is selected, so none was installed"))
            onDispose { }
        }
        return
    }
    if (collector == null) {
        DisposableEffect(Unit) {
            onOutcome(ObservationOutcome.Failed("the diagnostics collector could not be loaded"))
            onDispose { }
        }
        return
    }
    val holder = remember { arrayOfNulls<WebView>(1) }
    val run = remember { arrayOfNulls<WebViewEnvironmentReader.Run>(1) }
    AndroidView(
        // One pixel: attached to the window so page timers run normally, but nothing to look at.
        // The report is the output; this view's pixels are never read, which is also why the
        // deleted POC's OFF_SCREEN_PRERASTER would buy it nothing.
        modifier = Modifier.size(1.dp),
        factory = { ctx ->
            WebView(ctx).apply {
                holder[0] = this
                // The production configuration, and the only one on this screen.
                configurator.configure(this, profile, device)
                run[0] = WebViewEnvironmentReader(collector).read(this, onOutcome)
            }
        },
        onRelease = {
            // Cancel before destroying: a poll landing on a destroyed WebView would crash the one
            // screen a user opens because something is already wrong.
            run[0]?.cancel()
            run[0] = null
            holder[0]?.let { view ->
                view.stopLoading()
                view.destroy()
            }
            holder[0] = null
        },
    )
}

@Composable
private fun SectionBlock(section: DiagnosticsSection) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            section.title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = Spacing.sm),
        )
        section.checks.forEach { CheckRow(it) }
    }
}

@Composable
private fun CheckRow(check: DiagnosticCheck) {
    val tone = toneOf(check.status)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        // The status word itself, not a colour alone: the four states have to be distinguishable
        // without colour vision and in a screenshot pasted into an issue.
        Text(
            text = check.status.marker,
            style = MaterialTheme.typography.labelSmall,
            color = statusContentColor(tone),
            modifier = Modifier
                .background(statusContainerColor(tone), GeoShapeTokens.button)
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        )
        Column(Modifier.weight(1f)) {
            Text(check.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                check.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun toneOf(status: CheckStatus): StatusTone = when (status) {
    CheckStatus.PASS -> StatusTone.VERIFIED
    CheckStatus.WARNING -> StatusTone.ATTENTION
    CheckStatus.FAILED -> StatusTone.BLOCKED
    CheckStatus.UNSUPPORTED -> StatusTone.NEUTRAL
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("GeoAlign compatibility report", text))
}
