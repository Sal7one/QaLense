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
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import androidx.core.app.ServiceCompat
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

    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoFile: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording()
            return START_NOT_STICKY
        }
        startForegroundCompat()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else intent?.getParcelableExtra(EXTRA_DATA)
        val path = intent?.getStringExtra(EXTRA_VIDEO_PATH)

        if (resultCode == 0 || data == null || path == null) {
            QaLensSessionRecorder.onVideoConsentDenied()
            stopSelf()
            return START_NOT_STICKY
        }

        runCatching { startRecording(resultCode, data, File(path)) }
            .onFailure {
                QaLens.log("Video recording failed to start: ${it.message}")
                QaLensSessionRecorder.onVideoConsentDenied()
                stopSelf()
            }
        return START_STICKY
    }

    private fun startRecording(resultCode: Int, data: Intent, output: File) {
        videoFile = output
        val metrics = resources.displayMetrics
        // Cap the encode to a safe size. Modern phones (e.g. Pixel 8 Pro: 1344×2992) exceed common
        // H.264 encoder limits, and the encoder then produces ZERO frames — the "always no frames"
        // bug. Scale the longest side to ≤1280 (even dimensions; aspect preserved); the virtual
        // display mirrors the full screen into this surface.
        var w = metrics.widthPixels
        var h = metrics.heightPixels
        val maxDim = 1280
        val longest = maxOf(w, h)
        if (longest > maxDim) {
            val scale = maxDim.toFloat() / longest
            w = (w * scale).toInt()
            h = (h * scale).toInt()
        }
        val width = (w / 2) * 2     // encoders require even dimensions
        val height = (h / 2) * 2

        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this)
                  else @Suppress("DEPRECATION") MediaRecorder()
        rec.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(width, height)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(5_000_000)
            setOutputFile(output.absolutePath)
            prepare()
        }
        recorder = rec

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(resultCode, data)
        // Required on API 34+ before creating a VirtualDisplay.
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopRecording() }
        }, Handler(Looper.getMainLooper()))
        projection = proj

        virtualDisplay = proj.createVirtualDisplay(
            "qalens-capture", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            rec.surface, null, null
        )
        rec.start()
        QaLensSessionRecorder.onVideoStarted()
    }

    private var stopped = false

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

    override fun onDestroy() {
        super.onDestroy()
        if (recorder != null) runCatching { stopRecording() }
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
        val notification: Notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("QaLens · Recording screen")
            .setContentText("Tap to stop and save the recording")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(stopIntent)
            .addAction(0, "■ Stop recording", stopIntent)
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

        fun start(context: Context, resultCode: Int, data: Intent, videoPath: String) {
            val intent = Intent(context, QaLensProjectionService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
                putExtra(EXTRA_VIDEO_PATH, videoPath)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, QaLensProjectionService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
