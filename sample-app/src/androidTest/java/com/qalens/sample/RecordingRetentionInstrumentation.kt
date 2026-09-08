package com.qalens.sample

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import com.qalens.*
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
            verifyOssIntegrations()
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
            result.putString("stream", "\nOK: 600 requests and logs survived UI clearing; byte-budget loss is disclosed; Chucker coexistence, adapters and crash bridge pass.\n")
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
        runOnMainSync {
            QaLens.configure { captureNetworkBodies = body != null }
            QaLens.startRecording()
        }
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

    private fun verifyOssIntegrations() {
        check(QaLensChuckerBridge.isAvailable(targetContext)) { "Real Chucker was not detected" }
        var launchIntent: Intent? = null
        val launchContext = object : android.content.ContextWrapper(targetContext) {
            override fun startActivity(intent: Intent) { launchIntent = intent }
        }
        check(QaLensChuckerBridge.launch(launchContext)) { "Public Chucker launcher failed" }
        check(launchIntent?.component?.className?.startsWith("com.chuckerteam.chucker.") == true)

        runOnMainSync { QaLens.configure { networkFromChucker = true; captureNetworkBodies = false } }
        val payload = "{\"result\":\"preserved\"}"
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5_000
            val worker = java.util.concurrent.FutureTask {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { /* consume request headers */ }
                    socket.getOutputStream().write(("HTTP/1.1 201 Created\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${payload.toByteArray().size}\r\nConnection: close\r\n\r\n" + payload).toByteArray())
                }
            }
            Thread(worker, "qalens-fixture-http").start()
            val client = SampleOssTools.httpClient(targetContext).newBuilder()
                .addInterceptor(QaLensOkHttpInterceptor()).build() // accidental duplicate
            val url = "http://127.0.0.1:${server.localPort}/oss-check?token=fixture-secret"
            client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use {
                check(it.code == 201 && it.body?.string() == payload) { "Inspector altered the response" }
            }
            worker.get(5, java.util.concurrent.TimeUnit.SECONDS)
            waitForIdleSync()
            val events = QaLens.state.value.networkEvents.filter { it.url.contains("/oss-check") }
            check(events.size == 1 && events.single().status == 201) { "Chucker coexistence lost or duplicated the request" }
            check(!events.single().url.contains("fixture-secret") && events.single().responseBodyPreview == null)
        }
        val sink = QaLens.networkSink("Test transport")
        sink.record(NetworkEvent(method = "GET", url = "https://example.test/adapter", error = "person@example.test"))
        waitForIdleSync()
        check(QaLens.state.value.networkEvents.last().error == "[EMAIL_REDACTED]")
        val count = QaLens.state.value.networkEvents.size
        runOnMainSync { QaLens.configure { captureNetwork = false } }
        sink.record(NetworkEvent(method = "GET", url = "https://example.test/disabled"))
        waitForIdleSync()
        check(QaLens.state.value.networkEvents.size == count)
        runOnMainSync { QaLens.configure { captureNetwork = true; networkFromChucker = false } }

        var echoed = 0
        var inbound: ((QaLensCrash) -> Unit)? = null
        QaLens.bridgeCrashes(object : QaLensCrashBridge {
            override fun enrich(crash: QaLensCrash, evidence: String) { echoed++ }
            override fun onCrash(callback: (QaLensCrash) -> Unit) { inbound = callback }
        })
        inbound!!(QaLensCrash(type = CrashType.CRASH, thread = "vendor", throwable = "person@example.test", stackTrace = "fixture"))
        waitForIdleSync()
        check(echoed == 0) { "Inbound crash was echoed back to the vendor" }
        check(QaLens.state.value.crashes.last().throwable == "[EMAIL_REDACTED]")
        QaLens.bridgeCrashes(QaLensNoopCrashBridge)
    }

    private fun read(zip: ZipFile, name: String): String {
        val bytes = zip.getInputStream(zip.getEntry(name) ?: error("Missing $name")).use { it.readBytes() }
        return if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte())
            GZIPInputStream(bytes.inputStream()).bufferedReader().use { it.readText() }
        else bytes.toString(Charsets.UTF_8)
    }
    private fun coverage(zip: ZipFile) = JSONObject(read(zip, "analysis.json")).getJSONObject("coverage").getJSONObject("recording")
}
