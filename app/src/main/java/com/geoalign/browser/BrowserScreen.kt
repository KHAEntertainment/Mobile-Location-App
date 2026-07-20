package com.geoalign.browser

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.geoalign.core.model.LocationProfile
import com.geoalign.core.net.UrlNormalizer
import com.geoalign.di.AppGraph
import com.geoalign.web.environment.EnvBundleCompiler
import com.geoalign.web.policy.BrowserWebChromeClient
import com.geoalign.web.policy.BrowserWebViewClient

private const val HOME_URL = "https://duckduckgo.com/"

/**
 * Milestone 3 slice 1 — a single-tab browser shell (spec §10). Loads the active profile's
 * environment into a hardened WebView with the local-network and permission policies wired in,
 * and drives an address bar + back/forward/refresh/home toolbar. Tabs and desktop mode follow.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val store = remember { AppGraph.profileStore(context) }

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
    var address by remember { mutableStateOf(HOME_URL) }
    var progress by remember { mutableStateOf(0) }
    var loadingPage by remember { mutableStateOf(false) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }

    fun load(text: String) {
        val url = UrlNormalizer.normalize(text) ?: return
        address = url
        webView?.loadUrl(url)
    }

    BackHandler(enabled = canBack) { webView?.goBack() }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { webView?.goBack() }, enabled = canBack) { Text("‹") }
            TextButton(onClick = { webView?.goForward() }, enabled = canForward) { Text("›") }
            TextButton(onClick = { if (loadingPage) webView?.stopLoading() else webView?.reload() }) {
                Text(if (loadingPage) "✕" else "⟳")
            }
            TextButton(onClick = { load(HOME_URL) }) { Text("⌂") }
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

        if (loadingPage && progress in 1..99) {
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
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
                    }
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
                        WebSettingsCompat.setSafeBrowsingEnabled(settings, true)
                    }

                    webViewClient = BrowserWebViewClient(
                        onNav = { url, back, forward, loading ->
                            canBack = back
                            canForward = forward
                            loadingPage = loading
                            if (url != null) address = url
                        },
                    )
                    webChromeClient = BrowserWebChromeClient(
                        onProgress = { progress = it },
                        onTitle = { /* title used by tabs later */ },
                    )

                    // Install the active profile's environment before any page script runs.
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        val script = EnvBundleCompiler.compileFromAssets(ctx, activeProfile)
                        WebViewCompat.addDocumentStartJavaScript(this, script, setOf("*"))
                    }

                    loadUrl(HOME_URL)
                }.also { webView = it }
            },
        )
    }
}
