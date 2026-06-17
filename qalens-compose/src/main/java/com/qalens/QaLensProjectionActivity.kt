package com.qalens

import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Transparent helper that requests the one-time MediaProjection screen-capture consent, then hands
 * the grant to [QaLensProjectionService]. Launched by [QaLensSessionRecorder] only when the app
 * opts into video recording via `QaLens.startRecording(video = true)`.
 */
class QaLensProjectionActivity : ComponentActivity() {

    private var videoPath: String? = null

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val path = videoPath
        if (result.resultCode == RESULT_OK && data != null && path != null) {
            QaLensProjectionService.start(this, result.resultCode, data, path)
        } else {
            QaLensSessionRecorder.onVideoConsentDenied()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        videoPath = intent.getStringExtra(EXTRA_VIDEO_PATH)
        if (videoPath == null) { finish(); return }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        if (mpm == null) { QaLensSessionRecorder.onVideoConsentDenied(); finish(); return }
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
            .onFailure { QaLensSessionRecorder.onVideoConsentDenied(); finish() }
    }

    companion object {
        const val EXTRA_VIDEO_PATH = "qalens.video_path"
    }
}
