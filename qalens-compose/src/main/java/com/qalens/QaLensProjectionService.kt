package com.qalens

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.ResultReceiver
import androidx.core.app.ServiceCompat
import com.qalens.compose.R
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Foreground service that records the screen to an H.264 `video.mp4` via MediaProjection +
 * MediaRecorder, for the opt-in video recording path. On stop it hands the file back to
 * [QaLensSessionRecorder] for `.sal` packaging.
 *
 * NOTE: This path requires a system consent dialog and a foreground service. It is debug-only and
 * needs on-device validation (encoder sizes, API 34 callback ordering) — the default frame-based
 * recorder remains the dependency-free, permission-free path.
 */
class QaLensProjectionService : Service() {

    private val captureThread = HandlerThread("qalens-video")
    // Do not wait for a worker looper while Android is waiting for foreground promotion.
    private val captureHandler by lazy { captureThread.start(); Handler(captureThread.looper) }
    private var starting = false
    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoFile: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            captureHandler.post { stopRecording() }
            return START_NOT_STICKY
        }
        val path = intent?.getStringExtra(EXTRA_VIDEO_PATH)
        @Suppress("DEPRECATION")
        val ready: ResultReceiver? = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra(EXTRA_FOREGROUND_RESULT, ResultReceiver::class.java)
        else intent?.getParcelableExtra(EXTRA_FOREGROUND_RESULT)
        try { startForegroundCompat() } catch (failure: Exception) {
            ready?.send(FOREGROUND_FAILED, null)
            QaLensSessionRecorder.onVideoConsentDenied(path,
                "HD foreground service could not start (${failure.javaClass.simpleName}): ${failure.message}. Use frame recording.")
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else intent?.getParcelableExtra(EXTRA_DATA)

        if (resultCode == 0 || data == null || path == null) {
            ready?.send(FOREGROUND_FAILED, null)
            QaLensSessionRecorder.onVideoConsentDenied(path)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!QaLensSessionRecorder.isAwaitingVideo(path) || stopped) {
            ready?.send(FOREGROUND_FAILED, null)
            QaLensSessionRecorder.onVideoConsentDenied(path, "HD recording was cancelled before its service became ready. Try again or use frame recording.")
            stopSelf(); return START_NOT_STICKY
        }
        ready?.send(FOREGROUND_READY, null)
        if (starting) return START_NOT_STICKY
        starting = true
        captureHandler.post { runCatching {
            if (!QaLensSessionRecorder.isAwaitingVideo(path)) { stopSelf(); return@runCatching }
            startRecording(resultCode, data, File(path))
        }
            .onFailure {
                QaLens.log("Video recording failed to start: ${it.message}")
                QaLensSessionRecorder.onVideoConsentDenied(path, "HD video could not start: ${it.message}. Use frame recording.")
                stopRecording()
            } }
        return START_NOT_STICKY
    }

    private fun startRecording(resultCode: Int, data: Intent, output: File) {
        videoFile = output
        val metrics = resources.displayMetrics
        val capabilities = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { mime -> mime.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
            .mapNotNull { runCatching { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities }.getOrNull() }
        // Query real encoder alignment/ranges; even dimensions alone are insufficient on some phones.
        var prepared: MediaRecorder? = null
        var width = 0; var height = 0
        for (longEdge in listOf(1920, 1280, 960, 720)) {
            val scale = minOf(1.0, longEdge.toDouble() / maxOf(metrics.widthPixels, metrics.heightPixels))
            for (caps in capabilities) {
                val w = ((metrics.widthPixels * scale).toInt() / caps.widthAlignment) * caps.widthAlignment
                val h = ((metrics.heightPixels * scale).toInt() / caps.heightAlignment) * caps.heightAlignment
                if (w <= 0 || h <= 0 || !caps.areSizeAndRateSupported(w, h, 30.0)) continue
                val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this)
                    else @Suppress("DEPRECATION") MediaRecorder()
                // Fit the shared reader's 256 MiB video-entry limit for the configured session duration.
                // Longer sessions trade bitrate for duration; no claim that bitrate is a quality guarantee.
                val seconds = QaLens.config.value.recordingMaxDurationMinutes.coerceIn(1, 180) * 60
                val bitrate = minOf(4_000_000L, 230L * 1024 * 1024 * 8 / seconds).toInt()
                val ready = runCatching {
                    rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    rec.setVideoSize(w, h); rec.setVideoFrameRate(30)
                    rec.setVideoEncodingBitRate(caps.bitrateRange.clamp(bitrate))
                    rec.setMaxFileSize(240L * 1024 * 1024)
                    rec.setOnInfoListener { _, what, _ ->
                        if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) captureHandler.post { stopRecording() }
                    }
                    rec.setOutputFile(output.absolutePath); rec.prepare()
                }.isSuccess
                if (ready) { prepared = rec; width = w; height = h; break }
                runCatching { rec.release() }
            }
            if (prepared != null) break
        }
        val rec = checkNotNull(prepared) { "No supported H.264 screen encoder; use frame recording" }
        recorder = rec
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(resultCode, data)
        // Required on API 34+ before creating a VirtualDisplay.
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopRecording() }
        }, captureHandler)
        projection = proj

        virtualDisplay = proj.createVirtualDisplay(
            "qalens-capture", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            rec.surface, null, captureHandler
        )
        if (!QaLensSessionRecorder.isAwaitingVideo(output.absolutePath)) { stopRecording(); return }
        rec.start()
        QaLensSessionRecorder.onVideoStarted(output.absolutePath)
    }

    @Volatile private var stopped = false

    private fun stopRecording() {
        if (stopped) return
        stopped = true
        // MediaRecorder.stop() throws if NO frames were ever encoded (e.g. the encoder never
        // produced output — frequent on emulators). Track that: a throw means the video is
        // unusable and the recorder must fall back to the PixelCopy frames it captured alongside.
        var videoOk = false
        runCatching { recorder?.stop(); videoOk = true }
            .onFailure { QaLens.log("MediaRecorder.stop failed (no frames encoded): ${it.message}") }
        runCatching { recorder?.reset(); recorder?.release() }
        recorder = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { projection?.stop() }
        projection = null

        val file = videoFile
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (file != null) QaLensSessionRecorder.onVideoComplete(file, videoOk)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // A5: the task was swiped away (or removed) — the app can no longer drive the stop UI. Stop
        // the projection cleanly so the recorder finalizes (or falls back to frames) and isRecording
        // never strands true.
        captureHandler.post { stopRecording() }
    }

    override fun onDestroy() {
        super.onDestroy()
        // A5: service torn down while the projection is still active (system reclaim / task removal)
        // — stop and hand the result back to the recorder so isRecording flips false.
        if (captureThread.isAlive) captureHandler.post { stopRecording(); captureThread.quitSafely() }
    }

    private fun startForegroundCompat() {
        val channelId = "qalens_recording"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(channelId) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(channelId, "QaLens Recording", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        // A visible STOP is mandatory here: without draw-over-apps the floating chip may not
        // exist, and this foreground notification is what the user finds in the shade.
        val stopIntent = android.app.PendingIntent.getService(
            this, 2,
            Intent(this, QaLensProjectionService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val controlsIntent = android.app.PendingIntent.getActivity(
            this, 3,
            Intent(this, QaLensControlActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.qalens_recording_notification_title))
            .setContentText(getString(R.string.qalens_recording_notification_text))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(controlsIntent)
            .addAction(0, getString(R.string.qalens_stop_recording), stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 0x4152
        const val EXTRA_RESULT_CODE = "qalens.result_code"
        const val EXTRA_DATA = "qalens.data"
        const val EXTRA_VIDEO_PATH = "qalens.video_path"
        const val ACTION_STOP = "com.qalens.action.STOP_PROJECTION"
        private const val EXTRA_FOREGROUND_RESULT = "qalens.foreground_result"
        internal const val FOREGROUND_READY = 1
        internal const val FOREGROUND_FAILED = 0

        fun start(context: Context, resultCode: Int, data: Intent, videoPath: String) {
            start(context, resultCode, data, videoPath, null)
        }

        internal fun start(context: Context, resultCode: Int, data: Intent, videoPath: String, ready: ResultReceiver?) {
            val intent = Intent(context, QaLensProjectionService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
                putExtra(EXTRA_VIDEO_PATH, videoPath)
                putExtra(EXTRA_FOREGROUND_RESULT, ready)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            // A background app can stop its service without trying to start another service.
            // onDestroy queues encoder finalization on the capture worker.
            context.stopService(Intent(context, QaLensProjectionService::class.java))
        }
    }
}
