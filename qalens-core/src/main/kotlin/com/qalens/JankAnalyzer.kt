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
            p95TotalMs = nearestRankPercentile(sorted, 95),
            p99TotalMs = nearestRankPercentile(sorted, 99),
            worstFrameMs = sorted.last()
        )
    }

}
