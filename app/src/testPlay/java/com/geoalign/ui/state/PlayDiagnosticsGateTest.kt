package com.geoalign.ui.state

import com.geoalign.di.AppGraph
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `play` half of the diagnostics gate (issue #8).
 *
 * `PlayDistributionTest` pins the flag; this pins the *consequence* of the flag, through the same
 * `AppGraph` read the readiness screen performs. Without this, "play has no developer diagnostics"
 * would remain a statement about a boolean that nothing was proven to consult — which is exactly
 * what the flag was before this issue.
 *
 * Lives in `src/testPlay` so it runs only under `play` and needs no `if` of its own.
 */
class PlayDiagnosticsGateTest {

    private val ui = ReadinessPresenter.present(
        ReadinessPresentationInput(
            phase = LoadPhase.LOADED,
            evaluation = null,
            profile = null,
            nowMillis = 0L,
            developerDiagnostics = AppGraph.distributionCapabilities().developerDiagnostics,
        ),
    )

    @Test fun `play offers no route to the diagnostics screen`() {
        assertFalse(ui.disclosures.any { it.id == ActionId.OPEN_DIAGNOSTICS })
    }

    @Test fun `the rest of the readiness screen is unaffected`() {
        assertTrue(ui.disclosures.any { it.id == ActionId.OPEN_CONNECTION_DETAILS })
    }
}
