package com.qalens.replay

/** Session time stays authoritative outside video coverage; decoder time owns the covered interval. */
internal class ReplayTimeline(
    val startMs: Long,
    val endMs: Long,
    private val hasVideo: Boolean,
    private val videoStartMs: Long?
) {
    val durationMs = (endMs - startMs).coerceAtLeast(1)

    data class VideoWindow(val startMs: Long, val endMs: Long) {
        fun contains(ts: Long) = ts >= startMs && ts < endMs
    }

    fun clamp(ts: Long): Long = ts.coerceIn(startMs, endMs)

    fun videoWindow(duration: Long?): VideoWindow? {
        if (!hasVideo || duration == null || duration <= 0) return null
        // Legacy archives align the media end with session end. Clips may start before session start.
        val base = videoStartMs ?: (endMs - duration)
        val end = if (base > Long.MAX_VALUE - duration) Long.MAX_VALUE else base + duration
        return VideoWindow(base, end)
    }

    fun videoPosition(ts: Long, duration: Long?): Long? = videoWindow(duration)?.let {
        (clamp(ts) - it.startMs).coerceIn(0, duration!!)
    }

    fun advance(
        ts: Long,
        elapsedMs: Long,
        videoDurationMs: Long? = null,
        videoPositionMs: Long = 0,
        videoReady: Boolean = false,
        videoEnded: Boolean = false,
        videoFailed: Boolean = false
    ): Long {
        val current = clamp(ts)
        val elapsed = elapsedMs.coerceIn(0, endMs - current)
        if (!hasVideo || videoFailed) return current + elapsed
        val window = videoWindow(videoDurationMs) ?: return current // preparing/error: no invented media progress
        return when {
            current < window.startMs -> clamp(minOf(current + elapsed, window.startMs))
            current >= window.endMs -> clamp(current + elapsed)
            videoEnded -> clamp(window.endMs)
            !videoReady -> current // buffering freezes both media and event time
            else -> clamp(window.startMs + minOf(videoPositionMs.coerceIn(0, videoDurationMs!!), window.endMs - window.startMs))
        }
    }
}

/** Sorted tracks can contain tens of thousands of observations; each playback tick is O(log n). */
internal inline fun <T> List<T>.latestIndexAt(ts: Long, timestamp: (T) -> Long): Int {
    var low = 0
    var high = size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (timestamp(this[middle]) <= ts) low = middle + 1 else high = middle
    }
    return low - 1
}

internal inline fun <T> List<T>.firstIndexAtOrAfter(ts: Long, timestamp: (T) -> Long): Int {
    var low = 0
    var high = size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (timestamp(this[middle]) < ts) low = middle + 1 else high = middle
    }
    return low
}
