package com.qalens

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DashboardEventBufferTest {
    @Test fun floodRetainsNewestInOrderAndClearingCannotResurrectPendingLines() {
        val buffer = DashboardEventBuffer()
        repeat(20_000) { buffer.add(QaEvent(timestampMillis = it.toLong(), type = QaEventType.LOG,
            message = "line-$it"), 600) }
        val batch = buffer.drain()
        assertEquals(600, batch.size)
        assertEquals(19_400L, batch.first().timestampMillis)
        assertEquals(19_999L, batch.last().timestampMillis)
        assertTrue(buffer.drain().isEmpty())
        buffer.add(batch.last(), 600)
        buffer.clear()
        assertTrue(buffer.drain().isEmpty())
    }

    @Test fun verboseLogsHaveCountByteAndPreviewBoundsWithoutChangingTheOriginal() {
        val original = QaEvent(type = QaEventType.LOG, message = "x".repeat(100_000))
        val buffer = DashboardEventBuffer()
        repeat(2000) { buffer.add(original, Int.MAX_VALUE) }
        val retained = DashboardEventBuffer.append(emptyList(), buffer.drain(), Int.MAX_VALUE)
        assertTrue(retained.sumOf { it.message.length } <= DashboardEventBuffer.MAX_CHARACTERS)
        assertTrue(retained.all { it.message.endsWith("[dashboard preview truncated]") })
        assertEquals(100_000, original.message.length)
        assertEquals(10_000, DashboardEventBuffer.limit(Int.MAX_VALUE))
        assertEquals(10_000, QaLensConfig.Builder().apply { maxEventHistory = Int.MAX_VALUE }.build().maxEventHistory)
    }

    @Test fun concurrentProducersAndDrainKeepEveryAdmittedObservationExactlyOnce() {
        val buffer = DashboardEventBuffer()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val work = (0 until 4).map { producer -> pool.submit {
                repeat(1000) { buffer.add(QaEvent(type = QaEventType.LOG, message = "$producer:$it"), 10_000) }
            } }
            val seen = mutableListOf<QaEvent>()
            while (work.any { !it.isDone }) seen += buffer.drain()
            work.forEach { it.get(5, TimeUnit.SECONDS) }
            seen += buffer.drain()
            assertEquals(4000, seen.size)
            assertEquals(4000, seen.map { it.message }.toSet().size)
        } finally { pool.shutdownNow() }
    }

    @Test fun networkQueueCannotGrowDuringAContinuousFlood() {
        val queue = DashboardQueue<Int>(250)
        repeat(100_000, queue::add)
        assertEquals((99_750 until 100_000).toList(), queue.drain())
        queue.add(1)
        queue.clear()
        assertTrue(queue.drain().isEmpty())
    }
}
