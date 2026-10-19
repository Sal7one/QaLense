package example

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.qalens.QaLens
import java.io.File

/** Run the precompiled SDK UI on a host-supplied Compose runtime, not a recompiled same-BOM SDK. */
class ControlRoomCompatibilityInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = Bundle()
        var passed = false
        val prefs = targetContext.getSharedPreferences("qalens_prefs", 0)
        val oldUrl = prefs.getString("webhook_url", null)
        val fixture = File(targetContext.filesDir, "qalens/recordings/consumer-layout-fixture-${System.nanoTime()}.sal")
        try {
            check(!fixture.exists()) { "Fixture path already exists" }
            fixture.parentFile!!.mkdirs()
            fixture.writeText("synthetic layout fixture; not a replay archive")
            prefs.edit().putString("webhook_url", "http://127.0.0.1:1/layout-fixture").commit()
            runOnMainSync { QaLens.configure { enabled = true }; QaLens.refreshRecordings() }
            startActivitySync(Intent().setClassName(targetContext.packageName, "com.qalens.QaLensControlActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            // The old SDK dies with FlowRow NoSuchMethodError before this can render.
            seek("Open panel in app"); seek("Dock:", contains = true)
            seek("Delete").performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            Thread.sleep(200)
            // Read one viewport. Seeking each button can scroll between measurements and
            // falsely compare a top row's old bounds with a bottom row's new bounds.
            val actions = listOf("▶ Play", "Share", "⇪ Webhook", "Delete").map { label ->
                var node = checkNotNull(find(label)); check(node.isVisibleToUser) { "$label is clipped" }
                while (!node.isClickable && node.parent != null) node = node.parent
                val bounds = Rect(); node.getBoundsInScreen(bounds); bounds
            }
            val touchMin = (48 * targetContext.resources.displayMetrics.density).toInt()
            check(actions.all { it.width() >= touchMin && it.height() >= touchMin }) { "Recording actions were squeezed: $actions" }
            actions.forEachIndexed { i, a -> actions.drop(i + 1).forEach { b ->
                check(!Rect.intersects(a, b)) { "Recording actions overlap: $actions" }
            } }
            seek("⇪ Export & share"); seek("⤓ Import .appsal")
            uiAutomation.takeScreenshot()?.let { bitmap ->
                File(targetContext.cacheDir, "qalens-consumer-control.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            result.putString("stream", "\nOK: External consumer Control Room renders rescue, recording and configuration action groups on the host Compose runtime.\n")
            passed = true
        } catch (failure: Throwable) {
            uiAutomation.takeScreenshot()?.let { bitmap ->
                File(targetContext.cacheDir, "qalens-consumer-control-failure.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            result.putString("stream", "\nFAIL: ${failure.stackTraceToString()}\n")
        } finally {
            fixture.delete()
            prefs.edit().apply { if (oldUrl == null) remove("webhook_url") else putString("webhook_url", oldUrl) }.commit()
        }
        finish(if (passed) android.app.Activity.RESULT_OK else android.app.Activity.RESULT_CANCELED, result)
    }

    private fun find(label: String, contains: Boolean = false): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            if (if (contains) text.contains(label) || desc.contains(label) else text == label || desc == label) return node
            repeat(node.childCount) { node.getChild(it)?.let { visit(it)?.let { found -> return found } } }
            return null
        }
        return uiAutomation.rootInActiveWindow?.let(::visit)
    }

    private fun seek(label: String, contains: Boolean = false): AccessibilityNodeInfo {
        val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
        var direction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            find(label, contains)?.let { node ->
                if (node.isVisibleToUser) return node
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                Thread.sleep(150)
            }
            val scroll = find("Control Room sections")
            if (scroll?.performAction(direction) == false) direction = if (direction == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            Thread.sleep(150)
        }
        error("Control Room did not render $label on the host runtime")
    }
}
