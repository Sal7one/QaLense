package com.qalens.replay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max

internal data class RecordingInsightImage(val tMs: Long, val bytes: ByteArray, val source: String, val approximate: Boolean) {
    val id = "images:0"
    val mediaType = "image/jpeg"
    fun base64(): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    fun metadata(): JSONObject = JSONObject().put("id", id).put("tMs", tMs).put("mediaType", mediaType)
        .put("source", source).put("approximate", approximate).put("bytes", bytes.size)
}

internal object RecordingInsightImagePolicy {
    const val BYTE_LIMIT = 128 * 1024
    const val EDGE_LIMIT = 960
    fun videoPosition(startMs: Long, endMs: Long, videoStartMs: Long?, durationMs: Long, focusMs: Long): Long {
        require(durationMs > 0 && durationMs <= 86_400_000L) { "The saved video's duration is invalid." }
        val start = videoStartMs ?: endMs - durationMs
        val epoch = startMs + focusMs
        require(epoch >= start && epoch < start + durationMs) {
            "The selected moment has no saved video coverage. Move the playhead into the video's recorded interval."
        }
        return epoch - start
    }
}

/** One explicit saved still, never live capture. All decoders and bitmaps are released on IO. */
internal object RecordingInsightImageCapture {
    suspend fun capture(session: PlayerSession, focusMs: Long): RecordingInsightImage = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val epoch = session.startMs + focusMs.coerceIn(0, session.durationMs)
        val ref = session.frames.asSequence().filter { it.ts in session.startMs..session.endMs }.minByOrNull { abs(it.ts - epoch) }
        var bitmap: Bitmap? = null
        var at = focusMs
        var source = "recording-frame"
        var approximate = false
        try {
            if (ref != null) {
                require(ref.file.length() <= 8L * 1024 * 1024) { "The saved frame is too large to send safely." }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(ref.file.path, bounds)
                require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192 &&
                    bounds.outWidth.toLong() * bounds.outHeight <= 32_000_000L) { "The saved image dimensions are unsupported." }
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / sample > RecordingInsightImagePolicy.EDGE_LIMIT) sample *= 2
                coroutineContext.ensureActive()
                bitmap = BitmapFactory.decodeFile(ref.file.path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
                at = ref.ts - session.startMs
            } else {
                val video = session.videoFile ?: throw IllegalArgumentException("This recording has no saved image/video track. Text evidence can still be analyzed.")
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(video.path)
                    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                    val position = RecordingInsightImagePolicy.videoPosition(session.startMs, session.endMs, session.videoStartMs, duration, focusMs)
                    val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 32_000_000L) { "The saved video's dimensions are unsupported." }
                    coroutineContext.ensureActive()
                    bitmap = if (Build.VERSION.SDK_INT >= 27) {
                        val ratio = (RecordingInsightImagePolicy.EDGE_LIMIT.toDouble() / max(width, height)).coerceAtMost(1.0)
                        retriever.getScaledFrameAtTime(position * 1000, MediaMetadataRetriever.OPTION_CLOSEST,
                            (width * ratio).toInt().coerceAtLeast(1), (height * ratio).toInt().coerceAtLeast(1))
                    } else {
                        require(width.toLong() * height <= 2_560_000L) { "This Android version cannot safely extract a large HD still. Use text evidence or a Frames recording." }
                        retriever.getFrameAtTime(position * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                    }
                    source = "recording-video"; approximate = true
                } finally { retriever.release() }
            }
            var current = bitmap ?: throw IllegalArgumentException("A saved image could not be decoded at this moment. Text evidence can still be analyzed.")
            if (max(current.width, current.height) > RecordingInsightImagePolicy.EDGE_LIMIT) {
                val ratio = RecordingInsightImagePolicy.EDGE_LIMIT.toDouble() / max(current.width, current.height)
                val scaled = Bitmap.createScaledBitmap(current, (current.width * ratio).toInt().coerceAtLeast(1), (current.height * ratio).toInt().coerceAtLeast(1), true)
                if (scaled !== current) current.recycle()
                bitmap = scaled; current = scaled
            }
            coroutineContext.ensureActive()
            for (quality in listOf(80, 60, 40, 20)) {
                val out = ByteArrayOutputStream()
                require(current.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "The saved image could not be encoded." }
                val bytes = out.toByteArray()
                if (bytes.size <= RecordingInsightImagePolicy.BYTE_LIMIT) {
                    coroutineContext.ensureActive()
                    return@withContext RecordingInsightImage(at, bytes, source, approximate)
                }
            }
            throw IllegalArgumentException("The saved image exceeds 128 KiB after compression. Continue with text-only analysis.")
        } catch (_: OutOfMemoryError) {
            throw IllegalArgumentException("There is not enough memory to prepare a saved image. Continue with text-only analysis.")
        } finally { bitmap?.recycle() }
    }
}
