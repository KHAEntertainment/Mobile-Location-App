package com.geoalign.browser

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.geoalign.ui.components.AppScaffold
import com.geoalign.ui.readiness.ReadinessScreen
import com.geoalign.ui.theme.GeoAlignTheme
import com.geoalign.web.policy.BrowserPermissionPolicy
import com.geoalign.web.policy.LocalNetworkInterceptor
import android.util.Log
import org.json.JSONArray

/**
 * App entry point. Home is the readiness screen (spec §25); the POC diagnostics WebView is
 * reachable from it via the Diagnostics disclosure row so the on-device environment can still be
 * verified.
 */
private enum class Screen { Readiness, Diagnostics, Editor, Browser }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transparent system bars with dark icons, rather than painting both bars with the accent.
        // SystemBarStyle.light() applies its own scrim on API levels that cannot render dark
        // navigation-bar icons (< 27), which matters at minSdk 26.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
        setContent {
            GeoAlignTheme {
                // One surface, inset to the safe area. Content draws under the (transparent) bars.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        var screen by rememberSaveable { mutableStateOf(Screen.Readiness) }
                        when (screen) {
                            Screen.Diagnostics -> AppScaffold(
                                title = "Diagnostics",
                                onBack = { screen = Screen.Readiness },
                                scrollable = false,
                            ) {
                                PocWebView(Modifier.fillMaxSize())
                            }
                            Screen.Editor -> ProfileEditor(onDone = { screen = Screen.Readiness })
                            Screen.Browser -> BrowserScreen(onExit = { screen = Screen.Readiness })
                            Screen.Readiness -> ReadinessScreen(
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
                    // Explicitly refuse mixed content (spec §21). Cleartext is also blocked at the
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
