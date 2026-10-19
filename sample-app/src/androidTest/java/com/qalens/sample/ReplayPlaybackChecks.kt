package com.qalens.sample

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
import androidx.media3.ui.PlayerView
import com.qalens.QaLens
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Real controls, scrolling and rendered pixels against synthetic frames/video; no capture consent. */
internal class ReplayPlaybackChecks(private val test: Instrumentation) {
    private val root = File(test.targetContext.cacheDir, "qalens/replay-test-${java.util.UUID.randomUUID()}")
    private var activity: Activity? = null
    private val start = 1_700_000_000_000L

    fun run() {
        val enabled = QaLens.config.value.enabled
        test.runOnMainSync { QaLens.configure { this.enabled = false } }
        check(root.mkdirs())
        try {
            open("frames", video = false)
            seek(4_500)
            await("Frame timeline did not follow seek") { currentEvent("Replay event 22") }
            await("Frame seek did not show the matching captured frame") { dominant(Color.BLUE) }
            click("State")
            await("State did not follow frame seek") { find("Screen: screen-4") != null }
            click("Timeline")
            verifyTransportAndFollow()
            close()

            open("video", video = true)
            await("Video metadata did not prepare") { mediaDuration() in 5_900..6_100 }
            await("Consent preroll was presented as video") { find("No video at this time.", contains = true) != null }
            seek(4_500) // session 4.5s = video 2.5s (green)
            await("Video seek lost the consent offset") { kotlin.math.abs(mediaPosition() - 2_500) <= 100 }
            await("Decoded video did not match the seek") { dominant(Color.GREEN) }
            click("Next event")
            await("Next event did not seek forward") { timeMs() == 4_600L && currentEvent("Replay event 23") }
            click("Previous event")
            await("Previous event did not seek backward") { timeMs() == 4_400L && currentEvent("Replay event 22") }
            click("First error")
            await("Error navigation did not seek") { timeMs() == 5_000L && currentEvent("Replay event 25") }
            click("Network")
            await("Network did not follow video seek") { currentEvent("GET https://replay.test/10") }
            click("Logs")
            await("Logs did not follow video seek") { currentEvent("replay-log-25") }
            click("State")
            await("State did not follow video seek") { find("Screen: screen-5") != null }
            click("Timeline")
            seek(6_500) // video 4.5s (blue)
            await("Forward seek did not decode blue video") { dominant(Color.BLUE) }
            seek(2_500) // backward video seek 0.5s (red)
            await("Backward seek left a stale video frame") { dominant(Color.RED) }
            click("Fullscreen")
            await("Fullscreen surface lost the decoded video") { dominant(Color.RED) }
            check(test.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            await("Fullscreen Back did not preserve position") { timeMs() == 2_500L && find("‹ Close") != null }
            await("Restored video surface is blank/stale") { dominant(Color.RED) }
            verifyBackgroundPause()
            seek(7_500)
            click("Play recording")
            await("Video stopped playback before trailing evidence", 6_000) {
                timeMs() >= 9_000 && find("Pause recording") != null && currentEventAtPlayhead()
            }
            click("Pause recording")
            await("Post-video coverage was not disclosed") { find("Video ended at", contains = true) != null }
            verifyReplayFromEnd()
            click("Play recording")
            close()

            open("legacy", video = true, legacy = true)
            await("Legacy video metadata did not prepare") { mediaDuration() in 5_900..6_100 }
            seek(8_500) // legacy aligns video end to 12s: video 2.5s (green)
            await("Legacy end alignment is incorrect") { mediaPosition() in 2_400..2_600 && dominant(Color.GREEN) }
            close()

            open("invalid-video", video = true, invalid = true)
            await("Decoder failure was hidden from QA") { find("Video could not be played", contains = true) != null }
            click("Play recording")
            await("Failed video blocked evidence playback") { timeMs() >= 600 && currentEventAtPlayhead() && find("Pause recording") != null }
            click("Pause recording")
            close()
        } catch (failure: Throwable) {
            test.uiAutomation.takeScreenshot()?.let { bitmap ->
                try { File(test.targetContext.cacheDir, "qalens-replay-test-failure.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { bitmap.recycle() }
            }
            throw failure
        } finally {
            test.runOnMainSync { activity?.finish(); QaLens.configure { this.enabled = enabled } }
            root.deleteRecursively()
        }
    }

    private fun verifyTransportAndFollow() {
        seek(0)
        click("Play recording")
        val before = SystemClock.elapsedRealtime()
        await("Frame playback did not advance in elapsed time", 3_000) { timeMs() >= 1_200 && currentEventAtPlayhead() }
        check(kotlin.math.abs(timeMs() - (SystemClock.elapsedRealtime() - before)) <= 400) { "Frame playback drifted from real elapsed time" }
        click("Pause recording")
        val paused = timeMs(); Thread.sleep(450)
        check(timeMs() == paused) { "Pause did not freeze the timeline" }
        seek(6_000)
        await("Timeline current event is outside the visible viewport") { currentEvent("Replay event 30") }
        val pane = checkNotNull(find("Timeline events"))
        val bounds = Rect().also(pane::getBoundsInScreen)
        swipe(bounds.centerX().toFloat(), (bounds.bottom - 12).toFloat(), (bounds.top + 12).toFloat())
        await("Manual browsing was overridden by auto-follow") { find("Follow events")?.stateDescription?.toString() == "Off" }
        click("Play recording") // starting playback restores following
        await("Play did not resume following") { find("Follow events")?.stateDescription?.toString() == "On" }
        val browsing = Rect().also(checkNotNull(find("Timeline events"))::getBoundsInScreen)
        swipe(browsing.centerX().toFloat(), (browsing.bottom - 12).toFloat(), (browsing.top + 12).toFloat())
        await("Dragging during playback did not allow browsing") { find("Follow events")?.stateDescription?.toString() == "Off" }
        val running = timeMs(); Thread.sleep(400)
        check(timeMs() > running) { "Browsing events paused media playback" }
        click("Follow events")
        await("Follow did not return to the current event") { currentEventAtPlayhead() }
        val slider = Rect().also(checkNotNull(find("Recording position"))::getBoundsInScreen)
        drag(slider.left + slider.width() * 0.35f, slider.centerY().toFloat(),
            slider.left + slider.width() * 0.60f, slider.centerY().toFloat())
        await("Scrubbing interrupted Play or lost event follow") {
            timeMs() in 6_500..8_500 && find("Pause recording") != null && currentEventAtPlayhead()
        }
        click("Pause recording")
        seek(4_000)
        await("Upcoming row is missing") { find("Replay event 21", contains = true)?.isVisibleToUser == true }
        click("Replay event 21", contains = true)
        await("Event tap did not seek media/timeline") { timeMs() == 4_200L && currentEvent("Replay event 21") }
        verifyReplayFromEnd()
    }

    private fun verifyReplayFromEnd() {
        seek(12_000)
        click("Play recording")
        await("Play at end did not restart the session") { timeMs() in 0..900 && find("Pause recording") != null }
        click("Pause recording")
    }

    private fun verifyBackgroundPause() {
        click("Play recording")
        await("Video did not play") { mediaPlaying() && timeMs() >= 2_700 }
        check(test.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
        await("Video kept playing in background") { !mediaPlaying() }
        val position = mediaPosition(); Thread.sleep(450)
        check(mediaPosition() == position) { "Background pause did not freeze the decoder" }
        test.targetContext.startActivity(Intent().setClassName(test.targetContext, "com.qalens.replay.QaLensPlayerActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        await("Returning to replay autoplayed/reset the session") { find("Play recording") != null && timeMs() >= 2_700 }
    }

    private fun open(name: String, video: Boolean, legacy: Boolean = false, invalid: Boolean = false) {
        val archive = fixture(name, video, legacy, invalid)
        val uri = FileProvider.getUriForFile(test.targetContext, test.targetContext.packageName + ".qalens.fileprovider", archive)
        activity = test.startActivitySync(Intent(Intent.ACTION_VIEW).setClassName(test.targetContext, "com.qalens.replay.QaLensPlayerActivity")
            .setDataAndType(uri, "application/octet-stream").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        await("Synthetic recording did not open") { find("Playback time") != null }
    }
    private fun close() {
        click("‹ Close")
        await("Replay Close did not finish") { activity?.isFinishing == true }
        test.waitForIdleSync(); activity = null
    }

    private fun fixture(name: String, video: Boolean, legacy: Boolean, invalid: Boolean): File {
        val manifest = JSONObject().put("formatVersion", 1).put("startMillis", start).put("endMillis", start + 12_000)
            .put("app", JSONObject().put("name", "Synthetic replay $name"))
        val frames = JSONObject()
        val archive = File(root, "$name.sal")
        ZipOutputStream(archive.outputStream()).use { zip ->
            fun put(path: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() }
            if (video) {
                manifest.put("video", "video.mp4")
                if (!legacy) manifest.put("videoStartMillis", start + 2_000)
                put("video.mp4", if (invalid) "not video".toByteArray() else test.context.assets.open("replay-clock.mp4").use { it.readBytes() })
            } else {
                for (i in 0..12) {
                    val bitmap = Bitmap.createBitmap(160, 240, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(when (i) { in 0..1 -> Color.RED; in 2..3 -> Color.GREEN; else -> Color.BLUE })
                    val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
                    bitmap.recycle()
                    val path = "frames/$i.jpg"; frames.put((start + i * 1_000).toString(), path); put(path, bytes)
                }
                manifest.put("frameIndex", frames)
            }
            put("manifest.json", manifest.toString().toByteArray())
            val timeline = JSONArray(); val logs = JSONArray(); val network = JSONArray(); val state = JSONArray()
            // Reverse input deliberately: imported track order must not control synchronization.
            for (i in 60 downTo 0) {
                timeline.put(JSONObject().put("ts", start + i * 200).put("title", "Replay event $i").put("isError", i == 25)
                    .apply { if (i == 25) put("detail", "Long synthetic details " + "preview ".repeat(20_000)) })
                logs.put(JSONObject().put("ts", start + i * 200).put("type", "INFO").put("message", "replay-log-$i"))
            }
            for (i in 24 downTo 0) network.put(JSONObject().put("ts", start + i * 500).put("method", "GET")
                .put("url", "https://replay.test/$i").put("status", 200).put("latencyMs", 20))
            for (i in 12 downTo 0) state.put(JSONObject().put("ts", start + i * 1_000).put("screen", "screen-$i"))
            for ((path, value) in listOf("timeline.json" to timeline, "logs.json" to logs, "network.json" to network, "state.json" to state))
                put(path, value.toString().toByteArray())
        }
        return archive
    }

    private fun timeMs(): Long {
        val text = find("Playback time")?.text?.toString().orEmpty()
        val match = Regex("(\\d+):(\\d{2})\\.(\\d)").find(text) ?: return -1
        return match.groupValues[1].toLong() * 60_000 + match.groupValues[2].toLong() * 1_000 + match.groupValues[3].toLong() * 100
    }
    private fun seek(ms: Int) {
        val slider = checkNotNull(find("Recording position"))
        check(slider.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply {
            putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, ms.toFloat())
        })) { "Replay slider cannot seek" }
        await("Replay slider did not settle at $ms") { timeMs() == ms.toLong() && find("Play recording") != null }
    }
    private fun currentEvent(label: String): Boolean = allNodes().any {
        it.stateDescription?.toString()?.contains("Current event") == true && it.isVisibleToUser && nodeText(it).contains(label)
    }
    private fun nodeText(node: AccessibilityNodeInfo): String = buildString {
        append(node.text?.toString().orEmpty())
        repeat(node.childCount) { node.getChild(it)?.let { child -> append(' '); append(nodeText(child)) } }
    }
    private fun currentEventAtPlayhead(): Boolean {
        val time = timeMs()
        return time >= 0 && currentEvent("Replay event ${time / 200}")
    }
    private fun allNodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) test.uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) { result += node; repeat(node.childCount) { node.getChild(it)?.let(::visit) } }
        test.uiAutomation.rootInActiveWindow?.let(::visit)
        return result
    }
    private fun find(label: String, contains: Boolean = false): AccessibilityNodeInfo? = allNodes().firstOrNull {
        val text = it.text?.toString().orEmpty(); val desc = it.contentDescription?.toString().orEmpty()
        if (contains) text.contains(label) || desc.contains(label) else text == label || desc == label
    }
    private fun click(label: String, contains: Boolean = false) {
        await("Replay control missing: $label") { find(label, contains)?.isVisibleToUser == true }
        var node = checkNotNull(find(label, contains))
        while (!node.isClickable && node.parent != null) node = node.parent
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Replay cannot click $label" }
        test.waitForIdleSync()
    }
    private fun playerView(): PlayerView? {
        fun visit(view: View): PlayerView? {
            if (view is PlayerView) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))?.let { return it }
            return null
        }
        return activity?.window?.decorView?.let(::visit)
    }
    private fun mediaPosition(): Long { var value = -1L; test.runOnMainSync { value = playerView()?.player?.currentPosition ?: -1 }; return value }
    private fun mediaDuration(): Long { var value = -1L; test.runOnMainSync { value = playerView()?.player?.duration ?: -1 }; return value }
    private fun mediaPlaying(): Boolean { var value = false; test.runOnMainSync { value = playerView()?.player?.isPlaying == true }; return value }
    private fun dominant(channel: Int): Boolean {
        val bitmap = test.uiAutomation.takeScreenshot() ?: return false
        try {
            val bounds = Rect()
            test.runOnMainSync {
                val viewport = playerView()
                if (viewport != null) viewport.getGlobalVisibleRect(bounds)
            }
            if (bounds.isEmpty) {
                val frame = find("Recorded frame at", contains = true) ?: return false
                frame.getBoundsInScreen(bounds)
            }
            if (bounds.isEmpty) return false
            val pixel = bitmap.getPixel(bounds.centerX().coerceIn(0, bitmap.width - 1), bounds.centerY().coerceIn(0, bitmap.height - 1))
            val (r, g, b) = listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
            return when (channel) { Color.RED -> r > maxOf(g, b) + 60; Color.GREEN -> g > maxOf(r, b) + 60; else -> b > maxOf(r, g) + 60 }
        } finally { bitmap.recycle() }
    }
    private fun swipe(x: Float, from: Float, to: Float) {
        drag(x, from, x, to)
    }
    private fun drag(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val down = SystemClock.uptimeMillis()
        for (i in 0..12) {
            val action = when (i) { 0 -> MotionEvent.ACTION_DOWN; 12 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val event = MotionEvent.obtain(down, down + i * 25, action,
                fromX + (toX - fromX) * i / 12, fromY + (toY - fromY) * i / 12, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            check(test.uiAutomation.injectInputEvent(event, true)); event.recycle(); Thread.sleep(25)
        }
    }
    private fun await(message: String, timeout: Long = 10_000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < until) { if (condition()) return; Thread.sleep(80) }
        error("$message (time=${timeMs()}, media=${mediaPosition()}, nodes=${allNodes().map { listOf(it.text, it.contentDescription, it.stateDescription, it.isSelected, it.isVisibleToUser) }.take(40)})")
    }
}
