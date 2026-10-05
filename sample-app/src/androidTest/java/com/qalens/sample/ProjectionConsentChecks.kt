package com.qalens.sample

import android.app.Instrumentation
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.FutureTask

/** Explicit disposable-device test option. Production capture always keeps Android consent. */
internal object ProjectionConsentChecks {
    fun start(test: Instrumentation, decision: String?): FutureTask<Unit>? {
        if (decision == null) return null
        require(decision == "approve" || decision == "deny") { "projectionConsent must be approve or deny" }
        // Connect once on the instrumentation thread before the consent worker and UI checks
        // access it together; concurrent first access races UiAutomation's connection lifecycle.
        val automation = test.uiAutomation
        val result = FutureTask {
            val deadline = android.os.SystemClock.elapsedRealtime() + 60_000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
                val root = automation.rootInActiveWindow
                // Never match an SDK button or dismiss its privacy/disabled error. The OS owns
                // this window; these English labels are for the documented sample emulator.
                if (root?.packageName?.toString() == "com.android.systemui") {
                    fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                        val label = node.text?.toString().orEmpty()
                        val labels = if (decision == "deny") listOf("Cancel") else listOf("Share screen", "Start now")
                        if (labels.any { it.equals(label, true) }) return node
                        repeat(node.childCount) { node.getChild(it)?.let { child -> find(child)?.let { return it } } }
                        return null
                    }
                    find(root)?.let { target ->
                        if (!target.isVisibleToUser) {
                            target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                            return@let
                        }
                        var button = target
                        while (!button.isClickable && button.parent != null) button = button.parent
                        check(button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "OS consent button rejected the test action" }
                        return@FutureTask Unit
                    }
                }
                Thread.sleep(100)
            }
            error("OS projection consent not found; handle this device's dialog manually")
        }
        Thread(result, "projection-consent-fixture").apply { isDaemon = true; start() }
        return result
    }
}
