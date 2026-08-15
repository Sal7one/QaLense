package com.qalens

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlin.coroutines.CoroutineContext

/**
 * A [CoroutineExceptionHandler] that forwards uncaught coroutine exceptions into the QaLens
 * timeline (as `COROUTINE_EXCEPTION` crash events via [QaLensCrashHandler]) and then delegates
 * to the default handler — so the app still crashes if that's the installed behavior, but QaLens
 * captures the context that *led* to the exception (screen, route, last network).
 *
 * Usage:
 * ```kotlin
 * val handler = QaLens.coroutineExceptionHandler()
 * viewModelScope.launch(handler) { … }
 * ```
 *
 * Or install as the global default:
 * ```kotlin
 * QaLens.installCoroutineExceptionHandler()  // in Application.onCreate
 * ```
 *
 * B7: most modern Android apps use coroutines; an uncaught exception in a `viewModelScope` or
 * `lifecycleScope` is otherwise invisible unless the app logs it via Timber.
 */
class QaLensCoroutineExceptionHandler(
    private val rethrow: Boolean
) : CoroutineExceptionHandler {

    override val key: CoroutineContext.Key<*> = CoroutineExceptionHandler

    override fun handleException(context: CoroutineContext, exception: Throwable) {
        val thread = Thread.currentThread()
        QaLensCrashHandler.recordCoroutineException(thread, exception)
        if (rethrow) throw exception
    }

    companion object {
        /** Create a handler that captures the exception into QaLens but does not rethrow. */
        fun capture(): QaLensCoroutineExceptionHandler = QaLensCoroutineExceptionHandler(rethrow = false)
    }
}
