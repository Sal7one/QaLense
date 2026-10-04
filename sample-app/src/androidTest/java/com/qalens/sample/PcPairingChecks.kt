package com.qalens.sample

import android.app.Instrumentation
import android.content.Intent
import com.qalens.QaLens
import com.qalens.QaLensControlActivity
import kotlinx.coroutines.flow.StateFlow
import java.net.HttpURLConnection
import java.net.URL

/** Real shell offer / normal-app denial / phone approval with a manual-root consuming host. */
internal class PcPairingChecks(private val test: Instrumentation) {
    private val token = java.util.UUID.randomUUID().toString().replace("-", "")
    private val port = 18769
    private fun pending(): Any? {
        val type = Class.forName("com.qalens.QaLensPcPairing")
        return (type.getMethod("getRequest").invoke(type.getField("INSTANCE").get(null)) as StateFlow<*>).value
    }
    private fun offer(action: String = "REQUEST_PC_PAIRING", credential: String = token) {
        test.uiAutomation.executeShellCommand("am broadcast -n ${test.targetContext.packageName}/com.qalens.QaLensPcPairingReceiver -a com.qalens.action.$action --es token $credential --ei port $port")
            .use { descriptor -> android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() } }
    }
    private fun control() {
        // singleTask may already be visible: startActivitySync waits for a new instance forever.
        test.runOnMainSync { test.targetContext.startActivity(Intent(test.targetContext, QaLensControlActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        test.waitForIdleSync()
    }
    fun run() {
        RecordingControlChecks(test).removeAutoInstall()
        test.startActivitySync(Intent(test.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        test.runOnMainSync { QaLens.configure { enabled = true }; QaLens.stopLocalBridge() }
        try {
            val receiver = test.targetContext.packageManager.getReceiverInfo(
                android.content.ComponentName(test.targetContext, "com.qalens.QaLensPcPairingReceiver"), 0)
            check(receiver.permission == "android.permission.DUMP" && receiver.exported)
            // Normal app UID does not have DUMP; sending must not offer or start access.
            // A distinct installed test-package UID (not the host's own trusted UID).
            test.uiAutomation.executeShellCommand("run-as ${test.context.packageName} /system/bin/am broadcast --user 0 -n ${test.targetContext.packageName}/com.qalens.QaLensPcPairingReceiver -a com.qalens.action.REQUEST_PC_PAIRING --es token $token --ei port $port")
                .use { descriptor -> android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() } }
            Thread.sleep(400)
            check(pending() == null) { "Normal app bypassed adb sender permission" }
            offer(); await { pending() != null }
            val first = pending()
            offer(credential = token.reversed())
            check(pending() === first) { "Repeated offer swapped the request being approved" }
            check(QaLens.localBridgeStatus.value == "Stopped") { "Offering access started the listener before approval" }
            control(); PcInspectorUiChecks(test).click("Deny")
            check(pending() == null && QaLens.localBridgeStatus.value == "Stopped")
            offer(); await { pending() != null }
            offer("CANCEL_PC_PAIRING", token.reversed()); check(pending() != null)
            offer("CANCEL_PC_PAIRING"); await { pending() == null }
            offer(); await { pending() != null }; control()
            // The approval guard checks monotonic expiry even if Android delayed the timer callback.
            pending()!!.javaClass.getDeclaredField("expires").apply { isAccessible = true }.setLong(pending(), 0)
            PcInspectorUiChecks(test).click("Approve desktop")
            check(QaLens.localBridgeStatus.value == "Stopped") { "Expired request granted access" }
            offer(); await { pending() != null }; control()
            PcInspectorUiChecks(test).click("Approve desktop")
            await { QaLens.localBridgeStatus.value.startsWith("Listening") }
            await { runCatching { request(token) == 200 }.getOrDefault(false) }
            check(request(token.reversed()) == 401)
            check(!QaLens.buildFullReport().contains(token))
            test.runOnMainSync { QaLens.stopLocalBridge() }
            offer(); await { pending() != null }
            test.runOnMainSync { QaLens.configure { enabled = false } }
            check(pending() == null && QaLens.localBridgeStatus.value == "Stopped")
            offer(); check(pending() == null)
            test.runOnMainSync { QaLens.configure { enabled = true } }
            check(QaLens.localBridgeStatus.value == "Stopped")
        } finally { test.runOnMainSync { QaLens.configure { enabled = true }; QaLens.stopLocalBridge() } }
    }
    private fun request(credential: String): Int {
        val connection = URL("http://127.0.0.1:$port/v1/snapshot").openConnection() as HttpURLConnection
        connection.connectTimeout = 1000; connection.readTimeout = 2000
        connection.setRequestProperty("Authorization", "Bearer $credential")
        return try { connection.responseCode } finally { connection.disconnect() }
    }
    private fun await(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < until) Thread.sleep(100)
        check(condition()) { "Phone approval checkpoint timed out" }
    }
}
