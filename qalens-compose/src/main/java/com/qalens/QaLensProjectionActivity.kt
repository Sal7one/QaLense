package com.qalens

import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Transparent helper that requests the one-time MediaProjection screen-capture consent, then hands
 * the grant to [QaLensProjectionService]. Launched by [QaLensSessionRecorder] only when the app
 * opts into video recording via `QaLens.startRecording(video = true)`.
 */
class QaLensProjectionActivity : ComponentActivity() {

    private var videoPath: String? = null
    private var consentData: Intent? = null
    private var consentResult = RESULT_CANCELED
    private var serviceRequested = false
    private var foregroundReady = false
    private val handler = Handler(Looper.getMainLooper())
    private var serviceWaitStarted = 0L
    private val awaitService = object : Runnable {
        override fun run() {
            val path = videoPath ?: return
            if (!QaLensSessionRecorder.isAwaitingVideo(path)) { finish(); return }
            if (android.os.SystemClock.elapsedRealtime() - serviceWaitStarted >= 15_000) {
                QaLensSessionRecorder.onVideoConsentDenied(path, "HD service did not become ready. Try again or use frame recording.")
                finish(); return
            }
            handler.postDelayed(this, 100)
        }
    }

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val path = videoPath
        if (result.resultCode == RESULT_OK && data != null && path != null && QaLensSessionRecorder.isAwaitingVideo(path)) {
            consentData = data
            consentResult = result.resultCode
            startServiceWhenResumed()
        } else {
            QaLensSessionRecorder.onVideoConsentDenied(videoPath)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // On API 29+ the lifecycle RESUMED event arrives after onPostResume. Observe the event
        // itself rather than guessing that the Activity callback has already advanced Lifecycle.
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (foregroundReady) { finish(); return@LifecycleEventObserver }
                startServiceWhenResumed()
                if (serviceRequested && !isFinishing) {
                    serviceWaitStarted = android.os.SystemClock.elapsedRealtime()
                    handler.removeCallbacks(awaitService)
                    handler.post(awaitService)
                }
            }
        })
        videoPath = intent.getStringExtra(EXTRA_VIDEO_PATH)
        if (videoPath == null) { finish(); return }
        if (savedInstanceState != null) {
            @Suppress("DEPRECATION")
            consentData = savedInstanceState.getParcelable("consentData")
            consentResult = savedInstanceState.getInt("consentResult", RESULT_CANCELED)
            serviceRequested = savedInstanceState.getBoolean("serviceRequested")
            foregroundReady = savedInstanceState.getBoolean("foregroundReady")
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        if (mpm == null) { QaLensSessionRecorder.onVideoConsentDenied(videoPath); finish(); return }
        // API 34+ otherwise shows a "single app / entire screen" chooser — for a QA session
        // recording the whole screen is the point, so pre-select it (one less confusing dialog).
        val consent = if (android.os.Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(
                android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
            )
        } else {
            mpm.createScreenCaptureIntent()
        }
        runCatching { launcher.launch(consent) }
            .onFailure { QaLensSessionRecorder.onVideoConsentDenied(videoPath); finish() }
    }

    private fun startServiceWhenResumed() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || isFinishing || serviceRequested) return
        val data = consentData ?: return
        val path = videoPath ?: return
        if (!QaLensSessionRecorder.isAwaitingVideo(path)) { finish(); return }
        serviceRequested = true // Never reuse a one-shot consent token after recreation.
        serviceWaitStarted = android.os.SystemClock.elapsedRealtime()
        handler.post(awaitService)
        val ready = object : ResultReceiver(handler) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (isDestroyed || isFinishing) return
                handler.removeCallbacks(awaitService)
                foregroundReady = resultCode == QaLensProjectionService.FOREGROUND_READY
                if (!foregroundReady) {
                    QaLensSessionRecorder.onVideoConsentDenied(path, "HD service could not start. Try again or use frame recording.")
                }
                finish()
            }
        }
        runCatching { QaLensProjectionService.start(this, consentResult, data, path, ready) }
            .onFailure {
                handler.removeCallbacks(awaitService)
                QaLensSessionRecorder.onVideoConsentDenied(path,
                    "HD service launch failed (${it.javaClass.simpleName}): ${it.message}. Use frame recording.")
                finish()
            }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putParcelable("consentData", consentData)
        outState.putInt("consentResult", consentResult)
        outState.putBoolean("serviceRequested", serviceRequested)
        outState.putBoolean("foregroundReady", foregroundReady)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        handler.removeCallbacks(awaitService)
        super.onDestroy()
        if (!isChangingConfigurations && !foregroundReady && QaLensSessionRecorder.isAwaitingVideo(videoPath.orEmpty())) {
            QaLensSessionRecorder.onVideoConsentDenied(videoPath, "HD startup was interrupted. Try again or use frame recording.")
        }
    }

    companion object {
        const val EXTRA_VIDEO_PATH = "qalens.video_path"
    }
}
