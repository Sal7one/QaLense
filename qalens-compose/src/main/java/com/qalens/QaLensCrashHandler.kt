package com.qalens

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Captures uncaught exceptions (crashes) and ANRs (main-thread watchdog), folding them into the
 * QaLens timeline + evidence bundle + `.sal` `crashes.json` track. Installed by [QaLens.install].
 *
 * Crash capture: chains the previous [Thread.setDefaultUncaughtExceptionHandler]. The crash is
 * recorded with the screen/route/network context that *led to it*, then the previous handler runs
 * (so the app still crashes normally — QaLens doesn't swallow it).
 *
 * ANR watchdog: a [Handler] posts a tick on the main [Looper] every 1s. A background thread checks
 * if the tick ran; if the main thread is blocked for [ANR_THRESHOLD_MS], an ANR event is recorded.
 * The app keeps running (ANRs don't crash the process); the event surfaces in the Overview tab.
 */
internal object QaLensCrashHandler {

    private const val ANR_THRESHOLD_MS = 5000L
    private const val CHECK_INTERVAL_MS = 1000L

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var mainAlive = false
    @Volatile private var anrMonitoring = false
    @Volatile private var lastCrash: QaLensCrash? = null
    private val registration = CrashHandlerRegistration(
        getHandler = Thread::getDefaultUncaughtExceptionHandler,
        setHandler = Thread::setDefaultUncaughtExceptionHandler
    ) { thread, throwable ->
        runCatching { recordCrash(thread, throwable, CrashType.CRASH) }
        autoFinalizeRecordingIfActive()
    }

    /** Startup auto-install and explicit install may both run; never chain the handler to itself. */
    fun install() {
        registration.install()
        if (QaLens.config.value.enabled) startAnrWatchdog()
    }

    /**
     * R10: on an uncaught crash, finalize any in-flight recording (saved to the recordings dir,
     * no share sheet) BEFORE delegating to the previous handler, so QA keeps evidence of the crash
     * that killed the app. Runs on the crashing thread, fully guarded — never pushError here (the
     * app is dying), just log.
     */
    private fun autoFinalizeRecordingIfActive() {
        if (!QaLens.state.value.isRecording) return
        runCatching { QaLensSessionRecorder.autoFinalize(lastCrash) }
            .onFailure { android.util.Log.w("QaLensCrashHandler", "Recording auto-finalize failed: ${it.message}") }
    }

    /** Record a coroutine exception forwarded by [QaLensCoroutineExceptionHandler] (B7). */
    fun recordCoroutineException(thread: Thread, throwable: Throwable) {
        recordCrash(thread, throwable, CrashType.COROUTINE_EXCEPTION)
    }

    private fun recordCrash(thread: Thread, throwable: Throwable, type: CrashType) {
        if (!QaLens.config.value.enabled) return
        val state = QaLens.state.value
        val lastNet = state.networkEvents.lastOrNull()
        val crash = QaLensCrash(
            type = type,
            thread = thread.name,
            throwable = throwable.javaClass.name + ": " + throwable.message,
            stackTrace = throwable.stackTraceToString().take(4000),
            screen = state.screen.screenName ?: state.screen.route,
            route = state.screen.route,
            lastNetworkSummary = lastNet?.let { "${it.method} ${it.shortUrl} → ${it.statusLabel}" }
        )
        lastCrash = QaLensCrashEvidence.sanitize(crash, QaLens.config.value)
        QaLensSessionRecorder.evidence?.crash(crash)
        // Emit into the timeline + the crashes list (confined to main).
        mainHandler.post {
            QaLens.appendCrash(crash)
            QaLens.event("crash", "${type.display}: ${throwable.javaClass.simpleName}: ${throwable.message}")
        }
    }

    private var watchdogThread: Thread? = null
    private var watchdogTick: Runnable? = null
    @Volatile private var lastTickMs = 0L
    @Volatile private var watchGeneration = 0L

    fun stop() {
        anrMonitoring = false
        watchGeneration++
        watchdogTick?.let(mainHandler::removeCallbacks)
        watchdogTick = null
        watchdogThread?.interrupt()
        watchdogThread = null
    }

    private fun startAnrWatchdog() {
        if (anrMonitoring) return
        anrMonitoring = true
        val generation = ++watchGeneration
        lastTickMs = SystemClock.uptimeMillis()
        val tick = object : Runnable {
            override fun run() {
                if (generation != watchGeneration || !anrMonitoring) return
                lastTickMs = SystemClock.uptimeMillis()
                mainHandler.postDelayed(this, CHECK_INTERVAL_MS)
            }
        }
        watchdogTick = tick
        mainHandler.post(tick)
        watchdogThread = Thread({
            try {
                while (anrMonitoring && generation == watchGeneration) {
                    Thread.sleep(CHECK_INTERVAL_MS)
                    if (generation == watchGeneration && SystemClock.uptimeMillis() - lastTickMs >= ANR_THRESHOLD_MS)
                        checkAnr()
                }
            } catch (_: InterruptedException) { /* disabled */ }
        }, "qalens-anr-watchdog").also { it.isDaemon = true; it.start() }
    }

    @Volatile private var lastAnrMs = 0L
    private fun checkAnr() {
        if (!QaLens.config.value.enabled || !anrMonitoring) return
        val now = System.currentTimeMillis()
        // Throttle: at most one ANR event per 5s so a long freeze doesn't spam.
        if (now - lastAnrMs < ANR_THRESHOLD_MS) return
        lastAnrMs = now
        val state = QaLens.state.value
        val crash = QaLensCrash(
            type = CrashType.ANR,
            thread = "main",
            throwable = null,
            stackTrace = "Main thread blocked for >${ANR_THRESHOLD_MS}ms",
            screen = state.screen.screenName ?: state.screen.route,
            route = state.screen.route,
            lastNetworkSummary = state.networkEvents.lastOrNull()?.let { "${it.method} ${it.shortUrl} → ${it.statusLabel}" }
        )
        lastCrash = QaLensCrashEvidence.sanitize(crash, QaLens.config.value)
        QaLensSessionRecorder.evidence?.crash(crash)
        mainHandler.post {
            QaLens.appendCrash(crash)
            QaLens.event("anr", "ANR: main thread blocked >${ANR_THRESHOLD_MS}ms on ${state.screen.displayName}")
        }
    }

    /** The most recent crash/ANR, or null. Surfaced in the Overview tab. */
    fun peekLastCrash(): QaLensCrash? = lastCrash
}
