package com.qalens

/** Redacted, bounded crash metadata for host bridges and externally reported failures. */
object QaLensCrashEvidence {
    fun sanitize(crash: QaLensCrash, config: QaLensConfig): QaLensCrash = crash.copy(
        thread = config.redact(crash.thread.take(256)),
        throwable = crash.throwable?.let { config.redact(it.take(4096)) },
        stackTrace = config.redact(crash.stackTrace.take(16_384)),
        screen = crash.screen?.let { config.redact(it.take(1024)) },
        route = crash.route?.let { config.redact(it.take(2048)) },
        lastNetworkSummary = crash.lastNetworkSummary?.let { config.redact(it.take(2048)) }
    )
}
