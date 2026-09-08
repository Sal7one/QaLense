package com.qalens

import kotlin.test.*

class FrameTimingUnitsTest {
    @Test fun androidNanosecondsBecomeMilliseconds() {
        val frame = FrameMetricsSample.fromNanoseconds(12_000_000, 3_000_000, 2_000_000, 1_000_000)
        assertEquals(12L, frame.totalMs)
        assertEquals(3L, frame.layoutMs)
        assertEquals(2L, frame.drawMs)
        assertEquals(1L, frame.gpuMs)
        assertFalse(frame.jank)
        assertFalse(frame.frozen)
    }

    @Test fun thresholdsUseFullPrecisionAndOnlySlowFramesAreFrozen() {
        assertFalse(FrameMetricsSample.fromNanoseconds(16_000_000).jank)
        assertTrue(FrameMetricsSample.fromNanoseconds(16_000_001).jank)
        assertFalse(FrameMetricsSample.fromNanoseconds(700_000_000).frozen)
        assertTrue(FrameMetricsSample.fromNanoseconds(700_000_001).frozen)
    }

    @Test fun unavailableMetricsDoNotBecomeNegativeDurations() {
        val frame = FrameMetricsSample.fromNanoseconds(1_000_000, gpu = -1)
        assertEquals(0L, frame.gpuMs)
        assertFalse(frame.frozen)
    }
}
