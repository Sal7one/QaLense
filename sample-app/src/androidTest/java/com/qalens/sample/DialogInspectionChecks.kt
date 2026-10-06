package com.qalens.sample

import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.qalens.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/** Unmodified host dialogs, real window input and shared bridge exports; API 29+ public discovery. */
internal class DialogInspectionChecks(private val test: Instrumentation) {
    private val token = "synthetic-dialog-inspection-0123456789"
    private val port = 18766
    private fun waitFor(message: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < until) { if (condition()) return; Thread.sleep(100) }
        error(message)
    }
    private fun call(path: String, data: JSONObject? = null): Pair<Int, JSONObject> {
        val c = URL("http://127.0.0.1:$port/v1/$path").openConnection() as HttpURLConnection
        c.connectTimeout = 2_000; c.readTimeout = 3_000
        c.setRequestProperty("Authorization", "Bearer $token")
        if (data != null) {
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(data.toString().toByteArray()) }
        }
        return try {
            val code = c.responseCode
            code to JSONObject((if (code < 400) c.inputStream else c.errorStream).bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }
    private fun command(action: String, tag: String, extra: Pair<String, Any>? = null) =
        call("command", JSONObject().put("action", action).put("tag", tag).apply { extra?.let { put(it.first, it.second) } })
    private fun nodes(snapshot: JSONObject) = snapshot.getJSONArray("nodes").let { a -> (0 until a.length()).map(a::getJSONObject) }
    private fun windows(snapshot: JSONObject) = snapshot.getJSONArray("windows").let { a -> (0 until a.length()).map(a::getJSONObject) }
    private fun node(tag: String) = nodes(call("snapshot").second).single { it.optString("tag") == tag }
    private fun surfaces(): List<View> = WindowInspector.getGlobalWindowViews().filter { it.tag == "qalens_overlay_compose_view" }
    private fun awaitSurface(anchor: View) {
        waitFor("Inspector did not attach above its current window") {
            anchor.isAttachedToWindow && surfaces().singleOrNull()?.let {
                it.isShown && (it.layoutParams as WindowManager.LayoutParams).type == WindowManager.LayoutParams.TYPE_APPLICATION
            } == true
        }
        test.waitForIdleSync(); Thread.sleep(250)
    }
    private fun clickSurface(label: String) {
        // The SDK correctly excludes its own tree. Use public Compose UI semantics on the owned
        // test surface to choose All, then inject the real screen tap on a noninteractive element.
        test.runOnMainSync {
            var clicked = false
            fun visit(view: View) {
                if (view is RootForTest) view.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true).forEach { node ->
                    if (node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label })
                        clicked = node.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true || clicked
                }
                if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            visit(surfaces().single())
            check(clicked) { "Inspector control missing: $label" }
        }
        test.waitForIdleSync(); Thread.sleep(150)
    }
    private fun selectAll() = clickSurface("All")
    private fun screenshot(name: String) {
        test.uiAutomation.takeScreenshot()?.let { image ->
            try { File(test.targetContext.cacheDir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { image.recycle() }
        }
    }
    private fun appScreenshot() {
        val c = URL("http://127.0.0.1:$port/v1/screenshot").openConnection() as HttpURLConnection
        c.connectTimeout = 2_000; c.readTimeout = 5_000; c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $token"); c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write("{\"includeOverlay\":false}".toByteArray()) }
        try {
            check(c.responseCode == 200) { "App-window screenshot failed while inspecting a dialog" }
            val bytes = c.inputStream.use { it.readBytes() }
            val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Dialog-time screenshot PNG did not decode")
            image.recycle()
        } finally { c.disconnect() }
    }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 33) test.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && predicate(node)) return node
            repeat(node.childCount) { node.getChild(it)?.let { visit(it)?.let { found -> return found } } }
            return null
        }
        return test.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun point(node: JSONObject, activity: MainActivity): Pair<Float, Float> {
        val b = node.getJSONObject("bounds")
        val origin = IntArray(2)
        test.runOnMainSync { activity.window.decorView.getLocationOnScreen(origin) }
        return ((b.getDouble("left") + b.getDouble("right")) / 2 + origin[0]).toFloat() to
            ((b.getDouble("top") + b.getDouble("bottom")) / 2 + origin[1]).toFloat()
    }
    private fun gesture(x: Float, y: Float, dy: Float = 0f, fingers: Int = 1) {
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, progress: Float, count: Int) {
            val props = Array(count) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(count) { i -> MotionEvent.PointerCoords().apply { this.x = x + i * 30; this.y = y + dy * progress; pressure = 1f; size = 1f } }
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { check(test.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, 0f, 1)
        if (fingers == 2) send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 0f, 2)
        repeat(12) { Thread.sleep(16); send(MotionEvent.ACTION_MOVE, (it + 1) / 12f, fingers) }
        if (fingers == 2) send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 1f, 2)
        send(MotionEvent.ACTION_UP, 1f, 1)
    }

    fun run() {
        check(Build.VERSION.SDK_INT >= 29) { "Automatic dialog discovery requires the public API 29 WindowInspector" }
        val activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        val old = QaLens.config.value
        val show = mutableStateOf(true)
        val nested = mutableStateOf(false)
        val popup = mutableStateOf(false)
        val hook = mutableStateOf(false)
        val field = mutableStateOf("initial")
        val scroll = ScrollState(0)
        val backgroundTaps = AtomicInteger()
        val dialogTaps = AtomicInteger()
        var dialogView: View? = null
        var nestedView: View? = null
        test.runOnMainSync {
            QaLens.configure { enabled = true; enableSemanticsReflection = true }
            QaLens.closePanel(); QaLens.setWatchMode(false); QaLens.setInspectMode(false); QaLens.setTagMode(false)
            activity.setContent {
                Box(Modifier.fillMaxSize().background(Color(0xffdce8f4))) {
                    Text("Main window", Modifier.align(Alignment.TopCenter).padding(top = 70.dp).testTag("dialog.main"))
                    Box(Modifier.align(Alignment.Center).size(8.dp).testTag("dialog.background").clickable { backgroundTaps.incrementAndGet() })
                    if (show.value) Dialog(onDismissRequest = { show.value = false }) {
                        val source = LocalView.current
                        SideEffect { dialogView = source }
                        Box(Modifier.size(300.dp, 420.dp).background(Color.White)
                            .then(if (hook.value) Modifier.qaInspectionRoot() else Modifier)) {
                            Column(Modifier.fillMaxSize().testTag("dialog.scroll").verticalScroll(scroll).padding(12.dp)) {
                                Text("Unmodified Compose dialog", Modifier.testTag("dialog.title").qaName("Host dialog"))
                                OutlinedTextField(field.value, { field.value = it }, Modifier.testTag("dialog.field"))
                                OutlinedTextField("dialog-secret-password", {}, Modifier.testTag("dialog.password"), visualTransformation = PasswordVisualTransformation())
                                Box(Modifier.qaHiddenFromReports()) { Text("dialog-hidden-secret", Modifier.qaTag("dialog.hidden")) }
                                repeat(60) { Text("Dialog row $it", Modifier.height(42.dp)) }
                            }
                            Text("Dialog target", Modifier.align(Alignment.Center).size(160.dp, 60.dp).background(Color(0xfff5ddb3))
                                .qaTag("dialog.target").clickable { dialogTaps.incrementAndGet() })
                            if (nested.value) AlertDialog(onDismissRequest = { nested.value = false },
                                title = { Text("Nested dialog") }, text = {
                                    val source = LocalView.current
                                    SideEffect { nestedView = source }
                                    Text("Foreground nested element", Modifier.testTag("dialog.nested"))
                                }, confirmButton = { TextButton(onClick = { nested.value = false }) { Text("Close nested") } })
                        }
                    }
                    if (popup.value) Popup(alignment = Alignment.Center, offset = IntOffset(0, 180), properties = PopupProperties(focusable = true)) {
                        Text("Automatic Compose popup", Modifier.size(220.dp, 90.dp).background(Color.White).testTag("dialog.popup"))
                    }
                }
            }
            QaLens.startLocalBridge(token, port)
        }
        try {
            waitFor("Bridge did not start") { QaLens.localBridgeStatus.value.startsWith("Listening") }
            waitFor("Unhooked dialog was not discovered") { nodes(call("snapshot").second).any { it.optString("tag") == "dialog.target" } }
            val initial = call("snapshot").second
            check(windows(initial).size == 2) { "Activity + unhooked dialog must be distinct windows: $initial" }
            val target = node("dialog.target")
            val background = node("dialog.background")
            check(target.getString("windowId") != background.getString("windowId"))
            check(nodes(initial).count { it.optString("tag") == "dialog.target" } == 1) { "Manual qaTag duplicated dialog semantics" }
            check(!initial.toString().contains("dialog-secret-password") && !initial.toString().contains("dialog-hidden-secret") && !initial.toString().contains("dialog.hidden"))
            val exported = call("component", JSONObject().put("tag", "dialog.target"))
            check(exported.first == 200 && exported.second.getJSONObject("content").getJSONObject("tree").getJSONArray("path").length() > 0)
            check(!exported.second.toString().contains("dialog-secret-password") && !exported.second.toString().contains("dialog-hidden-secret"))
            val selectors = call("selectors", JSONObject().put("id", target.getString("id")))
            check(selectors.first == 200 && selectors.second.getString("xml").contains("dialog.target"))
            check(!selectors.second.toString().contains("dialog-secret-password") && !selectors.second.toString().contains("dialog-hidden-secret"))
            check(call("query", JSONObject().put("xpath", "//node[@tag='dialog.target']")).second.getInt("count") == 1)
            val dialog = dialogView!!.rootView
            val geometry = IntArray(4)
            test.runOnMainSync { dialog.getLocationOnScreen(geometry); geometry[2] = dialog.width; geometry[3] = dialog.height }
            test.runOnMainSync { QaLens.setInspectMode(true) }
            awaitSurface(dialog)
            val overlay = surfaces().single()
            test.runOnMainSync {
                val source = IntArray(2); val host = IntArray(2)
                overlay.getLocationOnScreen(source); activity.window.decorView.getLocationOnScreen(host)
                check(source.contentEquals(host)) { "Dialog inspector origin $source differs from host $host" }
                val after = IntArray(4); dialog.getLocationOnScreen(after); after[2] = dialog.width; after[3] = dialog.height
                check(geometry.contentEquals(after)) { "Inspector resized/repositioned the host dialog" }
                check(dialog.hasWindowFocus()) { "Inspector stole dialog focus" }
            }
            val dialogParams = dialog.layoutParams as WindowManager.LayoutParams
            val oldDialogFlags = dialogParams.flags
            test.runOnMainSync {
                dialogParams.flags = oldDialogFlags or WindowManager.LayoutParams.FLAG_SECURE
                activity.windowManager.updateViewLayout(dialog, dialogParams)
                QaLens.invalidateInspection()
            }
            waitFor("Inspector failed to inherit the dialog's secure capture policy") {
                (surfaces().single().layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            }
            val (x, y) = point(target, activity)
            gesture(x, y)
            waitFor("Phone hit selected background instead of foreground dialog") { QaLens.state.value.selectedNode?.testTag == "dialog.target" }
            check(backgroundTaps.get() == 0 && dialogTaps.get() == 0) { "Inspection executed a host click" }
            clickSurface("Search selectors & tags")
            var search: AccessibilityNodeInfo? = null
            waitFor("SDK selector dialog did not open above the host dialog") {
                search = find { it.isEditable && it.contentDescription?.toString() == "Search tags, text, roles and actions" }
                search != null
            }
            check(windows(call("snapshot").second).size == 2) { "SDK's selector dialog was inspected as host content" }
            test.runOnMainSync {
                val selector = WindowInspector.getGlobalWindowViews().single { it.hasWindowFocus() }
                check((selector.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_SECURE != 0) {
                    "SDK selector dialog leaked the host dialog's secure capture policy"
                }
            }
            check(search!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "dialog.target")
            }))
            var result: AccessibilityNodeInfo? = null
            waitFor("Dialog tag was absent from mobile selector search") {
                result = find { it.text?.toString()?.contains("Tag: dialog.target") == true }; result != null
            }
            var row = result!!
            while (!row.isClickable && row.parent != null) row = row.parent
            check(row.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitSurface(dialog)
            check(QaLens.state.value.selectedNode?.testTag == "dialog.target" && dialogTaps.get() == 0)
            test.runOnMainSync { dialogParams.flags = oldDialogFlags; activity.windowManager.updateViewLayout(dialog, dialogParams); QaLens.invalidateInspection() }
            waitFor("Inspector retained a stale secure policy") {
                (surfaces().single().layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_SECURE == 0
            }
            screenshot("dialog-inspector-check")
            appScreenshot()
            test.runOnMainSync {
                check(surfaces().single().visibility == View.VISIBLE) { "Screenshot lost the dialog inspector" }
                check(activity.window.decorView.findViewWithTag<View>("qalens_overlay_compose_view").visibility == View.INVISIBLE) {
                    "Screenshot re-exposed the underlying Activity inspector"
                }
            }
            check(command("tap", "dialog.target").first == 200 && dialogTaps.get() == 1 && backgroundTaps.get() == 0)
            check(command("type", "dialog.field", "text" to "typed from PC").first == 200)
            waitFor("Dialog semantic text action did not run") { field.value == "typed from PC" }
            check(command("scroll", "dialog.scroll", "dy" to 120).first == 200)
            waitFor("Dialog semantic scroll did not run") { scroll.value > 0 }
            test.runOnMainSync { QaLens.setInspectMode(false); QaLens.setTagMode(true) }
            test.waitForIdleSync()
            Thread.sleep(500) // Commit pointer input and settle the IME after RequestFocus/SetText.
            val (tagX, tagY) = point(node("dialog.target"), activity)
            gesture(tagX, tagY)
            var copied = ""
            waitFor("Tag inspection did not copy the foreground tag") {
                test.runOnMainSync { copied = (test.targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString().orEmpty() }
                copied == "dialog.target"
            }
            gesture(tagX, tagY, fingers = 2)
            test.waitForIdleSync()
            check(backgroundTaps.get() == 0 && dialogTaps.get() == 1) { "Stationary two-finger inspection clicked the host" }
            val beforeScroll = scroll.value
            val scrollBounds = node("dialog.scroll").getJSONObject("bounds")
            val origin = IntArray(2)
            test.runOnMainSync { activity.window.decorView.getLocationOnScreen(origin) }
            gesture(scrollBounds.getDouble("left").toFloat() + origin[0] + 30,
                scrollBounds.getDouble("bottom").toFloat() + origin[1] - 100, -220f, fingers = 2)
            waitFor("Two-finger inspector drag did not scroll the dialog") { scroll.value > beforeScroll }
            check(backgroundTaps.get() == 0 && dialogTaps.get() == 1)
            test.runOnMainSync { hook.value = true }
            waitFor("Explicit hook duplicated automatically discovered root") { nodes(call("snapshot").second).count { it.optString("tag") == "dialog.target" } == 1 }
            val oldSurface = surfaces().single()
            test.runOnMainSync { nested.value = true; QaLens.selectNode(null); QaLens.setTagMode(false); QaLens.setInspectMode(true) }
            waitFor("Unhooked AlertDialog missing") { nodes(call("snapshot").second).any { it.optString("tag") == "dialog.nested" } }
            waitFor("Inspector still belongs to the underlying dialog") { surfaces().singleOrNull()?.let { it !== oldSurface } == true }
            awaitSurface(nestedView!!)
            selectAll()
            val nestedNode = node("dialog.nested")
            val nestedSnapshot = call("snapshot").second
            check(windows(nestedSnapshot).last().getString("id") == nestedNode.getString("windowId"))
            val (nx, ny) = point(nestedNode, activity)
            gesture(nx, ny)
            waitFor("Nested dialog cannot be selected") { QaLens.state.value.selectedNode?.testTag == "dialog.nested" }
            test.runOnMainSync { check(nestedView!!.rootView.hasWindowFocus()); nested.value = false }
            waitFor("Dismissed nested window retained stale nodes") { nodes(call("snapshot").second).none { it.optString("tag") == "dialog.nested" } }
            check(call("component", JSONObject().put("id", nestedNode.getString("id"))).first == 404)
            val closingCapture = System.currentTimeMillis()
            test.runOnMainSync { QaLens.takeScreenshot(share = false); show.value = false }
            waitFor("Dismissed dialog retained inspection surface") { surfaces().isEmpty() }
            waitFor("Dismissed dialog retained stale nodes") { nodes(call("snapshot").second).none { it.optString("tag")?.startsWith("dialog.") == true && it.optString("tag") !in listOf("dialog.main", "dialog.background") } }
            check(call("component", JSONObject().put("id", target.getString("id"))).first == 404)
            waitFor("Screenshot did not finish during dialog dismissal") {
                File(activity.cacheDir, "qalens").listFiles()?.any { it.name.startsWith("qa_") && it.extension == "png" && it.lastModified() >= closingCapture } == true
            }
            test.runOnMainSync {
                check(activity.window.decorView.findViewWithTag<View>("qalens_overlay_compose_view").visibility == View.VISIBLE) {
                    "Dialog dismissal/screenshot failed to restore the Activity inspector"
                }
            }
            test.runOnMainSync { popup.value = true }
            waitFor("Unhooked Compose Popup was not discovered") { nodes(call("snapshot").second).any { it.optString("tag") == "dialog.popup" } }
            waitFor("Popup inspector did not open") { surfaces().size == 1 }
            Thread.sleep(600); selectAll()
            screenshot("popup-inspector-check")
            val popupSnapshot = call("snapshot").second
            val popupNode = nodes(popupSnapshot).single { it.optString("tag") == "dialog.popup" }
            check(windows(popupSnapshot).last().getString("id") == popupNode.getString("windowId"))
            val (px, py) = point(popupNode, activity)
            gesture(px, py)
            waitFor("Popup cannot be selected at $px,$py; node=$popupNode; windows=${windows(popupSnapshot)}") { QaLens.state.value.selectedNode?.testTag == "dialog.popup" }
            test.runOnMainSync {
                activity.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            waitFor("Background retained a dialog inspector window") { surfaces().isEmpty() }
            check(call("snapshot").first == 503) { "Paused host exposed live dialog semantics" }
            test.targetContext.startActivity(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            waitFor("Resume did not restore dialog inspection") { surfaces().size == 1 && surfaces().single().isShown }
            test.runOnMainSync { QaLens.configure { enabled = false } }
            waitFor("Disable retained a dialog inspector window") { surfaces().isEmpty() }
            check(QaLens.localBridgeStatus.value == "Stopped")
            test.runOnMainSync { QaLens.configure { enabled = true }; QaLens.setInspectMode(false); popup.value = false }
            check(QaLens.localBridgeStatus.value == "Stopped")
        } finally {
            test.runOnMainSync {
                show.value = false; nested.value = false; popup.value = false
                QaLens.stopLocalBridge(); QaLens.setInspectMode(false); QaLens.setTagMode(false); QaLens.closePanel()
                QaLens.configure { enabled = old.enabled; enableSemanticsReflection = old.enableSemanticsReflection }
                activity.finish()
            }
        }
    }
}
