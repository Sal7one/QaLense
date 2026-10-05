package com.qalens.replay

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ReplayTimelineTest {
    private fun video(start: Long? = 12_000) = ReplayTimeline(10_000, 20_000, true, start)

    @Test fun framesUseElapsedTimeAndStopAtTheSessionEnd() {
        val clock = ReplayTimeline(10_000, 20_000, false, null)
        assertEquals(10_847, clock.advance(10_000, 847))
        assertEquals(20_000, clock.advance(19_500, 5_000))
        assertEquals(15_000, clock.advance(15_000, -100))
    }

    @Test fun preparationAndBufferingNeverInventVideoProgress() {
        assertEquals(14_000, video().advance(14_000, 1_000))
        assertEquals(14_000, video().advance(14_000, 1_000, 5_000, 3_000, videoReady = false))
        assertNull(video().videoWindow(-9223372036854775807))
    }

    @Test fun consentPrerollIsPlayedWithoutJumpingToDecoderTime() {
        assertEquals(10_500, video().advance(10_000, 500, 5_000, 3_000, true))
        assertEquals(12_000, video().advance(11_900, 500, 5_000, 3_000, true))
    }

    @Test fun coveredVideoUsesDecoderPositionRatherThanTimerTicks() {
        assertEquals(14_150, video().advance(14_000, 900, 5_000, 2_150, true))
        assertEquals(12_250, video().advance(14_000, 900, 5_000, 250, true)) // backward seek
    }

    @Test fun eventsAfterVideoEndRemainPlayable() {
        val clock = video()
        assertEquals(17_000, clock.advance(16_950, 50, 5_000, 4_950, true, videoEnded = true))
        assertEquals(17_700, clock.advance(17_000, 700, 5_000, 5_000, false, videoEnded = true))
        assertEquals(20_000, clock.advance(19_900, 500, 5_000, 5_000, false, videoEnded = true))
    }

    @Test fun failedVideoCanReplayEvidenceWithoutPretendingToDecodeMedia() {
        assertEquals(14_500, video().advance(14_000, 500, videoFailed = true))
    }

    @Test fun seeksClampMediaButKeepSessionCoverageSeparate() {
        assertEquals(0L, video().videoPosition(10_100, 5_000))
        assertEquals(2_250L, video().videoPosition(14_250, 5_000))
        assertEquals(5_000L, video().videoPosition(19_000, 5_000))
        assertFalse(video().videoWindow(5_000)!!.contains(10_100))
        assertFalse(video().videoWindow(5_000)!!.contains(17_000))
    }

    @Test fun legacyVideoAlignsItsEndAndClipsCanHaveEarlierMedia() {
        assertEquals(ReplayTimeline.VideoWindow(15_000, 20_000), video(null).videoWindow(5_000))
        assertEquals(2_000L, video(8_000).videoPosition(10_000, 5_000))
        assertEquals(10_400, video(8_000).advance(10_000, 50, 5_000, 2_400, true))
    }

    @Test fun emptyAndDuplicateTracksHaveDeterministicNavigation() {
        val times = listOf(100L, 200L, 200L, 800L)
        assertEquals(-1, times.latestIndexAt(99) { it })
        assertEquals(2, times.latestIndexAt(200) { it })
        assertEquals(3, times.latestIndexAt(900) { it })
        assertEquals(1, times.firstIndexAtOrAfter(200) { it })
        assertEquals(4, times.firstIndexAtOrAfter(900) { it })
        assertEquals(-1, emptyList<Long>().latestIndexAt(100) { it })
        assertEquals(0, emptyList<Long>().firstIndexAtOrAfter(100) { it })
    }

    @Test fun largeTracksUseLogarithmicLookups() {
        val times = List(40_000) { it.toLong() * 10 }
        var reads = 0
        assertEquals(32_100, times.latestIndexAt(321_004) { reads++; it })
        assertTrue("Playback walked $reads events", reads <= 16)
    }

    @Test fun futureFramesAndStateAreNotShownBeforeCapture() {
        val session = PlayerSession(File("unused"), "Synthetic", 100, 1_000, 2,
            listOf(FrameRef(500, File("frame"))), listOf(PItem(700, "event", null, false)),
            listOf(PItem(600, "network", null, false)), emptyList(),
            listOf(PStateSample(500, "later", emptyMap(), emptyMap())), null, "", null)
        assertNull(session.frameAt(499))
        assertNull(session.stateAt(499))
        assertEquals(500L, session.frameAt(500)?.ts)
        assertEquals("later", session.stateAt(500)?.screen)
        assertEquals(listOf(600L, 700L), session.allEvents.map { it.ts })
        assertSame(session.allEvents, session.allEvents)
    }

    @Test fun extremeTimesAndElapsedValuesCannotOverflow() {
        val clock = ReplayTimeline(Long.MAX_VALUE - 100, Long.MAX_VALUE, false, null)
        assertEquals(Long.MAX_VALUE, clock.advance(Long.MAX_VALUE - 50, Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, ReplayTimeline(0, 1_000, true, Long.MAX_VALUE - 5).videoWindow(100)!!.endMs)
    }
}
