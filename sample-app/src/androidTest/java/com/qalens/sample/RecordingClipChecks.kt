package com.qalens.sample

import android.app.Instrumentation
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.StrictMode
import com.qalens.QaLens
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/** Synthetic evidence only. Video mode needs the real OS consent dialog to be approved externally. */
internal class RecordingClipChecks(private val test: Instrumentation) {
    fun denyVideo() {
        test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        test.waitForIdleSync()
        val root = File(test.targetContext.filesDir, "qalens/recordings")
        val before = root.listFiles()?.map { it.name }?.toSet().orEmpty()
        test.runOnMainSync { QaLens.clearLogs(); QaLens.configure { enabled = true; allowUnmaskedVideo = true }; QaLens.startRecording(true) }
        await(90_000, "Decline the real OS screen-sharing consent") { QaLens.state.value.events.any { it.message.contains("permission denied") } }
        check(!QaLens.state.value.isRecording && !QaLens.state.value.isSavingRecording)
        check(root.listFiles()?.none { it.extension == "sal" && it.name !in before } != false)
        test.runOnMainSync { QaLens.configure { allowUnmaskedVideo = false }; QaLens.startRecording() }
        check(QaLens.state.value.isRecording) { "Consent denial stranded recorder lifecycle" }
        Thread.sleep(1500)
        test.runOnMainSync { QaLens.stopRecording() }
        await(30_000, "Frame recording after denial could not save") { !QaLens.state.value.isSavingRecording }
    }

    fun run(video: Boolean, longSession: Boolean = false) {
        test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        test.waitForIdleSync()
        val root = File(test.targetContext.filesDir, "qalens/recordings")
        val before = root.listFiles()?.map { it.name }?.toSet().orEmpty()
        val originalVmPolicy = StrictMode.getVmPolicy()
        val contextViolations = java.util.concurrent.CopyOnWriteArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder()
            .detectIncorrectContextUse().penaltyDeath().penaltyListener(java.util.concurrent.Executor { it.run() }) {
                contextViolations += android.util.Log.getStackTraceString(it)
            }.build())
        try {
            test.runOnMainSync { QaLens.configure { enabled = true; allowUnmaskedVideo = video; recordingMaxDurationMinutes = 60 }; QaLens.startRecording(video) }
            await(90_000, "Capture did not begin; approve the OS video consent if testing video") { QaLens.state.value.isRecording }
            // Overflow keep-earliest logs; a late mark must still contain this recent failure.
            repeat(12_000) { QaLens.log("clip-burst-$it") }
            val waitMillis = if (longSession) 310_000L else 12_000L
            val until = System.currentTimeMillis() + waitMillis
            while (System.currentTimeMillis() < until) {
                check(QaLens.state.value.isRecording) { "Recording stopped before requested mark" }
                Thread.sleep(500)
            }
            test.runOnMainSync { QaLens.log("clip-recent-failure") }
            if (!longSession) markFromUi()
            test.runOnMainSync { QaLens.saveRecentClip(10, "Synthetic late bug") }
            Thread.sleep(800)
            check(QaLens.state.value.isRecording) { "Mark stopped the master recording" }
            test.runOnMainSync { QaLens.log("clip-after-mark-must-stay-out"); QaLens.stopRecording() }
            await(60_000, "Master and clip did not finish saving") { !QaLens.state.value.isSavingRecording && !QaLens.state.value.isRecording }
            val files = root.listFiles()?.filter { it.extension == "sal" && it.name !in before }.orEmpty()
            val master = files.single { it.name.startsWith("session_") }
            val clipFiles = files.filter { it.name.startsWith("clip_") }
            val clip = clipFiles.single { file -> ZipFile(file).use { JSONObject(text(it, "analysis.json")).getJSONObject("clip").getString("label") == "Synthetic late bug" } }
            if (!longSession) {
                val uiClips = clipFiles.filter { it != clip }.map { file -> ZipFile(file).use { JSONObject(text(it, "analysis.json")).getJSONObject("clip").getInt("requestedSeconds") } }
                check(uiClips.sorted() == listOf(10, 45)) { "Preset/custom menu did not export the chosen intervals: $uiClips" }
            }
            ZipFile(clip).use { zip ->
                val logs = JSONArray(text(zip, "logs.json"))
                val messages = (0 until logs.length()).map { logs.getJSONObject(it).getString("message") }
                check("clip-recent-failure" in messages) { "Recent logs were lost behind full-session budget" }
                check("clip-after-mark-must-stay-out" !in messages) { "Clip used save time instead of mark time" }
                val manifest = JSONObject(text(zip, "manifest.json"))
                val analysis = JSONObject(text(zip, "analysis.json"))
                check(analysis.getJSONObject("clip").getInt("requestedSeconds") == 10)
                check(analysis.getJSONObject("coverage").getJSONObject("recording").getString("policy") == "keep-latest-buffer")
                check(manifest.getLong("endMillis") - manifest.getLong("startMillis") in 1..30_000)
                if (video) verifyVideo(zip) else check(manifest.getJSONObject("frameIndex").length() > 0)
            }
            // Verify the UI-created clips too, not only the programmatic clip.
            if (video) (listOf(master) + clipFiles).forEach { file -> ZipFile(file).use { verifyVideo(it) } }
            if (longSession) ZipFile(master).use {
                val manifest = JSONObject(text(it, "manifest.json"))
                check(manifest.getLong("endMillis") - manifest.getLong("startMillis") > 300_000) { "Old five-minute cap remains" }
            }
            check(contextViolations.isEmpty()) { "Recording UI used a non-visual context: ${contextViolations.joinToString("\n")}" }
        } finally {
            StrictMode.setVmPolicy(originalVmPolicy)
            test.runOnMainSync { if (QaLens.state.value.isRecording) QaLens.stopRecording(); QaLens.configure { allowUnmaskedVideo = false } }
        }
    }
    private fun markFromUi() {
        val automation = test.uiAutomation
        val info = automation.serviceInfo
        val flags = info.flags
        info.flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        fun find(predicate: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean): android.view.accessibility.AccessibilityNodeInfo? {
            fun visit(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
                if (predicate(node)) return node
                for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it)?.let { found -> return found } }
                return null
            }
            return automation.windows.firstNotNullOfOrNull { it.root?.let(::visit) }
        }
        fun click(label: String) {
            await(10_000, "Recording control missing: $label") { find { it.text?.toString()?.equals(label, ignoreCase = true) == true || it.contentDescription?.toString()?.equals(label, ignoreCase = true) == true } != null }
            var node = checkNotNull(find { it.text?.toString()?.equals(label, ignoreCase = true) == true || it.contentDescription?.toString()?.equals(label, ignoreCase = true) == true })
            while (!node.isClickable && node.parent != null) node = node.parent
            check(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        }
        try {
            click("Save recent bug clip"); click("Last 10s")
            Thread.sleep(300)
            check(QaLens.state.value.isRecording)
            click("Save recent bug clip"); click("Custom duration…")
            await(10_000, "Custom duration input missing") { find { it.className?.toString() == "android.widget.EditText" } != null }
            val input = checkNotNull(find { it.className?.toString() == "android.widget.EditText" })
            val arguments = android.os.Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "45") }
            check(input.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
            click("Mark clip")
            check(QaLens.state.value.isRecording)
            // Stop later with a menu still visible; the SDK must dismiss it with the REC window.
            click("Save recent bug clip")
        } finally { info.flags = flags; automation.serviceInfo = info }
    }

    private fun verifyVideo(zip: ZipFile) {
        val entry = zip.getEntry("video.mp4") ?: error("Video fell back to frames; HD path is not fixed")
        val file = File(test.targetContext.cacheDir, "qalens-video-check.mp4")
        try {
            zip.getInputStream(entry).use { input -> file.outputStream().use(input::copyTo) }
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                val format = extractor.getTrackFormat(track)
                check(maxOf(format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT)) >= 960)
                extractor.selectTrack(track); check(extractor.sampleTime >= 0)
                check(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) { "Export starts without keyframe" }
                check(format.getLong(MediaFormat.KEY_DURATION) > 0)
            } finally { extractor.release() }
            val decoder = android.media.MediaMetadataRetriever()
            try {
                decoder.setDataSource(file.absolutePath)
                val frame = checkNotNull(decoder.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)) {
                    "Encoded master/clip had samples but could not decode a frame"
                }
                check(frame.width > 0 && frame.height > 0)
                frame.recycle()
            } finally { decoder.release() }
        } finally { file.delete() }
    }
    private fun text(zip: ZipFile, name: String): String {
        val bytes = zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }
        return if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte())
            GZIPInputStream(bytes.inputStream()).bufferedReader().use { it.readText() } else bytes.toString(Charsets.UTF_8)
    }
    private fun await(timeout: Long, message: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(100)
        check(condition()) { message }
    }
}
