package com.qalens

/**
 * Bidirectional bridge between QaLens and an existing crash reporter (Sentry, Bugsnag, Crashlytics, …).
 *
 * QaLens doesn't replace your crash reporter — it *enriches* it. Apps implement this interface
 * using their vendor's hooks (Sentry `beforeSend`, Bugsnag `addOnError`, Crashlytics
 * `setCustomKeys`/`recordException`) and register it via `QaLens.bridgeCrashes`.
 *
 * Flow:
 * 1. **QaLens → host reporter** ([enrich]): when QaLens captures a crash, it calls [enrich] so the
 *    app can attach QaLens evidence (score, repro, screen context, last network failure) to the
 *    report the host reporter is about to send.
 * 2. **Host reporter → QaLens** ([onCrash]): the host reporter forwards crashes it catches (e.g.
 *    a crash in a background process that QaLens's handler didn't see) into the QaLens timeline.
 *
 * Both directions are optional. Enrichment is best-effort on the main thread and is not guaranteed
 * to run before an uncaught crash terminates the process. Use your reporter's own synchronous
 * before-send hook when attaching evidence must precede transmission. Inbound crashes are never
 * echoed back through enrich.
 *
 * Lives in `qalens-core` so both the debug implementation (`qalens-compose`) and the release no-op
 * (`qalens-noop`) can reference it without a cross-module dependency.
 */
interface QaLensCrashBridge {

    /**
     * Called when QaLens captures a crash. Use this to attach QaLens evidence to the host report.
     * [crash] is the captured crash with screen/route/network context; [evidence] is a pre-built
     * summary string (score, classification, repro, last failures) suitable for a custom key/extra.
     */
    fun enrich(crash: QaLensCrash, evidence: String) = Unit

    /**
     * Register a callback the host reporter invokes when it catches a crash. The app's
     * implementation wires this to the vendor's hook (e.g. Bugsnag `addOnError`). The callback
     * forwards the crash into QaLens's timeline + crashes list.
     */
    fun onCrash(callback: (QaLensCrash) -> Unit) = Unit
}

/** Default no-op bridge for apps that haven't wired a vendor yet. Does nothing. */
object QaLensNoopCrashBridge : QaLensCrashBridge {
    override fun enrich(crash: QaLensCrash, evidence: String) = Unit
    override fun onCrash(callback: (QaLensCrash) -> Unit) = Unit
}
