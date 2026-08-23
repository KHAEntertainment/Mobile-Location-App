package com.geoalign.core.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadCoordinatorTest {

    private val enqueued = mutableListOf<DownloadRequest>()
    private val coordinator = DownloadCoordinator { enqueued += it }

    private fun request(url: String) = DownloadRequest(url, "UA", "attachment; filename=x.pdf", "application/pdf")

    @Test fun httpsDownloadsAreHandedToTheSystem() {
        assertTrue(coordinator.onDownloadRequested(request("https://example.com/a.pdf")))
        assertEquals(1, enqueued.size)
        assertEquals("UA", enqueued.single().userAgent)
    }

    @Test fun httpDownloadsAreHandedToTheSystem() {
        assertTrue(coordinator.onDownloadRequested(request("http://example.com/a.pdf")))
        assertEquals(1, enqueued.size)
    }

    @Test fun schemeMatchIsCaseInsensitive() {
        assertTrue(coordinator.onDownloadRequested(request("HTTPS://example.com/a.pdf")))
    }

    @Test fun nonWebSchemesAreRefused() {
        // DownloadManager cannot fetch any of these, and `file:` would be a request to copy
        // something out of the app's own storage.
        listOf(
            "blob:https://example.com/1234",
            "data:text/plain;base64,aGk=",
            "content://media/external/downloads/1",
            "file:///data/data/com.geoalign.browser/files/profiles.json",
            "ftp://example.com/a.pdf",
        ).forEach { url ->
            assertFalse(url, coordinator.onDownloadRequested(request(url)))
        }
        assertTrue(enqueued.isEmpty())
    }

    @Test fun aSchemeThatMerelyStartsWithHttpIsRefused() {
        // The old inline check was `url.startsWith("http")`, which let this through.
        assertFalse(coordinator.onDownloadRequested(request("httpfoo://example.com/a.pdf")))
        assertTrue(enqueued.isEmpty())
    }
}
