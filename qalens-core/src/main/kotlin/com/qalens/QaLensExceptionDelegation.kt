package com.qalens

/** Preserve the host thread's uncaught-exception policy, including in release builds. */
object QaLensExceptionDelegation {
    fun forward(error: Throwable, thread: Thread = Thread.currentThread()) {
        thread.uncaughtExceptionHandler.uncaughtException(thread, error)
    }
}
