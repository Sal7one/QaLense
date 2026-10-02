package com.qalens

import kotlin.test.*

class RecordingClipWindowTest {
    @Test fun lateMarkUsesMarkTimeAndClampsToCaptureStart() {
        assertEquals(RecordingClipWindow.Window(3_600_000, 3_620_000, 20), RecordingClipWindow.at(0, 3_620_000, 20))
        assertEquals(RecordingClipWindow.Window(1_000, 5_000, 60), RecordingClipWindow.at(1_000, 5_000, 60))
        assertFailsWith<IllegalArgumentException> { RecordingClipWindow.at(0, 1, 301) }
        assertFailsWith<IllegalArgumentException> { RecordingClipWindow.at(0, 1, 0) }
    }
    @Test fun recentEvidenceSurvivesFullSessionBudgetAndDoesNotCloseAtMark() {
        val store = RecordingEvidenceStore.withRecent(0)
        repeat(12_000) { store.event(QaEvent(it.toLong(), QaEventType.LOG, "event-$it")) }
        val mark = store.recentSnapshot()!!
        assertEquals("event-11999", mark.state.events.last().message)
        assertTrue(mark.state.events.size <= 2_000)
        assertEquals("keep-latest-buffer", mark.retention.policy)
        assertTrue(mark.retention.truncated)
        store.event(QaEvent(12_000, QaEventType.LOG, "after mark"))
        assertFalse(mark.state.events.any { it.message == "after mark" })
        assertEquals("after mark", store.recentSnapshot()!!.state.events.last().message)
        val master = store.close()
        assertEquals("event-0", master.state.events.first().message)
        assertFalse(master.state.events.any { it.message == "after mark" })
        store.event(QaEvent(12_001, QaEventType.LOG, "late"))
        assertFalse(store.recentSnapshot()!!.state.events.any { it.message == "late" })
    }
    @Test fun latestOversizedEntryDoesNotEvictUsableSmallEvidence() {
        val store = RecordingEvidenceStore(0, mapOf("logs" to RecordingEvidenceStore.Limit(2, 1000)), keepLatest = true)
        store.event(QaEvent(1, QaEventType.LOG, "first"))
        store.event(QaEvent(2, QaEventType.LOG, "x".repeat(2000)))
        store.event(QaEvent(3, QaEventType.LOG, "last"))
        assertEquals(listOf("first", "last"), store.snapshot().state.events.map { it.message })
        store.event(QaEvent(4, QaEventType.LOG, "newest"))
        assertEquals(listOf("last", "newest"), store.close().state.events.map { it.message })
    }
    @Test fun latestStateCopiesHostMapsAndRejectsOversizedSamples() {
        val store = RecordingEvidenceStore(0, mapOf("state" to RecordingEvidenceStore.Limit(2, 1000)), keepLatest = true)
        val flags = mutableMapOf("enabled" to true)
        store.state(StateSample(1, "First", null, flags, emptyMap()))
        flags["enabled"] = false
        store.state(StateSample(2, "x".repeat(2000), null, emptyMap(), emptyMap()))
        assertEquals(true, store.snapshot().stateSamples.single().featureFlags["enabled"])
        store.state(StateSample(3, "Second", null, emptyMap(), emptyMap()))
        store.state(StateSample(4, "Third", null, emptyMap(), emptyMap()))
        assertEquals(listOf("Second", "Third"), store.close().stateSamples.map { it.screenName })
    }

    @Test fun durationAndPresetBuilderBounds() {
        val config = QaLensConfig.Builder().apply {
            recordingMaxDurationMinutes = 999
            recordingClipPresetsSeconds = listOf(0, 10, 10, 300, 301)
        }.build()
        assertEquals(180, config.recordingMaxDurationMinutes)
        assertEquals(listOf(10, 300), config.recordingClipPresetsSeconds)
    }
}
