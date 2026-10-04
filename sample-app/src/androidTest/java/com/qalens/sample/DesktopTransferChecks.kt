package com.qalens.sample

import android.app.Instrumentation
import android.content.Intent
import com.qalens.QaLens
import com.qalens.QaLensControlActivity
import java.io.File

/** Paired by test_device_transfer.js, using synthetic sample data and a one-run random token. */
internal class DesktopTransferChecks(private val test: Instrumentation) {
    fun run(token: String) {
        require(token.matches(Regex("[a-f0-9]{48}")))
        val exchange = File(test.targetContext.filesDir, "qalens-transfer-test")
        exchange.deleteRecursively(); check(exchange.mkdirs())
        val stage = File(exchange, "stage")
        val signal = File(exchange, "signal")
        RecordingControlChecks(test).removeAutoInstall()
        test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        test.waitForIdleSync()
        try {
            test.runOnMainSync { QaLens.startLocalBridge(token, 18768) }
            await("Bridge did not listen") { QaLens.localBridgeStatus.value.startsWith("Listening") }
            stage.writeText("ready")
            await("PC did not establish its baseline") { signal.readTextOrEmpty() == "record" }
            test.runOnMainSync { QaLens.startRecording() }
            check(QaLens.state.value.isRecording)
            Thread.sleep(1800)
            test.runOnMainSync { QaLens.saveRecentClip(10, "Desktop transfer fixture") }
            Thread.sleep(500)
            test.runOnMainSync { QaLens.stopRecording() }
            await("Fixture did not save") { !QaLens.state.value.isSavingRecording }
            // Android's share sheet may be foreground. Control Room exercises a paused host too.
            test.startActivitySync(Intent(test.targetContext, QaLensControlActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            stage.writeText("saved")
            await("PC did not copy master and clip") { signal.readTextOrEmpty() == "copied" }
            test.runOnMainSync { QaLens.startLocalBridge(token.reversed(), 18768) }
            await("Rotated bridge did not listen") { QaLens.localBridgeStatus.value.startsWith("Listening") }
            stage.writeText("rotated")
            await("PC did not reject revoked pairing") { signal.readTextOrEmpty() == "done" }
        } finally {
            test.runOnMainSync { QaLens.stopLocalBridge() }
            exchange.deleteRecursively()
        }
    }

    private fun File.readTextOrEmpty() = if (exists()) readText() else ""
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 90_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        check(condition()) { message }
    }
}
