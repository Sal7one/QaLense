package com.qalens.sample

import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.qalens.QaLens
import java.io.File

/** Real overlay search/copy; optional checkpoints keep a paired browser session available. */
internal class SelectorUiChecks(private val test: Instrumentation) {
    fun run(desktop: Boolean = false) {
        test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        test.runOnMainSync {
            QaLens.configure { enabled = true; enableSemanticsReflection = true }
            QaLens.setInspectMode(false); QaLens.setTagMode(false); QaLens.setPanelMinimal(true); QaLens.openPanel()
        }
        val exchange = File(test.targetContext.filesDir, "qalens-selector-test")
        if (desktop) { exchange.deleteRecursively(); check(exchange.mkdirs()); File(exchange, "stage").writeText("ready") }
        try {
            val ui = PcInspectorUiChecks(test)
            if (desktop) {
                await("Browser did not request phone approval", 180_000) { signal(exchange) == "approve" }
                ui.click("Approve desktop")
                File(exchange, "stage").writeText("paired")
                await("Browser did not request phone selection", 180_000) { signal(exchange) == "select-phone" }
                test.runOnMainSync { QaLens.setInspectMode(false); QaLens.setPanelMinimal(true); QaLens.openPanel() }
            }
            ui.click("More tools")
            ui.click("Search selectors & tags")
            val field = seek("Editable selector search field missing") {
                it.contentDescription?.toString() == "Search tags, text, roles and actions" && it.isEditable
            }
            check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "home.total.balance")
            })) { "Selector search cannot edit: editable=${field.isEditable}, class=${field.className}, actions=${field.actionList}, children=${field.childCount}" }
            var row = seek("Tag search did not show total balance") { it.text?.toString()?.contains("Tag: home.total.balance") == true }
            while (!row.isClickable && row.parent != null) row = row.parent
            check(row.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("Search did not select the host element") { QaLens.state.value.selectedNode?.testTag == "home.total.balance" && QaLens.state.value.isInspectMode && !QaLens.state.value.isPanelOpen }
            ui.click("Actions & XPath selectors")
            var selector = seek("Generated tag XPath missing") { it.contentDescription?.toString()?.startsWith("Copy Test tag XPath,") == true }
            while (!selector.isClickable && selector.parent != null) selector = selector.parent
            check(selector.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Generated selector cannot be copied" }
            var copied = ""
            test.runOnMainSync { copied = (test.targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString().orEmpty() }
            check(copied == "//node[@tag='home.total.balance']") { "Overlay copied the wrong selector" }
            if (desktop) {
                File(exchange, "stage").writeText("phone-selected")
                await("Browser did not select its second element", 180_000) { signal(exchange) == "check-pc" }
                val expected = File(exchange, "expected-tag").readText().trim()
                await("PC did not highlight the selected tag") { QaLens.state.value.selectedNode?.testTag == expected }
                File(exchange, "stage").writeText("pc-selected")
                await("Browser test did not finish", 180_000) { signal(exchange) == "done" }
            }
        } finally {
            test.runOnMainSync { QaLens.stopLocalBridge(); QaLens.setInspectMode(false); QaLens.closePanel() }
            if (desktop) exchange.deleteRecursively()
        }
    }
    private fun signal(directory: File) = File(directory, "signal").takeIf { it.exists() }?.readText()?.trim()
    private fun seek(message: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var match: AccessibilityNodeInfo? = null
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && predicate(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { find(it)?.let { found -> return found } }
            return null
        }
        var diagnostics = ""
        fun describe(node: AccessibilityNodeInfo) {
            if (node.contentDescription != null || node.isEditable) diagnostics += "\n${node.className}: ${node.text} / ${node.contentDescription}, editable=${node.isEditable}, actions=${node.actionList}"
            for (i in 0 until node.childCount) node.getChild(i)?.let(::describe)
        }
        try { await(message) {
            if (android.os.Build.VERSION.SDK_INT >= 33) test.uiAutomation.clearCache()
            match = test.uiAutomation.rootInActiveWindow?.let(::find); match != null
        } } catch (failure: IllegalStateException) {
            test.uiAutomation.rootInActiveWindow?.let(::describe)
            error("${failure.message}$diagnostics")
        }
        return match!!
    }
    private fun await(message: String, timeout: Long = 10_000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeout
        while (!condition() && System.currentTimeMillis() < until) Thread.sleep(100)
        check(condition()) { message }
    }
}
