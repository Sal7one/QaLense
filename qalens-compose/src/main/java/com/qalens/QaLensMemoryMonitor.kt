package com.qalens

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Debug

/**
 * Captures memory pressure events (B8): hooks [ComponentCallbacks2.onTrimMemory] /
 * [onLowMemory] at the application level and records the trim level as timeline events.
 * Also samples [Runtime] + [Debug] heap stats periodically, feeding [MemorySample]s into
 * `QaLensUiState.memorySamples` (capped at 200) and the `.sal` `memory.json` track.
 *
 * Registered in [QaLens.install]. OOMs and memory pressure are common, hard-to-reproduce bugs
 * that QaLens had zero visibility into before this.
 */
internal object QaLensMemoryMonitor {

    @Volatile private var registered = false
    private val runtime = Runtime.getRuntime()
    private var lastSampleMs = 0L
    private const val SAMPLE_INTERVAL_MS = 2000L

    private val callbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        override fun onLowMemory() {
            QaLens.event("low_memory", "onLowMemory — system is reclaiming memory")
            sample("LOW")
        }

        override fun onTrimMemory(level: Int) {
            // Only log meaningful levels (not the trivial TRIM_MEMORY_UI_HIDDEN).
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                QaLens.event("memory_trim", "level=$level (${describe(level)})")
                sample(describe(level))
            }
            // Always sample on trim so we see the heap state at the moment of pressure.
            if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) sample(describe(level))
        }
    }

    fun start(application: Application) {
        if (registered || !QaLens.config.value.enabled) return
        registered = true
        application.registerComponentCallbacks(callbacks)
    }

    fun stop(application: Application) {
        if (registered) application.unregisterComponentCallbacks(callbacks)
        registered = false
        lastSampleMs = 0L
    }

    /** Called by the recorder tick to sample heap stats during recordings (every ~2s). */
    fun maybeSample() {
        val now = System.currentTimeMillis()
        if (now - lastSampleMs < SAMPLE_INTERVAL_MS) return
        lastSampleMs = now
        sample(null)
    }

    private fun sample(trimLevel: String?) {
        if (!QaLens.config.value.enabled) return
        val total = runtime.totalMemory() / 1024
        val free = runtime.freeMemory() / 1024
        val native = runCatching { Debug.getNativeHeapAllocatedSize() / 1024 }.getOrDefault(0L)
        QaLens.appendMemorySample(MemorySample(totalKb = total, freeKb = free, nativeKb = native, trimLevel = trimLevel))
    }

    private fun describe(level: Int): String = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
        ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
        else -> "LEVEL_$level"
    }
}
