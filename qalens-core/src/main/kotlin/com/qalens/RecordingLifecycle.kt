package com.qalens

/** Pure recorder lifecycle. Session IDs keep delayed capture/consent callbacks out of later sessions. */
class RecordingLifecycle {
    enum class Phase { IDLE, AWAITING_CONSENT, CAPTURING, SAVING }
    @Volatile var phase: Phase = Phase.IDLE
        private set
    @Volatile var sessionId: Long = 0
        private set

    @Synchronized fun start(awaitConsent: Boolean): Long? {
        if (phase != Phase.IDLE) return null
        sessionId++
        phase = if (awaitConsent) Phase.AWAITING_CONSENT else Phase.CAPTURING
        return sessionId
    }

    @Synchronized fun activate(id: Long): Boolean {
        if (id != sessionId || phase != Phase.AWAITING_CONSENT) return false
        phase = Phase.CAPTURING
        return true
    }

    @Synchronized fun acceptsFrame(id: Long): Boolean = id == sessionId && phase == Phase.CAPTURING

    @Synchronized fun beginSaving(id: Long): Boolean {
        if (id != sessionId || phase != Phase.CAPTURING) return false
        phase = Phase.SAVING
        return true
    }

    @Synchronized fun finish(id: Long): Boolean {
        if (id != sessionId || phase != Phase.SAVING) return false
        phase = Phase.IDLE
        return true
    }

    @Synchronized fun cancel() { phase = Phase.IDLE }
}
