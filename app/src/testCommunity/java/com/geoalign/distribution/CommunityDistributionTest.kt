package com.geoalign.distribution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The community edition's declared posture, pinned as literals. Mirror of
 * `src/testPlay/.../PlayDistributionTest.kt`.
 */
class CommunityDistributionTest {

    private val capabilities = BuildDistribution.CAPABILITIES

    @Test fun `the edition names itself community`() {
        assertEquals("community", BuildDistribution.EDITION)
    }

    @Test fun `community ships the experimental device profiles`() {
        assertTrue(capabilities.experimentalDeviceProfiles)
    }

    @Test fun `community ships the full developer diagnostics surface`() {
        assertTrue(capabilities.developerDiagnostics)
    }

    @Test fun `the partner directory does not apply to the sideloaded edition`() {
        assertFalse(capabilities.partnerDirectory)
    }
}
