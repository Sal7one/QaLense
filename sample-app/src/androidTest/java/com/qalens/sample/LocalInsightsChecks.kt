package com.qalens.sample

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
import com.qalens.QaLens
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit, synthetic-only phone UI → archive → HTTP provider → grounded report → seek check. */
internal class LocalInsightsChecks(private val test: Instrumentation) {
    private var activity: Activity? = null
    private val start = 1_800_000_000_000L
    private val transferPort = 8766
    private var transferToken = "synthetic-insights-transfer-0123456789"
    private val expected = "Pressing Play should start video playback."
    private val actual = "Pressing Play leaves the player buffering."
    fun run(baseUrl: String, transferHoldSeconds: Int = 0, suppliedTransferToken: String? = null) {
        require(baseUrl.startsWith("http://10.0.2.2:") || baseUrl.startsWith("http://127.0.0.1:")) { "Synthetic model fixture must be local." }
        require(transferHoldSeconds in 0..60)
        suppliedTransferToken?.let { require(it.matches(Regex("[A-Za-z0-9_-]{24,128}"))); transferToken = it }
        require(transferHoldSeconds == 0 || suppliedTransferToken != null) { "A PC handoff hold requires an explicit synthetic transfer token." }
        val readiness = File(test.targetContext.cacheDir, "qalens-insights-handoff-ready.json").also { it.delete() }
        val enabled = QaLens.config.value.enabled
        val root = File(test.targetContext.cacheDir, "qalens/insights-test-${java.util.UUID.randomUUID()}")
        check(root.mkdirs())
        val prefs = test.targetContext.getSharedPreferences("qalens_local_insights", android.content.Context.MODE_PRIVATE)
        val oldUrl = prefs.getString("url", null); val oldModel = prefs.getString("model", null)
        val before = status(baseUrl).optInt("chatRequests")
        test.runOnMainSync { QaLens.configure { this.enabled = false } }
        try {
            val file = fixture(root)
            val uri = FileProvider.getUriForFile(test.targetContext, test.targetContext.packageName + ".qalens.fileprovider", file)
            activity = test.startActivitySync(Intent(Intent.ACTION_VIEW).setClassName(test.targetContext, "com.qalens.replay.QaLensPlayerActivity")
                .setDataAndType(uri, "application/octet-stream").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            await("Recording did not open") { find("Recording position") != null }
            seek(52_000)
            click("Insights")
            await("Insights page did not open") { find("Local insights") != null }
            show("observations ·", contains = true)
            click("Recorded app")
            show("observations ·", contains = true)
            check(find("observations ·", contains = true) != null) { "Tapping the selected target left evidence preparation stuck" }
            check(status(baseUrl).optInt("chatRequests") == before) { "Opening Insights sent recording data without Analyze" }
            setText("Local model URL", baseUrl)
            click("Check installed models")
            show("qalens-synthetic-triage", contains = true)
            check(find("text-embedding-synthetic", contains = true) == null) { "Embedding-only model was selectable" }
            check(status(baseUrl).optInt("chatRequests") == before) { "Model discovery sent recording data" }
            setText("Expected result (optional)", expected)
            setText("Actual result (optional)", actual)
            click("Review exact evidence and coverage")
            show("Text telemetry only", contains = true)
            click("Hide exact evidence")
            click("I reviewed the selected data", contains = true)
            // Standalone replay returns a clear failure without trying to start/pair a bridge.
            click("Send to PC")
            show("No approved PC bridge", contains = true)
            check(status(baseUrl).optInt("chatRequests") == before) { "Send to PC invoked the local model" }
            test.runOnMainSync { QaLens.configure { this.enabled = true }; QaLens.startLocalBridge(transferToken, transferPort) }
            await("Synthetic investigation bridge did not listen") { QaLens.localBridgeStatus.value.startsWith("Listening") }
            check(bridge("investigations/inbox", auth = "invalid").first == 401) { "Investigation inbox was unauthenticated" }
            val receiver = test.targetContext.packageManager.getReceiverInfo(ComponentName(test.targetContext.packageName, "com.qalens.QaLensInvestigationReceiver"), 0)
            check(!receiver.exported) { "Investigation receiver exposes a cross-app surface" }
            check(sendDocument(JSONObject().put("schema", "incorrect").toString()) == 3) { "Invalid investigation envelope was queued" }
            click("Send to PC")
            val withoutReport = onlyTransfer()
            check(!withoutReport.getJSONObject("document").has("report")) { "Sending before Analyze invented a report" }
            checkTransferredContext(withoutReport.getJSONObject("document").getJSONObject("bundle"))
            acknowledge(withoutReport.getString("id"))
            check(status(baseUrl).optInt("chatRequests") == before) { "Reportless PC send submitted model data" }
            click("Analyze selected moment")
            show("Model assessment", contains = true, timeout = 20_000)
            show("HTTP 503", contains = true)
            check(find("HTTP 503", contains = true) != null) { "Captured network evidence missing in phone report" }
            val metrics = status(baseUrl)
            check(metrics.optInt("chatRequests") == before + 1) { "Phone Analyze did not make exactly one request" }
            check(metrics.optInt("lastImageCount") == 0) { "Text-only analysis sent an image without consent" }
            val ids = metrics.optJSONArray("lastEvidenceIds") ?: error("Fixture received no item IDs")
            val names = (0 until ids.length()).map(ids::optString)
            check("network:0" in names && "logs:1" in names && "state:0" in names && "connectivity:0" in names) {
                "Phone lost full-file IDs or preceding state/connectivity: $names"
            }
            show("QA report · verify before filing")
            show("1. Play pressed")
            show("Expected result · tester reported")
            show("Actual result · tester reported")
            click("Copy QA report")
            val markdown = clipboard()
            check(markdown.contains("1. Play pressed (`timeline:0`)") && markdown.contains(expected) && markdown.contains(actual)) { "QA Markdown lost actual captured steps or tester context" }
            check(markdown.contains("Source: Tester reported") && !markdown.contains("2. ")) { "QA copy invented a reproduction step" }
            click("Copy analysis JSON")
            val copied = JSONObject(clipboard())
            val qa = copied.getJSONObject("qaReport")
            check(qa.getString("expectedSource") == "tester" && qa.getString("actualSource") == "tester")
            check(qa.getJSONArray("steps").length() == 1 && qa.getJSONArray("steps").getJSONObject(0).getJSONArray("evidenceIds").getString(0) == "timeline:0")
            click("Send to PC")
            val completed = onlyTransfer()
            val completedDocument = completed.getJSONObject("document")
            checkTransferredContext(completedDocument.getJSONObject("bundle"))
            check(completedDocument.getJSONObject("report").toString() == copied.toString()) { "PC handoff silently rebuilt the reviewed report" }
            acknowledge(completed.getString("id"))
            // Both tester fields revoke model and PC consent and rebuild the reviewed context.
            setText("Expected result (optional)", "A changed expectation")
            show("I reviewed the selected data", contains = true)
            check(!enabled("Analyze selected moment") && !enabled("Send to PC")) { "Changing Expected retained sending consent" }
            setText("Expected result (optional)", expected)
            setText("Actual result (optional)", "A changed symptom")
            show("I reviewed the selected data", contains = true)
            check(!enabled("Analyze selected moment") && !enabled("Send to PC")) { "Changing Actual retained sending consent" }
            setText("Actual result (optional)", actual)
            check(status(baseUrl).optInt("chatRequests") == before + 1) { "Editing tester context automatically analyzed" }
            // Opt-in attaches exactly one reviewed archive still; no upload merely on checking it.
            click("Include one saved image", contains = true)
            show("Selected saved image preview")
            check(status(baseUrl).optInt("chatRequests") == before + 1) { "Preparing an optional image sent it automatically" }
            click("I reviewed the selected data", contains = true)
            click("Analyze selected moment")
            show("Model assessment", contains = true, timeout = 20_000)
            check(status(baseUrl).optInt("lastImageCount") == 1) { "Opted-in saved image was not sent exactly once" }
            click("Send to PC")
            val withImage = onlyTransfer()
            val images = withImage.getJSONObject("document").getJSONObject("bundle").getJSONArray("images")
            check(images.length() == 1 && images.getJSONObject(0).getString("id") == "images:0" && images.getJSONObject(0).getString("data").isNotEmpty()) { "Reviewed still did not survive PC handoff" }
            acknowledge(withImage.getString("id"))
            repeat(5) { index ->
                click("Send to PC")
                await("Investigation queue did not bound repeated sends") {
                    val inbox = bridge("investigations/inbox").second
                    inbox.getJSONArray("transfers").length() == minOf(index + 1, 4) && inbox.getInt("dropped") == maxOf(0, index - 3)
                }
            }
            test.runOnMainSync { QaLens.stopLocalBridge(); QaLens.startLocalBridge(transferToken, transferPort) }
            await("Investigation bridge restart failed") { QaLens.localBridgeStatus.value.startsWith("Listening") }
            check(bridge("investigations/inbox").second.getJSONArray("transfers").length() == 0) { "Stopping bridge retained investigation payloads" }
            check(status(baseUrl).optInt("chatRequests") == before + 2) { "PC queue operations triggered a model request" }
            click("images:0 · show in recording", contains = true)
            await("Saved image citation did not seek the actual frame timestamp") { find("Playback time")?.text?.contains("0:52.0") == true }
            click("Insights")
            show("Evidence review")
            click("QaLens player")
            show("current-player", contains = true)
            click("I reviewed the selected data", contains = true)
            click("Analyze selected moment")
            show("Model assessment", contains = true, timeout = 20_000)
            show("No current QaLens-player action trace", contains = true)
            val playerMetrics = status(baseUrl)
            check(playerMetrics.optString("lastTarget") == "qalens-player" && playerMetrics.optBoolean("runtimeProvided")) {
                "Phone player investigation did not preserve current runtime provenance"
            }
            check(playerMetrics.optInt("lastImageCount") == 0) { "Changing target reused the previous image without renewed opt-in" }
            click("player:runtime · review current player snapshot", contains = true)
            show("Local insights")
            check(find("Local insights") != null && find("Recording position") == null) { "Runtime citation pretended to be a saved host event" }
            setText("Question (optional)", "Does the QaLens player itself have a replay bug?")
            show("I reviewed the selected data", contains = true)
            check(find("Model assessment", contains = true) == null) { "Question change retained a report" }
            check(!enabled("Analyze selected moment")) { "Changed question retained Analyze consent" }
            val calls = status(baseUrl).optInt("chatRequests")
            check(test.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
            test.waitForIdleSync(); Thread.sleep(400)
            test.targetContext.startActivity(Intent().setClassName(test.targetContext, "com.qalens.replay.QaLensPlayerActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            show("Local insights")
            show("observations ·", contains = true)
            check(status(baseUrl).optInt("chatRequests") == calls) { "Resuming Insights automatically submitted a model request" }
            check(!enabled("Analyze selected moment")) { "Resume retained prior evidence consent" }
            click("Recorded app")
            show("observations ·", contains = true)
            // Cancel a real in-flight local HTTP request, then recover without editing the question/focus.
            show("z-fixture-slow", contains = true)
            click("z-fixture-slow", contains = true)
            check(find("Model assessment", contains = true) == null) { "Changing model retained a stale report" }
            val beforeSlow = status(baseUrl).optInt("chatRequests")
            click("I reviewed the selected data", contains = true)
            click("Analyze selected moment")
            await("Slow local request never reached the provider") { status(baseUrl).optInt("chatRequests") == beforeSlow + 1 }
            click("Cancel analysis")
            show("observations ·", contains = true)
            show("Canceled.", contains = true)
            check(find("Model assessment", contains = true) == null && !enabled("Analyze selected moment")) { "Cancel retained a report or sending consent" }
            check(status(baseUrl).optInt("chatRequests") == beforeSlow + 1) { "Cancel recovery submitted another request automatically" }
            click("qalens-synthetic-triage", contains = true)
            click("I reviewed the selected data", contains = true)
            click("Analyze selected moment")
            show("Model assessment", contains = true, timeout = 20_000)
            check(status(baseUrl).optInt("chatRequests") == beforeSlow + 2) { "Normal analysis did not recover after HTTP cancellation" }
            click("network:0 · show in recording", contains = true)
            await("Evidence citation did not return to the recording timestamp") { find("Playback time")?.text?.contains("0:50.2") == true }
            click("Insights")
            await("Changed focus did not prepare new evidence") { find("0:50.200", contains = true) != null }
            check(find("Model assessment", contains = true) == null) { "Changing focus retained a stale assessment" }
            click("‹ Recording")
            click("‹ Close")
            await("Close did not finish the replay Activity") { activity?.isFinishing == true }
            test.waitForIdleSync()
            // HD still comes from the saved video consent interval, without any new capture.
            val hd = fixture(root, video = true)
            val hdUri = FileProvider.getUriForFile(test.targetContext, test.targetContext.packageName + ".qalens.fileprovider", hd)
            activity = test.startActivitySync(Intent(Intent.ACTION_VIEW).setClassName(test.targetContext, "com.qalens.replay.QaLensPlayerActivity")
                .setDataAndType(hdUri, "application/octet-stream").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            await("Saved HD fixture did not open") { find("Recording position") != null }
            seek(52_000); click("Insights")
            setText("Local model URL", baseUrl); click("Check installed models")
            show("qalens-synthetic-triage", contains = true)
            setText("Expected result (optional)", expected); setText("Actual result (optional)", actual)
            click("Include one saved image", contains = true)
            show("Selected saved image preview")
            show("approximate video frame time", contains = true)
            click("I reviewed the selected data", contains = true); click("Analyze selected moment")
            show("Model assessment", contains = true, timeout = 20_000)
            check(status(baseUrl).optInt("lastImageCount") == 1) { "HD still was not sent exactly once" }
            show("Model assessment", contains = true)
            test.uiAutomation.takeScreenshot()?.let { bitmap ->
                try { File(test.targetContext.cacheDir, "qalens-insights-synthetic.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { bitmap.recycle() }
            }
            if (transferHoldSeconds > 0) {
                // Optional root-owned actual Python session check. No secrets are written to cache.
                test.runOnMainSync { QaLens.stopLocalBridge(); QaLens.startLocalBridge(transferToken, transferPort) }
                await("Final PC handoff bridge did not listen") { QaLens.localBridgeStatus.value.startsWith("Listening") }
                click("Send to PC"); onlyTransfer()
                readiness.writeText(JSONObject().put("ready", true).put("port", transferPort).toString())
                val deadline = SystemClock.elapsedRealtime() + transferHoldSeconds * 1000L
                while (SystemClock.elapsedRealtime() < deadline && bridge("investigations/inbox").second.getJSONArray("transfers").length() != 0) Thread.sleep(200)
                check(bridge("investigations/inbox").second.getJSONArray("transfers").length() == 0) { "PC did not receive/ack the optional held investigation" }
            }
            click("‹ Recording"); click("‹ Close")
        } catch (failure: Throwable) {
            saveFailure(failure)
            throw failure
        } finally {
            test.runOnMainSync { activity?.finish(); QaLens.stopLocalBridge(); QaLens.configure { this.enabled = enabled } }
            readiness.delete()
            prefs.edit().apply {
                if (oldUrl == null) remove("url") else putString("url", oldUrl)
                if (oldModel == null) remove("model") else putString("model", oldModel)
            }.commit()
            root.deleteRecursively()
        }
    }

    private fun checkTransferredContext(bundle: JSONObject) {
        val tester = bundle.getJSONObject("qaContext")
        check(tester.getString("expectedResult") == expected && tester.getString("actualResult") == actual) { "PC handoff lost reviewed expected/actual" }
        val items = bundle.getJSONArray("items")
        val ids = (0 until items.length()).map { items.getJSONObject(it).getString("id") }
        check(ids.containsAll(listOf("timeline:0", "network:0", "logs:1", "state:0", "connectivity:0"))) { "PC handoff lost selected stable IDs" }
        check(!bundle.has("apiKey") && !bundle.has("settings")) { "PC handoff exported provider configuration" }
    }
    private fun bridge(path: String, command: JSONObject? = null, auth: String = transferToken): Pair<Int, JSONObject> {
        val connection = URL("http://127.0.0.1:$transferPort/v1/$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        connection.connectTimeout = 2000; connection.readTimeout = 3000; connection.instanceFollowRedirects = false
        connection.setRequestProperty("Authorization", "Bearer $auth")
        if (command != null) {
            connection.requestMethod = "POST"; connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(command.toString().toByteArray(Charsets.UTF_8)) }
        }
        return try {
            val code = connection.responseCode
            code to (if (code < 400) connection.inputStream else connection.errorStream).use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
        } finally { connection.disconnect() }
    }
    private fun onlyTransfer(): JSONObject {
        await("Reviewed investigation did not reach the approved queue") { bridge("investigations/inbox").second.getJSONArray("transfers").length() == 1 }
        return bridge("investigations/inbox").second.getJSONArray("transfers").getJSONObject(0)
    }
    private fun acknowledge(id: String) {
        val ids = JSONObject().put("ids", JSONArray().put(id))
        check(bridge("investigations/ack", ids, auth = "invalid").first == 401)
        check(bridge("investigations/ack", ids).first == 200 && bridge("investigations/ack", ids).first == 200)
        check(bridge("investigations/inbox").second.getJSONArray("transfers").length() == 0)
    }
    private fun sendDocument(document: String): Int {
        val result = java.util.concurrent.atomic.AtomicInteger(0)
        val ready = CountDownLatch(1)
        test.targetContext.sendOrderedBroadcast(Intent("com.qalens.action.SEND_INVESTIGATION")
            .setComponent(ComponentName(test.targetContext.packageName, "com.qalens.QaLensInvestigationReceiver")).putExtra("document", document),
            null, object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { result.set(resultCode); ready.countDown() }
            }, android.os.Handler(android.os.Looper.getMainLooper()), 0, null, null)
        check(ready.await(8, TimeUnit.SECONDS)) { "Private investigation receiver did not finish" }
        return result.get()
    }
    private fun clipboard(): String {
        var value = ""
        test.runOnMainSync { value = (test.targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString().orEmpty() }
        check(value.isNotEmpty()) { "Expected copied report" }
        return value
    }

    private fun fixture(root: File, video: Boolean = false): File {
        val manifest = JSONObject().put("formatVersion", 1).put("startMillis", start).put("endMillis", start + 90_000)
            .put("app", JSONObject().put("name", "Synthetic video player insights"))
            .apply {
                if (video) put("video", "video.mp4").put("videoStartMillis", start + 48_000)
                else put("frameIndex", JSONObject().put((start + 52_000).toString(), "frames/selected.jpg"))
            }
        val log = JSONArray().put(JSONObject().put("ts", start + 1_000).put("type", "LOG").put("message", "Synthetic player ready"))
            .put(JSONObject().put("ts", start + 50_100).put("type", "LOG").put("message", "Synthetic player buffering failed"))
        val network = JSONArray().put(JSONObject().put("ts", start + 50_200).put("method", "GET").put("url", "https://synthetic.test/stream")
            .put("status", 503).put("latencyMs", 1000).put("responseBodyPreview", "Synthetic service unavailable"))
        val state = JSONArray().put(JSONObject().put("ts", start + 40_000).put("screen", "Player")
            .put("dataSources", JSONObject().put("player", JSONObject().put("playing", "false").put("buffering", "true"))))
        val connectivity = JSONArray().put(JSONObject().put("ts", start).put("type", "WIFI"))
        val timeline = JSONArray().put(JSONObject().put("ts", start + 49_000).put("kind", "ACTION").put("title", "Play pressed"))
            .put(JSONObject().put("ts", start + 50_200).put("kind", "ERROR").put("title", "Synthetic failed stream request").put("isError", true))
        val analysis = JSONObject().put("coverage", JSONObject().put("networkCaptureEnabled", true).put("networkInterceptorInstalled", true)
            .put("network", true).put("logs", true).put("state", true).put("recording", JSONObject().put("truncated", false)))
        return File(root, if (video) "synthetic-player-hd.sal" else "synthetic-player.sal").also { output ->
            ZipOutputStream(output.outputStream()).use { zip ->
                fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
                for ((name, value) in listOf("manifest.json" to manifest, "logs.json" to log, "network.json" to network,
                    "state.json" to state, "connectivity.json" to connectivity, "timeline.json" to timeline, "analysis.json" to analysis))
                    put(name, value.toString().toByteArray())
                if (video) put("video.mp4", test.context.assets.open("replay-clock.mp4").use { it.readBytes() })
                else {
                    val bitmap = Bitmap.createBitmap(160, 240, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.GREEN)
                        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
                        put("frames/selected.jpg", bytes)
                    } finally { bitmap.recycle() }
                }
            }
        }
    }
    private fun status(base: String): JSONObject {
        val connection = URL("$base/fixture/status").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        connection.connectTimeout = 2000; connection.readTimeout = 2000; connection.instanceFollowRedirects = false
        return try { connection.inputStream.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) } }
        finally { connection.disconnect() }
    }
    private fun seek(ms: Int) {
        val slider = checkNotNull(find("Recording position"))
        check(slider.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply {
            putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, ms.toFloat())
        }))
        test.waitForIdleSync()
    }
    private fun setText(label: String, value: String) {
        show(label, editable = true)
        val input = checkNotNull(find(label, editable = true))
        check(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        })) { "Cannot set $label" }
        test.waitForIdleSync()
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) test.uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) { result += node; repeat(node.childCount) { node.getChild(it)?.let(::visit) } }
        test.uiAutomation.rootInActiveWindow?.let(::visit)
        return result
    }
    private fun find(label: String, contains: Boolean = false, editable: Boolean = false): AccessibilityNodeInfo? {
        fun matches(node: AccessibilityNodeInfo): Boolean {
            val text = node.text?.toString().orEmpty(); val description = node.contentDescription?.toString().orEmpty()
            return if (contains) text.contains(label) || description.contains(label) else text == label || description == label
        }
        fun subtreeMatches(node: AccessibilityNodeInfo): Boolean {
            if (matches(node)) return true
            repeat(node.childCount) { index -> node.getChild(index)?.let { if (subtreeMatches(it)) return true } }
            return false
        }
        // Material's editable owner may have no label; its noneditable descendant owns semantics.
        return nodes().firstOrNull { if (editable) it.isEditable && subtreeMatches(it) else matches(it) }
    }
    private fun show(label: String, contains: Boolean = false, editable: Boolean = false, timeout: Long = 15_000) {
        var direction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        val until = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < until) {
            find(label, contains, editable)?.let { match ->
                if (match.isVisibleToUser) return
                if (match.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)) {
                    test.waitForIdleSync()
                    if (find(label, contains, editable)?.isVisibleToUser == true) return
                }
            }
            // Offscreen Compose semantics can be absent: search while scrolling in both directions.
            val scroll = nodes().filter { it.isScrollable && it.className?.toString() != "android.widget.HorizontalScrollView" }
                .maxByOrNull { node -> Rect().also(node::getBoundsInScreen).let { it.width().toLong() * it.height() } }
            val before = visibleSignature()
            val accepted = scroll?.performAction(direction) == true
            test.waitForIdleSync(); Thread.sleep(100)
            // Some accessibility delegates accept a scroll at the boundary without moving anything.
            if (!accepted || visibleSignature() == before) direction = if (direction == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        error("Missing/hidden $label; visible=${nodes().filter { it.isVisibleToUser }.map { it.text ?: it.contentDescription }.take(35)}")
    }
    private fun visibleSignature(): List<String> = nodes().filter { it.isVisibleToUser }.take(100).map { node ->
        val bounds = Rect().also(node::getBoundsInScreen)
        "${node.text?.toString()?.take(100)}|${node.contentDescription?.toString()?.take(100)}|${bounds.flattenToString()}"
    }
    private fun click(label: String, contains: Boolean = false) {
        show(label, contains)
        var node = checkNotNull(find(label, contains))
        while (!node.isClickable && node.parent != null) node = node.parent
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Cannot click $label" }
        test.waitForIdleSync()
    }
    private fun enabled(label: String): Boolean {
        show(label)
        var node: AccessibilityNodeInfo? = find(label)
        while (node != null) {
            if (!node.isEnabled) return false
            if (node.isClickable) return true
            node = node.parent
        }
        return false
    }
    private fun saveFailure(failure: Throwable) {
        // Disposable synthetic UI only, captured before the finally block closes the player.
        runCatching {
            test.uiAutomation.takeScreenshot()?.let { bitmap ->
                try { File(test.targetContext.cacheDir, "qalens-insights-failure.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { bitmap.recycle() }
            }
        }
        runCatching {
            val tree = JSONArray()
            nodes().take(500).forEach { node ->
                val bounds = Rect().also(node::getBoundsInScreen)
                tree.put(JSONObject().put("text", node.text?.toString()?.take(1200).orEmpty())
                    .put("description", node.contentDescription?.toString()?.take(400).orEmpty())
                    .put("class", node.className?.toString().orEmpty()).put("bounds", bounds.flattenToString())
                    .put("editable", node.isEditable).put("clickable", node.isClickable).put("enabled", node.isEnabled)
                    .put("visible", node.isVisibleToUser).put("actions", JSONArray(node.actionList.map { it.id })))
            }
            File(test.targetContext.cacheDir, "qalens-insights-failure.json").writeText(JSONObject()
                .put("error", failure.message.orEmpty().take(1000)).put("nodes", tree).toString(2))
        }
    }
    private fun await(message: String, timeout: Long = 10_000, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < until) { if (predicate()) return; Thread.sleep(80) }
        error("$message; visible=${nodes().filter { it.isVisibleToUser }.map { it.text ?: it.contentDescription }.take(35)}")
    }
}
