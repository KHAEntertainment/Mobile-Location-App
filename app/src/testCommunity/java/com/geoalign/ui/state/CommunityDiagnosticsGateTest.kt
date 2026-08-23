package com.geoalign.ui.state

import com.geoalign.di.AppGraph
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `community` half of the diagnostics gate (issue #8). Mirror of
 * `src/testPlay/.../PlayDiagnosticsGateTest.kt`: the sideloaded edition does offer the route, so the
 * play-side assertion is proof of a gate rather than of a row nobody ever renders.
 */
class CommunityDiagnosticsGateTest {

    private val ui = ReadinessPresenter.present(
        ReadinessPresentationInput(
            phase = LoadPhase.LOADED,
            evaluation = null,
            profile = null,
            nowMillis = 0L,
            developerDiagnostics = AppGraph.distributionCapabilities().developerDiagnostics,
        ),
    )

    @Test fun `community offers the route to the diagnostics screen`() {
        assertTrue(ui.disclosures.any { it.id == ActionId.OPEN_DIAGNOSTICS })
    }

    @Test fun `the connection details row is still there`() {
        assertTrue(ui.disclosures.any { it.id == ActionId.OPEN_CONNECTION_DETAILS })
    }
}
