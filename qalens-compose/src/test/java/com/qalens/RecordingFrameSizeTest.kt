package com.qalens

import org.junit.Assert.*
import org.junit.Test

class RecordingFrameSizeTest {
    @Test fun boundsPortraitLandscapeAndExtremeWindowsBeforeAllocation() {
        for ((width, height) in listOf(1344 to 2992, 3840 to 2160, 720 to 1280,
            1440 to 10_000, Int.MAX_VALUE to Int.MAX_VALUE, 1 to Int.MAX_VALUE, Int.MAX_VALUE to 1)) {
            val (w, h) = RecordingFrameSize.of(width, height)
            assertTrue(w in 1..minOf(width, 720))
            assertTrue(h in 1..minOf(height, 2880))
            assertTrue(w.toLong() * h <= RecordingFrameSize.MAX_PIXELS)
        }
        assertEquals(720 to 1602, RecordingFrameSize.of(1344, 2992))
        assertEquals(720 to 1280, RecordingFrameSize.of(720, 1280))
    }
}
