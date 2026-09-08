package com.qalens

import kotlin.test.*

class RecordingWindowTest {
    @Test fun everyTrackExcludesEarlierAndLaterSessions() {
        val times = listOf(99L, 100L, 150L, 200L, 201L)
        val state = QaLensUiState(
            events = times.map { QaEvent(timestampMillis = it, type = QaEventType.LOG, message = "test") },
            networkEvents = times.map { NetworkEvent(timestampMillis = it, method = "GET", url = "https://example.com") },
            crashes = times.map { QaLensCrash(it, CrashType.CRASH, "test", "test", "test") },
            frameMetrics = times.map { FrameMetricsSample(timestampMillis = it, totalMs = 5) },
            connectivityTransitions = times.map { ConnectivitySnapshot(timestampMillis = it, type = ConnectivityType.WIFI) },
            memorySamples = times.map { MemorySample(it, 10, 5) },
            bookmarks = times.map { Bookmark(timestampMillis = it, label = "test") }
        )
        val slice = RecordingWindow.slice(state, 100, 200)
        val expected = listOf(100L, 150L, 200L)
        assertEquals(expected, slice.events.map { it.timestampMillis })
        assertEquals(expected, slice.networkEvents.map { it.timestampMillis })
        assertEquals(expected, slice.crashes.map { it.timestampMillis })
        assertEquals(expected, slice.frameMetrics.map { it.timestampMillis })
        assertEquals(expected, slice.connectivityTransitions.map { it.timestampMillis })
        assertEquals(expected, slice.memorySamples.map { it.timestampMillis })
        assertEquals(expected, slice.bookmarks.map { it.timestampMillis })
        assertEquals(5, state.crashes.size)
    }

    @Test fun emptyWindowStaysEmptyAndReversedRangeIsRejected() {
        assertEquals(emptyList(), RecordingWindow.slice(QaLensUiState(), 0, 0).events)
        assertFailsWith<IllegalArgumentException> { RecordingWindow.slice(QaLensUiState(), 2, 1) }
    }
}
