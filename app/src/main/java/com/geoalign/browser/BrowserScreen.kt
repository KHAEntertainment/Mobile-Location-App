package com.geoalign.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.geoalign.core.browser.BackAction
import com.geoalign.core.browser.BrowserGateDecision
import com.geoalign.core.browser.PageError
import com.geoalign.core.browser.RendererGone
import com.geoalign.core.browser.SitePrivacySheet
import com.geoalign.core.browser.WebViewUpdatePlan
import com.geoalign.core.device.DeviceProfile
import com.geoalign.core.device.DeviceProfiles
import com.geoalign.core.model.LocationProfile
import com.geoalign.di.AppGraph
import com.geoalign.ui.components.AlignmentIndicator
import com.geoalign.ui.components.AlignmentRecoveryPanel
import com.geoalign.ui.components.AlignmentWarningBanner
import com.geoalign.web.config.AndroidWebViewCapabilityProbe
import com.geoalign.web.config.AndroidWebViewUpdateLauncher
import com.geoalign.web.config.WebViewConfigurator
import com.geoalign.web.config.gateDecision
import com.geoalign.web.config.supportedBrowserCapabilities
import com.geoalign.web.download.AndroidDownloadEnqueuer
import com.geoalign.web.policy.BrowserWebChromeClient
import com.geoalign.web.policy.BrowserWebViewClient
import com.geoalign.web.session.AndroidWebViewHost

private const val HOME_URL = "https://duckduckgo.com/"

/**
 * Milestone 3 slice 4 — the browser with its finishing touches (spec §10, §11, §14, §16, §19, §21,
 * §25). One hardened WebView with device emulation and per-tab state, plus: invalid-TLS refusal with
 * a visible warning, safe external-scheme hand-off, system-managed downloads, a clear-session action,
 * and a site-info sheet describing what the browser is and isn't protecting.
 *
 * This composable renders; it does not decide. Tabs, the attached tab and its parked pages, the
 * address, progress, navigation enablement, load errors, renderer recovery and the device choice all
 * live in [BrowserViewModel] / `BrowserSessionController`, where JVM tests can reach them. What is
 * left here is the WebView construction the `AndroidView` factory has to own, plus the menus and
 * dialogs, which are genuinely view state.
 */
@Composable
fun BrowserScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val vm: BrowserViewModel = viewModel(
        factory = BrowserViewModel.Factory(
            store = AppGraph.profileStore(context),
            downloadEnqueuer = AndroidDownloadEnqueuer(context),
            homeUrl = HOME_URL,
            // The application-scoped singleton, already started. Taking a per-screen instance would
            // re-register the network callback on every navigation and forget the verified exit
            // address that is how an exit change becomes visible at all.
            monitor = AppGraph.alignmentMonitor(context),
            readiness = AppGraph.readinessService(context),
        ),
    )
    // The real WebView UA, captured once. "This device" mode serves a cleaned (de-WebView-ified)
    // version so pages see a genuine Chrome rather than an embedded WebView.
    val deviceUa = remember { android.webkit.WebSettings.getDefaultUserAgent(context) }
    // What the installed WebView supports, asked once. Every capability-dependent decision below
    // reads these answers rather than re-querying the platform.
    val capabilities = remember { AndroidWebViewCapabilityProbe(context).probe() }
    val configurator = remember(capabilities, deviceUa) { WebViewConfigurator(capabilities, deviceUa) }
    // Required vs optional capabilities, decided by a pure table in core/. A missing *required*
    // capability stops here: `WebViewConfigurator` would still hand back a working WebView, just one
    // with no virtual environment installed, and browsing in that state is not what this app offers.
    val gate = remember(capabilities) { capabilities.gateDecision() }

    if (!gate.allowsAlignedBrowsing) {
        CapabilityBlockCard(gate = gate, onExit = onExit)
        return
    }

    val profileState by vm.profileState.collectAsState()
    val session by vm.session.collectAsState()
    // Live alignment, already reduced to a description by `BrowserAlignmentPresenter`. This screen
    // reads tone, label and buttons off it; it does not compute any of them.
    val alignment by vm.alignment.collectAsState()

    if (!profileState.loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val activeProfile = profileState.profile
    if (activeProfile == null) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("No location profile selected.", style = MaterialTheme.typography.titleMedium)
            Text("Create one from the dashboard (Match browser to VPN) before browsing.")
            Button(onClick = onExit) { Text("Back") }
        }
        return
    }
    val device = profileState.device ?: DeviceProfiles.forProfile(activeProfile)

    var deviceMenuOpen by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    var showSiteInfo by remember { mutableStateOf(false) }

    // Leaving the browser destroys the WebView and removes the document-start scripts it registered.
    // Before this existed there was no `WebView.destroy()` anywhere in the tree.
    DisposableEffect(Unit) {
        onDispose { vm.disposeWebView() }
    }

    // Back is always handled here now. It used to be `enabled = canBack`, so Back at the first page
    // of the first tab fell through to the Activity and dropped the user out of the app mid-session.
    // The ladder — dismiss an overlay, then go back, then close a tab, then leave — is decided by
    // `BackPolicy` and unit-tested; only the last rung is ours, because only this screen knows what
    // leaving means.
    // While the recovery prompt is up, Back maps to the choice the user could already make there —
    // "Leave the browser". It is not trapped (that would be hostile) and it does not fall through
    // to the tab ladder, which would slide the prompt out of the way without answering it.
    BackHandler {
        if (alignment.prompt != null) onExit()
        else if (vm.onBack() == BackAction.LEAVE_BROWSER) onExit()
    }

    // The four choices, in a dialog *over* the live page rather than in place of it. The WebView
    // stays in the composition, attached and rendering, so a half-filled form behind this is still
    // there when the user picks one — the pause holds new navigation, it does not tear the session
    // down. `onDismissRequest` is a no-op: leaving is one of the four buttons, and a tap-outside
    // that silently resumed browsing would defeat the whole mechanism.
    alignment.prompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = {
                AlignmentRecoveryPanel(
                    prompt = prompt,
                    errorMessage = alignment.rematchError,
                    onAction = { action -> if (vm.onRecoveryAction(action)) onExit() },
                )
            },
        )
    }

    if (showSiteInfo) {
        val host = session.address.substringAfter("://", session.address).substringBefore('/')
        // Every line is derived from the capability facts and the live session. The sheet used to
        // state "Location: virtual" on every device, including one whose WebView cannot inject a
        // document-start script and therefore never received a virtual environment — no protection
        // is reported active merely because it was requested.
        val report = remember(host, device.displayName, capabilities) {
            SitePrivacySheet.forSession(
                host = host,
                deviceLabel = device.displayName,
                supported = capabilities.supportedBrowserCapabilities(),
            )
        }
        AlertDialog(
            onDismissRequest = { showSiteInfo = false },
            confirmButton = { TextButton(onClick = { showSiteInfo = false }) { Text("Close") } },
            title = { Text("Site & privacy") },
            text = { Text(report.text, style = MaterialTheme.typography.bodyMedium) },
        )
    }

    Column(Modifier.fillMaxSize()) {
        // Tab strip.
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            session.tabs.tabs.forEach { tab ->
                FilterChip(
                    selected = tab.id == session.tabs.activeId,
                    onClick = { vm.switchTo(tab.id) },
                    label = { Text(tabLabel(tab.title, tab.url), maxLines = 1) },
                    trailingIcon = {
                        TextButton(
                            onClick = { vm.closeTab(tab.id) },
                            contentPadding = PaddingValues(0.dp),
                        ) { Text("×") }
                    },
                )
            }
            TextButton(onClick = { vm.openNewTab() }) { Text("+") }
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { vm.goBack() }, enabled = session.canGoBack) { Text("‹") }
            TextButton(onClick = { vm.goForward() }, enabled = session.canGoForward) { Text("›") }
            TextButton(onClick = { vm.reloadOrStop() }) {
                Text(if (session.loading) "✕" else "⟳")
            }
            TextButton(onClick = { vm.goHome() }) { Text("⌂") }

            Box {
                TextButton(onClick = { deviceMenuOpen = true }) { Text("Device") }
                DropdownMenu(expanded = deviceMenuOpen, onDismissRequest = { deviceMenuOpen = false }) {
                    DeviceProfiles.ALL.forEach { d ->
                        DropdownMenuItem(
                            text = { Text((if (d.id == device.id) "✓ " else "") + d.displayName) },
                            onClick = { deviceMenuOpen = false; vm.selectDevice(d) },
                        )
                    }
                }
            }

            Box {
                TextButton(onClick = { overflowOpen = true }) { Text("⋮") }
                DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Site & privacy info") },
                        onClick = { overflowOpen = false; showSiteInfo = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Clear session") },
                        onClick = { overflowOpen = false; vm.clearSession() },
                    )
                }
            }

            // Persistent while browsing: it is in the chrome, not in a card that appears only when
            // something is wrong, so "aligned" is as visible as "not aligned".
            AlignmentIndicator(alignment, modifier = Modifier.padding(horizontal = 4.dp))

            TextButton(onClick = onExit) { Text("Done") }
        }

        OutlinedTextField(
            value = session.address,
            onValueChange = { vm.editAddress(it) },
            label = { Text("Address") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { vm.load(session.address) }),
        )

        // Standing, non-dismissible, and present for as long as the accepted risk is.
        AlignmentWarningBanner(alignment)

        // A navigation that was queued rather than performed. Says so explicitly, including that
        // the current page was left alone, so a paused tap does not read as the app ignoring it.
        alignment.heldNavigationNotice?.let { notice ->
            Text(
                text = notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .testTag("browser_alignment_held_notice"),
            )
        }

        session.sslWarning?.let { msg ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.fillMaxWidth(0.8f),
                    )
                    TextButton(onClick = { vm.dismissSslWarning() }) { Text("Dismiss") }
                }
            }
        }

        if (session.loading && session.progress in 1..99) {
            LinearProgressIndicator(progress = { session.progress / 100f }, modifier = Modifier.fillMaxWidth())
        }

        Box(Modifier.fillMaxSize()) {
            val rendererGone = session.rendererGone
            if (rendererGone == null) {
                // A dead renderer can only be recovered by building a new WebView, so the generation
                // counter is the composition key: bumping it releases the old view (destroying it and
                // removing its scripts) and constructs a replacement.
                key(session.webViewGeneration) {
                    BrowserWebView(
                        vm = vm,
                        configurator = configurator,
                        activeProfile = activeProfile,
                        device = device,
                        canReadErrorDescription = capabilities.errorDescription,
                    )
                }
            } else {
                RendererRecoveryCard(rendererGone, onReload = { vm.recoverFromRendererCrash() })
            }

            // Main-frame failures only — a subframe failure never gets here, so a broken tracking
            // pixel or a 404 iframe cannot blank out a page that is otherwise fine.
            session.pageError?.let { error ->
                PageErrorCard(
                    error = error,
                    onRetry = { vm.retry() },
                    onOpenExternally = { openExternally(context, error.url) },
                    onDismiss = { vm.dismissPageError() },
                )
            }
        }
    }
}

/**
 * The WebView itself. Constructed once per WebView generation and handed to the ViewModel as an
 * `AndroidWebViewHost`; released — and destroyed — when it leaves the composition.
 */
@Composable
private fun BrowserWebView(
    vm: BrowserViewModel,
    configurator: WebViewConfigurator,
    activeProfile: LocationProfile,
    device: DeviceProfile,
    canReadErrorDescription: Boolean,
) {
    // Survives recomposition inside this generation so `onRelease` can tear down the same host the
    // factory built.
    val hostHolder = remember { arrayOfNulls<AndroidWebViewHost>(1) }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    // AndroidView leaves a child's own LayoutParams at WRAP_CONTENT and relies on
                    // Compose to measure it. WebView, however, derives its CSS viewport height from
                    // its LayoutParams, so a wrap-content height makes it report a viewport height
                    // of 0 — `100vh` resolves to 0 and `(orientation: landscape)` matches on every
                    // page, because a 368x0 viewport has an infinite aspect ratio. MATCH_PARENT
                    // gives it a bounded height to measure against.
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    // Settings matrix, user-agent + client hints, safe browsing and both
                    // document-start bundles. Runs before the first load below, which is the
                    // only ordering in which the bundles beat the page's own scripts.
                    val installed = configurator.configure(this, activeProfile, device)

                    webViewClient = BrowserWebViewClient(
                        onNav = { url, back, forward, loading ->
                            vm.onNavigationStateChanged(url, back, forward, loading)
                        },
                        onExternal = { url -> openExternally(ctx, url) },
                        onSslError = { host -> vm.onSslError(host) },
                        onLoadError = { mainFrame, url, description ->
                            vm.onLoadError(mainFrame, url, description)
                        },
                        onHttpError = { mainFrame, url, status, reason ->
                            vm.onHttpError(mainFrame, url, status, reason)
                        },
                        // Returns true from the callback itself; returning false would kill the app
                        // process instead of recovering.
                        onRendererGone = { didCrash -> vm.onRenderProcessGone(didCrash) },
                        // In-page links go through the alignment hold too. Without this the pause
                        // would cover only the address bar, and a tap on the page would navigate
                        // straight past it.
                        onHoldNavigation = { url -> vm.holdLinkNavigation(url) },
                        // From the single probe, not a WebViewFeature query of its own.
                        canReadErrorDescription = canReadErrorDescription,
                    )
                    webChromeClient = BrowserWebChromeClient(
                        onProgress = { vm.onProgress(it) },
                        onTitle = { title -> vm.onTitle(title) },
                    )

                    // Hand downloads to Android's DownloadManager instead of trying to render them.
                    setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                        vm.onDownloadRequested(url, userAgent, contentDisposition, mimeType)
                    }

                    // The controller decides what this WebView loads: a returning tab gets its
                    // parked page restored, a fresh one gets its url.
                    val host = AndroidWebViewHost(this, configurator, installed.environment, installed.device)
                    hostHolder[0] = host
                    vm.attachWebView(host)
                }
            },
            onRelease = { hostHolder[0]?.let { vm.releaseWebView(it) } },
        )
    }
}

/**
 * The browser refusing to open, because a required WebView capability is missing.
 *
 * There is deliberately no "continue anyway": the only required capability is document-start
 * injection, and without it the browser would present as aligned while pages read the device's real
 * environment. The two things offered instead are the reason and a route to a newer WebView.
 *
 * Optional gaps are shown here too, clearly separated — they are why the browser is *not* blocked,
 * and a user looking at this screen on a second device deserves to see the difference.
 */
@Composable
private fun CapabilityBlockCard(gate: BrowserGateDecision, onExit: () -> Unit) {
    val context = LocalContext.current
    // Set only when neither the Play app nor the web listing could be opened — a sideloaded install
    // on a device with no Play Store at all, which is an expected configuration here, not an error.
    var updateFallback by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(gate.headline, style = MaterialTheme.typography.titleMedium)
        Text(gate.reason, style = MaterialTheme.typography.bodyMedium)
        Text(gate.installedWebViewLabel, style = MaterialTheme.typography.bodySmall)

        gate.optionalNotice?.let { notice ->
            Text("Also unavailable on this WebView:", style = MaterialTheme.typography.titleSmall)
            Text(notice, style = MaterialTheme.typography.bodySmall)
        }

        updateFallback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val outcome = AndroidWebViewUpdateLauncher(context).launch(gate.webViewPackageName)
                    updateFallback = WebViewUpdatePlan.outcomeMessage(
                        outcome,
                        WebViewUpdatePlan.packageToUpdate(gate.webViewPackageName),
                    )
                },
            ) { Text(WebViewUpdatePlan.OFFER_LABEL) }
            OutlinedButton(onClick = onExit) { Text("Back") }
        }
    }
}

/** Main-frame load failure: what happened, and the two ways out of it. */
@Composable
private fun PageErrorCard(
    error: PageError,
    onRetry: () -> Unit,
    onOpenExternally: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(error.headline, style = MaterialTheme.typography.titleMedium)
        Text(error.detail, style = MaterialTheme.typography.bodyMedium)
        Text(error.url, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRetry) { Text("Retry") }
            if (error.canOpenExternally) {
                OutlinedButton(onClick = onOpenExternally) { Text("Open externally") }
            }
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

/** The renderer died. Recovery, rather than the blank view the user would otherwise be left with. */
@Composable
private fun RendererRecoveryCard(state: RendererGone, onReload: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(state.headline, style = MaterialTheme.typography.titleMedium)
        Text(state.detail, style = MaterialTheme.typography.bodyMedium)
        Text(state.url, style = MaterialTheme.typography.bodySmall)
        Button(onClick = onReload) { Text("Reload page") }
    }
}

/** Launch a safe external scheme (tel:, mailto:, geo:, …) through the system, ignoring failures. */
private fun openExternally(context: Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

/** Short, human-friendly tab label: the page title if present, else the url host. */
private fun tabLabel(title: String, url: String): String {
    if (title.isNotBlank()) return title.take(24)
    val host = url.substringAfter("://", url).substringBefore('/').removePrefix("www.")
    return host.ifBlank { url }.take(24)
}
