package com.qalens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Feature-flag capture modes must be reflected HONESTLY in the `.sal` digest: the coverage
 * section has to distinguish "disabled by config" from "not installed" so an AI consuming the
 * archive never mistakes a gated track for an absent one.
 */
class QaLensCaptureFlagsTest {

    private val config = QaLensConfig()

    private fun digestWith(coverage: QaLensAnalysis.Coverage): String =
        QaLensAnalysis.digest(
            coverage = coverage,
            startMillis = 0L,
            endMillis = 1_000L,
            network = emptyList(),
            events = emptyList(),
            timeline = emptyList(),
            stateSamples = emptyList(),
            classification = null,
            config = config
        )

    @Test
    fun defaultsKeepCaptureEnabled() {
        val cfg = QaLensConfig()
        assertTrue(cfg.captureNetwork)
        assertTrue(cfg.captureLogs)
        assertTrue(!cfg.networkFromChucker)
        val b = QaLensConfig.Builder().build()
        assertTrue(b.captureNetwork && b.captureLogs && !b.networkFromChucker)
    }

    @Test
    fun networkDisabledByConfigIsStatedInNotes() {
        val json = digestWith(
            QaLensAnalysis.Coverage(
                hasFrames = true, hasVideo = false,
                networkInterceptorInstalled = true, networkCount = 0,
                logCount = 0, stateCount = 0,
                networkCaptureEnabled = false
            )
        )
        assertTrue(json.contains("networkCaptureEnabled\":false"), json)
        assertTrue(json.contains("DISABLED via QaLensConfig.captureNetwork"), json)
        // The old "not installed" note must NOT appear when capture is simply gated off.
        assertTrue(!json.contains("NOT installed"), json)
    }

    @Test
    fun logsDisabledByConfigIsStatedInNotes() {
        val json = digestWith(
            QaLensAnalysis.Coverage(
                hasFrames = true, hasVideo = false,
                networkInterceptorInstalled = true, networkCount = 1,
                logCount = 0, stateCount = 0,
                logCaptureEnabled = false
            )
        )
        assertTrue(json.contains("logCaptureEnabled\":false"), json)
        assertTrue(json.contains("DISABLED via QaLensConfig.captureLogs"), json)
    }

    @Test
    fun legacyChuckerModeDoesNotInventLiveTransactionForwarding() {
        val json = digestWith(
            QaLensAnalysis.Coverage(
                hasFrames = true, hasVideo = false,
                networkInterceptorInstalled = false, networkCount = 3,
                logCount = 0, stateCount = 0,
                networkFromChucker = true
            )
        )
        assertTrue(json.contains("networkFromChucker\":true"), json)
        assertTrue(json.contains("live transaction forwarding is unsupported"), json)
        assertTrue(!json.contains("NOT installed"), json)
    }

    @Test
    fun notInstalledNoteStillAppearsWhenEnabledAndAbsent() {
        val json = digestWith(
            QaLensAnalysis.Coverage(
                hasFrames = true, hasVideo = false,
                networkInterceptorInstalled = false, networkCount = 0,
                logCount = 0, stateCount = 0
            )
        )
        assertTrue(json.contains("NOT installed"), json)
    }
}
