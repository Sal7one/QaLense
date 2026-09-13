package com.qalens.sample

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
            verifyClientSafety()
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
            result.putString("stream", "\nOK: 600 requests and logs survived UI clearing; byte-budget loss is disclosed; Chucker coexistence, adapters and crash bridge pass; client privacy, disable/resume, navigation, DataStore, macros, SQL, replay and webhook queue/retry checks pass.\n")
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

    private fun waitUntil(message: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!check() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        check(check()) { message }
    }

    private fun verifyClientSafety() {
        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        runOnMainSync {
            QaLens.configure { enabled = true; allowUnmaskedVideo = false; saveScreenshotsToGallery = false }
            activity.setContent { Box(Modifier.fillMaxSize().background(Color.Red).qaHiddenFromReports()) }
        }
        waitForIdleSync()
        Thread.sleep(700) // Let the synthetic red surface be presented before PixelCopy.
        val dir = File(targetContext.cacheDir, "qalens")
        fun screenshots() = dir.listFiles().orEmpty().filter { it.extension == "png" }.map { it.name }.toSet()
        val before = screenshots()
        runOnMainSync { QaLens.takeScreenshot(share = false) }
        waitUntil("Screenshot was not saved") { screenshots().any { it !in before } }
        val shot = dir.listFiles()!!.first { it.extension == "png" && it.name !in before }
        val bitmap = android.graphics.BitmapFactory.decodeFile(shot.path)
        check(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) == android.graphics.Color.BLACK) { "Hidden Compose content leaked into screenshot" }
        bitmap.recycle()
        val secureBefore = screenshots()
        runOnMainSync {
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            QaLens.takeScreenshot(share = false)
        }
        Thread.sleep(400)
        check(screenshots() == secureBefore) { "FLAG_SECURE window was captured" }
        runOnMainSync { activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }

        // The named macro driver is internal; exercise it through its JVM entry point from this
        // external consumer fixture without exposing test-only API in the shipped SDK.
        val macros = Class.forName("com.qalens.QaLensMacros")
        val driver = macros.getField("INSTANCE").get(null)
        val run = macros.getMethod("run", com.qalens.android.AppSalMacro::class.java)
        val result = macros.methods.first { it.name.startsWith("lastRunResult") }
        for ((index, step) in listOf("unknown-action", "assert nonsense", "record video").withIndex()) {
            val name = "invalid-fixture-$index"
            runOnMainSync { run.invoke(driver, com.qalens.android.AppSalMacro(name, listOf(step))) }
            waitUntil("Macro did not terminate: $step") { result.invoke(driver, name) != null }
            val outcome = result.invoke(driver, name)
            check(outcome.javaClass.getMethod("getPassed").invoke(outcome) == false) { "Invalid macro passed: $step" }
        }

        runOnMainSync {
            activity.setContent { Box(Modifier.fillMaxSize().testTag("route-one")) }
            QaLens.setScreen("Route one", "route-one")
        }
        waitUntil("Initial route semantics missing") { QaLens.state.value.nodes.any { it.testTag == "route-one" } }
        runOnMainSync {
            QaLens.setScreen("Route two", "route-two")
            check(QaLens.state.value.nodes.none { it.testTag == "route-one" }) { "Previous route nodes survived screen change" }
            activity.setContent { Box(Modifier.fillMaxSize().background(Color.Red).testTag("route-two").qaHiddenFromReports()) }
        }
        waitForIdleSync()

        val starts = java.util.concurrent.atomic.AtomicInteger()
        val stops = java.util.concurrent.atomic.AtomicInteger()
        QaLens.observeDataStore("fixture-observer", kotlinx.coroutines.flow.flow<Int> {
            starts.incrementAndGet()
            try { emit(0); kotlinx.coroutines.awaitCancellation() } finally { stops.incrementAndGet() }
        })
        waitUntil("DataStore observer did not start") { starts.get() == 1 }
        val archivesBefore = dir.listFiles().orEmpty().map { it.name }.toSet()
        val memoryBefore = QaLens.state.value.memorySamples.size
        runOnMainSync { QaLens.startRecording() }
        check(QaLens.state.value.isRecording)
        Thread.sleep(2600)
        check(QaLens.state.value.memorySamples.size > memoryBefore) { "Recording never sampled memory" }
        runOnMainSync { QaLens.configure { enabled = false } }
        check(!QaLens.state.value.isRecording)
        waitUntil("DataStore observer survived disable") { stops.get() == 1 }
        check(activity.window.decorView.findViewWithTag<android.view.View>("qalens_overlay_compose_view") == null)
        val eventCount = QaLens.state.value.events.size
        val networkCount = QaLens.state.value.networkEvents.size
        runOnMainSync { QaLens.startRecording(); QaLens.takeScreenshot(false); QaLens.log("disabled fixture") }
        QaLens.logNetwork(NetworkEvent(method = "GET", url = "https://fixture.test/disabled"))
        waitForIdleSync()
        check(!QaLens.state.value.isRecording && QaLens.state.value.events.size == eventCount)
        check(QaLens.state.value.networkEvents.size == networkCount)
        waitUntil("Disable failed to finalize recording") { !QaLens.state.value.isSavingRecording }
        val archive = dir.listFiles()!!.first { it.extension == "sal" && it.name !in archivesBefore }
        ZipFile(archive).use { zip ->
            val frame = zip.entries().asSequence().first { it.name.endsWith(".jpg") }
            val image = zip.getInputStream(frame).use { android.graphics.BitmapFactory.decodeStream(it) }
            val pixel = image.getPixel(image.width / 2, image.height / 2)
            check(android.graphics.Color.red(pixel) < 8 && android.graphics.Color.green(pixel) < 8 && android.graphics.Color.blue(pixel) < 8) { "Hidden pixels leaked into recorded frames" }
            image.recycle()
        }
        val session = archive.inputStream().use { com.qalens.replay.QaLensSalReader.read(targetContext, it) }
        check(session.frames.isNotEmpty())
        com.qalens.replay.QaLensSalReader.release(session)
        runOnMainSync { QaLens.configure { enabled = true } }
        waitForIdleSync()
        check(activity.window.decorView.findViewWithTag<android.view.View>("qalens_overlay_compose_view") != null)
        waitUntil("DataStore observer did not resume exactly once") { starts.get() == 2 }
        QaLens.observeDataStore("fixture-observer", kotlinx.coroutines.flow.emptyFlow<Int>())
        verifyConfigCredentialIsolation()
        verifySqlLimit()
        verifyWebhookFailures()
    }

    private fun verifyConfigCredentialIsolation() {
        val old = com.qalens.android.QaLensAppSal.current(targetContext)
        try {
            com.qalens.android.QaLensPrefs.setWebhookUrl(targetContext, "https://one.test/upload")
            com.qalens.android.QaLensPrefs.setWebhookHeaderValue(targetContext, "fixture-secret")
            val exported = com.qalens.android.QaLensAppSal.encode(old.copy(webhookUrl = "https://user:password@two.test/upload?secret=value", webhookParams = "token=secret"), false)
            check(!exported.contains("password") && !exported.contains("secret=value") && !exported.contains("token=secret"))
            com.qalens.android.QaLensAppSal.apply(targetContext, old.copy(webhookUrl = "https://two.test/upload", webhookHeaderValue = ""))
            check(com.qalens.android.QaLensPrefs.webhookHeaderValue(targetContext).isBlank()) { "Old credential followed imported destination" }
        } finally {
            com.qalens.android.QaLensAppSal.apply(targetContext, old)
            com.qalens.android.QaLensPrefs.setWebhookUrl(targetContext, old.webhookUrl)
            com.qalens.android.QaLensPrefs.setWebhookHeaderValue(targetContext, old.webhookHeaderValue)
        }
    }

    private fun verifyWebhookFailures() {
        val old = com.qalens.android.QaLensAppSal.current(targetContext)
        val oldQueue = com.qalens.android.QaLensPrefs.webhookQueue(targetContext)
        val cls = Class.forName("com.qalens.QaLensWebhook")
        val instance = cls.getField("INSTANCE").get(null)
        val upload = cls.getMethod("upload", android.content.Context::class.java, RecordingInfo::class.java)
        @Suppress("UNCHECKED_CAST")
        val states = cls.getMethod("getStates").invoke(instance) as kotlinx.coroutines.flow.StateFlow<Map<String, Any>>
        val files = (0..11).map { File(targetContext.cacheDir, "upload-fixture-$it.sal").apply { writeText("fixture") } }
        fun send(file: File) { upload.invoke(instance, targetContext, RecordingInfo(name = file.name, path = file.path, sizeBytes = file.length(), createdAtMillis = System.currentTimeMillis())) }
        try {
            com.qalens.android.QaLensPrefs.setWebhookQueue(targetContext, "[]")
            java.net.ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
                com.qalens.android.QaLensPrefs.setWebhookUrl(targetContext, "http://127.0.0.1:${server.localPort}/upload")
                // Two workers wait on these sockets; eight wait in the executor; the eleventh
                // must leave Uploading immediately and enter the retry queue.
                val accepted = java.util.concurrent.ConcurrentLinkedQueue<java.net.Socket>()
                val acceptor = Thread({ runCatching { repeat(2) { accepted.add(server.accept()) } } }, "upload-blocked-fixture").apply { isDaemon = true; start() }
                files.take(11).forEach(::send)
                waitUntil("Executor rejection left upload stuck") {
                    states.value[files[10].path]?.toString()?.contains("queue full") == true &&
                        com.qalens.android.QaLensPrefs.webhookQueue(targetContext).contains(files[10].name)
                }
                runOnMainSync { QaLens.configure { enabled = false } }
                check(states.value.values.none { it.javaClass.simpleName == "Uploading" })
                server.close()
                acceptor.join(1000)
                accepted.forEach { runCatching { it.close() } }
            }
            runOnMainSync { QaLens.configure { enabled = true } }
            com.qalens.android.QaLensPrefs.setWebhookQueue(targetContext, "[]")
            java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 15_000
                com.qalens.android.QaLensPrefs.setWebhookUrl(targetContext, "http://127.0.0.1:${server.localPort}/upload")
                val responder = java.util.concurrent.FutureTask {
                    repeat(3) {
                        server.accept().use { socket ->
                            socket.soTimeout = 10_000
                            val reader = socket.getInputStream().bufferedReader()
                            var length = 0
                            while (true) {
                                val line = reader.readLine() ?: error("Missing HTTP headers")
                                if (line.isEmpty()) break
                                if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                            }
                            repeat(length) { check(reader.read() >= 0) }
                            socket.getOutputStream().write("HTTP/1.1 503 Unavailable\r\nContent-Length: 5\r\nConnection: close\r\n\r\nretry".toByteArray())
                        }
                    }
                }
                Thread(responder, "upload-503-fixture").apply { isDaemon = true; start() }
                send(files.last())
                waitUntil("Exhausted 503 retries were not retained") {
                    states.value[files.last().path]?.toString()?.contains("code=503") == true &&
                        com.qalens.android.QaLensPrefs.webhookQueue(targetContext).contains(files.last().name)
                }
                responder.get(1, java.util.concurrent.TimeUnit.SECONDS)
            }
        } finally {
            runOnMainSync { QaLens.configure { enabled = false }; QaLens.configure { enabled = true } }
            com.qalens.android.QaLensAppSal.apply(targetContext, old)
            com.qalens.android.QaLensPrefs.setWebhookUrl(targetContext, old.webhookUrl)
            com.qalens.android.QaLensPrefs.setWebhookHeaderValue(targetContext, old.webhookHeaderValue)
            com.qalens.android.QaLensPrefs.setWebhookQueue(targetContext, oldQueue)
            files.forEach { it.delete() }
        }
    }

    private fun verifySqlLimit() {
        val name = "qalens-fixture.db"
        targetContext.openOrCreateDatabase(name, 0, null).use { it.execSQL("CREATE TABLE IF NOT EXISTS fixture (id INTEGER)") }
        try {
            val cls = Class.forName("com.qalens.QaLensDataTools")
            val instance = cls.getField("INSTANCE").get(null)
            val method = cls.getMethod("runQuery", android.content.Context::class.java, String::class.java, String::class.java, android.os.CancellationSignal::class.java)
            val query = "WITH RECURSIVE cnt(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM cnt WHERE x<10000) SELECT x FROM cnt"
            val result = method.invoke(instance, targetContext, name, query, android.os.CancellationSignal())
            val rows = result.javaClass.getMethod("getRows").invoke(result) as List<*>
            check(rows.size == 100) { "SQL result limit was not applied" }
        } finally { targetContext.deleteDatabase(name) }
    }

    private fun read(zip: ZipFile, name: String): String {
        val bytes = zip.getInputStream(zip.getEntry(name) ?: error("Missing $name")).use { it.readBytes() }
        return if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte())
            GZIPInputStream(bytes.inputStream()).bufferedReader().use { it.readText() }
        else bytes.toString(Charsets.UTF_8)
    }
    private fun coverage(zip: ZipFile) = JSONObject(read(zip, "analysis.json")).getJSONObject("coverage").getJSONObject("recording")
}
