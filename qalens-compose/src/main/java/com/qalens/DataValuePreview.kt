package com.qalens

/** Bounded, read-only previews. Never try to deserialize/decrypt an app's backing files. */
internal object DataValuePreview {
    const val MAX_FIELDS = 100
    private const val MAX_KEY = 200
    private const val MAX_VALUE = 2_048
    private val secretKey = Regex("(?i)(password|passwd|token|secret|credential|authorization|cookie|private.?key|api.?key)")

    fun sanitize(
        values: Map<String, String>, config: QaLensConfig,
        redactKeys: Set<String> = emptySet(), redactPatterns: List<Regex> = emptyList(),
        redactAll: Boolean = false
    ): Map<String, String> = values.entries.take(MAX_FIELDS).associate { (key, value) ->
        val safe = if (redactAll || key in redactKeys || secretKey.containsMatchIn(key) ||
            redactPatterns.any { it.containsMatchIn(value) }) "[REDACTED]" else config.redact(value)
        config.redact(key).take(MAX_KEY) to if (safe.length > MAX_VALUE)
            safe.take(MAX_VALUE) + "… [truncated]" else safe
    }
}

internal enum class DataStoreValuePhase { WAITING, LIVE, PAUSED, STOPPED, ERROR }
internal data class DataStoreValueStatus(
    val phase: DataStoreValuePhase,
    val updatedAtMillis: Long? = null
)
