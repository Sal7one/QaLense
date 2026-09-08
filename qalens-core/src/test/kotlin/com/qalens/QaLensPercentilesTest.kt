package com.qalens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QaLensPercentilesTest {
    @Test
    fun emptySingletonAndRepeatedValues() {
        for (percent in listOf(1, 50, 95, 99, 100)) {
            assertEquals(0L, nearestRankPercentile(emptyList(), percent))
            assertEquals(42L, nearestRankPercentile(listOf(42L), percent))
            assertEquals(42L, nearestRankPercentile(List(100) { 42L }, percent))
        }
    }

    @Test
    fun exactRanksDoNotSelectTheFollowingSample() {
        assertEquals(19L, nearestRankPercentile((1L..20L).toList(), 95))
        assertEquals(95L, nearestRankPercentile((1L..100L).toList(), 95))
        assertEquals(99L, nearestRankPercentile((1L..100L).toList(), 99))
    }

    @Test
    fun fractionalRanksRoundUpAndEndpointsStayInBounds() {
        assertEquals(2L, nearestRankPercentile(listOf(1L, 2L), 95))
        assertEquals(20L, nearestRankPercentile((1L..21L).toList(), 95))
        assertEquals(1L, nearestRankPercentile((1L..100L).toList(), 1))
        assertEquals(100L, nearestRankPercentile((1L..100L).toList(), 100))
    }

    @Test
    fun invalidPercentagesAreRejected() {
        for (percent in listOf(-1, 0, 101)) {
            assertFailsWith<IllegalArgumentException> { nearestRankPercentile(listOf(1L), percent) }
        }
    }

    @Test
    fun jankUsesNearestRankAfterSorting() {
        val digest = JankAnalyzer.analyze((100L downTo 1L).map { FrameMetricsSample(totalMs = it) })
        assertEquals(95L, digest.p95TotalMs)
        assertEquals(99L, digest.p99TotalMs)
        assertEquals(100L, digest.worstFrameMs)
    }

    private fun network(latency: Long, status: Int = 200, error: String? = null) =
        NetworkEvent(method = "GET", url = "https://example.com/test", status = status,
            latencyMs = latency, error = error)

    @Test
    fun networkHealthUsesNearestRankAndStillExcludesFailures() {
        val calls = listOf(network(300), network(120), network(999, 500), network(999, error = "offline"))
        assertEquals(300L, NetworkHealthEngine.summarize(calls).p95LatencyMs)
        assertEquals(0L, NetworkHealthEngine.summarize(calls.drop(2)).p95LatencyMs)
    }

    @Test
    fun exportedDigestUsesNearestRankAndPreservesItsSamplePopulation() {
        // The digest includes HTTP errors with measured latency, but excludes transport errors.
        val calls = (19L downTo 1L).map { network(it) } + network(20, 500) + network(999, error = "offline")
        fun p95(network: List<NetworkEvent>): Long {
            val json = QaLensAnalysis.digest(
                coverage = QaLensAnalysis.Coverage(false, false, true, network.size, 0, 0),
                startMillis = 0, endMillis = 1000, network = network,
                events = emptyList(), timeline = emptyList(), stateSamples = emptyList(),
                classification = null, config = QaLensConfig()
            )
            return Regex("\"p95LatencyMs\":([0-9]+)").find(json)!!.groupValues[1].toLong()
        }
        assertEquals(19L, p95(calls))
        assertEquals(0L, p95(emptyList()))
        assertEquals(42L, p95(listOf(network(42))))
    }
}
