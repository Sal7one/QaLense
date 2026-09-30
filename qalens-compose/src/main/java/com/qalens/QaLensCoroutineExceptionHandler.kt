package com.qalens

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlin.coroutines.CoroutineContext

/** Forwards failures to the host thread's uncaught handler. The installed QaLens process handler
 * observes the failure before delegating to the host. Never consumes an application exception.
 * The constructor argument is retained for source compatibility; both values now delegate.
 */
class QaLensCoroutineExceptionHandler(@Suppress("UNUSED_PARAMETER") rethrow: Boolean) : CoroutineExceptionHandler {
    override val key: CoroutineContext.Key<*> = CoroutineExceptionHandler
    override fun handleException(context: CoroutineContext, exception: Throwable) =
        QaLensExceptionDelegation.forward(exception)
    companion object {
        fun capture(): QaLensCoroutineExceptionHandler = QaLensCoroutineExceptionHandler(true)
    }
}
