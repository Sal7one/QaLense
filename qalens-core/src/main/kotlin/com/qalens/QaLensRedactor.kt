package com.qalens

/**
 * Single choke-point for redaction. Every report, timeline line, network entry, feature-flag
 * value, and screenshot label passes through here before it can leave the device.
 *
 * Redaction is intentionally conservative: it prefers to over-redact (e.g. masking a long
 * order id) rather than risk leaking PII or secrets into a copied bug report.
 */
object QaLensRedactor {

    /** Redact a single string against the given rules. Null becomes "". */
    fun redact(value: String?, rules: List<RedactionRule>): String =
        if (value.isNullOrEmpty()) "" else rules.fold(value) { acc, rule -> rule.apply(acc) }

    /** Redact a value only when [enabled]; otherwise pass through unchanged (still null-safe). */
    fun redactIf(enabled: Boolean, value: String?, rules: List<RedactionRule>): String =
        if (enabled) redact(value, rules) else value.orEmpty()

    /** Redact both keys and values of a map (used for feature flags / header maps). */
    fun redactMap(map: Map<String, String>, rules: List<RedactionRule>): Map<String, String> =
        map.entries.associate { (k, v) -> redact(k, rules) to redact(v, rules) }

    /**
     * Redact a URL while preserving its shape (scheme://host/path) so network evidence stays
     * useful. Query string values are stripped — they routinely carry tokens and ids.
     */
    fun redactUrl(url: String?, rules: List<RedactionRule>): String {
        if (url.isNullOrBlank()) return ""
        val noQuery = url.substringBefore("?")
        val hadQuery = url.contains("?")
        return redact(noQuery, rules) + if (hadQuery) "?[QUERY_REDACTED]" else ""
    }
}
