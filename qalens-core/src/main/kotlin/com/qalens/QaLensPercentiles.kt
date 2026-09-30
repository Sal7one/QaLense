package com.qalens

/**
 * Nearest-rank percentile of ascending samples: rank = ceil(percent * count / 100).
 * Returns an observed value (no interpolation), or zero when there are no samples.
 * Small samples can legitimately have p95/p99 equal to the maximum.
 */
internal fun nearestRankPercentile(sorted: List<Long>, percent: Int): Long {
    require(percent in 1..100)
    if (sorted.isEmpty()) return 0L
    val rank = (percent.toLong() * sorted.size + 99L) / 100L
    return sorted[rank.toInt() - 1]
}
