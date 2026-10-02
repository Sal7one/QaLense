package com.qalens.sample

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.qalens.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Real semantics commands and MotionEvent routing, exercised in LTR/RTL on a disposable device. */
internal class LocalBridgeChecks(private val runner: Instrumentation) {
    private val token = "synthetic-device-pairing-0123456789"
    private val port = 18766
    private fun waitFor(message: String, check: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < until) { if (check()) return; Thread.sleep(100) }
        error(message)
    }
    private fun call(path: String, command: JSONObject? = null, auth: String = token): Pair<Int, JSONObject> {
        val connection = URL("http://127.0.0.1:$port/v1/$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 2_000; connection.readTimeout = 3_000
        connection.setRequestProperty("Authorization", "Bearer $auth")
        if (command != null) {
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(command.toString().toByteArray()) }
        }
        try {
            val code = connection.responseCode
            val stream = if (code < 400) connection.inputStream else connection.errorStream
            return code to JSONObject(stream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }
    private fun command(action: String, tag: String, extra: Pair<String, Any>? = null): Pair<Int, JSONObject> =
        call("command", JSONObject().put("action", action).put("tag", tag).apply { extra?.let { put(it.first, it.second) } })

    fun run() {
        val activity = runner.startActivitySync(Intent(runner.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        val scroll = ScrollState(0)
        val field = mutableStateOf("initial")
        val taps = java.util.concurrent.atomic.AtomicInteger()
        runner.runOnMainSync {
            QaLens.closePanel(); QaLens.setInspectMode(false)
            activity.setContent {
                Column(Modifier.fillMaxSize().testTag("bridge.scroll").verticalScroll(scroll).padding(top = 70.dp, bottom = 100.dp)) {
                    Text("Bridge target", Modifier.testTag("bridge.tap").clickable { taps.incrementAndGet() })
                    OutlinedTextField(field.value, { field.value = it }, Modifier.testTag("bridge.field"))
                    OutlinedTextField("never-export-password", {}, Modifier.testTag("bridge.password"), visualTransformation = PasswordVisualTransformation())
                    Column(Modifier.qaHiddenFromReports()) { Text("hidden-secret", Modifier.testTag("bridge.hidden")) }
                    Text("Duplicate one", Modifier.testTag("bridge.duplicate").clickable { taps.incrementAndGet() })
                    Text("Duplicate two", Modifier.testTag("bridge.duplicate").clickable { taps.incrementAndGet() })
                    repeat(80) { Text("Scrollable row $it", Modifier.height(48.dp)) }
                }
            }
            QaLens.startLocalBridge(token, port)
        }
        try {
            waitFor("Bridge did not listen") { QaLens.localBridgeStatus.value.startsWith("Listening") }
            waitFor("Fixture not composed") { call("snapshot").second.getJSONArray("nodes").toString().contains("bridge.field") }
            check(call("snapshot", auth = "invalid").first == 401)
            runner.runOnMainSync { QaLens.configure { enableSemanticsReflection = false } }
            check(call("snapshot").first == 403 && command("tap", "bridge.tap").first == 403)
            runner.runOnMainSync { QaLens.configure { enableSemanticsReflection = true } }
            val snapshot = call("snapshot").second
            val json = snapshot.toString()
            check(!json.contains("never-export-password") && !json.contains("hidden-secret") && !json.contains("bridge.hidden")) { "Bridge leaked private semantics" }
            check(snapshot.getJSONArray("nodes").getJSONObject(0).has("parentId"))
            check(command("tap", "bridge.duplicate").first == 409) { "Ambiguous tag silently chose a node" }
            check(command("tap", "bridge.hidden").first == 404)
            check(command("tap", "bridge.tap").first == 200 && taps.get() == 1)
            check(command("type", "bridge.field", "text" to "مرحبا QA").first == 200)
            waitFor("SetText did not reach host") { field.value == "مرحبا QA" }
            check(command("scroll", "bridge.scroll", "dy" to 200).first == 200)
            waitFor("ScrollBy did not move host") { scroll.value > 0 }
            runner.runOnMainSync { QaLens.log("bridge-observation-fixture") }
            waitFor("Observed logs missing") { call("events").second.toString().contains("bridge-observation-fixture") }
            check(call("command", JSONObject().put("action", "delete").put("tag", "bridge.tap")).first == 400)
            runner.runOnMainSync { QaLens.setInspectMode(true) }
            runner.waitForIdleSync()
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                runner.runOnMainSync { activity.window.decorView.layoutDirection = direction }
                Thread.sleep(250)
                val before = scroll.value
                val width = activity.window.decorView.width
                val height = activity.window.decorView.height
                drag(width * .45f, height * .50f, 0f, -height * .18f, fingers = 2)
                waitFor("Two-finger inspect drag did not scroll (direction=$direction)") { scroll.value > before }
                check(taps.get() == 1) { "Inspector scrolling clicked host" }
                drag(bounds("QaLens bubble").exactCenterX(), bounds("QaLens bubble").exactCenterY(), -width * .6f, height * .1f)
                val left = bounds("QaLens bubble")
                drag(left.exactCenterX(), left.exactCenterY(), width * .25f, 0f)
                val right = bounds("QaLens bubble")
                check(right.left > left.left) { "Physical right drag failed direction=$direction left=$left right=$right panel=${QaLens.state.value.isPanelOpen}" }
                check(right.left >= 0 && right.right <= width && right.bottom <= height) { "Bubble escaped viewport" }
                val handle = bounds("Move inspector")
                val travel = if (handle.top > height * .3f) -height * .25f else height * .25f
                drag(handle.exactCenterX(), handle.exactCenterY(), 0f, travel)
                val moved = bounds("Move inspector")
                check(if (travel < 0) moved.top < handle.top else moved.top > handle.top) {
                    "Inspector handle did not move dock direction=$direction from=$handle to=$moved"
                }
                for (label in listOf("All", "Actions", "Tagged", "Issues")) {
                    val button = find(label) ?: error("Filter missing: $label")
                    var clickable = button
                    while (!clickable.isClickable && clickable.parent != null) clickable = clickable.parent
                    check(clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    val b = Rect(); button.getBoundsInScreen(b)
                    val bottomInset = if (android.os.Build.VERSION.SDK_INT >= 30)
                        activity.window.decorView.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())?.bottom ?: 0 else 0
                    check(b.bottom <= height - bottomInset && b.top >= 0) { "Filter below system navigation/IME: $b inset=$bottomInset" }
                }
            }
            // A command queued behind a busy main thread must time out and never execute later.
            val blocker = java.util.concurrent.CountDownLatch(1)
            android.os.Handler(android.os.Looper.getMainLooper()).post { blocker.countDown(); Thread.sleep(1_900) }
            check(blocker.await(1, java.util.concurrent.TimeUnit.SECONDS))
            val count = taps.get()
            check(command("tap", "bridge.tap").first == 504)
            Thread.sleep(600)
            check(taps.get() == count)
            runner.runOnMainSync { QaLens.configure { enabled = false } }
            check(QaLens.localBridgeStatus.value == "Stopped")
            check(runCatching { call("snapshot") }.isFailure) { "Disable left socket open" }
            runner.runOnMainSync { QaLens.configure { enabled = true } }
            check(QaLens.localBridgeStatus.value == "Stopped") { "Re-enable silently exposed bridge" }
            runner.runOnMainSync { QaLens.startLocalBridge(token, port) }
            waitFor("Bridge restart failed") { QaLens.localBridgeStatus.value.startsWith("Listening") }
            check(call("snapshot").first == 200)
        } finally {
            runner.runOnMainSync { QaLens.stopLocalBridge(); QaLens.setInspectMode(false); activity.window.decorView.layoutDirection = View.LAYOUT_DIRECTION_LTR }
        }
    }

    private fun find(label: String): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) runner.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && (node.contentDescription?.toString() == label || node.text?.toString() == label)) return node
            repeat(node.childCount) { node.getChild(it)?.let { child -> visit(child)?.let { return it } } }
            return null
        }
        return runner.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun bounds(label: String): Rect {
        var hit: AccessibilityNodeInfo? = null
        waitFor("Control missing: $label") { hit = find(label); hit != null }
        return Rect().also { hit!!.getBoundsInScreen(it) }
    }
    private fun drag(x: Float, y: Float, dx: Float, dy: Float, fingers: Int = 1) {
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, fraction: Float, count: Int) {
            val properties = Array(count) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(count) { index -> MotionEvent.PointerCoords().apply { this.x = x + dx * fraction + index * 40; this.y = y + dy * fraction; pressure = 1f; size = 1f } }
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count, properties, coords, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { check(runner.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, 0f, 1)
        if (fingers == 2) send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 0f, 2)
        repeat(20) { Thread.sleep(12); send(MotionEvent.ACTION_MOVE, (it + 1) / 20f, fingers) }
        if (fingers == 2) send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 1f, 2)
        send(MotionEvent.ACTION_UP, 1f, 1)
        Thread.sleep(200)
    }
}
