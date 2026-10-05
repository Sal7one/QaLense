package com.qalens.sample

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag as semanticsTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import com.qalens.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.onEach
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/** Dependency-free device regression runner; use a disposable sample-app emulator.
 * Exercises the real SDK observation hooks, UI clearing, async writer and Android ZIP producer.
 */
class RecordingRetentionInstrumentation : Instrumentation() {
    private var videoDenyOnly = false
    private var clipsOnly = false
    private var videoOnly = false
    private var longSession = false
    private var bridgeOnly = false
    private var desktopCaptureOnly = false
    private var desktopGuiHoldSeconds = 0
    private var overlayLoadOnly = false
    private var controlOnly = false
    private var controlVideoOnly = false
    private var manualRootOnly = false
    private var pcPairingOnly = false
    private var pcUiOnly = false
    private var workflowOnly = false
    private var quickVideoOnly = false
    private var dataUiOnly = false
    private var replayOnly = false
    private var videoRecoveryOnly = false
    private var projectionConsent: String? = null
    private var desktopTransferToken: String? = null
    private var desktopPhoneApproval = false
    private var selectorsOnly = false
    private var desktopSelectors = false
    override fun onCreate(arguments: Bundle?) {
        videoDenyOnly = arguments?.getString("videoDenyOnly") == "true"
        clipsOnly = arguments?.getString("clipsOnly") == "true"
        videoOnly = arguments?.getString("videoOnly") == "true"
        longSession = arguments?.getString("longSession") == "true"
        bridgeOnly = arguments?.getString("bridgeOnly") == "true"
        desktopCaptureOnly = arguments?.getString("desktopCaptureOnly") == "true"
        desktopGuiHoldSeconds = arguments?.getString("desktopGuiHoldSeconds")?.toIntOrNull() ?: 0
        overlayLoadOnly = arguments?.getString("overlayLoadOnly") == "true"
        controlOnly = arguments?.getString("controlOnly") == "true"
        controlVideoOnly = arguments?.getString("controlVideoOnly") == "true"
        manualRootOnly = arguments?.getString("manualRootOnly") == "true"
        pcPairingOnly = arguments?.getString("pcPairingOnly") == "true"
        pcUiOnly = arguments?.getString("pcUiOnly") == "true"
        workflowOnly = arguments?.getString("workflowOnly") == "true"
        quickVideoOnly = arguments?.getString("quickVideoOnly") == "true"
        dataUiOnly = arguments?.getString("dataUiOnly") == "true"
        replayOnly = arguments?.getString("replayOnly") == "true"
        videoRecoveryOnly = arguments?.getString("videoRecoveryOnly") == "true"
        projectionConsent = arguments?.getString("projectionConsent")
        desktopTransferToken = arguments?.getString("desktopTransferToken")
        desktopPhoneApproval = arguments?.getString("desktopPhoneApproval") == "true"
        selectorsOnly = arguments?.getString("selectorsOnly") == "true"
        desktopSelectors = arguments?.getString("desktopSelectors") == "true"
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var consent: java.util.concurrent.FutureTask<Unit>? = null
        try {
            if (desktopGuiHoldSeconds > 0) {
                DesktopCaptureChecks(this).holdGui(desktopGuiHoldSeconds)
                result.putString("stream", "\nOK: Disposable desktop GUI fixture finished and restored HD policy.\n")
                finish(android.app.Activity.RESULT_OK, result); return
            }
            if (desktopCaptureOnly) {
                DesktopCaptureChecks(this).run()
                result.putString("stream", "\nOK: Desktop recording/clip commands, HD opt-in/pending consent/cancel, masked PNG capture and exact overlay restoration/security pass.\n")
                finish(android.app.Activity.RESULT_OK, result); return
            }
            if (dataUiOnly) {
                AppDataUiChecks(this).run()
                result.putString("stream", "\nOK: Control Room SQL picker/actions/results/cancellation and decoded DataStore values/redaction/foreground updates/pause/resume/stop pass.\n")
                finish(android.app.Activity.RESULT_OK, result); return
            }
            if (replayOnly) {
                ReplayPlaybackChecks(this).run()
                result.putString("stream", "\nOK: Android frame/video replay clock, decoded seek colors, chronological event following/manual browse, track/state sync, fullscreen, background pause, trailing evidence, legacy alignment, restart and decoder errors pass.\n")
                finish(android.app.Activity.RESULT_OK, result); return
            }
            if (selectorsOnly || desktopSelectors) {
                SelectorUiChecks(this).run(desktopSelectors)
                result.putString("stream", "\nOK: Overlay tag search selects the host component and copies validated QaLens XPath; linked browser checkpoints pass when enabled.\n")
                finish(android.app.Activity.RESULT_OK, result); return
            }
            if (controlVideoOnly || videoOnly || videoDenyOnly || videoRecoveryOnly || quickVideoOnly) {
                consent = ProjectionConsentChecks.start(this, projectionConsent)
            } else require(projectionConsent == null) { "projectionConsent requires a focused video mode" }
            if (quickVideoOnly) {
                TesterWorkflowChecks(this).quickVideo()
                consent?.get(1, java.util.concurrent.TimeUnit.SECONDS)
                result.putString("stream", "\nOK: Quick actions HD button starts consent-approved video; REC Stop saves a decodable HD archive.\n")
                finish(android.app.Activity.RESULT_OK, result); return
            }
            if (videoRecoveryOnly) {
                RecordingClipChecks(this).backgroundVideoStop()
                consent?.get(1, java.util.concurrent.TimeUnit.SECONDS)
                result.putString("stream", "\nOK: HD stays active in background; its real notification Stop finalizes a decodable video and removes the foreground notification.\n")
                finish(android.app.Activity.RESULT_OK, result); return
            }
            if (workflowOnly) {
                TesterWorkflowChecks(this).run()
                result.putString("stream", "\nOK: Quick screenshot/bug mark, successful macro interactions/capture, private config round trip, profile-attributed upload, Android replay controls and panic discard pass.\n")
                finish(android.app.Activity.RESULT_OK, result); return
            }
            desktopTransferToken?.let { token ->
                DesktopTransferChecks(this).run(token, desktopPhoneApproval)
                result.putString("stream", "\nOK: Real desktop transfer copied the completed master/clip while Control Room was foreground and revoked pairing after rotation.\n")
                finish(android.app.Activity.RESULT_OK, result); return
            }
            if (pcPairingOnly) {
                PcPairingChecks(this).run()
                result.putString("stream", "\nOK: Shell pairing requires phone approval; normal-app sender, deny, cancellation, expiry and disable gates pass with a manual-root host.\n")
                finish(android.app.Activity.RESULT_OK, result); return
            }
            if (pcUiOnly) {
                PcInspectorUiChecks(this).run(manualRootOnly)
                result.putString("stream", "\nOK: SDK PC inspector pairs from the overlay, shares/rotates tokens in Control Room, stops from full overlay and stays stopped after disable/re-enable.\n")
                finish(android.app.Activity.RESULT_OK, result); return
            }
            if (controlOnly || controlVideoOnly) {
                RecordingControlChecks(this).run(controlVideoOnly, manualRootOnly)
                consent?.get(1, java.util.concurrent.TimeUnit.SECONDS)
                result.putString("stream", "\nOK: Control Room recording buttons start app capture, clips keep capture running, and master/clip archives contain media (video=$controlVideoOnly, manualRoot=$manualRootOnly).\n")
                finish(android.app.Activity.RESULT_OK, result); return
            }
            if (videoDenyOnly) {
                RecordingClipChecks(this).denyVideo()
                consent?.get(1, java.util.concurrent.TimeUnit.SECONDS)
                result.putString("stream", "\nOK: Real video consent denial leaves no archive, clears recording/saving and allows subsequent frame capture.\n")
                finish(android.app.Activity.RESULT_OK, result); return
            }
            if (clipsOnly || videoOnly) {
                RecordingClipChecks(this).run(videoOnly, longSession)
                consent?.get(1, java.util.concurrent.TimeUnit.SECONDS)
                result.putString("stream", "\nOK: Retrospective clip preserved recent evidence, excluded post-mark logs, kept master capture running and exported compatible media (video=$videoOnly, long=$longSession).\n")
                finish(android.app.Activity.RESULT_OK, result); return
            }
            if (bridgeOnly) {
                LocalBridgeChecks(this).run()
                result.putString("stream", "\nOK: Local bridge semantics/actions/privacy/shutdown and LTR/RTL inspector gestures pass.\n")
                finish(android.app.Activity.RESULT_OK, result)
                return
            }
            if (overlayLoadOnly) {
                verifyContinuousOverlayLoad()
                result.putString("stream", "\nOK: Overlay/Activity/Logs/Network remain responsive under continuous log and network load.\n")
                finish(android.app.Activity.RESULT_OK, result)
                return
            }
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
            verifySavedRecordingStorage(retained)
            verifyOssIntegrations()
            verifyRoomIntegration()
            verifyRealDataStoreIntegration()
            verifyClientSafety()
            verifyComposeInspection()
            LocalBridgeChecks(this).run()
            verifyContinuousOverlayLoad()
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
            result.putString("stream", "\nOK: 600 requests and logs survived UI clearing; saved archives are durable, shareable and legacy cache archives migrate; Compose dialog roots, hidden subtrees and semantics-only updates are inspected; local bridge actions/privacy/shutdown and LTR/RTL inspector gestures pass; real Room/DataStore hooks and preference snapshot changes reach recording analysis; byte-budget loss is disclosed; Chucker coexistence, adapters and crash bridge pass; client privacy, disable/resume, navigation, macros, SQL, replay and webhook queue/retry checks pass.\n")
            result.putString("retainedArchive", retained.name)
            result.putString("limitedArchive", limited.name)
            finish(android.app.Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            result.putString("stream", "\nFAIL: ${failure.stackTraceToString()}\n")
            finish(android.app.Activity.RESULT_CANCELED, result)
        } finally {
            consent?.cancel(true)
        }
    }

    private fun record(label: String, count: Int, body: String?, clearUi: Boolean): File {
        startActivitySync(Intent(targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        waitForIdleSync()
        val dir = File(targetContext.filesDir, "qalens/recordings")
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

    private fun verifySavedRecordingStorage(retained: File) {
        val durable = File(targetContext.filesDir, "qalens/recordings")
        check(retained.parentFile == durable) { "Saved archive is still in cache" }
        val legacy = File(targetContext.cacheDir, "qalens").apply { mkdirs() }
        val old = File(legacy, "session_migration_fixture_${System.nanoTime()}.sal")
        val moved = File(durable, old.name)
        try {
            old.writeText("migration fixture")
            QaLens.refreshRecordings()
            check(!old.exists() && moved.readText() == "migration fixture") { "Legacy archive was not moved" }
            check(QaLens.state.value.recordings.any { it.path == moved.absolutePath }) { "Migrated archive is missing from the tester sheet" }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                targetContext, targetContext.packageName + ".qalens.fileprovider", moved
            )
            check(targetContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } == "migration fixture") {
                "FileProvider cannot share a durable archive"
            }
        } finally {
            old.delete()
            moved.delete()
            QaLens.refreshRecordings()
        }
    }

    private fun verifyComposeInspection() {
        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        val status = mutableStateOf("off")
        runOnMainSync {
            activity.setContent {
                Box(Modifier.fillMaxSize()) {
                    Text("Main root", Modifier.testTag("qa.root.main"))
                    Text("Status", Modifier.semantics { semanticsTag = "qa.state"; stateDescription = status.value })
                    Box(Modifier.testTag("qa.clickable.parent").clickable { }) {
                        Text("Open details", Modifier.semantics { contentDescription = "Open details" })
                    }
                    Box(Modifier.size(80.dp).qaHiddenFromReports()) {
                        Text("Sensitive", Modifier.qaTag("qa.hidden.child"))
                    }
                    Dialog(onDismissRequest = {}) {
                        Box(Modifier.size(200.dp).qaInspectionRoot().testTag("qa.root.dialog")) {
                            Text("Dialog root", Modifier.qaTag("qa.root.dialog.text").qaName("Dialog element"))
                        }
                    }
                }
            }
            QaLens.setInspectMode(true)
        }
        waitForIdleSync()
        waitUntil("Separate dialog Compose root was not inspected") {
            val tags = QaLens.state.value.nodes.mapNotNull { it.testTag }.toSet()
            "qa.root.main" in tags && "qa.root.dialog.text" in tags
        }
        val nodes = QaLens.state.value.nodes
        val main = nodes.first { it.testTag == "qa.root.main" }
        val dialog = nodes.first { it.testTag == "qa.root.dialog.text" }
        check(main.id != dialog.id) { "Compose roots shared a semantics ID" }
        check(dialog.bounds.centerX in 0..activity.window.decorView.width) { "Dialog bounds were not mapped to host window" }
        check(dialog.qaName == "Dialog element") { "Dialog qaTag did not reconcile with its semantics node" }
        check(nodes.count { it.testTag == "qa.root.dialog.text" } == 1) {
            "Dialog qaTag created duplicate nodes: ${nodes.filter { it.testTag == "qa.root.dialog.text" }}"
        }
        check(nodes.first { it.testTag == "qa.clickable.parent" }.hasHumanLabel) {
            "Clickable parent's merged child label was not inspected"
        }
        check(nodes.first { it.testTag == "qa.clickable.parent" }.contentDescription.isEmpty()) {
            "Clickable parent duplicated a child's content description despite having visible text"
        }
        check(nodes.none { it.testTag == "qa.hidden.child" }) { "Hidden subtree leaked through manual qaTag" }
        runOnMainSync { status.value = "on" }
        waitUntil("State-only semantics change was not inspected") {
            QaLens.state.value.nodes.any { it.testTag == "qa.state" && it.stateDescription == "on" }
        }
        runOnMainSync { QaLens.setInspectMode(false) }
    }

    private fun verifyRoomIntegration() {
        val db = Room.inMemoryDatabaseBuilder(targetContext, FixtureRoomDatabase::class.java).build()
        try {
            val before = QaLens.state.value.events.count { it.tag == QaLensDataEvents.ROOM }
            QaLens.observeRoom(db, "entries")
            QaLens.observeRoom(db, " entries ", "entries") // duplicate registration must be idempotent
            waitForIdleSync()
            db.entries().insert(FixtureRoomDatabase.Entry(1, "synthetic"))
            waitUntil("Room invalidation did not reach the QaLens event track") {
                QaLens.state.value.events.count { it.tag == QaLensDataEvents.ROOM } > before
            }
            check(QaLens.state.value.events.count { it.tag == QaLensDataEvents.ROOM } == before + 1) {
                "Duplicate Room registration emitted duplicate change events"
            }
            check(!QaLens.state.value.events.last { it.tag == QaLensDataEvents.ROOM }.message
                .contains("synthetic")) { "Room row value leaked into the change event" }
            QaLens.stopObservingRoom(db)
            waitForIdleSync()
            val stoppedAt = QaLens.state.value.events.count { it.tag == QaLensDataEvents.ROOM }
            db.entries().insert(FixtureRoomDatabase.Entry(2, "synthetic"))
            Thread.sleep(300)
            check(QaLens.state.value.events.count { it.tag == QaLensDataEvents.ROOM } == stoppedAt) {
                "Room observer continued after unregistration"
            }
        } finally {
            QaLens.stopObservingRoom(db)
            db.close()
        }
    }

    private fun verifyRealDataStoreIntegration() {
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        )
        val file = File(targetContext.cacheDir, "qalens-pref-fixture-${System.nanoTime()}.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val initial = java.util.concurrent.CountDownLatch(1)
        val emissions = java.util.concurrent.atomic.AtomicInteger()
        try {
            QaLens.observeDataStore("real-pref", store.data.onEach {
                if (emissions.incrementAndGet() == 1) initial.countDown()
            }) { "theme updated" }
            check(initial.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "DataStore initial value was not collected" }
            val before = QaLens.state.value.events.count { it.tag == QaLensDataEvents.DATASTORE }
            kotlinx.coroutines.runBlocking {
                store.edit { it[stringPreferencesKey("theme")] = "dark" }
            }
            waitUntil("Real DataStore edit did not reach the QaLens event track") {
                QaLens.state.value.events.count { it.tag == QaLensDataEvents.DATASTORE } > before
            }
            check(!QaLens.state.value.events.last { it.tag == QaLensDataEvents.DATASTORE }.message.contains("dark")) {
                "DataStore value leaked into the change event"
            }
            QaLens.stopObservingDataStore("real-pref")
            waitForIdleSync()
            val stoppedAt = QaLens.state.value.events.count { it.tag == QaLensDataEvents.DATASTORE }
            kotlinx.coroutines.runBlocking {
                store.edit { it[stringPreferencesKey("theme")] = "light" }
            }
            Thread.sleep(300)
            check(QaLens.state.value.events.count { it.tag == QaLensDataEvents.DATASTORE } == stoppedAt) {
                "DataStore observer continued after unregistration"
            }
        } finally {
            QaLens.stopObservingDataStore("real-pref")
            scope.cancel()
            file.delete()
        }
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
            waitUntil("Chucker request was not published") {
                QaLens.state.value.networkEvents.any { it.url.contains("/oss-check") }
            }
            val events = QaLens.state.value.networkEvents.filter { it.url.contains("/oss-check") }
            check(events.size == 1 && events.single().status == 201) { "Chucker coexistence lost or duplicated the request" }
            check(!events.single().url.contains("fixture-secret") && events.single().responseBodyPreview == null)
        }
        val sink = QaLens.networkSink("Test transport")
        sink.record(NetworkEvent(method = "GET", url = "https://example.test/adapter", error = "person@example.test"))
        waitUntil("Adapter request was not published") {
            QaLens.state.value.networkEvents.lastOrNull()?.url?.contains("/adapter") == true
        }
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

    /** Exercise actual panel composition while producers never become idle. */
    private fun verifyContinuousOverlayLoad() {
        startActivitySync(Intent(targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        waitForIdleSync()
        val originalLimit = QaLens.config.value.maxEventHistory
        val originalPanel = QaLens.state.value.minimalPanel
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val produced = java.util.concurrent.atomic.AtomicInteger()
        var worstHeartbeatMs = 0L
        fun heartbeat() {
            val latch = java.util.concurrent.CountDownLatch(1)
            val start = android.os.SystemClock.elapsedRealtime()
            android.os.Handler(android.os.Looper.getMainLooper()).post { latch.countDown() }
            check(latch.await(2500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                "Main thread stalled under continuous capture"
            }
            worstHeartbeatMs = maxOf(worstHeartbeatMs, android.os.SystemClock.elapsedRealtime() - start)
        }
        // Compose virtual nodes support traversal; framework find-by-text is not reliable here.
        fun visibleText(text: String, exact: Boolean = true): android.view.accessibility.AccessibilityNodeInfo? {
            // Continuous updates can leave UiAutomation's cached child visibility behind scroll.
            if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
            fun find(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
                val label = node.text?.toString()?.trim().orEmpty()
                if (node.isVisibleToUser && (if (exact) label == text else label.contains(text))) return node
                repeat(node.childCount) { index -> node.getChild(index)?.let { find(it)?.let { found -> return found } } }
                return null
            }
            return uiAutomation.rootInActiveWindow?.let(::find)
        }
        fun openTab(label: String, heading: String) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
            fun tabStrip(): android.view.accessibility.AccessibilityNodeInfo? {
                if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
                fun find(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
                    if (node.contentDescription?.toString() == "Diagnostic tabs") return node
                    repeat(node.childCount) { index -> node.getChild(index)?.let { find(it)?.let { found -> return found } } }
                    return null
                }
                return uiAutomation.rootInActiveWindow?.let(::find)
            }
            fun tab(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                node ?: return null
                if (node.isVisibleToUser && node.text?.toString() == label) return node
                repeat(node.childCount) { index -> tab(node.getChild(index))?.let { return it } }
                return null
            }
            var strip = tabStrip()
            var target = tab(strip)
            while (target == null && android.os.SystemClock.elapsedRealtime() < deadline) {
                // Avoid repeatedly scanning the changing network list for off-screen tab labels.
                strip?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                heartbeat()
                Thread.sleep(400) // Let the accessibility scroll complete before issuing another.
                strip = tabStrip()
                target = tab(strip)
            }
            var clickable = checkNotNull(target) {
                val labels = mutableListOf<String>()
                fun collect(node: android.view.accessibility.AccessibilityNodeInfo) {
                    node.text?.let { labels += it.toString().take(100) }
                    repeat(node.childCount) { index -> node.getChild(index)?.let(::collect) }
                }
                uiAutomation.rootInActiveWindow?.let(::collect)
                "Panel tab $label was not reachable (open=${QaLens.state.value.isPanelOpen}, " +
                    "minimal=${QaLens.state.value.minimalPanel}, overlay=${QaLens.state.value.overlayEnabled}): ${labels.take(30)}"
            }
            while (!clickable.isClickable && clickable.parent != null) clickable = clickable.parent
            if (!clickable.isSelected && !clickable.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                // Continuous evidence publication can invalidate an accessibility node between
                // lookup and dispatch. Exercise the same visible tab with a real tester tap.
                val bounds = android.graphics.Rect()
                clickable.getBoundsInScreen(bounds)
                val down = android.os.SystemClock.uptimeMillis()
                for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                    val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action,
                        bounds.exactCenterX(), bounds.exactCenterY(), 0).apply {
                        source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    }
                    try { check(uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                }
            }
            waitUntil("$label did not produce content while logs kept arriving") {
                heartbeat()
                tab(tabStrip())?.isSelected == true && visibleText(heading, exact = false) != null
            }
            repeat(10) { heartbeat(); Thread.sleep(100) }
        }
        val producer = Thread({
            while (running.get()) {
                val n = produced.incrementAndGet()
                QaLens.log("continuous-overlay-$n " + "payload ".repeat(20))
                if (n % 5 == 0) QaLens.logNetwork(NetworkEvent(method = "GET",
                    url = "https://overlay.test/$n", status = if (n % 50 == 0) 500 else 200))
                if (n % 100 == 0) android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (running.get()) repeat(100) { QaLens.log("main-thread-log-$n-$it " + "payload ".repeat(20)) }
                }
                Thread.sleep(1)
            }
        }, "qalens-continuous-fixture")
        try {
            runOnMainSync {
                QaLens.configure { maxEventHistory = 20_000 }
                QaLens.clearLogs()
                QaLens.clearNetworkLog()
                QaLens.setPanelMinimal(false)
            }
            repeat(12_000) { QaLens.log("overlay-flood-$it " + "payload ".repeat(20)) }
            waitUntil("Flood history did not publish") { QaLens.state.value.events.size > 1000 }
            producer.start()
            heartbeat()
            runOnMainSync { QaLens.openPanel() }
            Thread.sleep(300)
            openTab("Activity", "Timeline (")
            openTab("Network", "requests")
            openTab("Logs", "events kept")
            check(produced.get() > 100) { "Load stopped before the tabs were exercised" }
            check(QaLens.state.value.events.size <= 10_000)
            check(QaLens.state.value.events.sumOf { it.message.length + (it.tag?.length ?: 0) } <= 1_048_576)
            check(QaLens.state.value.networkEvents.size <= 250)
            android.util.Log.i("QaLensLoadTest", "produced=${produced.get()} worstMainHeartbeatMs=$worstHeartbeatMs")
        } finally {
            running.set(false)
            producer.join(5000)
            runOnMainSync {
                QaLens.closePanel()
                QaLens.setPanelMinimal(originalPanel)
                QaLens.configure { maxEventHistory = originalLimit }
                QaLens.clearLogs()
                QaLens.clearNetworkLog()
            }
        }
        runOnMainSync {
            QaLens.log("immediate-export-fixture")
            QaLens.logNetwork(NetworkEvent(method = "GET", url = "https://overlay.test/immediate-export", status = 500))
            val exported = QaLens.evidenceBundle()
            check(exported.snapshot.events.any { it.message == "immediate-export-fixture" })
            check(exported.networkEvents.any { it.url.endsWith("/immediate-export") })
            QaLens.clearLogs()
            QaLens.clearNetworkLog()
        }
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
            activity.setContent {
                Box(Modifier.fillMaxSize().background(Color.Red).testTag("route-two")) {
                    Box(Modifier.align(androidx.compose.ui.Alignment.BottomEnd).fillMaxSize(0.5f).qaHiddenFromReports())
                }
            }
        }
        waitForIdleSync()

        val starts = java.util.concurrent.atomic.AtomicInteger()
        val stops = java.util.concurrent.atomic.AtomicInteger()
        QaLens.observeDataStore("fixture-observer", kotlinx.coroutines.flow.flow<Int> {
            starts.incrementAndGet()
            try { emit(0); kotlinx.coroutines.awaitCancellation() } finally { stops.incrementAndGet() }
        })
        waitUntil("DataStore observer did not start") { starts.get() == 1 }
        val archiveDir = File(targetContext.filesDir, "qalens/recordings")
        val archivesBefore = archiveDir.listFiles().orEmpty().map { it.name }.toSet()
        val memoryBefore = QaLens.state.value.memorySamples.size
        runOnMainSync { QaLens.startRecording() }
        check(QaLens.state.value.isRecording)
        val nextTheme = if (SamplePreferences.current["theme"] == "dark") "light" else "dark"
        SamplePreferences.setTheme(nextTheme == "dark")
        waitUntil("DataStore change did not refresh the registered preference snapshot") {
            QaLens.state.value.dataSources["Preferences"]?.get("theme") == nextTheme
        }
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
        val archive = archiveDir.listFiles()!!.first { it.extension == "sal" && it.name !in archivesBefore }
        ZipFile(archive).use { zip ->
            val states = JSONArray(read(zip, "state.json"))
            check((0 until states.length()).any { i ->
                states.getJSONObject(i).getJSONObject("dataSources")
                    .optJSONObject("Preferences")?.optString("theme") == nextTheme
            }) { "Updated preference was absent from the recording state track" }
            val analysis = JSONObject(read(zip, "analysis.json"))
            check(analysis.getJSONObject("stats").getInt("preferenceChanges") > 0) {
                "Recorded analysis missed the observed preference change"
            }
            val frame = zip.entries().asSequence().first { it.name.endsWith(".jpg") }
            val image = zip.getInputStream(frame).use { android.graphics.BitmapFactory.decodeStream(it) }
            check(image.width <= 720 && image.height <= 2880 && image.byteCount <= 8_000_000)
            val pixel = image.getPixel(image.width * 3 / 4, image.height * 3 / 4)
            check(android.graphics.Color.red(pixel) < 8 && android.graphics.Color.green(pixel) < 8 && android.graphics.Color.blue(pixel) < 8) { "Hidden pixels leaked into recorded frames" }
            val visible = image.getPixel(image.width / 4, image.height / 4)
            check(android.graphics.Color.red(visible) > 200 && android.graphics.Color.green(visible) < 16) { "Scaled mask erased visible content or capture was empty" }
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
