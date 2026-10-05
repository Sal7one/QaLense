package com.qalens.sample

import android.app.Instrumentation
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.qalens.QaLens
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Calls the actual SDK protocol, including PixelCopy and the existing recorder on a real window. */
internal class DesktopCaptureChecks(private val runner: Instrumentation) {
    private val token = "synthetic-desktop-capture-0123456789"
    private val port = 18766
    private fun waitFor(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(100) }
        error(message)
    }
    private fun request(path: String, input: JSONObject? = null, auth: String = token): Pair<Int, ByteArray> {
        val connection = URL("http://127.0.0.1:$port/v1/$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 2_000; connection.readTimeout = 5_000
        connection.setRequestProperty("Authorization", "Bearer $auth")
        if (input != null) {
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(input.toString().toByteArray()) }
        }
        return try {
            val code = connection.responseCode
            code to (if (code < 400) connection.inputStream else connection.errorStream).use { it.readBytes() }
        } finally { connection.disconnect() }
    }
    private fun json(path: String, input: JSONObject? = null): Pair<Int, JSONObject> =
        request(path, input).let { it.first to JSONObject(String(it.second)) }
    private fun controls() = json("recordings").second.getJSONObject("controls")
    private fun command(action: String, extra: Pair<String, Any>? = null) = json("recording",
        JSONObject().put("action", action).apply { extra?.let { put(it.first, it.second) } })

    /** Disposable browser QA fixture: normal app UI, temporary host HD opt-in, main thread free. */
    fun holdGui(seconds: Int) {
        val original = QaLens.config.value
        runner.startActivitySync(Intent(runner.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        runner.runOnMainSync { QaLens.configure { enabled = true; allowUnmaskedVideo = true } }
        try { Thread.sleep(seconds.coerceIn(1, 600) * 1000L) }
        finally { runner.runOnMainSync { QaLens.stopRecording(); QaLens.stopLocalBridge(); QaLens.configure { allowUnmaskedVideo = original.allowUnmaskedVideo } } }
    }

    fun run() {
        val original = QaLens.config.value
        val activity = runner.startActivitySync(Intent(runner.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        runner.runOnMainSync {
            QaLens.configure { enabled = true; enableSemanticsReflection = true; allowUnmaskedVideo = false }
            QaLens.closePanel(); QaLens.setTagMode(false); QaLens.setInspectMode(false); QaLens.setWatchMode(false)
            activity.setContent {
                Column(Modifier.fillMaxSize().padding(70.dp)) {
                    Text("Desktop capture fixture", Modifier.testTag("desktop.title"))
                    OutlinedTextField("synthetic-secret", {}, Modifier.testTag("desktop.password"), visualTransformation = PasswordVisualTransformation())
                }
            }
            QaLens.startLocalBridge(token, port)
        }
        try {
            waitFor("Desktop bridge did not start") { QaLens.localBridgeStatus.value.startsWith("Listening") }
            waitFor("Password fixture absent") { json("snapshot").second.toString().contains("desktop.password") }
            check(controls().getString("phase") == "idle")
            check(request("recording", JSONObject().put("action", "start"), "invalid").first == 401)
            check(command("start", "video" to true).first == 403) { "HD bypassed host opt-in" }
            check(command("stop").first == 409 && command("clip", "seconds" to 10).first == 409)
            check(command("clip", "seconds" to 1.5).first == 400)
            check(json("inspection", JSONObject().put("enabled", true)).first == 200)
            check(QaLens.state.value.isInspectMode)
            runner.runOnMainSync { QaLens.setTagMode(true) }
            check(json("inspection", JSONObject().put("enabled", false)).first == 200)
            check(!QaLens.state.value.isInspectMode && !QaLens.state.value.isTagMode)
            runner.waitForIdleSync()
            val overlay = activity.window.decorView.findViewWithTag<View>("qalens_overlay_compose_view") ?: error("Overlay not installed")
            for (visibility in listOf(View.VISIBLE, View.INVISIBLE)) {
                runner.runOnMainSync { overlay.visibility = visibility }
                for (include in listOf(false, true)) {
                    val result = request("screenshot", JSONObject().put("includeOverlay", include))
                    check(result.first == 200) { "Screenshot failed ${result.first}: ${String(result.second)}" }
                    val bitmap = BitmapFactory.decodeByteArray(result.second, 0, result.second.size) ?: error("PNG did not decode")
                    try {
                        val nodes = json("snapshot").second.getJSONArray("nodes")
                        val field = (0 until nodes.length()).map { nodes.getJSONObject(it) }.single { it.optString("tag") == "desktop.password" }.getJSONObject("bounds")
                        check(bitmap.getPixel((field.getDouble("left") + field.getDouble("right")).toInt() / 2,
                            (field.getDouble("top") + field.getDouble("bottom")).toInt() / 2) == Color.BLACK) { "Screenshot failed to mask the password" }
                    } finally { bitmap.recycle() }
                    runner.runOnMainSync { check(overlay.visibility == visibility) { "Screenshot changed overlay visibility" } }
                }
            }
            runner.runOnMainSync { overlay.visibility = View.VISIBLE; activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            check(request("screenshot", JSONObject().put("includeOverlay", false)).first == 409)
            runner.runOnMainSync { check(overlay.visibility == View.VISIBLE); activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            check(request("screenshot", JSONObject().put("includeOverlay", "yes")).first == 400)
            check(command("start").first == 200)
            check(controls().getString("phase") == "capturing")
            check(command("start").first == 409)
            check(json("inspection", JSONObject().put("enabled", true)).first == 409)
            Thread.sleep(1_200)
            val bugNote = "Checkout stalled after Pay"
            check(json("recording", JSONObject().put("action", "clip").put("seconds", 10).put("label", bugNote)).first == 200)
            check(controls().getInt("markedClips") == 1 && QaLens.state.value.isRecording)
            val session = controls().getString("sessionName")
            check(command("stop").first == 200)
            waitFor("Desktop stop never finished saving") { controls().getString("phase") == "idle" }
            val files = json("recordings").second.getJSONArray("items")
            check((0 until files.length()).any { files.getJSONObject(it).getString("name") == session })
            val start = session.removePrefix("session_").removeSuffix(".sal")
            check((0 until files.length()).any { files.getJSONObject(it).getString("name").contains("clip_${start}_1") }) { "Marked clip did not export with the master" }
            val clip = (0 until files.length()).map { files.getJSONObject(it).getString("name") }.first { it.contains("clip_${start}_1") }
            fun archiveText(name: String, entry: String): String {
                val bytes = request("recordings/$name").second
                java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zip ->
                    while (true) {
                        val item = zip.nextEntry ?: error("Missing archive entry $entry")
                        if (item.name == entry) {
                            val content = zip.readBytes()
                            return if (content.size >= 2 && content[0] == 0x1f.toByte() && content[1] == 0x8b.toByte())
                                java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(content)).use { String(it.readBytes()) }
                            else String(content)
                        }
                    }
                }
            }
            check(archiveText(session, "marks.json").contains(bugNote)) { "Bug note missing from master timeline" }
            check(JSONObject(archiveText(clip, "analysis.json")).getJSONObject("clip").getString("label") == bugNote) { "Bug note missing from clip analysis" }
            runner.runOnMainSync { check(overlay.visibility == View.VISIBLE) }
            check(command("stop").first == 409)
            // PixelCopy callbacks run after this main turn: a capture must not undo a concurrent
            // recording Start/Stop's overlay decision. These reproduce both visibility races.
            fun awaitScreenshot(after: Long) {
                waitFor("Concurrent screenshot was not delivered") {
                    java.io.File(activity.cacheDir, "qalens").listFiles()?.any { it.name.startsWith("qa_") && it.extension == "png" && it.lastModified() >= after } == true
                }
                runner.waitForIdleSync()
            }
            val beforeStart = System.currentTimeMillis()
            runner.runOnMainSync { QaLens.takeScreenshot(share = false); QaLens.startRecording() }
            awaitScreenshot(beforeStart)
            runner.runOnMainSync { check(overlay.visibility == View.INVISIBLE) { "Screenshot re-exposed the active recorder overlay" } }
            Thread.sleep(600)
            val beforeStop = System.currentTimeMillis()
            runner.runOnMainSync { QaLens.takeScreenshot(share = false); QaLens.stopRecording() }
            awaitScreenshot(beforeStop)
            waitFor("Concurrent phone Stop never saved") { controls().getString("phase") == "idle" }
            runner.runOnMainSync { check(overlay.visibility == View.VISIBLE) { "Screenshot hid the overlay after phone Stop" } }
            runner.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            // MainActivity is singleTop: startActivitySync waits forever for a new activity when
            // Android reuses it via onNewIntent. Resume it, then verify the bridge's host read.
            runner.runOnMainSync { activity.startActivity(Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            waitFor("App did not resume after the phone share screen") { json("snapshot").first == 200 }
            // Check pending HD consent separately: it must not be reported as capture or accept clips.
            runner.runOnMainSync { QaLens.configure { allowUnmaskedVideo = true } }
            check(command("start", "video" to true).first == 200)
            check(controls().getString("phase") == "awaiting_consent" && !QaLens.state.value.isRecording)
            check(command("clip", "seconds" to 10).first == 409)
            check(command("stop").first == 200)
            waitFor("HD cancellation did not return idle") { controls().getString("phase") == "idle" }
            // Cancellation of the lifecycle alone leaves Android's consent activity until Back.
            runner.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        } finally {
            runner.runOnMainSync {
                QaLens.stopRecording(); QaLens.stopLocalBridge(); QaLens.setInspectMode(false)
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                QaLens.configure { enabled = original.enabled; enableSemanticsReflection = original.enableSemanticsReflection; allowUnmaskedVideo = original.allowUnmaskedVideo }
            }
        }
    }
}
