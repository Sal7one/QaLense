package com.qalens

import kotlin.test.*

class CrashHandlerRegistrationTest {
    @Test fun repeatedInstallationCapturesAndDelegatesExactlyOnce() {
        var captured = 0
        var delegated = 0
        val error = IllegalStateException("synthetic crash")
        var handler = Thread.UncaughtExceptionHandler { _, received ->
            assertSame(error, received)
            delegated++
        }
        val registration = CrashHandlerRegistration({ handler }, { handler = it }) { _, _ -> captured++ }
        registration.install()
        registration.install()
        handler.uncaughtException(Thread.currentThread(), error)
        assertEquals(1, captured)
        assertEquals(1, delegated)
    }

    @Test fun evidenceFailureStillReachesTheHostHandler() {
        var delegated = false
        var handler = Thread.UncaughtExceptionHandler { _, _ -> delegated = true }
        val registration = CrashHandlerRegistration({ handler }, { handler = it }) { _, _ -> error("capture failed") }
        registration.install()
        assertFailsWith<IllegalStateException> { handler.uncaughtException(Thread.currentThread(), Exception()) }
        assertTrue(delegated)
    }
}
