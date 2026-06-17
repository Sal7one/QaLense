package com.qalens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QaLensNetworkHealthTest {

    private fun net(status: Int = 200, latency: Long = 100, error: String? = null) =
        NetworkEvent(method = "GET", url = "https://api.x/v1/y", status = status, latencyMs = latency, error = error)

    @Test
    fun emptyIsPerfectHealthNoData() {
        val h = NetworkHealthEngine.summarize(emptyList())
        assertEquals(100, h.healthScore)
        assertEquals("No data", h.band)
    }

    @Test
    fun allSuccessIsHealthy() {
        val h = NetworkHealthEngine.summarize(listOf(net(200, 120), net(200, 300)))
        assertEquals(100, h.healthScore)
        assertEquals("Healthy", h.band)
        assertEquals(210L, h.avgLatencyMs)
    }

    @Test
    fun failuresAndSlowDeductHealth() {
        val h = NetworkHealthEngine.summarize(
            listOf(net(500, 100), net(200, 3000)),
            slowThresholdMs = 2000L
        )
        // 100 - 15 (1 failed) - 5 (1 slow) = 80
        assertEquals(80, h.healthScore)
        assertEquals(1, h.failed)
        assertEquals(1, h.slow)
    }

    @Test
    fun healthClampedAtZero() {
        val many = (1..20).map { net(500, 100) }
        assertEquals(0, NetworkHealthEngine.summarize(many).healthScore)
    }

    // ── Score bands ──────────────────────────────────────────────────────────

    @Test
    fun scoreBandsMapCorrectly() {
        fun band(score: Int) = ReleaseReadinessScore(score, emptyList(), 0, 0, 0, 0, 0, 0).band
        assertEquals("Ready", band(90))
        assertEquals("Minor risk", band(75))
        assertEquals("Needs attention", band(55))
        assertEquals("High risk", band(20))
    }

    // ── Classifier: slow → performance ─────────────────────────────────────────

    @Test
    fun slowRequestClassifiedAsPerformance() {
        val c = BugClassifier.classify(
            network = listOf(net(200, 5000)),
            warnings = emptyList(),
            screenHistory = listOf("home"),
            slowThresholdMs = 2000L
        )
        assertEquals(BugCategory.PERFORMANCE, c.category)
        assertTrue(c.reasons.first().contains("took"))
    }

    @Test
    fun buildIssuesClassifiedAsConfiguration() {
        val c = BugClassifier.classify(
            network = emptyList(),
            warnings = emptyList(),
            screenHistory = listOf("home", "settings"),
            buildSafetyIssues = listOf("Environment is 'staging' but expected 'production'.")
        )
        assertEquals(BugCategory.CONFIGURATION_ENVIRONMENT, c.category)
    }
}
