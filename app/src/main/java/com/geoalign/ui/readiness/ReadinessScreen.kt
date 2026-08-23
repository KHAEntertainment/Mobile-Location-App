package com.geoalign.ui.readiness

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.geoalign.core.monitor.MonitorReason
import com.geoalign.data.profiles.ProfileFactory
import com.geoalign.di.AppGraph
import com.geoalign.ui.components.AppScaffold
import com.geoalign.ui.components.DisclosureRow
import com.geoalign.ui.components.GeoInfoDialog
import com.geoalign.ui.components.InfoNote
import com.geoalign.ui.components.PrimaryAction
import com.geoalign.ui.components.SecondaryAction
import com.geoalign.ui.components.SecondaryActionRow
import com.geoalign.ui.components.TextAction
import com.geoalign.ui.state.ActionId
import com.geoalign.ui.state.LiveReadiness
import com.geoalign.ui.state.NoVpnAcceptance
import com.geoalign.ui.state.ReadinessPresenter
import com.geoalign.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

private const val CLOCK_TICK_MILLIS = 30_000L

/**
 * Readiness screen (spec §25). Answers one question — is the browser aligned and safe to open? —
 * and keeps everything technical behind progressive disclosure.
 *
 * All the decisions live in [ReadinessPresenter] and [LiveReadiness], which are pure and
 * unit-tested, and in the application-scoped `AlignmentMonitor`, whose state machine is pure Kotlin
 * in `core/`. This composable gathers observations, renders what the presenter returns, and decides
 * nothing itself. That split is deliberate: the stale-profile bug in 3d3108b was business logic
 * inside a @Composable, where no test could reach it.
 */
@Composable
fun ReadinessScreen(
    onOpenDiagnostics: () -> Unit,
    onEditProfile: () -> Unit,
    onOpenBrowser: () -> Unit,
) {
    val context = LocalContext.current
    val service = remember { AppGraph.readinessService(context) }
    val store = remember { AppGraph.profileStore(context) }
    val monitor = remember { AppGraph.alignmentMonitor(context) }
    val scope = rememberCoroutineScope()

    // The single source of live truth. Every observation the screen renders — transport, exit,
    // profile, freshness — arrives through here, so nothing on screen can disagree with what the
    // monitor last saw, and a VPN that drops while the screen is open is visible without a tap.
    val snapshot by monitor.snapshots.collectAsState()

    var acceptedNoVpn by remember { mutableStateOf(false) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var matching by remember { mutableStateOf(false) }
    var matchError by remember { mutableStateOf<String?>(null) }

    var showDetails by remember { mutableStateOf(false) }
    var showDisclaimer by remember { mutableStateOf(false) }
    var showNoVpnPrompt by remember { mutableStateOf(false) }

    fun refresh() {
        matchError = null
        nowMillis = System.currentTimeMillis()
        monitor.refreshNow(MonitorReason.MANUAL_REFRESH)
    }

    /**
     * One-tap "Match Browser to VPN" (spec §9).
     *
     * The estimate is re-fetched here rather than read from the monitor's last snapshot. Reading
     * the cache saved whatever was observed at the *last* check, so changing VPN exit and pressing
     * this stored the previous country and then re-rendered with the new one. Evaluating first
     * makes what we save and what we display come from the same observation.
     */
    fun matchToVpn() {
        matching = true
        matchError = null
        scope.launch {
            runCatching {
                val fresh = service.evaluate(
                    profileSelected = true,
                    userAcceptedNoVpn = acceptedNoVpn,
                )
                val geo = fresh.geolocation
                    ?: throw IllegalStateException("no location estimate for the current connection")
                val built = ProfileFactory.fromGeolocation(
                    geo = geo,
                    contentLanguage = Locale.getDefault().language.ifBlank { "en" },
                    id = UUID.randomUUID().toString(),
                    nowMillis = System.currentTimeMillis(),
                ) ?: throw IllegalStateException("estimate lacked coordinates")
                store.clear()
                store.upsert(built)
            }.onSuccess {
                matching = false
                nowMillis = System.currentTimeMillis()
                // The monitor re-reads the store, so the profile that was saved and the state
                // machine's verdict on it come from one observation rather than two.
                monitor.refreshNow(MonitorReason.MANUAL_REFRESH)
            }.onFailure {
                matching = false
                matchError = it.message ?: "could not save profile"
            }
        }
    }

    // Returning from the system VPN settings re-evaluates on its own — the point of this screen no
    // longer needing a "Check again" tap. A LifecycleEventObserver is brought up to the owner's
    // current state when registered, so this also covers first composition and needs no separate
    // initial-load effect.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, monitor) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                monitor.refreshNow(MonitorReason.LIFECYCLE_RESUMED)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // A detected VPN retires any standing "continue without a VPN" opt-in, so a later drop blocks
    // again instead of silently inheriting an agreement to a different situation.
    LaunchedEffect(snapshot.monitor.transport) {
        acceptedNoVpn = NoVpnAcceptance.next(acceptedNoVpn, snapshot.monitor.transport)
        monitor.setUserAcceptedNoVpn(acceptedNoVpn)
        nowMillis = System.currentTimeMillis()
    }

    // Without this the freshness line is frozen at whatever it said on load, so "moments ago"
    // never becomes "8 min ago" and the staleness downgrade never fires while the screen is open.
    LaunchedEffect(Unit) {
        while (true) {
            delay(CLOCK_TICK_MILLIS)
            nowMillis = System.currentTimeMillis()
        }
    }

    val ui = ReadinessPresenter.present(
        LiveReadiness.input(
            snapshot = snapshot,
            nowMillis = nowMillis,
            userAcceptedNoVpn = acceptedNoVpn,
            pendingAction = matching,
            actionError = matchError,
        ),
    )

    AppScaffold(title = "GeoAlign") {
        AlignmentStatusHeader(
            status = ui.status,
            isRefreshing = !ui.refresh.enabled,
            onRefresh = { refresh() },
        )

        PrimaryAction(
            text = ui.primaryAction.label,
            enabled = ui.primaryAction.enabled,
            onClick = onOpenBrowser,
        )

        // Only ever two side by side. A third makes every label truncate to "Re-mat…" at default
        // font scale, let alone at 200%.
        val inRow = ui.secondaryActions.filter {
            it.id == ActionId.REMATCH || it.id == ActionId.EDIT_PROFILE
        }
        if (inRow.isNotEmpty()) {
            SecondaryActionRow {
                inRow.forEach { action ->
                    SecondaryAction(
                        text = action.label,
                        enabled = action.enabled,
                        testTag = "secondary_${action.id.name}",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            when (action.id) {
                                ActionId.REMATCH -> matchToVpn()
                                ActionId.EDIT_PROFILE -> onEditProfile()
                                else -> Unit
                            }
                        },
                    )
                }
            }
        }

        // Recovery from a blocked or errored state gets its own full-width target rather than
        // competing for space in the row above.
        ui.secondaryActions.firstOrNull { it.id == ActionId.REFRESH }?.let {
            SecondaryAction(
                text = it.label,
                enabled = it.enabled,
                testTag = "secondary_REFRESH",
                modifier = Modifier.fillMaxWidth(),
                onClick = { refresh() },
            )
        }

        ui.secondaryActions.firstOrNull { it.id == ActionId.CONTINUE_WITHOUT_VPN }?.let {
            TextAction(text = it.label, onClick = { showNoVpnPrompt = true })
        }

        Spacer(Modifier.height(Spacing.xs))

        ui.disclosures.forEach { item ->
            DisclosureRow(
                label = item.label,
                summary = item.summary,
                summaryMono = item.id == ActionId.OPEN_CONNECTION_DETAILS,
                testTag = "disclosure_${item.id.name}",
                onClick = {
                    when (item.id) {
                        ActionId.OPEN_CONNECTION_DETAILS -> showDetails = true
                        ActionId.OPEN_DIAGNOSTICS -> onOpenDiagnostics()
                        else -> Unit
                    }
                },
            )
        }

        InfoNote(text = ui.disclaimerShort, onClick = { showDisclaimer = true })
    }

    if (showDetails) {
        ConnectionDetailsDialog(rows = ui.connectionDetails, onDismiss = { showDetails = false })
    }
    if (showDisclaimer) {
        GeoInfoDialog(
            title = "What GeoAlign does",
            body = ui.disclaimerFull,
            onDismiss = { showDisclaimer = false },
        )
    }
    ui.noVpnPrompt?.let { prompt ->
        if (showNoVpnPrompt) {
            AlertDialog(
                onDismissRequest = { showNoVpnPrompt = false },
                title = { Text(prompt.title, style = MaterialTheme.typography.titleMedium) },
                text = { Text(prompt.body, style = MaterialTheme.typography.bodyMedium) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showNoVpnPrompt = false
                            acceptedNoVpn = true
                            monitor.setUserAcceptedNoVpn(true)
                            refresh()
                        },
                        modifier = Modifier.testTag("no_vpn_confirm"),
                    ) { Text(prompt.confirmLabel) }
                },
                dismissButton = {
                    TextButton(onClick = { showNoVpnPrompt = false }) { Text(prompt.dismissLabel) }
                },
            )
        }
    }
}
