package com.qalens

/** Install once and always delegate host crashes, even when evidence collection fails. */
class CrashHandlerRegistration(
    private val getHandler: () -> Thread.UncaughtExceptionHandler?,
    private val setHandler: (Thread.UncaughtExceptionHandler) -> Unit,
    private val capture: (Thread, Throwable) -> Unit
) {
    private var installed = false

    @Synchronized fun install() {
        if (installed) return
        val previous = getHandler()
        setHandler(Thread.UncaughtExceptionHandler { thread, error ->
            try { capture(thread, error) }
            finally { previous?.uncaughtException(thread, error) }
        })
        installed = true
    }
}
