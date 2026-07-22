package com.geoalign.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.geoalign.web.policy.BrowserPermissionPolicy
import com.geoalign.web.policy.LocalNetworkInterceptor
import android.util.Log
import org.json.JSONArray

/**
 * App entry point. Home is the readiness dashboard (spec §25); the POC diagnostics WebView is
 * reachable from it via "Open diagnostics" so the on-device environment can still be verified.
 */
private enum class Screen { Dashboard, Diagnostics, Editor, Browser }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                // Tint the system-bar strips so the status-bar clock/notifications and the nav bar
                // are readable (they sit on a colored band, with light icons) instead of white-on-white.
                val barColor = MaterialTheme.colorScheme.primary
                val view = LocalView.current
                if (!view.isInEditMode) {
                    SideEffect {
                        val window = (view.context as Activity).window
                        val controller = WindowCompat.getInsetsController(window, view)
                        controller.isAppearanceLightStatusBars = false
                        controller.isAppearanceLightNavigationBars = false
                    }
                }
                // Outer surface fills behind the system bars (edge-to-edge) and provides the bar color;
                // the inner surface is inset to the safe area and hosts the app content.
                Surface(modifier = Modifier.fillMaxSize(), color = barColor) {
                    Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                        Box(Modifier.fillMaxSize()) {
                            var screen by remember { mutableStateOf(Screen.Dashboard) }
                            when (screen) {
                                Screen.Diagnostics -> Column(Modifier.fillMaxSize()) {
                                    OutlinedButton(
                                        onClick = { screen = Screen.Dashboard },
                                        modifier = Modifier.padding(8.dp),
                                    ) { Text("← Back to readiness") }
                                    PocWebView(Modifier.fillMaxSize())
                                }
                                Screen.Editor -> ProfileEditor(onDone = { screen = Screen.Dashboard })
                                Screen.Browser -> BrowserScreen(onExit = { screen = Screen.Dashboard })
                                Screen.Dashboard -> ReadinessDashboard(
                                    onOpenDiagnostics = { screen = Screen.Diagnostics },
                                    onEditProfile = { screen = Screen.Editor },
                                    onOpenBrowser = { screen = Screen.Browser },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val SAMPLE_LAT = 51.5074      // London, as a demo profile
private const val SAMPLE_LNG = -0.1278
private const val SAMPLE_ACC = 1500.0
private const val SAMPLE_TZ = "Europe/London"
private const val SAMPLE_LANG = "en-GB"

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PocWebView(modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    @Suppress("DEPRECATION")
                    allowFileAccessFromFileURLs = false
                    @Suppress("DEPRECATION")
                    allowUniversalAccessFromFileURLs = false
                    // Explicitly refuse mixed content (plan §21). Cleartext is also blocked at the
                    // manifest level (usesCleartextTraffic=false); set here to remove the discrepancy.
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }
                if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
                    WebSettingsCompat.setSafeBrowsingEnabled(settings, true)
                }
                if (WebViewFeature.isFeatureSupported(WebViewFeature.OFF_SCREEN_PRERASTER)) {
                    WebSettingsCompat.setOffscreenPreRaster(settings, true)
                }

                // POC 5: block local-network requests at the WebView boundary.
                webViewClient = LocalNetworkInterceptor { url, reason ->
                    Log.i("GeoAlign", "blocked local-network request ($reason)")
                }
                // POC 6 / permission policy: deny camera/mic and the native geolocation prompt.
                webChromeClient = BrowserPermissionPolicy()

                // Install the environment BEFORE any page script runs.
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    val script = buildEnvBundle(context)
                    WebViewCompat.addDocumentStartJavaScript(this, script, setOf("*"))
                }

                loadUrl("file:///android_asset/poc.html")
            }
        },
    )
}

private fun buildEnvBundle(context: android.content.Context): String {
    val template = context.assets.open("env_bundle.js").bufferedReader().use { it.readText() }
    val langs = JSONArray(listOf(SAMPLE_LANG, "en")).toString()
    return template
        .replace("__LAT__", SAMPLE_LAT.toString())
        .replace("__LNG__", SAMPLE_LNG.toString())
        .replace("__ACC__", SAMPLE_ACC.toString())
        .replace("__TZ__", SAMPLE_TZ)
        .replace("__LANG__", SAMPLE_LANG)
        .replace("__LANGS__", langs)
}
