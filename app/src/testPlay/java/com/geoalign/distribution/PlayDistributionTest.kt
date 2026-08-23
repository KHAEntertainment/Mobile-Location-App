package com.geoalign.distribution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The Play edition's declared posture, pinned as literals.
 *
 * This is deliberately in `src/testPlay` rather than a branch inside a shared test: asserting what
 * `play` says by running only under `play` needs no `if`, which is the same discipline the
 * production code is held to.
 */
class PlayDistributionTest {

    private val capabilities = BuildDistribution.CAPABILITIES

    @Test fun `the edition names itself play`() {
        assertEquals("play", BuildDistribution.EDITION)
    }

    @Test fun `play ships no experimental device profiles`() {
        assertFalse(capabilities.experimentalDeviceProfiles)
    }

    @Test fun `play ships no developer diagnostics surface`() {
        // Consumed by issue #8, which gates the Diagnostics screen on this. Pinned now so the
        // gate has something stable to be written against.
        assertFalse(capabilities.developerDiagnostics)
    }

    @Test fun `play has no partner directory at first release`() {
        assertFalse(capabilities.partnerDirectory)
    }
}
