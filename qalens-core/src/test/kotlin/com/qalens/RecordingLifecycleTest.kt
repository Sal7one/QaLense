package com.qalens

import kotlin.test.*

class RecordingLifecycleTest {
    @Test fun consentMustActivateBeforeFramesAreAccepted() {
        val lifecycle = RecordingLifecycle()
        val id = lifecycle.start(true)!!
        assertFalse(lifecycle.acceptsFrame(id))
        assertFalse(lifecycle.beginSaving(id))
        assertTrue(lifecycle.activate(id))
        assertTrue(lifecycle.acceptsFrame(id))
        assertFalse(lifecycle.activate(id))
    }

    @Test fun savingBlocksNewRecordingsAndLateFrames() {
        val lifecycle = RecordingLifecycle()
        val id = lifecycle.start(false)!!
        assertTrue(lifecycle.beginSaving(id))
        assertFalse(lifecycle.acceptsFrame(id))
        assertNull(lifecycle.start(false))
        assertFalse(lifecycle.beginSaving(id))
        assertTrue(lifecycle.finish(id))
        assertNotNull(lifecycle.start(false))
    }

    @Test fun oldCallbacksCannotAlterTheNextRecording() {
        val lifecycle = RecordingLifecycle()
        val old = lifecycle.start(true)!!
        lifecycle.cancel()
        val current = lifecycle.start(true)!!
        assertFalse(lifecycle.activate(old))
        assertFalse(lifecycle.acceptsFrame(old))
        assertFalse(lifecycle.finish(old))
        assertEquals(RecordingLifecycle.Phase.AWAITING_CONSENT, lifecycle.phase)
        assertTrue(lifecycle.activate(current))
    }

    @Test fun cancellationInvalidatesAlreadyRequestedCaptures() {
        val lifecycle = RecordingLifecycle()
        val id = lifecycle.start(false)!!
        lifecycle.cancel()
        assertFalse(lifecycle.acceptsFrame(id))
        assertFalse(lifecycle.beginSaving(id))
        assertFalse(lifecycle.finish(id))
    }
}
