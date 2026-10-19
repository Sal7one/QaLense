package com.qalens.sample

import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.qalens.QaLens
import com.qalens.QaLensControlActivity
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Pair through SDK UI only. No sample Settings, generated test token or API start hook. */
internal class PcInspectorUiChecks(private val test: Instrumentation) {
    private val port = 18767
    fun run(manualRoot: Boolean = false) {
        if (manualRoot) RecordingControlChecks(test).removeAutoInstall()
        test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        test.runOnMainSync { QaLens.configure { enabled = true }; QaLens.stopLocalBridge(); QaLens.setPanelMinimal(true); QaLens.openPanel() }
        try {
            check(QaLens.localBridgeStatus.value == "Stopped")
            click("Connect to PC"); click("Manual pairing")
            val field = seek { it.contentDescription?.toString() == "PC inspector device port" }
            check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, port.toString())
            }))
            click("Start PC inspector")
            listening()
            val original = revealToken()
            test.runOnMainSync { QaLens.closePanel() }
            check(call(original) == 200) { "SDK UI pairing cannot read host Compose tree" }
            val (_, snapshot) = request(original, "snapshot")
            val target = snapshot.getJSONArray("nodes").let { nodes -> (0 until nodes.length()).map { nodes.getJSONObject(it) }
                .first { it.optString("tag").isNotBlank() } }
            check(request(original, "command", JSONObject().put("action", "select").put("id", target.getString("id"))).first == 200)
            click("Send to PC")
            await("Send to PC did not capture attributes in a root-only host") {
                request(original, "components/inbox").second.getJSONArray("transfers").length() == 1
            }
            check(!QaLens.buildFullReport().contains(original)) { "Pairing token entered a QA report" }
            test.runOnMainSync { test.targetContext.startActivity(Intent(test.targetContext, QaLensControlActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            check(revealToken() == original) { "Control Room replaced the overlay's pairing" }
            check(request(original, "components/inbox").first == 200) { "Control Room blocked already-captured component transfer" }
            check(request(original, "recordings").first == 200) { "Control Room blocked recording discovery" }
            click("New pairing token"); listening()
            val rotated = revealToken()
            check(rotated != original)
            check(call(original) == 401) { "New token did not revoke old pairing" }
            check(test.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            await("Host did not resume after Control Room") { runCatching { call(rotated) == 200 }.getOrDefault(false) }
            test.runOnMainSync { QaLens.setPanelMinimal(false); QaLens.openPanel() }
            click("Back to quick actions")
            click("PC connection"); click("Manual pairing")
            check(revealToken() == rotated) { "Full overlay lost shared pairing" }
            click("Stop PC inspector")
            await("UI Stop did not close bridge") { QaLens.localBridgeStatus.value == "Stopped" }
            check(runCatching { call(rotated) }.getOrNull() != 200)
            click("Start PC inspector"); listening()
            test.runOnMainSync { QaLens.configure { enabled = false } }
            check(QaLens.localBridgeStatus.value == "Stopped")
            test.runOnMainSync { QaLens.configure { enabled = true } }
            check(QaLens.localBridgeStatus.value == "Stopped") { "Re-enable reused a pairing" }
        } finally {
            test.runOnMainSync { QaLens.stopLocalBridge(); QaLens.closePanel(); QaLens.setPanelMinimal(true) }
        }
    }

    private fun listening() = await("SDK pairing did not listen") { QaLens.localBridgeStatus.value.startsWith("Listening") }
    private fun revealToken(): String {
        click("Show pairing token")
        return seek { it.text?.toString()?.matches(Regex("[a-f0-9]{48}")) == true }.text.toString()
    }
    private fun call(token: String): Int = request(token, "snapshot").first
    private fun request(token: String, path: String, body: JSONObject? = null): Pair<Int, JSONObject> {
        val connection = URL("http://127.0.0.1:$port/v1/$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 1000; connection.readTimeout = 3000
        connection.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        return try {
            val status = connection.responseCode
            val payload = (if (status < 400) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            status to JSONObject(payload)
        } finally { connection.disconnect() }
    }
    internal fun click(label: String, horizontal: Boolean = false) {
        var node = seek(horizontal) { it.text?.toString() == label || it.contentDescription?.toString() == label }
        while (!node.isClickable && node.parent != null) node = node.parent
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "SDK button could not be clicked: $label" }
        Thread.sleep(200)
    }
    private fun seek(horizontal: Boolean = false, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        fun find(node: AccessibilityNodeInfo, condition: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            if (condition(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { find(it, condition)?.let { found -> return found } }
            return null
        }
        val until = System.currentTimeMillis() + 10_000
        var direction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        while (System.currentTimeMillis() < until) {
            if (Build.VERSION.SDK_INT >= 33) test.uiAutomation.clearCache()
            val root = test.uiAutomation.rootInActiveWindow
            if (root != null) {
                find(root) { it.isVisibleToUser && predicate(it) }?.let { return it }
                val offscreen = find(root, predicate)
                if (offscreen?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id) != true) {
                    val scroll = find(root) { it.isScrollable && (it.className?.toString() == "android.widget.HorizontalScrollView") == horizontal }
                    if (scroll?.performAction(direction) != true) direction = if (direction == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
            }
            Thread.sleep(250)
        }
        error("SDK PC inspector UI control missing")
    }
    private fun await(message: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < until) Thread.sleep(100)
        check(condition()) { message }
    }
}
