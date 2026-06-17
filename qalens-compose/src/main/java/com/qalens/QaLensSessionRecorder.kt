package com.qalens

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference

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
    private val intervalMs = 1000L / FPS

    private val handler = Handler(Looper.getMainLooper())
    private var activityRef: WeakReference<Activity>? = null
    private var recording = false
    private var capturing = false
    private var videoMode = false
    // True when the floating system-window chip is the stop control (overlay fully hidden, clean
    // frames). False = fallback: overlay stays visible showing only the in-window REC chip, and
    // each frame capture hides the overlay for that single PixelCopy so frames stay clean.
    private var systemChipMode = false
    private var startMs = 0L

    /** True while a recording uses the floating system-window chip (overlay stays fully hidden). */
    val usesSystemChip: Boolean get() = systemChipMode
    private var frameCounter = 0
    private val frameIndex = linkedMapOf<Long, String>()
    private val stateSamples = mutableListOf<StateSample>()
    private var sessionDir: File? = null
    private var videoFile: File? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!recording) return
            sampleState()
            // Capture PixelCopy frames in BOTH modes. In video mode they're a SAFETY NET: if
            // MediaProjection produces an empty/unplayable video.mp4 (common on emulators and
            // locked-down encoders) the .sal still replays from frames instead of "no frames".
            if (frameCounter < MAX_FRAMES) {
                captureFrame()
            } else if (!videoMode) {
                QaLens.log("Recording hit ${MAX_FRAMES}-frame cap; stopping.")
                stop()
                return
            }
            handler.postDelayed(this, if (videoMode) 500L else intervalMs)
        }
    }

    fun start(activity: Activity, useVideo: Boolean = false) {
        if (recording) return
        activityRef = WeakReference(activity)
        recording = true
        videoMode = useVideo
        startMs = System.currentTimeMillis()
        frameCounter = 0
        frameIndex.clear()
        stateSamples.clear()
        sessionDir = File(activity.cacheDir, "qalens/rec_$startMs").apply { mkdirs() }
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
                .onFailure { QaLens.log("Could not launch video consent: ${it.message}"); onVideoConsentDenied() }
        } else {
            beginCapture(activity)
            QaLens.breadcrumb("● Recording started")
        }
    }

    /** Go live: hide the QaLens overlay, raise the stop chip, flip UI state, start the sampler. */
    private fun beginCapture(activity: Activity) {
        // The stop chip lives in its OWN window — overlay mode (draw-over-apps) or a permission-free
        // window attached to the activity — so the frame recorder never captures it and nothing
        // blinks per frame. The in-app QaLens overlay is hidden for the whole session.
        systemChipMode = QaLensSystemChip.canShow(activity)
        QaLensScreenCapture.setOverlayVisible(activity, false)
        if (systemChipMode) QaLensSystemChip.show(activity) else QaLensSystemChip.showInApp(activity)
        QaLens.setRecording(true)
        handler.post(tick)
    }

    fun stop() {
        if (!recording) return
        recording = false
        handler.removeCallbacks(tick)
        QaLensSystemChip.hide()
        restoreOverlay()
        QaLens.setRecording(false)
        QaLens.breadcrumb("■ Recording stopped")
        if (videoMode) {
            // Defer packaging until the service finalizes video.mp4 (onVideoComplete). The service
            // stop must never depend on a live activity — fall back to the app context.
            val context = activityRef?.get() ?: QaLens.currentActivity ?: QaLens.appContext
            if (context != null) QaLensProjectionService.stop(context)
            else { QaLens.log("No context to stop video service"); finalizeAndShare(null) }
        } else {
            finalizeAndShare(null)
        }
    }

    /** Abort an in-flight recording WITHOUT packaging or share sheet (Control Room panic path). */
    fun cancel() {
        if (!recording && sessionDir == null) return
        recording = false
        handler.removeCallbacks(tick)
        QaLensSystemChip.hide()
        restoreOverlay()
        if (videoMode) {
            (activityRef?.get() ?: QaLens.currentActivity ?: QaLens.appContext)
                ?.let { runCatching { QaLensProjectionService.stop(it) } }
        }
        videoMode = false
        QaLens.setRecording(false)
        sessionDir?.deleteRecursively()
        sessionDir = null
        QaLens.log("Recording discarded.")
    }

    /** Un-hide the overlay on whichever activity is alive — never strand QA with an invisible UI. */
    private fun restoreOverlay() {
        (QaLens.currentActivity ?: activityRef?.get())
            ?.let { QaLensScreenCapture.setOverlayVisible(it, true) }
    }

    private var videoStartMs: Long? = null

    // ── Video-mode callbacks (from QaLensProjectionService / Activity) ─────────
    /** Consent granted and the projection is live — NOW we reveal the recording UI. */
    fun onVideoStarted() {
        handler.post {
            if (!recording) return@post
            videoStartMs = System.currentTimeMillis()
            val activity = QaLens.currentActivity ?: activityRef?.get()
            if (activity != null) beginCapture(activity)
            else { QaLens.setRecording(true); handler.post(tick) }  // no activity (backgrounded)
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
            // The stop may have come from OUTSIDE QaLens (the OS "stop sharing" status chip, the
            // projection notification's Stop action, the system revoking the projection). In that
            // case stop() never ran — do its bookkeeping here, or QaLens stays stuck "recording".
            if (recording) {
                recording = false
                handler.removeCallbacks(tick)
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

    fun onVideoConsentDenied() {
        handler.post {
            if (!videoMode && !recording) return@post
            recording = false
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
        stateSamples += StateSample(
            timestampMillis = System.currentTimeMillis(),
            screenName = s.screen.screenName,
            route = s.screen.route,
            featureFlags = s.featureFlags,
            dataSources = s.dataSources
        )
    }

    private fun captureFrame() {
        if (capturing) return
        val activity = activityRef?.get() ?: return
        val dir = sessionDir ?: return
        capturing = true
        // Overlay is hidden for the whole session and the REC chip lives in a separate window
        // (PixelCopy can't see it) — so no per-frame toggling, no flashing.
        QaLensScreenCapture.captureFrame(activity, manageOverlay = false) { bmp ->
            try {
                if (bmp != null) {
                    val scaled = scale(bmp)
                    val ts = System.currentTimeMillis()
                    frameCounter++
                    val name = "frames/%06d.jpg".format(frameCounter)
                    FileOutputStream(File(dir, name)).use { scaled.compress(Bitmap.CompressFormat.JPEG, 60, it) }
                    frameIndex[ts] = name
                    if (scaled !== bmp) scaled.recycle()
                    bmp.recycle()
                }
            } catch (e: Exception) {
                QaLens.log("Frame capture failed: ${e.message}")
            } finally {
                capturing = false
            }
        }
    }

    private fun scale(bmp: Bitmap): Bitmap {
        if (bmp.width <= MAX_FRAME_WIDTH) return bmp
        val ratio = MAX_FRAME_WIDTH.toFloat() / bmp.width
        return Bitmap.createScaledBitmap(bmp, MAX_FRAME_WIDTH, (bmp.height * ratio).toInt(), true)
    }

    private fun finalizeAndShare(video: File?) {
        val dir = sessionDir ?: return
        val usingVideo = videoMode && video != null
        val activity = activityRef?.get() ?: QaLens.currentActivity
        val cfg = QaLens.config.value
        val s = QaLens.state.value
        val endMs = System.currentTimeMillis()

        val windowEvents = s.events.filter { it.timestampMillis >= startMs }
        val windowNetwork = s.networkEvents.filter { it.timestampMillis >= startMs }
        val timeline = TimelineMerger.merge(windowEvents, windowNetwork, cfg)

        // Derived insight over the recorded window (warnings are the at-stop snapshot).
        val buildIssues = s.buildSafety?.issues ?: emptyList()
        val score = ReleaseReadinessEngine.score(s.warnings, s.screen, windowNetwork, buildIssues, cfg.slowNetworkThresholdMs)
        val classification = BugClassifier.classify(windowNetwork, s.warnings, s.screen.history, cfg.slowNetworkThresholdMs, buildIssues)
        val repro = ReproStepGenerator.generate(timeline)

        val baseFiles = listOf(
            "manifest.json", "summary.json", "timeline.json", "network.json", "logs.json",
            "state.json", "analysis.json", "for_ai.md", "report.txt"
        )
        val mediaFiles = if (usingVideo) listOf("video.mp4") else frameIndex.values.toList()

        val manifest = SalManifest(
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
            frameIndex = frameIndex,
            files = baseFiles + mediaFiles,
            counts = mapOf(
                "frames" to frameIndex.size,
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

        // Machine-readable digest + self-describing guide → every .sal is AI-ready on arrival.
        val analysisJson = QaLensAnalysis.digest(
            coverage = QaLensAnalysis.Coverage(
                hasFrames = !usingVideo && frameIndex.isNotEmpty(),
                hasVideo = usingVideo,
                networkInterceptorInstalled = s.networkAvailable,
                networkCount = windowNetwork.size,
                logCount = windowEvents.size,
                stateCount = stateSamples.size
            ),
            startMillis = startMs,
            endMillis = endMs,
            network = windowNetwork,
            events = windowEvents,
            timeline = timeline,
            stateSamples = stateSamples,
            classification = classification,
            config = cfg
        )

        val texts = mapOf(
            "manifest.json" to SalTracks.manifest(manifest),
            "summary.json" to SalTracks.summary(score, classification, repro, cfg),
            "timeline.json" to SalTracks.timeline(timeline, cfg),
            "network.json" to SalTracks.network(windowNetwork, cfg),
            "logs.json" to SalTracks.logs(windowEvents, cfg),
            "state.json" to SalTracks.state(stateSamples, cfg),
            "analysis.json" to analysisJson,
            "for_ai.md" to QaLensAnalysis.aiGuide(),
            "report.txt" to QaLens.buildFullReport()
        )

        try {
            val cacheRoot = activity?.cacheDir ?: QaLens.appContext?.cacheDir ?: dir.parentFile
            val salDir = File(cacheRoot, "qalens").apply { mkdirs() }
            val target = File(salDir, "session_$startMs.sal")
            val extras = if (usingVideo) mapOf("video.mp4" to video!!) else emptyMap()
            QaLensSalWriter.write(target, texts, File(dir, "frames"), if (usingVideo) emptyList() else frameIndex.values.toList(), extras)
            val sizeNote = if (usingVideo) "video" else "${frameIndex.size} frames"
            QaLens.log("Recording saved: ${target.name} ($sizeNote, ${(endMs - startMs) / 1000}s)")
            videoMode = false
            trimRetention(salDir, dir)
            QaLens.refreshRecordings()
            if (activity != null) shareFile(activity, target)
        } catch (e: Exception) {
            QaLens.log("Failed to write .sal: ${e.message}")
        }
    }

    /** Frees space: deletes this session's media dir (now inside the .sal) and old `rec_*` dirs,
     *  and keeps only the newest [MAX_SAVED_SAL] `.sal` files. */
    private fun trimRetention(salDir: File, currentSessionDir: File) {
        runCatching {
            currentSessionDir.deleteRecursively()
            salDir.listFiles { f -> f.isDirectory && f.name.startsWith("rec_") }
                ?.forEach { it.deleteRecursively() }
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
