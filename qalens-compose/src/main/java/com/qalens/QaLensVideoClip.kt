package com.qalens

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Remux a finalized H.264 file off main; never read MediaRecorder's actively written output. */
internal object QaLensVideoClip {
    data class Result(val file: File, val startOffsetMillis: Long)
    fun cut(source: File, target: File, fromMillis: Long, toMillis: Long): Result {
        require(toMillis > fromMillis)
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        var success = false
        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("No encoded video track")
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            extractor.seekTo(fromMillis * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val first = extractor.sampleTime
            check(first >= 0 && first < toMillis * 1000 && extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) { "No playable keyframe in clip" }
            val output = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = output
            val metadata = MediaMetadataRetriever()
            try {
                metadata.setDataSource(source.absolutePath)
                metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
                    ?.takeIf { it in listOf(0, 90, 180, 270) }?.let(output::setOrientationHint)
            } finally { metadata.release() }
            val destination = output.addTrack(format)
            output.start(); started = true
            val capacity = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 4 * 1024 * 1024
            check(capacity in 1..16 * 1024 * 1024) { "Video sample exceeds clip buffer budget" }
            val buffer = ByteBuffer.allocateDirect(capacity)
            val info = MediaCodec.BufferInfo()
            var count = 0
            while (extractor.sampleTime in first until toMillis * 1000) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                check(size <= capacity)
                info.set(0, size, extractor.sampleTime - first,
                    if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                output.writeSampleData(destination, buffer, info); count++
                if (!extractor.advance()) break
            }
            check(count > 0)
            output.stop(); started = false; success = true
            return Result(target, first / 1000)
        } finally {
            extractor.release()
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            if (!success) target.delete()
        }
    }
}
