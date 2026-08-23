package com.geoalign.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebStorage
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.geoalign.core.device.DeviceProfile
import com.geoalign.core.device.NativeIdentity
import com.geoalign.core.device.DeviceProfiles
import com.geoalign.core.model.LocationProfile
import com.geoalign.core.net.UrlNormalizer
import com.geoalign.core.tabs.TabListReducer
import com.geoalign.core.tabs.TabsState
import com.geoalign.di.AppGraph
import com.geoalign.web.environment.DeviceBundleCompiler
import com.geoalign.web.environment.NativeUaMetadata
import com.geoalign.web.environment.EnvBundleCompiler
import com.geoalign.web.policy.BrowserWebChromeClient
import com.geoalign.web.policy.BrowserWebViewClient
import kotlinx.coroutines.launch

private const val HOME_URL = "https://duckduckgo.com/"

/**
 * Milestone 3 slice 4 — the browser with its finishing touches (spec §10, §11, §14, §16, §19, §21,
 * §25). One hardened WebView with device emulation and per-tab state, plus: invalid-TLS refusal with
 * a visible warning, safe external-scheme hand-off, system-managed downloads, a clear-session action,
 * and a site-info sheet describing what the browser is and isn't protecting.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val store = remember { AppGraph.profileStore(context) }
    val scope = rememberCoroutineScope()
    // The real WebView UA, captured once. "This device" mode serves a cleaned (de-WebView-ified)
    // version so pages see a genuine Chrome rather than an embedded WebView.
    val deviceUa = remember { android.webkit.WebSettings.getDefaultUserAgent(context) }

    var profile by remember { mutableStateOf<LocationProfile?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        profile = store.list().firstOrNull()
        loaded = true
    }

    if (!loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val activeProfile = profile
    if (activeProfile == null) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("No location profile selected.", style = MaterialTheme.typography.titleMedium)
            Text("Create one from the dashboard (Match browser to VPN) before browsing.")
            Button(onClick = onExit) { Text("Back") }
        }
        return
    }

    var webView by remember { mutableStateOf<WebView?>(null) }
    var deviceScript by remember { mutableStateOf<ScriptHandler?>(null) }
    var device by remember { mutableStateOf(DeviceProfiles.forProfile(activeProfile)) }
    var deviceMenuOpen by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    var showSiteInfo by remember { mutableStateOf(false) }
    var sslWarning by remember { mutableStateOf<String?>(null) }

    var tabs by remember { mutableStateOf(TabsState.initial(HOME_URL)) }
    // The tab whose page is currently loaded into the single WebView. Kept in sync with tabs.activeId
    // through the swap helpers below.
    var attachedTabId by remember { mutableStateOf(tabs.activeId) }
    val savedStates = remember { mutableMapOf<Long, Bundle>() }

    var address by remember { mutableStateOf(HOME_URL) }
    var progress by remember { mutableStateOf(0) }
    var loadingPage by remember { mutableStateOf(false) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }

    // Park the WebView's current page under the attached tab so it can be restored later.
    fun persistAttached() {
        val wv = webView ?: return
        val b = Bundle()
        if (wv.saveState(b) != null) savedStates[attachedTabId] = b
    }

    // Load the given tab into the WebView: restore its parked page, or fetch its url fresh.
    fun bindTabToWebView(id: Long) {
        val wv = webView ?: return
        val tab = tabs.tabs.firstOrNull { it.id == id } ?: return
        val saved = savedStates[id]
        if (saved != null) wv.restoreState(saved) else wv.loadUrl(tab.url)
        attachedTabId = id
        address = tab.url
        // Reset the transient chrome; the callbacks will repopulate as the page settles.
        progress = 0
        loadingPage = false
        canBack = wv.canGoBack()
        canForward = wv.canGoForward()
    }

    fun switchTo(id: Long) {
        if (id == tabs.activeId) return
        persistAttached()
        tabs = TabListReducer.selectTab(tabs, id)
        bindTabToWebView(tabs.activeId)
    }

    fun openNewTab() {
        persistAttached()
        tabs = TabListReducer.openTab(tabs, HOME_URL)
        // A brand-new tab has no parked state, so this loads HOME_URL fresh.
        bindTabToWebView(tabs.activeId)
    }

    fun closeTab(id: Long) {
        val wasActive = id == tabs.activeId
        tabs = TabListReducer.closeTab(tabs, id, HOME_URL)
        savedStates.remove(id)
        if (wasActive) bindTabToWebView(tabs.activeId)
    }

    fun load(text: String) {
        val url = UrlNormalizer.normalize(text) ?: return
        address = url
        tabs = TabListReducer.updateTab(tabs, attachedTabId, url = url)
        webView?.loadUrl(url)
    }

    // The UA a device should present: a preset's spoofed UA, or the cleaned real UA for "This device".
    fun uaFor(d: DeviceProfile): String =
        if (d.native) NativeIdentity.reduceUserAgent(deviceUa) else d.userAgent

    // Switch the emulated device live: swap the UA string, re-inject the device bundle, reload, and
    // persist the choice back to the active profile so it sticks next session.
    fun changeDevice(newDevice: DeviceProfile) {
        deviceMenuOpen = false
        if (newDevice.id == device.id) return
        device = newDevice
        webView?.let { wv ->
            wv.settings.userAgentString = uaFor(newDevice)
            // Client hints must follow the UA string, or the two contradict each other.
            NativeUaMetadata.applyOrRestore(wv.settings, deviceUa, newDevice.native)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                deviceScript?.remove()
                deviceScript = WebViewCompat.addDocumentStartJavaScript(
                    wv, DeviceBundleCompiler.compileFromAssets(wv.context, newDevice), setOf("*"),
                )
            }
            wv.reload()
        }
        scope.launch {
            runCatching { store.upsert(activeProfile.copy(userAgentProfileId = newDevice.id)) }
        }
    }

    // Wipe browsing state: cookies, web storage, cache, form data, history — then reset to one fresh
    // home tab (spec §25 "clear session"). Does not touch the saved location/device profile.
    fun clearSession() {
        overflowOpen = false
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        webView?.clearCache(true)
        webView?.clearFormData()
        webView?.clearHistory()
        savedStates.clear()
        tabs = TabsState.initial(HOME_URL)
        attachedTabId = tabs.activeId
        address = HOME_URL
        webView?.loadUrl(HOME_URL)
    }

    BackHandler(enabled = canBack) { webView?.goBack() }

    if (showSiteInfo) {
        val host = address.substringAfter("://", address).substringBefore('/')
        AlertDialog(
            onDismissRequest = { showSiteInfo = false },
            confirmButton = { TextButton(onClick = { showSiteInfo = false }) { Text("Close") } },
            title = { Text("Site & privacy") },
            text = {
                Text(
                    "Site: ${host.ifBlank { "—" }}\n\n" +
                        "• Location: virtual — pages see your profile's coordinates, not the device GPS.\n" +
                        "• Camera & microphone: blocked for every site.\n" +
                        "• Device: presenting as ${device.displayName}.\n" +
                        "• Connections: HTTPS only; invalid certificates are refused.\n\n" +
                        "This app does not operate the VPN and does not promise anonymity.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
        // Tab strip.
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.tabs.forEach { tab ->
                FilterChip(
                    selected = tab.id == tabs.activeId,
                    onClick = { switchTo(tab.id) },
                    label = { Text(tabLabel(tab.title, tab.url), maxLines = 1) },
                    trailingIcon = {
                        TextButton(
                            onClick = { closeTab(tab.id) },
                            contentPadding = PaddingValues(0.dp),
                        ) { Text("×") }
                    },
                )
            }
            TextButton(onClick = { openNewTab() }) { Text("+") }
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { webView?.goBack() }, enabled = canBack) { Text("‹") }
            TextButton(onClick = { webView?.goForward() }, enabled = canForward) { Text("›") }
            TextButton(onClick = { if (loadingPage) webView?.stopLoading() else webView?.reload() }) {
                Text(if (loadingPage) "✕" else "⟳")
            }
            TextButton(onClick = { load(HOME_URL) }) { Text("⌂") }

            Box {
                TextButton(onClick = { deviceMenuOpen = true }) { Text("Device") }
                DropdownMenu(expanded = deviceMenuOpen, onDismissRequest = { deviceMenuOpen = false }) {
                    DeviceProfiles.ALL.forEach { d ->
                        DropdownMenuItem(
                            text = { Text((if (d.id == device.id) "✓ " else "") + d.displayName) },
                            onClick = { changeDevice(d) },
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
                        onClick = { clearSession() },
                    )
                }
            }

            TextButton(onClick = onExit) { Text("Done") }
        }

        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text("Address") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { load(address) }),
        )

        sslWarning?.let { msg ->
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
                    TextButton(onClick = { sslWarning = null }) { Text("Dismiss") }
                }
            }
        }

        if (loadingPage && progress in 1..99) {
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                // On debuggable (sideload) builds, allow chrome://inspect so WebView-hostile sites
                // can be diagnosed live. No-op on release.
                if (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                    WebView.setWebContentsDebuggingEnabled(true)
                }
                WebView(ctx).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        @Suppress("DEPRECATION")
                        allowFileAccessFromFileURLs = false
                        @Suppress("DEPRECATION")
                        allowUniversalAccessFromFileURLs = false
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        // Honor the page's <meta viewport> the way mobile Chrome does. Without this
                        // (WebView default useWideViewPort=false), responsive sites are laid out at
                        // the raw view width, so window.innerWidth/innerHeight and the
                        // (orientation: landscape) media query can read as a wide/landscape desktop —
                        // which is what makes Tinder show its "portrait view" gate. Enabling these
                        // makes width=device-width resolve to the real CSS width, so orientation
                        // reads portrait like it does in Chrome.
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        // Present the active device's UA from the very first request.
                        userAgentString = uaFor(device)
                    }
                    // Native mode's client hints (and Sec-CH-UA headers) come from here.
                    NativeUaMetadata.applyOrRestore(settings, deviceUa, device.native)
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
                        WebSettingsCompat.setSafeBrowsingEnabled(settings, true)
                    }

                    webViewClient = BrowserWebViewClient(
                        onNav = { url, back, forward, loading ->
                            canBack = back
                            canForward = forward
                            loadingPage = loading
                            if (url != null) {
                                address = url
                                tabs = TabListReducer.updateTab(tabs, attachedTabId, url = url)
                            }
                        },
                        onExternal = { url -> openExternally(ctx, url) },
                        onSslError = { host ->
                            sslWarning = "Refused an insecure connection to $host — the site's security " +
                                "certificate could not be trusted."
                        },
                    )
                    webChromeClient = BrowserWebChromeClient(
                        onProgress = { progress = it },
                        onTitle = { title -> tabs = TabListReducer.updateTab(tabs, attachedTabId, title = title) },
                    )

                    // Hand downloads to Android's DownloadManager instead of trying to render them.
                    setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                        enqueueDownload(ctx, url, userAgent, contentDisposition, mimeType)
                    }

                    // Document-start hooks (order: location environment, then device signals). Both are
                    // WebView-wide, so they apply to every tab loaded here.
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        WebViewCompat.addDocumentStartJavaScript(
                            this, EnvBundleCompiler.compileFromAssets(ctx, activeProfile), setOf("*"),
                        )
                        deviceScript = WebViewCompat.addDocumentStartJavaScript(
                            this, DeviceBundleCompiler.compileFromAssets(ctx, device), setOf("*"),
                        )
                    }

                    loadUrl(HOME_URL)
                }.also { webView = it }
            },
        )
    }
}

/**
 * Turn the default WebView UA into a Chrome-like one: drop the "; wv" embedded-WebView marker, the
 * "Version/4.0 " WebView token, and the internal "Build/…" fingerprint. The result reads as mobile
 * Chrome, which sites like Tinder accept where an embedded-WebView UA is refused.
 */
/** Launch a safe external scheme (tel:, mailto:, geo:, …) through the system, ignoring failures. */
private fun openExternally(context: Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

/** Route an http(s) download to Android's DownloadManager (public Downloads dir). */
private fun enqueueDownload(
    context: Context,
    url: String,
    userAgent: String?,
    contentDisposition: String?,
    mimeType: String?,
) {
    if (!url.startsWith("http")) return
    runCatching {
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setMimeType(mimeType)
            userAgent?.let { addRequestHeader("User-Agent", it) }
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDescription("GeoAlign download")
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
    }
}

/** Short, human-friendly tab label: the page title if present, else the url host. */
private fun tabLabel(title: String, url: String): String {
    if (title.isNotBlank()) return title.take(24)
    val host = url.substringAfter("://", url).substringBefore('/').removePrefix("www.")
    return host.ifBlank { url }.take(24)
}
