package com.qalens

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QaLensDataInsightTest {
    @Test fun mergedTimelineDoesNotDoubleCountSourceFailuresAsABurst() {
        val events = listOf(QaEvent(11_100, QaEventType.LOG, "PlaybackException synthetic failure"))
        val requests = listOf(NetworkEvent(timestampMillis = 11_000, method = "GET", url = "https://example.test/video", status = 500))
        val config = QaLensConfig()
        val digest = QaLensAnalysis.digest(QaLensAnalysis.Coverage(false, false, true, 1, 1, 0), 10_000, 20_000,
            requests, events, TimelineMerger.merge(events, requests, config), emptyList(), null, config)
        assertFalse(digest.contains("error_burst"))
        assertContains(digest, "\"errorLogs\":1")
        val independent = TimelineEvent(11_000, TimelineKind.ERROR, "Independent manually reported decoder failure", isError = true)
        val withIndependent = QaLensAnalysis.digest(QaLensAnalysis.Coverage(false, false, true, 1, 1, 0), 10_000, 20_000,
            requests, events, TimelineMerger.merge(events, requests, config) + independent, emptyList(), null, config)
        assertContains(withIndependent, "error_burst")
        val ordinary = listOf(QaEvent(11_000, QaEventType.LOG, "canRetry=true"), QaEvent(11_100, QaEventType.LOG, "canRender=true"),
            QaEvent(11_200, QaEventType.LOG, "canRestart=true"))
        val healthy = QaLensAnalysis.digest(QaLensAnalysis.Coverage(false, false, false, 0, 3, 0), 10_000, 20_000,
            emptyList(), ordinary, TimelineMerger.merge(ordinary, emptyList(), config), emptyList(), null, config)
        assertContains(healthy, "\"errorLogs\":0")
        assertFalse(healthy.contains("error_burst"))
    }

    @Test fun failureContextLinksObservedActionsLogsAndStateWithOriginalPositions() {
        val config = QaLensConfig()
        val events = listOf(QaEvent(14_900, QaEventType.LOG, "Unrelated"), QaEvent(12_150, QaEventType.LOG, "Player failed"))
        val requests = listOf(NetworkEvent(timestampMillis = 11_000, method = "GET", url = "https://example.test/good", status = 200),
            NetworkEvent(timestampMillis = 12_000, method = "GET", url = "https://example.test/video?token=hidden", status = 503, latencyMs = 200))
        val timeline = listOf(TimelineEvent(15_000, TimelineKind.ACTION, "Retry later"),
            TimelineEvent(11_900, TimelineKind.ACTION, "Play"))
        val states = listOf(StateSample(15_000, screenName = "After", route = null), StateSample(11_500, screenName = "Player", route = null))
        val digest = QaLensAnalysis.digest(QaLensAnalysis.Coverage(false, false, true, 2, 2, 2), 10_000, 20_000,
            requests, events, timeline, states, null, config)
        assertContains(digest, "failure_context")
        assertContains(digest, "\"evidenceIds\":[\"network:1\",\"timeline:1\",\"logs:1\",\"state:1\"]")
        assertContains(digest, "Timing alone does not establish cause")
        assertFalse(digest.contains("token=hidden"))
        assertFalse(digest.contains("Retry later"))
    }

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
