package com.geoalign.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.geoalign.core.readiness.ReadinessLevel
import com.geoalign.core.readiness.VpnTransport
import com.geoalign.data.net.EffectiveIpUtil
import com.geoalign.data.readiness.ReadinessService
import com.geoalign.di.AppGraph
import kotlinx.coroutines.launch

/**
 * Readiness dashboard (spec §25). Composes the live [ReadinessService] evaluation into a simple
 * status screen: VPN transport, effective IP (redacted), approximate exit, and any warnings.
 * Intentionally a thin, ViewModel-free MVP screen driven by a coroutine scope.
 */
@Composable
fun ReadinessDashboard(onOpenDiagnostics: () -> Unit) {
    val context = LocalContext.current
    val service = remember { AppGraph.readinessService(context) }
    val store = remember { AppGraph.profileStore(context) }
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var eval by remember { mutableStateOf<ReadinessService.Evaluation?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        loading = true
        error = null
        scope.launch {
            runCatching {
                val profileSelected = store.list().isNotEmpty()
                service.evaluate(profileSelected = profileSelected)
            }.onSuccess { eval = it; loading = false }
                .onFailure { error = it.message ?: "readiness check failed"; loading = false }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("GeoAlign — Readiness", style = MaterialTheme.typography.titleLarge)

        when {
            loading -> CircularProgressIndicator()
            error != null -> Text("Error: $error", color = MaterialTheme.colorScheme.error)
            eval != null -> ReadinessContent(eval!!)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { refresh() }, enabled = !loading) { Text("Check again") }
            OutlinedButton(onClick = onOpenDiagnostics) { Text("Open diagnostics") }
        }

        Text(
            "This app does not operate the VPN or change Android's system GPS. Location changes " +
                "apply only inside the embedded browser. It does not promise anonymity.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ReadinessContent(eval: ReadinessService.Evaluation) {
    val state = eval.state
    val inputs = eval.inputs

    val (headline, tone) = when (state.level) {
        ReadinessLevel.READY -> "Ready" to MaterialTheme.colorScheme.primary
        ReadinessLevel.READY_WITH_WARNINGS -> "Ready — with warnings" to MaterialTheme.colorScheme.tertiary
        ReadinessLevel.BLOCKED_NO_VPN -> "Not ready — no VPN detected" to MaterialTheme.colorScheme.error
        ReadinessLevel.CHECKING -> "Checking…" to MaterialTheme.colorScheme.onSurface
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(headline, style = MaterialTheme.typography.titleMedium, color = tone)
            Divider()

            StatusRow("VPN transport", vpnLabel(inputs.vpn))
            StatusRow("Internet", stepLabel(inputs.internetReachable.name))
            StatusRow(
                "Effective IP",
                eval.effectiveIp?.let { "${EffectiveIpUtil.redact(it.ip)} (${it.version})" } ?: "unknown",
            )

            val geo = eval.geolocation
            if (geo != null) {
                StatusRow("Approx. exit", listOfNotNull(geo.city, geo.countryCode).joinToString(", ").ifBlank { "—" })
                if (geo.latitude != null && geo.longitude != null) {
                    StatusRow("Suggested coords", "%.4f, %.4f".format(geo.latitude, geo.longitude))
                }
                StatusRow("Timezone", geo.timezone ?: "—")
                geo.org?.let { StatusRow("Network", it) }
            }

            StatusRow("Browser can open", if (state.canOpenBrowser) "yes" else "no")
        }
    }

    if (state.warnings.isNotEmpty()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Warnings", style = MaterialTheme.typography.titleSmall)
                state.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
private fun StatusRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

private fun vpnLabel(t: VpnTransport): String = when (t) {
    VpnTransport.DETECTED -> "detected"
    VpnTransport.NOT_DETECTED -> "not detected"
    VpnTransport.NETWORK_UNAVAILABLE -> "no network"
    VpnTransport.CHECKING -> "checking"
    VpnTransport.ERROR -> "error"
}

private fun stepLabel(name: String): String = name.lowercase()
