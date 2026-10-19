package com.qalens.sample

import android.app.Instrumentation
import android.content.Intent
import com.qalens.QaLens
import com.qalens.QaLensControlActivity
import kotlinx.coroutines.flow.StateFlow
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real shell offer / normal-app denial / phone approval with a manual-root consuming host. */
internal class PcPairingChecks(private val test: Instrumentation) {
    private val token = java.util.UUID.randomUUID().toString().replace("-", "")
    private val port = 18769
    private fun pending(): Any? {
        val type = Class.forName("com.qalens.QaLensPcPairing")
        return (type.getMethod("getRequest").invoke(type.getField("INSTANCE").get(null)) as StateFlow<*>).value
    }
    private fun offer(action: String = "REQUEST_PC_PAIRING", credential: String = token): String =
        test.uiAutomation.executeShellCommand("am broadcast -n ${test.targetContext.packageName}/com.qalens.QaLensPcPairingReceiver -a com.qalens.action.$action --es token $credential --ei port $port")
            .use { descriptor -> android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().toString(Charsets.UTF_8) } }
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
            check(offer("QUERY_PC_PAIRING").contains("result=10"))
            check(offer("QUERY_PC_PAIRING", token.reversed()).contains("result=13"))
            val deniedQuery = test.uiAutomation.executeShellCommand("run-as ${test.context.packageName} /system/bin/am broadcast --user 0 -n ${test.targetContext.packageName}/com.qalens.QaLensPcPairingReceiver -a com.qalens.action.QUERY_PC_PAIRING --es token $token")
                .use { descriptor -> android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().toString(Charsets.UTF_8) } }
            check(!deniedQuery.contains("result=10")) { "Normal app read shell-only approval status" }
            val first = pending()
            offer(credential = token.reversed())
            check(pending() === first) { "Repeated offer swapped the request being approved" }
            check(QaLens.localBridgeStatus.value == "Stopped") { "Offering access started the listener before approval" }
            control(); PcInspectorUiChecks(test).click("Deny")
            check(pending() == null && QaLens.localBridgeStatus.value == "Stopped")
            check(offer("QUERY_PC_PAIRING").contains("result=13"))
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
            val approved = offer("QUERY_PC_PAIRING")
            check(approved.contains("result=12") && approved.contains("data=\"$port\"")) { "Phone did not report its approved port" }
            await { runCatching { request(token) == 200 }.getOrDefault(false) }
            check(request(token.reversed()) == 401)
            check(request(token.reversed(), "health") == 401)
            healthWhileMainBusy()
            check(!QaLens.buildFullReport().contains(token))
            offer("CANCEL_PC_PAIRING", token.reversed())
            check(request(token, "health") == 200) { "Mismatched cancellation revoked someone else's inspector" }
            offer(credential = token.reversed())
            val nextRequest = pending()
            check(nextRequest != null)
            offer("CANCEL_PC_PAIRING")
            await { QaLens.localBridgeStatus.value == "Stopped" }
            check(pending() === nextRequest && offer("QUERY_PC_PAIRING", token.reversed()).contains("result=10")) {
                "Old approved-session cancellation cleared a newer phone prompt"
            }
            offer("CANCEL_PC_PAIRING", token.reversed()); check(pending() == null)
            check(offer("QUERY_PC_PAIRING").contains("result=13")) { "Cancel after approval left access active" }
            offer(); await { pending() != null }
            test.runOnMainSync { QaLens.configure { enabled = false } }
            check(pending() == null && QaLens.localBridgeStatus.value == "Stopped")
            offer(); check(pending() == null)
            check(offer("QUERY_PC_PAIRING").contains("result=14"))
            test.runOnMainSync { QaLens.configure { enabled = true } }
            check(QaLens.localBridgeStatus.value == "Stopped")
        } finally { test.runOnMainSync { QaLens.configure { enabled = true }; QaLens.stopLocalBridge() } }
    }
    private fun request(credential: String, route: String = "snapshot"): Int = call(credential, route).first
    private fun call(credential: String, route: String): Pair<Int, JSONObject> {
        val connection = URL("http://127.0.0.1:$port/v1/$route").openConnection() as HttpURLConnection
        connection.connectTimeout = 1000; connection.readTimeout = 2000
        connection.setRequestProperty("Authorization", "Bearer $credential")
        return try {
            val code = connection.responseCode
            val stream = if (code < 400) connection.inputStream else connection.errorStream
            code to JSONObject(stream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }
    private fun healthWhileMainBusy() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        try {
            check(entered.await(2, TimeUnit.SECONDS))
            val start = android.os.SystemClock.elapsedRealtime()
            val (code, health) = call(token, "health")
            check(code == 200 && health.getString("schema") == "qalens.bridge.health")
            check(health.getString("package") == test.targetContext.packageName && health.getInt("port") == port)
            check(android.os.SystemClock.elapsedRealtime() - start < 1_000) { "Connection health waited for the main thread" }
            // The old approval probe did depend on main; preserve that contract for capture controls.
            check(runCatching { request(token, "recordings") }.getOrDefault(-1) != 200) { "Expected main-dependent recording controls to wait" }
        } finally { release.countDown(); test.waitForIdleSync() }
    }
    private fun await(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < until) Thread.sleep(100)
        check(condition()) { "Phone approval checkpoint timed out" }
    }
}
