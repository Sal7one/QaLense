package com.qalens.sample

import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.core.content.FileProvider
import com.qalens.QaLens
import com.qalens.QaLensRoot
import com.qalens.android.AppSalMacro
import com.qalens.android.AppSalQuery
import com.qalens.android.QaLensAppSal
import com.qalens.android.QaLensPrefs
import com.qalens.android.QaLensProfiles
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.FutureTask

/** Positive tester workflows missing from the narrower privacy/failure regression cases.
 * Only synthetic sample data is used; a loopback receiver checks a real multipart upload.
 */
internal class TesterWorkflowChecks(private val test: Instrumentation) {
    fun run() {
        val context = test.targetContext
        val original = QaLensAppSal.current(context)
        val prefs = context.getSharedPreferences("qalens_prefs", 0)
        val profiles = prefs.getString("qa_profiles", null)
        val active = prefs.getString("qa_active_profile", null)
        val user = QaLensPrefs.userName(context)
        val automation = test.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val activity = test.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        try {
            test.runOnMainSync {
                QaLens.configure { enabled = true; allowUnmaskedVideo = false; saveScreenshotsToGallery = false }
                QaLens.panicRestore(); QaLens.setPanelMinimal(true); QaLens.setTagMode(false)
            }
            quickCapture()
            diagnosticPanels()
            val archive = macroCapture(activity)
            configRoundTrip()
            profileUpload(archive)
            replay(archive)
            await("Host did not regain focus after replay") { activity.hasWindowFocus() }
            test.runOnMainSync {
                QaLens.closePanel(); QaLens.startRecording()
                check(QaLens.state.value.isRecording) { "Panic fixture did not start capture" }
                QaLens.setOverlayAlpha(.2f)
                QaLens.setOverlayEnabled(false); QaLens.panicRestore()
            }
            check(!QaLens.state.value.isRecording && !QaLens.state.value.isSavingRecording)
            check(QaLens.state.value.overlayEnabled && QaLens.state.value.overlayAlpha == 1f)
            check(activity.window.decorView.findViewWithTag<android.view.View>("qalens_overlay_compose_view") != null)
        } catch (failure: Throwable) {
            runCatching { test.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.cacheDir, "qalens-test-failure.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            } }
            throw failure
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
            test.runOnMainSync { QaLens.panicRestore() }
            QaLensAppSal.apply(context, original)
            prefs.edit().apply {
                if (profiles == null) remove("qa_profiles") else putString("qa_profiles", profiles)
                if (active == null) remove("qa_active_profile") else putString("qa_active_profile", active)
            }.apply()
            QaLensPrefs.setUserName(context, user)
            test.runOnMainSync {
                QaLens.setPanelMinimal(original.panelMode == "minimal")
                QaLens.setOverlayAlpha(original.overlayAlpha)
            }
        }
    }

    private fun quickCapture() {
        val directory = File(test.targetContext.cacheDir, "qalens")
        fun shots() = directory.listFiles().orEmpty().filter { it.extension == "png" }.map { it.name }.toSet()
        var before = shots()
        test.runOnMainSync { QaLens.openPanel() }
        click("Screenshot")
        await("Quick screenshot did not save") { shots().any { it !in before } }
        before = shots()
        test.runOnMainSync { QaLens.openPanel() }
        click("Mark a bug")
        await("Bug mark did not include a screenshot and breadcrumb") {
            shots().any { it !in before } && QaLens.state.value.events.any { it.message == "⭐ Marked by QA" }
        }
        val archives = QaLens.state.value.recordings.map { it.path }.toSet()
        test.runOnMainSync { QaLens.openPanel() }
        click("Record a session")
        await("Quick Record did not start") { QaLens.state.value.isRecording }
        Thread.sleep(1500)
        // Capture deliberately hides the Compose sheet. QA stops using the visible REC chip.
        click("Stop recording, elapsed", contains = true)
        await("Quick Stop did not save", 30_000) {
            !QaLens.state.value.isSavingRecording && QaLens.state.value.recordings.any { it.path !in archives }
        }
        dismissShare()
    }

    private fun macroCapture(activity: MainActivity): File {
        val input = mutableStateOf("")
        val submitted = mutableStateOf(false)
        val macro = AppSalMacro("Synthetic workflow", listOf("record", "wait 1000",
            "type workflow.input synthetic QA", "tap workflow.submit", "assert exists workflow.done",
            "mark Workflow completed", "screenshot", "wait 1000", "stop"))
        QaLensAppSal.setMacros(test.targetContext, listOf(macro))
        test.runOnMainSync {
            activity.setContent {
                QaLensRoot {
                    Column(Modifier.fillMaxSize()) {
                        OutlinedTextField(input.value, { input.value = it }, Modifier.testTag("workflow.input"))
                        Button({ submitted.value = true }, Modifier.testTag("workflow.submit")) { Text("Submit fixture") }
                        if (submitted.value) Text("Workflow completed", Modifier.testTag("workflow.done"))
                    }
                }
            }
            QaLens.openPanel()
        }
        click("More tools  +")
        click("Synthetic workflow")
        val type = Class.forName("com.qalens.QaLensMacros")
        val driver = type.getField("INSTANCE").get(null)
        val result = type.methods.first { it.name.startsWith("lastRunResult") }
        await("Macro did not complete", 45_000) { result.invoke(driver, macro.name) != null }
        val outcome = result.invoke(driver, macro.name)
        check(outcome.javaClass.getMethod("getPassed").invoke(outcome) == true) {
            "Positive macro failed: ${QaLens.state.value.events.takeLast(15).map { it.message }}"
        }
        check(input.value == "synthetic QA" && submitted.value)
        check(!QaLens.state.value.isRecording && !QaLens.state.value.isSavingRecording)
        dismissShare()
        return File(QaLens.state.value.recordings.first().path).also { check(it.isFile && it.length() > 0) }
    }

    private fun diagnosticPanels() {
        test.runOnMainSync { QaLens.setPanelMinimal(false); QaLens.openPanel() }
        fun strip(): AccessibilityNodeInfo? {
            fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (node.contentDescription?.toString() == "Diagnostic tabs") return node
                repeat(node.childCount) { node.getChild(it)?.let { child -> visit(child)?.let { return it } } }
                return null
            }
            return root()?.let(::visit)
        }
        val panels = listOf("Overview" to "Copy integration check", "Bug Bundle" to "Evidence Completeness",
            "Repro" to "Timeline (", "Screen Health" to "Visited Screens (", "Network" to "requests",
            "Navigation" to "Back Stack", "Accessibility" to "rule=", "Automation Tags" to "Show Tags On Screen",
            "Device & Build" to "App", "Tools" to "Bookmarks", "Inspect" to "Components (",
            "Logs" to "Live dashboard window")
        await("Diagnostic tab strip missing") { strip() != null }
        await("First diagnostic tab not reachable") {
            if (find("Overview")?.isVisibleToUser == true) true else {
                strip()?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                Thread.sleep(250); false
            }
        }
        for ((label, heading) in panels) {
            await("Diagnostic tab not reachable: $label") {
                val target = find(label)
                if (target?.isVisibleToUser == true) true else {
                    strip()?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    Thread.sleep(250); false
                }
            }
            // An already-selected tab already displays its pane.
            if (find(label)?.isSelected != true) click(label)
            await("Diagnostic tab did not show content: $label") {
                find(heading, contains = true) != null || (label == "Accessibility" && find("No warnings.") != null)
            }
        }
        test.runOnMainSync { QaLens.closePanel(); QaLens.setPanelMinimal(true); QaLens.setWatchMode(true) }
        await("Watch HUD missing") { find("● WATCH") != null }
        click("■ Stop")
        await("Watch Stop did not restore normal overlay") { !QaLens.state.value.isWatchMode }
    }

    private fun configRoundTrip() {
        val current = QaLensAppSal.current(test.targetContext).copy(panelMode = "full", overlayAlpha = .7f,
            queries = listOf(AppSalQuery("Fixture", "sample.db", "SELECT 1")), watchPrefs = listOf("sample_settings"))
        val decoded = checkNotNull(QaLensAppSal.decode(QaLensAppSal.encode(current, false)))
        check(decoded.packageName == test.targetContext.packageName && decoded.queries == current.queries)
        check(decoded.macros == current.macros && decoded.watchPrefs == current.watchPrefs)
        QaLensAppSal.apply(test.targetContext, decoded)
        check(QaLensAppSal.panelMode(test.targetContext) == "full")
        check(QaLensAppSal.queries(test.targetContext) == current.queries)
        check(QaLensAppSal.macros(test.targetContext) == current.macros)
        check(QaLensAppSal.decode("{\"format\":\"wrong\"}") == null)
        test.runOnMainSync { QaLens.setPanelMinimal(true) }
    }

    private fun profileUpload(archive: File) {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 15_000
            val received = FutureTask {
                server.accept().use { socket ->
                    socket.soTimeout = 15_000
                    val stream = socket.getInputStream()
                    val header = ByteArrayOutputStream()
                    var ending = 0
                    while (ending != 0x0d0a0d0a) {
                        val value = stream.read(); check(value >= 0 && header.size() < 65_536)
                        header.write(value); ending = (ending shl 8) or value
                    }
                    val headers = header.toString("UTF-8")
                    val length = headers.lineSequence().first { it.startsWith("Content-Length:", true) }
                        .substringAfter(':').trim().toInt()
                    check(length in 1..16_777_216)
                    val body = ByteArray(length)
                    java.io.DataInputStream(stream).readFully(body)
                    check(body.toString(Charsets.ISO_8859_1).contains(archive.name))
                    val response = "{\"ok\":true,\"verdict\":\"pass\"}"
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${response.length}\r\nConnection: close\r\n\r\n$response").toByteArray())
                    headers
                }
            }
            Thread(received, "workflow-upload-fixture").apply { isDaemon = true; start() }
            QaLensPrefs.setWebhookUrl(test.targetContext, "http://127.0.0.1:${server.localPort}/upload")
            QaLensPrefs.setWebhookHeaderValue(test.targetContext, "")
            QaLensPrefs.setWebhookParams(test.targetContext, "")
            val profile = QaLensProfiles.captureCurrent(test.targetContext, "Synthetic QA", "synthetic.qa")
            QaLensProfiles.save(test.targetContext, profile)
            QaLensProfiles.activate(test.targetContext, profile)
            check(QaLensProfiles.activeName(test.targetContext) == profile.name)
            test.runOnMainSync { QaLens.openPanel() }
            click("Send latest session")
            await("Successful upload was not shown to QA", 20_000) { find("The backend accepted this session.") != null }
            val headers = received.get(1, java.util.concurrent.TimeUnit.SECONDS)
            check(headers.contains("X-QaLens-User: synthetic.qa", true)) { "Profile attribution did not reach the receiver" }
            QaLensProfiles.delete(test.targetContext, profile.name)
            check(QaLensProfiles.activeName(test.targetContext) == null)
            test.runOnMainSync { QaLens.closePanel() }
        }
    }

    private fun replay(archive: File) {
        val context = test.targetContext
        val uri = FileProvider.getUriForFile(context, context.packageName + ".qalens.fileprovider", archive)
        val player = test.startActivitySync(Intent(Intent.ACTION_VIEW).setClassName(context, "com.qalens.replay.QaLensPlayerActivity")
            .setDataAndType(uri, "application/octet-stream")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        click("▶")
        await("Android replay did not start") { find("⏸") != null }
        click("⏸"); click("⏭"); click("⏮")
        for (track in listOf("Summary", "Timeline", "Network", "Logs", "State")) click(track)
        click("⛶")
        await("Player did not enter fullscreen") { find("⛶ exit")?.isVisibleToUser == true }
        check(test.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        test.waitForIdleSync()
        check(!player.isFinishing) { "Back from fullscreen closed the player instead of returning to replay controls" }
        await("Player controls did not return from fullscreen") { find("‹ Close")?.isVisibleToUser == true }
        click("‹ Close")
        await("Player Close did not return to the app") { player.isFinishing }
    }

    private fun dismissShare() {
        await("Recording share sheet missing") {
            root()?.packageName?.toString()?.let { "intentresolver" in it || it == "android" } == true
        }
        check(test.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        await("App did not resume after share") { root()?.packageName?.toString() == test.targetContext.packageName }
    }

    private fun root(): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 33) test.uiAutomation.clearCache()
        return test.uiAutomation.rootInActiveWindow
    }
    private fun find(label: String, contains: Boolean = false): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val text = node.text?.toString().orEmpty()
            val description = node.contentDescription?.toString().orEmpty()
            if (if (contains) text.contains(label) || description.contains(label) else text == label || description == label) return node
            repeat(node.childCount) { node.getChild(it)?.let { child -> visit(child)?.let { return it } } }
            return null
        }
        return root()?.let(::visit) ?: test.uiAutomation.windows.firstNotNullOfOrNull { it.root?.let(::visit) }
    }
    private fun click(label: String, contains: Boolean = false) {
        await("Workflow control missing: $label") {
            if (find(label, contains) != null) true else {
                find("Quick actions sheet")?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                Thread.sleep(250); false
            }
        }
        var node = checkNotNull(find(label, contains))
        if (!node.isVisibleToUser) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            test.waitForIdleSync()
            node = checkNotNull(find(label, contains))
        }
        while (!node.isClickable && node.parent != null) node = node.parent
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            "Could not click $label (selected=${node.isSelected}, enabled=${node.isEnabled}, actions=${node.actionList})"
        }
        test.waitForIdleSync()
    }
    private fun await(message: String, timeout: Long = 10_000, condition: () -> Boolean) {
        val until = android.os.SystemClock.elapsedRealtime() + timeout
        while (android.os.SystemClock.elapsedRealtime() < until) { if (condition()) return; Thread.sleep(100) }
        error("$message (open=${QaLens.state.value.isPanelOpen}, minimal=${QaLens.state.value.minimalPanel}, root=${root()?.packageName})")
    }
}
