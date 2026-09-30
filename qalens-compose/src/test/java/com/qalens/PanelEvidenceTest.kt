package com.qalens

import org.junit.Assert.*
import org.junit.Test

class PanelEvidenceTest {
    @Test fun unrelatedOverlayUpdatesDoNotRebuildEvidence() {
        val state = QaLensUiState(events = listOf(QaEvent(type = QaEventType.LOG, message = "fixture")))
        val config = QaLensConfig()
        val first = PanelEvidenceInput.capture(state, config)
        assertEquals(first, PanelEvidenceInput.capture(state.copy(isPanelOpen = true), config))
        assertEquals(first, PanelEvidenceInput.capture(state.copy(isWatchMode = true), config))
        assertNotEquals(first, PanelEvidenceInput.capture(state.copy(events = emptyList()), config))
    }

    @Test fun capturedHistoryIsConsistentAndRetainsRedactionFailureAndScreenshotCoverage() {
        val events = (0 until 10_000).map { QaEvent(timestampMillis = it.toLong(), type = QaEventType.LOG,
            message = "fixture-$it user@example.test") }
        val state = QaLensUiState(events = events, networkAvailable = true,
            networkEvents = listOf(NetworkEvent(timestampMillis = 10_001, method = "GET",
                url = "https://fixture.test/failure?token=secret", status = 500)))
        val input = PanelEvidenceInput.capture(state, QaLensConfig(),
            listOf(EvidenceAttachment("screenshot", "fixture.png", true)))
        val bundle = input.build()
        assertEquals(10_001, bundle.timeline.size)
        assertFalse(bundle.timeline.any { it.title.contains("user@example.test") || it.title.contains("secret") })
        assertTrue(bundle.repro.actual.contains("Failure observed"))
        assertTrue(bundle.completeness.hasScreenshot)
        assertEquals(events, bundle.snapshot.events)
        assertTrue(bundle.snapshot.generatedAtMillis > 0)
    }

    @Test fun logFilteringAndGroupingPreserveFullSelectedHistory() {
        val logs = listOf(
            QaEvent(type = QaEventType.EVENT, message = "business"),
            QaEvent(type = QaEventType.LOG, tag = "HTTP", message = "repeat"),
            QaEvent(type = QaEventType.LOG, tag = "HTTP", message = "repeat"),
            QaEvent(type = QaEventType.LOG, tag = "DB", message = "changed")
        )
        val rows = PanelLogRows.build(PanelLogInput(logs, QaEventType.LOG, "http"))
        assertEquals(2, rows.events.size)
        assertEquals(1, rows.rows.size)
        assertEquals(2, rows.rows.single().second)
        assertEquals(logs[2], rows.rows.single().first)
    }
}
