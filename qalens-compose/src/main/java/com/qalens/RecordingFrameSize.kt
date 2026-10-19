package com.qalens

/** Bound recording allocations before PixelCopy; do not allocate a full display and shrink later. */
internal object RecordingFrameSize {
    const val MAX_PIXELS = 2_000_000
    fun of(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val scale = minOf(1.0, 720.0 / width, 2880.0 / height,
            kotlin.math.sqrt(MAX_PIXELS / (width.toDouble() * height)))
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }
}
