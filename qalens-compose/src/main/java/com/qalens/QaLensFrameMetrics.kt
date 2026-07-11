package com.qalens

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import androidx.annotation.RequiresApi

/**
 * Captures per-frame render timings via [Window.OnFrameMetricsAvailableListener] (API 24+).
 * Feeds [FrameMetricsSample]s into [QaLens.appendFrameMetrics], which populates the
 * `QaLensUiState.frameMetrics` ring buffer → jank score + the `.sal` `performance.json` track.
 *
 * Jank = total render time > 16ms (missed 60Hz vsync); frozen = > 700ms (app appeared stuck).
 * On API < 24 this is a no-op (the listener doesn't exist).
 */
internal object QaLensFrameMetrics {

    @Volatile private var attached: Boolean = false
    private var currentActivity: android.app.Activity? = null

    fun attach(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        // If the activity changed (rotation, recreation), detach from the old one first.
        if (attached && currentActivity !== activity) {
            detach(currentActivity ?: return)
        }
        if (attached) return
        attached = true
        currentActivity = activity
        activity.window.addOnFrameMetricsAvailableListener(
            listener,
            Handler(Looper.getMainLooper())
        )
    }

    fun detach(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        if (!attached) return
        attached = false
        currentActivity = null
        runCatching { activity.window.removeOnFrameMetricsAvailableListener(listener) }
    }

    private val listener = @RequiresApi(Build.VERSION_CODES.N) object : Window.OnFrameMetricsAvailableListener {
        override fun onFrameMetricsAvailable(window: Window?, frameMetrics: FrameMetrics?, additionalData: Int) {
            val m = frameMetrics ?: return
            val total = m.getMetric(FrameMetrics.TOTAL_DURATION).toLong()
            if (total <= 0) return
            val layout = runCatching { m.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION).toLong() }.getOrDefault(0L)
            val draw = runCatching { m.getMetric(FrameMetrics.DRAW_DURATION).toLong() }.getOrDefault(0L)
            val gpu = runCatching { m.getMetric(FrameMetrics.GPU_DURATION).toLong() }.getOrDefault(0L)
            val jank = total > FrameMetricsSample.JANK_THRESHOLD_MS
            val frozen = total > FrameMetricsSample.FROZEN_THRESHOLD_MS
            // Only record frames worth recording: jank, frozen, or sampled (1-in-10) to bound volume.
            if (jank || frozen || total % 10 == 0L) {
                QaLens.appendFrameMetrics(
                    FrameMetricsSample(
                        totalMs = total, layoutMs = layout, drawMs = draw, gpuMs = gpu,
                        jank = jank, frozen = frozen
                    )
                )
            }
        }
    }
}
