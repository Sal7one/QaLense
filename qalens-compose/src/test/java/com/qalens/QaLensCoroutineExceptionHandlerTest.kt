package com.qalens

import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

class QaLensCoroutineExceptionHandlerTest {
    @Test fun debugHandlerAlwaysDelegatesTheOriginalFailure() {
        val thread = Thread.currentThread()
        val previous = thread.uncaughtExceptionHandler
        val error = IllegalStateException("fixture")
        var observed: Throwable? = null
        thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { origin, failure ->
            assertSame(thread, origin)
            observed = failure
        }
        try {
            for (handler in listOf(QaLensCoroutineExceptionHandler.capture(), QaLensCoroutineExceptionHandler(false))) {
                observed = null
                handler.handleException(EmptyCoroutineContext, error)
                assertSame(error, observed)
            }
        } finally { thread.uncaughtExceptionHandler = previous }
    }
}
