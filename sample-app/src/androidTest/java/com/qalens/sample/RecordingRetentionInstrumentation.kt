package com.qalens.sample

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import com.qalens.NetworkEvent
import com.qalens.QaLens
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/** Dependency-free device regression runner; use a disposable sample-app emulator.
 * Exercises the real SDK observation hooks, UI clearing, async writer and Android ZIP producer.
 */
class RecordingRetentionInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = Bundle()
        try {
            val retained = record("retention", 600, null, clearUi = true)
            ZipFile(retained).use { zip ->
                val requests = JSONArray(read(zip, "network.json"))
                val ours = (0 until requests.length()).map { requests.getJSONObject(it) }
                    .filter { it.getString("url").contains("retention.test/retention/") }
                check(ours.size == 600) { "Only ${ours.size}/600 requests survived UI eviction/clearing" }
                check(ours.first().getInt("status") == 500) { "The earliest failure disappeared" }
                val logs = JSONArray(read(zip, "logs.json"))
                check((0 until logs.length()).count { logs.getJSONObject(it).getString("message").startsWith("retention-log-") } == 600)
                check(!coverage(zip).getBoolean("truncated"))
            }
            val limited = record("budget", 100, "x".repeat(100_000), clearUi = false)
            ZipFile(limited).use { zip ->
                val coverage = coverage(zip)
                val network = coverage.getJSONObject("tracks").getJSONObject("network")
                check(coverage.getBoolean("truncated")) { "Evidence budget loss was silent" }
                check(network.getLong("dropped") > 0)
                check(network.getLong("observed") == network.getLong("retained") + network.getLong("dropped"))
                check(network.getLong("retained") == JSONArray(read(zip, "network.json")).length().toLong())
                check(read(zip, "report.txt").contains("observations omitted"))
            }
            result.putString("stream", "\nOK: 600 requests and logs survived UI clearing; byte-budget loss is disclosed.\n")
            result.putString("retainedArchive", retained.name)
            result.putString("limitedArchive", limited.name)
            finish(android.app.Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            result.putString("stream", "\nFAIL: ${failure.stackTraceToString()}\n")
            finish(android.app.Activity.RESULT_CANCELED, result)
        }
    }

    private fun record(label: String, count: Int, body: String?, clearUi: Boolean): File {
        startActivitySync(Intent(targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        waitForIdleSync()
        val dir = File(targetContext.cacheDir, "qalens")
        val previous = dir.listFiles()?.map { it.name }?.toSet().orEmpty()
        runOnMainSync { QaLens.startRecording() }
        check(QaLens.state.value.isRecording) { "Recording did not start: ${QaLens.state.value.errors.map { it.message }}" }
        repeat(count) { n ->
            QaLens.logNetwork(NetworkEvent(method = "GET", url = "https://retention.test/$label/$n",
                status = if (n == 0 && label == "retention") 500 else 200, responseBodyPreview = body))
            if (clearUi) QaLens.log("retention-log-$n")
        }
        waitForIdleSync()
        if (clearUi) runOnMainSync { QaLens.clearLogs(); QaLens.clearNetworkLog() }
        runOnMainSync { QaLens.stopRecording() }
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val saved = dir.listFiles()?.firstOrNull { it.extension == "sal" && it.name !in previous }
            if (saved != null && !QaLens.state.value.isSavingRecording) return saved
            Thread.sleep(100)
        }
        error("Recording did not finish saving")
    }

    private fun read(zip: ZipFile, name: String): String {
        val bytes = zip.getInputStream(zip.getEntry(name) ?: error("Missing $name")).use { it.readBytes() }
        return if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte())
            GZIPInputStream(bytes.inputStream()).bufferedReader().use { it.readText() }
        else bytes.toString(Charsets.UTF_8)
    }
    private fun coverage(zip: ZipFile) = JSONObject(read(zip, "analysis.json")).getJSONObject("coverage").getJSONObject("recording")
}
