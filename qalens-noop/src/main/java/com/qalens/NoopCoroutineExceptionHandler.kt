package com.qalens

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlin.coroutines.CoroutineContext

/** Release twin: forwards failures to the host thread's uncaught handler without collecting data.
 * Never consumes an application exception.
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
