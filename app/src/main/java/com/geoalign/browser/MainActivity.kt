package com.geoalign.browser

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.geoalign.core.readiness.ReadinessInputs
import com.geoalign.core.readiness.ReadinessReducer
import org.json.JSONArray

/**
 * POC harness (Milestone 1). This is NOT the production browser — it wires the document-start
 * environment bundle into a hardened WebView and loads the bundled POC diagnostics page so the
 * injection/timezone/locale/identity behavior can be verified on a real device.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column {
                        val featureOk = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                        // Demonstrate the pure readiness reducer producing a state string.
                        val state = ReadinessReducer.reduce(ReadinessInputs())
                        Text(
                            text = "Document-start supported: $featureOk   •   readiness=${state.level}",
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        PocWebView(Modifier.fillMaxSize())
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
                }
                if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
                    WebSettingsCompat.setSafeBrowsingEnabled(settings, true)
                }
                if (WebViewFeature.isFeatureSupported(WebViewFeature.OFF_SCREEN_PRERASTER)) {
                    WebSettingsCompat.setOffscreenPreRaster(settings, true)
                }

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
