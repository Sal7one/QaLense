package com.qalens

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QaLensDataInsightTest {
    @Test fun observedDataChangesAreContextNotFabricatedUserSteps() {
        val event = QaEvent(11_000, QaEventType.EVENT, "Room tables changed: accounts", QaLensDataEvents.ROOM)
        val timeline = TimelineMerger.merge(listOf(event), emptyList(), QaLensConfig())
        assertTrue(timeline.single().kind == TimelineKind.LOG)
        assertContains(timeline.single().title, "accounts")
        assertFalse(ReproStepGenerator.generate(timeline).steps.any { it.contains("Trigger:") })
    }

    @Test fun digestLinksOnlyPrecedingObservedChangesToFailures() {
        val events = listOf(
            QaEvent(11_000, QaEventType.EVENT, "Room tables changed: accounts", QaLensDataEvents.ROOM),
            QaEvent(12_000, QaEventType.EVENT, "Preferences changed: secret-value", QaLensDataEvents.DATASTORE),
            QaEvent(31_000, QaEventType.EVENT, "Preferences changed later", QaLensDataEvents.DATASTORE)
        )
        val requests = listOf(
            NetworkEvent(timestampMillis = 13_000, method = "POST", url = "https://example.test/save?token=secret", status = 500),
            NetworkEvent(timestampMillis = 30_000, method = "GET", url = "https://example.test/refresh", status = 503)
        )
        val digest = QaLensAnalysis.digest(
            coverage = QaLensAnalysis.Coverage(false, false, true, requests.size, events.size, 0),
            startMillis = 10_000,
            endMillis = 40_000,
            network = requests,
            events = events,
            timeline = TimelineMerger.merge(events, requests, QaLensConfig()),
            stateSamples = emptyList(),
            classification = null,
            config = QaLensConfig()
        )
        assertContains(digest, "\"roomChanges\":1")
        assertContains(digest, "\"preferenceChanges\":2")
        assertContains(digest, "\"dataChangeFailureLinks\":1")
        assertContains(digest, "data_change_near_failure")
        assertContains(digest, "timing does not prove cause")
        assertFalse(digest.contains("secret-value"))
        assertFalse(digest.contains("token=secret"))
    }

    @Test fun digestDisclosesBoundedCorrelationList() {
        val events = listOf(QaEvent(11_000, QaEventType.EVENT, "Room changed", QaLensDataEvents.ROOM))
        val requests = (1..21).map { index ->
            NetworkEvent(timestampMillis = 12_000L + index, method = "GET",
                url = "https://example.test/items", status = 500)
        }
        val digest = QaLensAnalysis.digest(
            coverage = QaLensAnalysis.Coverage(false, false, true, requests.size, events.size, 0),
            startMillis = 10_000, endMillis = 20_000,
            network = requests, events = events,
            timeline = emptyList(), stateSamples = emptyList(), classification = null,
            config = QaLensConfig()
        )
        assertContains(digest, "\"dataChangeFailureLinks\":21")
        assertContains(digest, "1 additional data-change/failure timing links omitted")
        assertTrue(Regex("data_change_near_failure").findAll(digest).count() == 20)
    }
}
