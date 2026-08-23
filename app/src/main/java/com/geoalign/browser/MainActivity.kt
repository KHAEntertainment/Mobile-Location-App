package com.geoalign.browser

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.geoalign.di.AppGraph
import com.geoalign.ui.diagnostics.DiagnosticsScreen
import com.geoalign.ui.readiness.ReadinessScreen
import com.geoalign.ui.theme.GeoAlignTheme

/**
 * App entry point. Home is the readiness screen (spec §25); [Screen.Diagnostics] is the production
 * compatibility report, reachable from its disclosure row on editions that ship developer
 * diagnostics.
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
                        // Read from the injected distribution capabilities, never from
                        // BuildConfig.FLAVOR (`CONTRIBUTING.md` §5). False on `play`.
                        val diagnosticsAvailable =
                            remember { AppGraph.distributionCapabilities().developerDiagnostics }
                        var screen by rememberSaveable { mutableStateOf(Screen.Readiness) }
                        // The *route* is gated, not only the screen: on an edition without developer
                        // diagnostics the readiness screen offers no row that leads here (the
                        // presenter drops the disclosure), and a restored saved state naming the
                        // destination lands on the readiness screen instead of rendering it.
                        val destination =
                            if (screen == Screen.Diagnostics && !diagnosticsAvailable) Screen.Readiness else screen
                        when (destination) {
                            Screen.Diagnostics -> DiagnosticsScreen(onBack = { screen = Screen.Readiness })
                            Screen.Editor -> ProfileEditor(onDone = { screen = Screen.Readiness })
                            Screen.Browser -> BrowserScreen(onExit = { screen = Screen.Readiness })
                            Screen.Readiness -> ReadinessScreen(
                                onOpenDiagnostics = {
                                    if (diagnosticsAvailable) screen = Screen.Diagnostics
                                },
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
