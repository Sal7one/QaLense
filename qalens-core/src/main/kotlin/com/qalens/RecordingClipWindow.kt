package com.qalens

/** A retrospective interval ends at the mark, never at eventual archive-save time. */
object RecordingClipWindow {
    data class Window(val startMillis: Long, val endMillis: Long, val requestedSeconds: Int)
    fun at(recordingStart: Long, markedAt: Long, seconds: Int): Window {
        require(seconds in 1..300) { "Clip duration must be 1–300 seconds" }
        require(markedAt >= recordingStart)
        return Window(maxOf(recordingStart, markedAt - seconds * 1000L), markedAt, seconds)
    }
}
