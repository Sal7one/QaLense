package com.qalens

/** Aggregate network view for the Network tab and reports. Pure derivation over captured calls. */
data class NetworkHealth(
    val total: Int,
    val failed: Int,
    val slow: Int,
    val avgLatencyMs: Long,
    val p95LatencyMs: Long,
    val healthScore: Int
) {
    val band: String get() = when {
        total == 0  -> "No data"
        healthScore >= 85 -> "Healthy"
        healthScore >= 60 -> "Degraded"
        else        -> "Unhealthy"
    }
}

object NetworkHealthEngine {

    private const val P_FAILED = 15
    private const val P_SLOW = 5

    fun summarize(network: List<NetworkEvent>, slowThresholdMs: Long = 2000L): NetworkHealth {
        if (network.isEmpty()) {
            return NetworkHealth(0, 0, 0, 0, 0, 100)
        }
        val failed = network.count { it.isError }
        val slow = network.count { !it.isError && it.latencyMs >= slowThresholdMs }
        val latencies = network.filter { !it.isError }.map { it.latencyMs }.sorted()
        val avg = if (latencies.isEmpty()) 0L else latencies.average().toLong()
        val p95 = if (latencies.isEmpty()) 0L else {
            val idx = ((latencies.size - 1) * 0.95).toInt().coerceIn(0, latencies.lastIndex)
            latencies[idx]
        }
        val health = (100 - failed * P_FAILED - slow * P_SLOW).coerceIn(0, 100)
        return NetworkHealth(network.size, failed, slow, avg, p95, health)
    }
}
