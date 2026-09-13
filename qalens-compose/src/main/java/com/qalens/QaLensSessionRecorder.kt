package com.qalens

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Records a QA session as a `.sal` file.
 *
 * Two capture modes:
 *  - **Frame** (default, permission-free): samples the screen via PixelCopy at a low frame rate.
 *  - **Video** (opt-in): a MediaProjection H.264 `video.mp4` via [QaLensProjectionService] (consent
 *    dialog + foreground service). Needs on-device validation.
 *
 * Both modes capture the redacted timeline / network / logs / state tracks and a derived summary,
 * then package everything (see docs/replay_backlog.md, R2 + R5).
 */
internal object QaLensSessionRecorder {

    private const val FPS = 2
    private const val MAX_FRAMES = 600          // ~5 min at 2fps
    private const val MAX_FRAME_WIDTH = 720
    private const val MAX_SAVED_SAL = 5
    private const val WATCHDOG_TIMEOUT_MS = 10_000L // A5: auto-cancel if no frame for 10s
    private const val WATCHDOG_TICK_MS = 2_000L       // A5: watchdog polls every 2s
    private val intervalMs = 1000L / FPS

    private val handler = Handler(Looper.getMainLooper())
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "qalens-save").apply { isDaemon = true }
    }
    private var activityRef: WeakReference<Activity>? = null
    private val lifecycle = RecordingLifecycle()
    private val recording: Boolean get() = lifecycle.phase == RecordingLifecycle.Phase.CAPTURING ||
        lifecycle.phase == RecordingLifecycle.Phase.AWAITING_CONSENT
    private var shareWhenSaved = true
    private var savingStarted = false
    private var capturing = false
    private var videoMode = false
    // True when the floating system-window chip is the stop control (overlay fully hidden, clean
    // frames). False = fallback: overlay stays visible showing only the in-window REC chip, and
    // each frame capture hides the overlay for that single PixelCopy so frames stay clean.
    private var systemChipMode = false
    private var startMs = 0L
    @Volatile private var lastFrameTimestamp = 0L

    /** True while a recording uses the floating system-window chip (overlay stays fully hidden). */
    val usesSystemChip: Boolean get() = systemChipMode
    private val mediaLock = Any()
    @Volatile private var frameCounter = 0
    private val frameIndex = linkedMapOf<Long, String>()
    @Volatile internal var evidence: RecordingEvidenceStore? = null
        private set
    private var capturedEvidence: RecordingEvidenceStore.Snapshot? = null
    private var stopMs = 0L
    private var sessionDir: File? = null
    private var videoFile: File? = null

    // A5: Watchdog — poll every 2s; auto-cancel the recording if no frame arrives for
    // WATCHDOG_TIMEOUT_MS (screen may be FLAG_SECURE, or capture stalled).
    private val watchdog = object : Runnable {
        override fun run() {
            if (!recording) return
            if (!videoMode && System.currentTimeMillis() - lastFrameTimestamp > WATCHDOG_TIMEOUT_MS) {
                val msg = "Watchdog: no frames captured for ${WATCHDOG_TIMEOUT_MS / 1000}s — saving captured evidence (screen may be secure, backgrounded, or capture stalled)"
                QaLens.log(msg)
                QaLens.pushError(ErrorKind.RECORDING, msg, retry = { QaLens.startRecording() })
                stop(share = false)
                return
            }
            handler.postDelayed(this, WATCHDOG_TICK_MS)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!recording) return
            if (!QaLens.config.value.enabled) { stop(share = false); return }
            QaLensMemoryMonitor.maybeSample()
            sampleState()
            // Capture PixelCopy frames in BOTH modes. In video mode they're a SAFETY NET: if
            // MediaProjection produces an empty/unplayable video.mp4 (common on emulators and
            // locked-down encoders) the .sal still replays from frames instead of "no frames".
            if (frameCounter < MAX_FRAMES && System.currentTimeMillis() - startMs < 300_000L) {
                captureFrame()
            } else {
                QaLens.log("Recording hit ${MAX_FRAMES}-frame cap; stopping.")
                stop()
                return
            }
            handler.postDelayed(this, if (videoMode) 500L else intervalMs)
        }
    }

    fun start(activity: Activity, useVideo: Boolean = false) {
        if (!QaLens.config.value.enabled) return
        if (useVideo && !QaLens.config.value.allowUnmaskedVideo) {
            QaLens.pushError(ErrorKind.RECORDING, "Video has no privacy masks. Use frame recording, or have the host explicitly enable allowUnmaskedVideo.")
            return
        }
        if (lifecycle.start(useVideo) == null) {
            QaLens.pushError(ErrorKind.RECORDING, "A recording is already active or still being saved.")
            return
        }
        activityRef = WeakReference(activity)
        videoMode = useVideo
        startMs = System.currentTimeMillis()
        lastFrameTimestamp = System.currentTimeMillis()
        capturing = false
        shareWhenSaved = true
        savingStarted = false
        frameCounter = 0
        frameIndex.clear()
        evidence = null
        capturedEvidence = null
        stopMs = 0L
        sessionDir = File(activity.cacheDir, "qalens/rec_${startMs}_${lifecycle.sessionId}").apply { mkdirs() }
        File(sessionDir, "frames").mkdirs()   // always — video mode keeps frames as a fallback
        videoFile = null
        videoStartMs = null

        if (useVideo) {
            // Video mode: do NOT touch the UI yet. The OS is about to show its "Cast / share your
            // screen?" consent dialog; hiding the overlay or showing the REC chip now would flash
            // the recording widget BEFORE the user has agreed (the reported bug). We go live only
            // when the projection actually starts — see onVideoStarted().
            val video = File(sessionDir, "video.mp4")
            videoFile = video
            val intent = Intent(activity, QaLensProjectionActivity::class.java)
                .putExtra(QaLensProjectionActivity.EXTRA_VIDEO_PATH, video.absolutePath)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { activity.startActivity(intent) }
                .onFailure { QaLens.log("Could not launch video consent: ${it.message}"); onVideoConsentDenied(video.absolutePath) }
        } else {
            beginCapture(activity)
            QaLens.breadcrumb("● Recording started")
        }
    }

    /** Go live: hide the QaLens overlay, raise the stop chip, flip UI state, start the sampler. */
    private fun beginCapture(activity: Activity) {
        startEvidence()
        // The stop chip lives in its OWN window — overlay mode (draw-over-apps) or a permission-free
        // window attached to the activity — so the frame recorder never captures it and nothing
        // blinks per frame. The in-app QaLens overlay is hidden for the whole session.
        systemChipMode = QaLensSystemChip.canShow(activity)
        QaLensScreenCapture.setOverlayVisible(activity, false)
        if (systemChipMode) QaLensSystemChip.show(activity) else QaLensSystemChip.showInApp(activity)
        QaLens.setRecording(true)
        handler.post(tick)
        handler.postDelayed(watchdog, WATCHDOG_TIMEOUT_MS) // A5: start watchdog
    }

    private fun startEvidence() {
        startMs = System.currentTimeMillis()
        evidence = RecordingEvidenceStore(startMs)
    }

    private fun freezeEvidence() {
        if (capturedEvidence != null) return
        val store = evidence ?: return
        capturedEvidence = store.close()
        stopMs = System.currentTimeMillis()
        evidence = null
    }

    fun stop(share: Boolean = true) {
        if (!recording) return
        if (lifecycle.phase == RecordingLifecycle.Phase.AWAITING_CONSENT) { cancel(); return }
        if (!lifecycle.beginSaving(lifecycle.sessionId)) return
        shareWhenSaved = share
        QaLens.setSavingRecording(true)
        handler.removeCallbacks(tick)
        handler.removeCallbacks(watchdog) // A5: stop watchdog
        QaLensSystemChip.hide()
        restoreOverlay()
        QaLens.setRecording(false)
        QaLens.breadcrumb("■ Recording stopped")
        freezeEvidence()
        if (videoMode) {
            // Defer packaging until the service finalizes video.mp4 (onVideoComplete). The service
            // stop must never depend on a live activity — fall back to the app context.
            val context = activityRef?.get() ?: QaLens.currentActivity ?: QaLens.appContext
            val stoppingId = lifecycle.sessionId
            handler.postDelayed({
                if (lifecycle.sessionId == stoppingId && lifecycle.phase == RecordingLifecycle.Phase.SAVING && !savingStarted) {
                    QaLens.log("Video service did not finish in time; saving captured frames.")
                    context?.let { runCatching { it.stopService(Intent(it, QaLensProjectionService::class.java)) } }
                    finalizeAndShare(null)
                }
            }, 5_000L)
            if (context != null) runCatching { QaLensProjectionService.stop(context) }
                .onFailure { QaLens.log("Video stop failed; saving captured frames"); finalizeAndShare(null) }
            else { QaLens.log("No context to stop video service"); finalizeAndShare(null) }
        } else {
            finalizeAndShare(null)
        }
    }

    /** Abort an in-flight recording WITHOUT packaging or share sheet (Control Room panic path). */
    fun cancel() {
        if (lifecycle.phase == RecordingLifecycle.Phase.SAVING) return // Never delete media under the writer.
        if (!recording && sessionDir == null) return
        lifecycle.cancel()
        evidence?.close()
        evidence = null
        capturedEvidence = null
        handler.removeCallbacks(tick)
        handler.removeCallbacks(watchdog) // A5: stop watchdog
        QaLensSystemChip.hide()
        restoreOverlay()
        val discarded = sessionDir
        sessionDir = null
        videoFile = null
        if (videoMode) {
            (activityRef?.get() ?: QaLens.currentActivity ?: QaLens.appContext)
                ?.let { runCatching { QaLensProjectionService.stop(it) } }
        }
        videoMode = false
        QaLens.setRecording(false)
        discarded?.deleteRecursively()
        QaLens.log("Recording discarded.")
    }

    /** A5: true when a frame/video sample was captured within [withinMs] ms. */
    internal fun hasRecentFrames(withinMs: Long): Boolean =
        lastFrameTimestamp > 0 && System.currentTimeMillis() - lastFrameTimestamp <= withinMs

    /** Un-hide the overlay on whichever activity is alive — never strand QA with an invisible UI. */
    private fun restoreOverlay() {
        (QaLens.currentActivity ?: activityRef?.get())
            ?.let { QaLensScreenCapture.setOverlayVisible(it, true) }
    }

    private var videoStartMs: Long? = null

    // ── Video-mode callbacks (from QaLensProjectionService / Activity) ─────────
    /** Consent granted and the projection is live — NOW we reveal the recording UI. */
    fun isAwaitingVideo(path: String): Boolean =
        QaLens.config.value.enabled && QaLens.config.value.allowUnmaskedVideo &&
            lifecycle.phase == RecordingLifecycle.Phase.AWAITING_CONSENT && videoFile?.absolutePath == path

    fun onVideoStarted(path: String) {
        handler.post {
            if (!isAwaitingVideo(path) || videoFile?.absolutePath != path || !lifecycle.activate(lifecycle.sessionId)) return@post
            videoStartMs = System.currentTimeMillis()
            val activity = QaLens.currentActivity ?: activityRef?.get()
            if (activity != null) beginCapture(activity)
            else { startEvidence(); QaLens.setRecording(true); handler.post(tick) }  // no activity (backgrounded)
            QaLens.breadcrumb("● Recording started (video)")
            QaLens.log("Screen recording started")
        }
    }

    /**
     * [videoOk] is false when MediaRecorder.stop() threw (no frames were ever encoded) — common on
     * emulators / restricted encoders. In that case we discard the broken video and replay the
     * .sal from the PixelCopy frames captured alongside it, so QA never gets a "no frames" session.
     */
    fun onVideoComplete(file: File, videoOk: Boolean) {
        handler.post {
            if (file != videoFile || sessionDir == null) return@post
            // The stop may have come from OUTSIDE QaLens (the OS "stop sharing" status chip, the
            // projection notification's Stop action, the system revoking the projection). In that
            // case stop() never ran — do its bookkeeping here, or QaLens stays stuck "recording".
            if (recording) {
                if (!lifecycle.beginSaving(lifecycle.sessionId)) return@post
                QaLens.setSavingRecording(true)
                handler.removeCallbacks(tick)
                handler.removeCallbacks(watchdog)
                QaLensSystemChip.hide()
                restoreOverlay()
                QaLens.setRecording(false)
                QaLens.breadcrumb("■ Recording stopped (system)")
            }
            val usableVideo = file.takeIf { videoOk && it.exists() && it.length() > 4096 }
            if (usableVideo == null && frameIndex.isNotEmpty()) {
                QaLens.log("HD video unavailable — saving from ${frameIndex.size} captured frames instead.")
            }
            finalizeAndShare(usableVideo)
        }
    }

    fun onVideoConsentDenied(path: String?) {
        handler.post {
            if (path == null || lifecycle.phase != RecordingLifecycle.Phase.AWAITING_CONSENT || videoFile?.absolutePath != path) return@post
            lifecycle.cancel()
            evidence?.close()
            evidence = null
            capturedEvidence = null
            videoMode = false
            handler.removeCallbacks(tick)
            QaLensSystemChip.hide()
            restoreOverlay()
            QaLens.setRecording(false)
            QaLens.log("Screen recording permission denied — recording cancelled.")
            sessionDir?.deleteRecursively()
            sessionDir = null
        }
    }

    private fun sampleState() {
        val s = QaLens.state.value
        evidence?.state(StateSample(
            timestampMillis = System.currentTimeMillis(),
            screenName = s.screen.screenName,
            route = s.screen.route,
            featureFlags = s.featureFlags,
            dataSources = s.dataSources
        ))
    }

    private fun captureFrame() {
        if (capturing) return
        val activity = QaLens.currentActivity ?: return
        activityRef = WeakReference(activity)
        val id = lifecycle.sessionId
        val dir = sessionDir ?: return
        capturing = true
        // Overlay is hidden for the whole session and the REC chip lives in a separate window
        // (PixelCopy can't see it) — so no per-frame toggling, no flashing.
        QaLensScreenCapture.captureFrame(activity, manageOverlay = false) { bmp ->
            if (bmp == null) { capturing = false; return@captureFrame }
            // Serialize media writes with archive writes, but never compress or wait for disk on main.
            writer.execute {
                var scaled: Bitmap? = null
                try {
                    if (!lifecycle.acceptsFrame(id) || !QaLens.config.value.enabled) return@execute
                    scaled = scale(bmp)
                    val ts = System.currentTimeMillis()
                    val name = "frames/%06d.jpg".format(frameCounter + 1)
                    val file = File(dir, name)
                    FileOutputStream(file).use { check(scaled.compress(Bitmap.CompressFormat.JPEG, 60, it)) }
                    synchronized(mediaLock) {
                        if (lifecycle.acceptsFrame(id) && sessionDir == dir && QaLens.config.value.enabled) {
                            frameCounter++
                            frameIndex[ts] = name
                            lastFrameTimestamp = ts
                        } else file.delete()
                    }
                } catch (failure: Exception) {
                    QaLens.log("Frame capture failed: ${failure.message}")
                } finally {
                    if (scaled !== bmp) scaled?.recycle()
                    bmp.recycle()
                    handler.post { if (id == lifecycle.sessionId) capturing = false }
                }
            }
        }
    }

    private fun scale(bmp: Bitmap): Bitmap {
        if (bmp.width <= MAX_FRAME_WIDTH) return bmp
        val ratio = MAX_FRAME_WIDTH.toFloat() / bmp.width
        return Bitmap.createScaledBitmap(bmp, MAX_FRAME_WIDTH, (bmp.height * ratio).toInt(), true)
    }

    private fun finalizeAndShare(video: File?) = finalize(video, share = shareWhenSaved)

    /** R10: finalize an in-flight recording on crash — saves the .sal WITHOUT the share sheet. */
    fun autoFinalize(crash: QaLensCrash? = null) {
        if (!recording || sessionDir == null) return
        if (!lifecycle.beginSaving(lifecycle.sessionId)) return
        handler.removeCallbacks(tick)
        handler.removeCallbacks(watchdog)
        // Video.mp4 was not stopped cleanly on a crash; finalize from the PixelCopy safety-net frames.
        runCatching { finalize(video = null, share = false, crash = crash) }
            .onFailure { QaLens.log("Recording auto-finalize failed: ${it.message}") }
    }

    private fun finalize(video: File?, share: Boolean, crash: QaLensCrash? = null) {
        val dir = sessionDir ?: return
        if (savingStarted) return
        savingStarted = true
        val usingVideo = videoMode && video != null
        val activity = activityRef?.get() ?: QaLens.currentActivity
        val cfg = QaLens.config.value
        val id = lifecycle.sessionId
        freezeEvidence()
        val captured = capturedEvidence ?: return
        capturedEvidence = null
        val endMs = stopMs
        val startMs = this.startMs
        val frameIndex = synchronized(mediaLock) { this.frameIndex.toMap() }
        val stateSamples = captured.stateSamples
        val videoStartMs = this.videoStartMs
        if (Looper.myLooper() == Looper.getMainLooper()) QaLensFrameMetrics.flushPending()
        val s = RecordingWindow.slice(captured.applyTo(QaLens.state.value), startMs, endMs)
        val cacheRoot = activity?.cacheDir ?: QaLens.appContext?.cacheDir ?: dir.parentFile
        val save = Runnable {
            try {
                val windowEvents = s.events.filter { it.timestampMillis >= startMs }
                val windowNetwork = s.networkEvents.filter { it.timestampMillis >= startMs }
                val timeline = TimelineMerger.merge(windowEvents, windowNetwork, cfg)

                // Derived insight over the recorded window (warnings are the at-stop snapshot).
                val buildIssues = s.buildSafety?.issues ?: emptyList()
                val score = ReleaseReadinessEngine.score(s.warnings, s.screen, windowNetwork, buildIssues, cfg.slowNetworkThresholdMs, s.frameMetrics)
                val classification = BugClassifier.classify(windowNetwork, s.warnings, s.screen.history, cfg.slowNetworkThresholdMs, buildIssues)
                val repro = ReproStepGenerator.generate(timeline)

                // Machine-readable digest + self-describing guide → every .sal is AI-ready on arrival.
                val sessionCrashes = s.crashes
                    .filter { it.timestampMillis in startMs..endMs }
                val sessionFrameMetrics = s.frameMetrics.filter { it.timestampMillis in startMs..endMs }
                val sessionConnectivity = s.connectivityTransitions.filter { it.timestampMillis in startMs..endMs }
                val analysisJson = QaLensAnalysis.digest(
                    coverage = QaLensAnalysis.Coverage(
                        hasFrames = !usingVideo && frameIndex.isNotEmpty(),
                        hasVideo = usingVideo,
                        networkInterceptorInstalled = s.networkAvailable,
                        networkCount = windowNetwork.size,
                        logCount = windowEvents.size,
                        stateCount = stateSamples.size,
                        crashCount = sessionCrashes.size,
                        frameMetricsCount = sessionFrameMetrics.size,
                        connectivityCount = sessionConnectivity.size,
                        networkCaptureEnabled = cfg.captureNetwork,
                        logCaptureEnabled = cfg.captureLogs,
                        networkFromChucker = false,
                        networkSources = s.networkSources.toList(),
                        recordingRetention = captured.retention
                    ),
                    startMillis = startMs,
                    endMillis = endMs,
                    network = windowNetwork,
                    events = windowEvents,
                    timeline = timeline,
                    stateSamples = stateSamples,
                    classification = classification,
                    config = cfg,
                    crashes = sessionCrashes,
                    frameMetrics = sessionFrameMetrics,
                    connectivityTransitions = sessionConnectivity
                )

                // Every track except manifest.json (whose checksum depends on the file list — keep it out
                // of files[] to avoid a self-referential crc32).
                val texts = mapOf(
                    "summary.json" to SalTracks.summary(score, classification, repro, cfg),
                    "timeline.json" to SalTracks.timeline(timeline, cfg),
                    "network.json" to SalTracks.network(windowNetwork, cfg),
                    "logs.json" to SalTracks.logs(windowEvents, cfg),
                    "state.json" to SalTracks.state(stateSamples, cfg),
                    "crashes.json" to SalTracks.crashes(sessionCrashes, cfg),
                    "performance.json" to SalTracks.performance(sessionFrameMetrics, cfg),
                    "connectivity.json" to SalTracks.connectivity(sessionConnectivity),
                    "memory.json" to SalTracks.memory(s.memorySamples.filter { it.timestampMillis in startMs..endMs }),
                    "marks.json" to SalTracks.marks(s.bookmarks.filter { it.timestampMillis in startMs..endMs }),
                    "analysis.json" to analysisJson,
                    "for_ai.md" to QaLensAnalysis.aiGuide(),
                    "report.txt" to (captured.retention.notes().takeIf { it.isNotEmpty() }
                        ?.joinToString("\n", prefix = "Recording coverage:\n", postfix = "\n\n") ?: "") + QaLensReports.full(EvidenceBuilder.build(
                        snapshot = InspectionSnapshot(screen = s.screen, device = s.device, nodes = s.nodes,
                            warnings = s.warnings, testTags = s.nodes.mapNotNull { it.testTag }, events = windowEvents),
                        network = windowNetwork, config = cfg, featureFlags = s.featureFlags,
                        networkInterceptorInstalled = s.networkAvailable, slowThresholdMs = cfg.slowNetworkThresholdMs,
                        dataSources = s.dataSources, frameMetrics = sessionFrameMetrics
                    ), cfg)
                )

                val frameRelPaths = if (usingVideo) emptyList() else frameIndex.values.toList()
                val extras = if (usingVideo) mapOf("video.mp4" to video!!) else emptyMap()

                // R9 v2: compute per-entry checksums (of uncompressed content) before building the manifest.
                val framesDir = File(dir, "frames")
                val fileEntries = buildFileEntries(texts, framesDir, frameRelPaths, extras)

                val manifest = SalManifest(
                    formatVersion = 2,
                    createdAtMillis = endMs,
                    appName = s.device.appName,
                    appVersion = s.device.appVersion,
                    buildVariant = s.device.buildVariant,
                    environment = s.device.environment,
                    gitSha = s.device.gitSha,
                    device = "${s.device.manufacturer} ${s.device.deviceModel}",
                    androidVersion = s.device.androidVersion,
                    startMillis = startMs,
                    endMillis = endMs,
                    fps = if (usingVideo) 30 else FPS,
                    frameIndex = if (usingVideo) emptyMap() else frameIndex,
                    files = fileEntries.map { it.name },
                    counts = mapOf(
                        "frames" to if (usingVideo) 0 else frameIndex.size,
                        "network" to windowNetwork.size,
                        "logs" to windowEvents.size,
                        "timeline" to timeline.size
                    ),
                    videoFile = if (usingVideo) "video.mp4" else null,
                    videoStartMillis = if (usingVideo) videoStartMs else null,
                    sessionId = java.util.UUID.randomUUID().toString(),
                    sdkInt = s.device.sdkVersion,
                    locale = java.util.Locale.getDefault().toString(),
                    timezone = java.util.TimeZone.getDefault().id,
                    screenWidthDp = s.device.screenWidthDp,
                    screenHeightDp = s.device.screenHeightDp,
                    density = s.device.density,
                    fontScale = s.device.fontScale
                )
                val manifestJson = SalTracks.manifest(manifest, fileEntries)

                val salDir = File(cacheRoot, "qalens").apply { mkdirs() }
                val target = File(salDir, "session_$startMs.sal")
                writeSalZip(target, manifestJson, texts, framesDir, frameRelPaths, extras)
                val sizeNote = if (usingVideo) "video" else "${frameIndex.size} frames"
                QaLens.log("Recording saved: ${target.name} ($sizeNote, ${(endMs - startMs) / 1000}s)")
                trimRetention(salDir, dir)
                handler.post {
                    videoMode = false
                    sessionDir = null
                    videoFile = null
                    lifecycle.finish(id)
                    QaLens.setSavingRecording(false)
                    QaLens.refreshRecordings()
                    if (share) (QaLens.currentActivity ?: activity)?.let { shareFile(it, target) }
                }
            } catch (e: Exception) {
                handler.post {
                    lifecycle.finish(id)
                    QaLens.setSavingRecording(false)
                    sessionDir = null // Keep source media on disk for recovery.
                    QaLens.pushError(ErrorKind.RECORDING, "Failed to save recording; captured files retained: ${e.message}")
                }
            }
        }
        // A crash must finish before the process dies; normal saves never block the UI thread.
        if (crash != null) save.run() else writer.execute(save)
    }

    private fun fileCrc32(file: File): String {
        val crc = java.util.zip.CRC32()
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                crc.update(buffer, 0, count)
            }
        }
        return crc.value.toString(16).padStart(8, '0')
    }

    /** R9: v2 file entries — crc32 of uncompressed content; compressed=true only for JSON tracks. */
    private fun buildFileEntries(
        texts: Map<String, String>,
        framesDir: File,
        frameRelPaths: List<String>,
        extraFiles: Map<String, File>
    ): List<SalFileEntry> {
        val entries = mutableListOf<SalFileEntry>()
        texts.forEach { (path, content) ->
            val bytes = content.toByteArray(Charsets.UTF_8)
            entries += SalFileEntry(path, SalTracks.crc32Hex(bytes), path.endsWith(".json"))
        }
        frameRelPaths.forEach { rel ->
            val f = File(framesDir.parentFile, rel)
            if (f.exists()) entries += SalFileEntry(rel, fileCrc32(f), false)
        }
        extraFiles.forEach { (rel, f) ->
            if (f.exists()) entries += SalFileEntry(rel, fileCrc32(f), false)
        }
        return entries
    }

    /** R9: write the v2 .sal ZIP — gzip JSON tracks + manifest; frames/video stay plain. */
    private fun writeSalZip(
        target: File,
        manifestJson: String,
        texts: Map<String, String>,
        framesDir: File,
        frameRelPaths: List<String>,
        extraFiles: Map<String, File>
    ) {
        val pending = File(target.parentFile, target.name + ".partial")
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(pending))).use { zip ->
                // manifest.json first, gzipped like any other JSON track.
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(SalTracks.gzip(manifestJson))
                zip.closeEntry()
                texts.forEach { (path, content) ->
                    val bytes = content.toByteArray(Charsets.UTF_8)
                    zip.putNextEntry(ZipEntry(path))
                    if (path.endsWith(".json")) zip.write(SalTracks.gzip(content)) else zip.write(bytes)
                    zip.closeEntry()
                }
                frameRelPaths.forEach { rel ->
                    val f = File(framesDir.parentFile, rel)
                    if (f.exists()) {
                        zip.putNextEntry(ZipEntry(rel))
                        FileInputStream(f).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                extraFiles.forEach { (rel, f) ->
                    if (f.exists()) {
                        zip.putNextEntry(ZipEntry(rel))
                        FileInputStream(f).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }

            check(pending.renameTo(target)) { "Could not publish saved recording" }
        } finally { pending.delete() }
    }

    /** Frees space: deletes this session's media dir only after a successful save,
     *  and keeps only the newest [MAX_SAVED_SAL] `.sal` files. */
    private fun trimRetention(salDir: File, currentSessionDir: File) {
        runCatching {
            currentSessionDir.deleteRecursively()
            salDir.listFiles { f -> f.isFile && f.name.endsWith(".sal") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_SAVED_SAL)
                ?.forEach { it.delete() }
        }
    }

    internal fun shareFile(context: android.content.Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".qalens.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "QaLens session recording (.sal) — open in the QaLens Player.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "Share .sal recording")
            if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            QaLens.log("Share .sal failed: ${e.message}")
        }
    }

    /** Directory holding saved `.sal` files (and transient `rec_*` frame dirs). */
    internal fun recordingsDir(context: android.content.Context): File =
        File(context.cacheDir, "qalens").apply { mkdirs() }
}
