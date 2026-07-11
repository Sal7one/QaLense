package com.qalens

/**
 * Pure analysis of [FrameMetricsSample]s. Kept in `qalens-core` so it's unit-testable without Android.
 * Used by the scoring engine (jank/frozen penalties) and the `.sal` `analysis.json` stats.
 */
object JankAnalyzer {

    data class JankDigest(
        val sampleCount: Int,
        val jankCount: Int,
        val frozenCount: Int,
        val p95TotalMs: Long,
        val p99TotalMs: Long,
        val worstFrameMs: Long
    ) {
        /** Fraction of frames that janked. High rates (>30%) indicate pervasive UI slowness. */
        val jankRate: Float get() = if (sampleCount == 0) 0f else jankCount.toFloat() / sampleCount
    }

    fun analyze(samples: List<FrameMetricsSample>): JankDigest {
        if (samples.isEmpty()) return JankDigest(0, 0, 0, 0, 0, 0)
        val sorted = samples.map { it.totalMs }.sorted()
        return JankDigest(
            sampleCount = samples.size,
            jankCount = samples.count { it.jank },
            frozenCount = samples.count { it.frozen },
            p95TotalMs = percentile(sorted, 95),
            p99TotalMs = percentile(sorted, 99),
            worstFrameMs = sorted.last()
        )
    }

    private fun percentile(sorted: List<Long>, pct: Int): Long {
        if (sorted.isEmpty()) return 0
        val idx = ((pct / 100.0) * sorted.size).toInt().coerceAtMost(sorted.size - 1)
        return sorted[idx]
    }
}
