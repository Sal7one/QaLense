package com.qalens

import kotlin.test.*

class RecordingEvidenceStoreTest {
    private fun log(n: Int) = QaEvent(100L + n, QaEventType.LOG, "event $n")

    @Test fun busySessionPreservesEarlyFailureDespiteUiEvictionAndClearing() {
        val store = RecordingEvidenceStore(100)
        var ui = QaLensUiState()
        repeat(600) { n ->
            val request = NetworkEvent(100L + n, "GET", "https://example.test/$n", if (n == 0) 500 else 200)
            store.network(request)
            store.event(log(n))
            ui = ui.copy(networkEvents = (ui.networkEvents + request).takeLast(250), events = (ui.events + log(n)).takeLast(100))
        }
        assertFalse(ui.networkEvents.any { it.isError })
        val snapshot = store.close()
        val recording = snapshot.applyTo(ui.copy(networkEvents = emptyList(), events = emptyList()))
        assertEquals(600, recording.networkEvents.size)
        assertEquals(600, recording.events.size)
        assertTrue(recording.networkEvents.first().isError)
        assertFalse(snapshot.retention.truncated)
    }

    @Test fun fiveMinuteFrameHistoryExceedsUiBuffer() {
        val store = RecordingEvidenceStore(0)
        repeat(36_000) { store.frame(FrameMetricsSample(it.toLong(), if (it == 0) 900 else 5)) }
        val result = store.close()
        assertEquals(36_000, result.state.frameMetrics.size)
        assertEquals(900L, result.state.frameMetrics.first().totalMs)
        assertFalse(result.retention.truncated)
    }

    @Test fun entryLimitKeepsEarliestAndCountsOmissions() {
        val store = RecordingEvidenceStore(100, mapOf("logs" to RecordingEvidenceStore.Limit(2, 1_000_000)))
        repeat(5) { store.event(log(it)) }
        val result = store.close()
        assertEquals(listOf(log(0), log(1)), result.state.events)
        val coverage = result.retention.tracks.getValue("logs")
        assertEquals(5L, coverage.observed)
        assertEquals(3L, coverage.dropped)
        assertTrue(result.retention.truncated)
        assertTrue(result.retention.notes().single().contains("3 observations omitted"))
    }

    @Test fun oversizedItemCannotStarveOtherTracksOrLaterSmallItems() {
        val store = RecordingEvidenceStore(100, mapOf("logs" to RecordingEvidenceStore.Limit(100, 200)))
        store.event(log(0).copy(message = "x".repeat(10_000)))
        store.event(log(1))
        store.network(NetworkEvent(100, "GET", "/first", 500))
        val result = store.close()
        assertEquals(listOf(log(1)), result.state.events)
        assertEquals(1L, result.retention.tracks.getValue("logs").dropped)
        assertEquals(1, result.state.networkEvents.size)
        assertTrue(result.retention.tracks.getValue("logs").estimatedBytes <= 200)
    }

    @Test fun closeFreezesSnapshotAndRejectsLateCallbacksAcrossSessions() {
        val old = RecordingEvidenceStore(100)
        old.event(log(0).copy(timestampMillis = 99))
        old.event(log(0))
        val closed = old.close()
        val next = RecordingEvidenceStore(200)
        old.event(log(1))
        old.droppedFrames(10)
        next.event(log(1)) // Stale timestamp must not enter the next recording.
        assertSame(closed, old.close())
        assertEquals(listOf(log(0)), closed.state.events)
        assertEquals(0L, closed.retention.droppedFrameCallbacks)
        assertTrue(next.close().state.events.isEmpty())
    }

    @Test fun annotationsRespectExplicitRemovalButDoNotReportItAsDataLoss() {
        val store = RecordingEvidenceStore(100)
        store.bookmark(Bookmark("a", 100, "first"))
        store.bookmark(Bookmark("b", 101, "second"))
        store.removeBookmark("a")
        store.clearBookmarks()
        store.bookmark(Bookmark("c", 102, "keep"))
        val result = store.close()
        store.clearBookmarks()
        assertEquals(listOf("keep"), result.state.bookmarks.map { it.label })
        assertEquals(2L, result.retention.tracks.getValue("marks").removed)
        assertFalse(result.retention.truncated)
    }

    @Test fun capturedStateDoesNotChangeWhenHostMutatesMaps() {
        val flags = mutableMapOf("feature" to true)
        val data = mutableMapOf("screen" to "first")
        val store = RecordingEvidenceStore(100)
        store.state(StateSample(100, "Home", "/", flags, mapOf("prefs" to data)))
        flags["feature"] = false
        data["screen"] = "later"
        val state = store.close().stateSamples.single()
        assertEquals(true, state.featureFlags["feature"])
        assertEquals("first", state.dataSources["prefs"]?.get("screen"))
    }

    @Test fun allSecondaryTracksKeepMoreThanDashboardHistory() {
        val store = RecordingEvidenceStore(100)
        repeat(300) { n ->
            store.memory(MemorySample(100L + n, 100, 10))
            store.connectivity(ConnectivitySnapshot(100L + n, ConnectivityType.WIFI))
        }
        repeat(20) { store.crash(QaLensCrash(100L + it, CrashType.ANR, "main", null, "stack")) }
        val result = store.close()
        assertEquals(300, result.state.memorySamples.size)
        assertEquals(300, result.state.connectivityTransitions.size)
        assertEquals(20, result.state.crashes.size)
    }

    @Test fun concurrentObserversDoNotLoseEntries() {
        val store = RecordingEvidenceStore(100)
        val threads = List(8) { i -> Thread { repeat(500) { store.event(log(i * 500 + it)) } } }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        val result = store.close()
        assertEquals(4_000, result.state.events.size)
        assertEquals(4_000, result.state.events.toSet().size)
        assertFalse(result.retention.truncated)
    }

    @Test fun digestExposesLimitsAndAndroidCallbackLoss() {
        val store = RecordingEvidenceStore(100, mapOf("network" to RecordingEvidenceStore.Limit(0, 0)))
        store.network(NetworkEvent(100, "GET", "/"))
        store.droppedFrames(7)
        store.droppedFrames(-1)
        val result = store.close()
        val json = QaLensAnalysis.digest(
            QaLensAnalysis.Coverage(false, false, true, 0, 0, 0, recordingRetention = result.retention),
            100, 200, emptyList(), emptyList(), emptyList(), emptyList(), null, QaLensConfig())
        assertTrue(json.contains("\"truncated\":true"))
        assertTrue(json.contains("\"droppedFrameCallbacks\":7"))
        assertTrue(json.contains("\"maxEntries\":0"))
        assertTrue(json.contains("1 observations omitted"))
        assertTrue(json.contains("performance statistics are partial"))
    }
}
