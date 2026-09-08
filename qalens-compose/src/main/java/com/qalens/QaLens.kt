package com.qalens

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.room.InvalidationTracker
import androidx.room.RoomDatabase
import com.qalens.android.QaLensAndroidInfo
import com.qalens.android.QaLensPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object QaLens {
    private val configState = MutableStateFlow(QaLensConfig())
    private val uiStateMutable = MutableStateFlow(QaLensUiState())
    private val manualNodes = linkedMapOf<String, InspectNode>()
    private val recomposeBuffer = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private var lastRecomposeFlushMs = 0L

    private var featureFlagProvider: (() -> Map<String, Boolean>)? = null
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Main-thread handler for all state mutations + debounced analysis. Never null after first use. */
    private val mainHandler = Handler(Looper.getMainLooper())
    /** True when a recompute is pending (set on any thread, consumed on main). */
    private val analysisDirty = AtomicBoolean(false)
    /** A pending recompute runnable, deduped so bursts coalesce into one pass. */
    private var analysisPending: Runnable? = null
    /** Debounce window for [markAnalysisDirty]. Tuned so a burst of 10 network calls → 1 recompute. */
    private const val ANALYSIS_DEBOUNCE_MS = 250L
    private var lastScreenKey: String? = null
    private var lastScreenshot: EvidenceAttachment? = null
    private val contracts = linkedMapOf<String, ScreenContract>()
    private val dataSourceProviders = linkedMapOf<String, DataSourceEntry>()
    /** B3: registered custom tab providers — appended after the built-in tabs in the panel. */
    private val customTabs = linkedMapOf<String, QaLensTabProvider>()
    private var pendingScenario: DeepLinkScenario? = null
    private var lastNavMs = 0L

    /** A3: the analysis pipeline, extracted from the orchestrator so it's testable in isolation. */
    private val analysisEngine: AnalysisEngine by lazy {
        AnalysisEngine(
            contracts = contracts,
            dataSourceProviders = dataSourceProviders,
            featureFlagProvider = { featureFlagProvider?.invoke() ?: emptyMap() },
            onError = ::onAnalysisError
        )
    }

    private var appRef: WeakReference<Application>? = null
    private var currentActivityRef: WeakReference<Activity>? = null

    /** App context for paths/notifications/services when no activity is alive (e.g. mid-recording). */
    internal val appContext: Context? get() = appRef?.get()
    internal val currentActivity: Activity? get() = currentActivityRef?.get()

    /** Recording mode armed from the Control Room: started on the next host-app activity resume. */
    internal var pendingRecordingVideo: Boolean? = null
        private set

    val state: StateFlow<QaLensUiState> = uiStateMutable.asStateFlow()
    val config: StateFlow<QaLensConfig> = configState.asStateFlow()

    fun configure(block: QaLensConfig.Builder.() -> Unit) {
        configState.update { current -> QaLensConfig.Builder(current).apply(block).build() }
        if (!configState.value.enabled) {
            // Run-condition says off — tear down anything the startup initializer already attached.
            currentActivityRef?.get()?.let { QaLensActivityInstaller.detachOverlay(it) }
            appContext?.let { com.qalens.android.QaLensNotification.dismiss(it) }
            return
        }
        currentActivityRef?.get()?.let { updateDeviceAndScreen(it) }
        // Network source may have been flipped in configure() after install — apply it lazily.
        if (configState.value.networkFromChucker) {
            appContext?.let { QaLensChuckerSource.ensureRegistered(it) }
        }
        markAnalysisDirty()
    }

    /**
     * Register a provider that returns the current feature flag values. Evaluated safely on every
     * analysis pass — a throwing provider is caught and ignored, never crashing the host app.
     */
    fun setFeatureFlagProvider(provider: () -> Map<String, Boolean>) {
        featureFlagProvider = provider
        markAnalysisDirty()
    }

    // ── Deep link scenarios ───────────────────────────────────────────────────
    /**
     * Register a named deep-link QA can launch from the panel. When [expectedRoute] is set, QaLens
     * validates the route the app lands on after launch and marks the scenario pass/fail.
     */
    fun registerDeepLinkScenario(
        name: String,
        uri: String,
        expectedRoute: String? = null,
        tags: List<String> = emptyList()
    ) {
        val scenario = DeepLinkScenario(name, uri, expectedRoute, tags)
        uiStateMutable.update { old ->
            val others = old.deepLinkScenarios.filterNot { it.name == name }
            old.copy(deepLinkScenarios = others + scenario)
        }
    }

    fun runScenario(scenario: DeepLinkScenario) {
        navigate(scenario.uri)
        val status = if (scenario.expectedRoute != null) ScenarioStatus.PENDING else ScenarioStatus.LAUNCHED
        if (scenario.expectedRoute != null) pendingScenario = scenario
        recordScenarioRun(scenario.name, status, null)
    }

    private fun resolvePendingScenario(screenName: String, route: String?) {
        val pending = pendingScenario ?: return
        val expected = pending.expectedRoute ?: return
        val ok = ScenarioMatcher.matches(expected, route, screenName)
        recordScenarioRun(pending.name, if (ok) ScenarioStatus.PASS else ScenarioStatus.FAIL, route ?: screenName)
        pendingScenario = null
    }

    private fun recordScenarioRun(name: String, status: ScenarioStatus, actualRoute: String?) {
        uiStateMutable.update { old ->
            old.copy(scenarioRuns = old.scenarioRuns + (name to ScenarioRun(name, status, actualRoute)))
        }
    }

    // ── Screen contracts ──────────────────────────────────────────────────────
    /**
     * Register an optional quality contract for a screen. Validated live whenever that screen is
     * active; screens without a contract produce no output.
     *
     *   QaLens.contract("Checkout") {
     *       requiresTag("checkout.submit.button")
     *       requiresLabel("Submit payment")
     *       requiresNoCriticalAccessibilityWarnings()
     *   }
     */
    fun contract(screen: String, block: ScreenContractBuilder.() -> Unit) {
        contracts[screen] = ScreenContractBuilder(screen).apply(block).build()
        markAnalysisDirty()
    }

    // ── Custom panel tabs (B3) ────────────────────────────────────────────────
    /**
     * Register a custom tab in the inspector panel. The tab's [QaLensTabProvider.Content] is
     * rendered when QA selects it, receiving the current [QaLensUiState] + [QaLensConfig]. Tabs
     * appear after the built-ins. In release builds this is a no-op (the provider compiles but is
     * never invoked).
     */
    fun registerTab(provider: QaLensTabProvider) {
        customTabs[provider.title] = provider
    }

    /** Remove a previously registered custom tab by title. */
    fun unregisterTab(title: String) {
        customTabs.remove(title)
    }

    /** The registered custom tab providers (internal — the panel appends them to the tab strip). */
    internal fun registeredTabs(): List<QaLensTabProvider> = customTabs.values.toList()

    // ── Custom data sources (DataStore prefs / Room snapshots) ─────────────────
    /**
     * Register a read-only snapshot of app-owned data (DataStore preferences, Room counts, etc.)
     * to appear in the panel, evidence bundle, reports, and recorded sessions. Evaluated safely on
     * every analysis pass — a throwing provider is caught and ignored. Values are redacted.
     *
     * B16: per-source redaction options on top of the global defaults:
     * - [redactKeys] — exact key names whose values are always masked (e.g. `"ssn"`, `"email"`).
     * - [redactPatterns] — regexes applied to this source's values only (domain-specific PII).
     * - [redactAll] — mask every value from this source.
     * The global [QaLensConfig.redact] rules still apply as a backstop after these.
     *
     *   QaLens.registerDataSource("User", provider, redactKeys = listOf("ssn", "email"))
     */
    fun registerDataSource(
        name: String,
        redactKeys: List<String> = emptyList(),
        redactPatterns: List<Regex> = emptyList(),
        redactAll: Boolean = false,
        provider: () -> Map<String, String>
    ) {
        dataSourceProviders[name] = DataSourceEntry(
            provider = provider,
            redactKeys = redactKeys.toSet(),
            redactPatterns = redactPatterns,
            redactAll = redactAll
        )
        markAnalysisDirty()
    }

    // ── Room / DataStore change events ─────────────────────────────────────────
    /**
     * Emit a timeline event whenever any of [tables] change in [db], via Room's InvalidationTracker.
     * Captures *that* a table changed (not row contents). Debug/QA only — call from a debug build.
     */
    fun observeRoom(db: RoomDatabase, vararg tables: String) {
        if (tables.isEmpty()) return
        val observer = object : InvalidationTracker.Observer(tables.toList().toTypedArray()) {
            override fun onInvalidated(changed: Set<String>) {
                event("DB changed", "Room tables changed: ${changed.joinToString()}")
            }
        }
        runCatching { db.invalidationTracker.addObserver(observer) }
            .onFailure { pushError(ErrorKind.DATA_SOURCE, "observeRoom failed: ${it.message}") }
    }

    /**
     * Emit a timeline event whenever [flow] emits a new value (e.g. a DataStore `data` flow).
     * The initial value is skipped so only *changes* are reported; [describe] turns a value into a
     * short, redaction-aware label.
     */
    fun <T> observeDataStore(name: String, flow: Flow<T>, describe: (T) -> String = { "updated" }) {
        bgScope.launch {
            runCatching {
                flow.drop(1).collect { value ->
                    val desc = runCatching { describe(value) }.getOrDefault("updated")
                    event("$name changed", desc)
                }
            }.onFailure { log("observeDataStore($name) failed: ${it.message}") }
        }
    }

    // ── A6: Generic data-source observer (Room/DataStore agnostic) ───────────
    private val dataObservers = mutableListOf<DataSourceObserver>()

    /**
     * Register a [DataSourceObserver] to be notified of data-layer changes.
     * The observer is called on the thread that fires the change — typically a binder or
     * background thread. Implementations should be lightweight (emit a timeline event or
     * push to QaLens state) rather than performing heavy work.
     */
    fun registerDataSourceObserver(observer: DataSourceObserver) {
        if (observer in dataObservers) return
        dataObservers += observer
    }

    /** Remove a previously registered [DataSourceObserver]. */
    fun unregisterDataSourceObserver(observer: DataSourceObserver) {
        dataObservers -= observer
    }

    /** Notify all registered observers of a data-layer change. Called by the host app. */
    fun notifyDataChange(source: String, tableName: String, changeType: ChangeType) {
        val snapshot = dataObservers.toList()
        snapshot.forEach { obs ->
            runCatching { obs.onChanged(source, tableName, changeType) }
                .onFailure { log("DataSourceObserver.onChanged failed: ${it.message}") }
        }
    }

    /** Notify all registered observers of a data-layer error. */
    fun notifyDataError(source: String, error: String) {
        val snapshot = dataObservers.toList()
        snapshot.forEach { obs ->
            runCatching { obs.onError(source, error) }
                .onFailure { log("DataSourceObserver.onError failed: ${it.message}") }
        }
    }

    fun install(application: Application) {
        appRef = WeakReference(application)
        // Restore persisted QA preferences so overlay setups survive process death.
        uiStateMutable.update {
            it.copy(
                overlayAlpha = QaLensPrefs.overlayAlpha(application).coerceIn(0.1f, 1f),
                dockBottom = QaLensPrefs.dockBottom(application),
                overlayEnabled = QaLensPrefs.overlayEnabled(application),
                minimalPanel = com.qalens.android.QaLensAppSal.panelMode(application) == "minimal"
            )
        }
        QaLensActivityInstaller.install(application)
        QaLensCrashHandler.install()
        QaLensConnectivity.start(application)
        QaLensMemoryMonitor.start(application)
        // Feature flag: networkFromChucker → Chucker's TransactionListener becomes the network
        // source (QaLensOkHttpInterceptor turns pass-through). Idempotent + logs a fallback note.
        if (configState.value.networkFromChucker) QaLensChuckerSource.ensureRegistered(application)
    }

    fun setScreen(name: String, route: String? = null) {
        val cfg = configState.value
        // Redact the history entry for display/export safety (deep-link args can carry PII/tokens),
        // but keep the raw route on the screen so scenario/contract matching still works.
        val nextHistoryItem = cfg.redact(route ?: name)
        val isDuplicate = uiStateMutable.value.screen.history.lastOrNull() == nextHistoryItem

        uiStateMutable.update { old ->
            val history = if (isDuplicate) old.screen.history
                          else (old.screen.history + nextHistoryItem).takeLast(25)
            old.copy(
                screen = old.screen.copy(
                    screenName = name,
                    route = route,
                    history = history
                )
            )
        }

        // Rapid-navigation detection — surfaces churn that often signals a navigation bug.
        val now = System.currentTimeMillis()
        if (!isDuplicate && lastNavMs != 0L && now - lastNavMs < 350L) {
            pushEvent(QaEvent(type = QaEventType.BREADCRUMB,
                message = "⚡ Rapid navigation to $name (${now - lastNavMs}ms after previous)"))
        }
        lastNavMs = now

        resolvePendingScenario(name, route)
        markAnalysisDirty()
    }

    fun log(message: String) = pushEvent(QaEvent(type = QaEventType.LOG, message = message))

    fun event(name: String, message: String = name) = pushEvent(
        QaEvent(type = QaEventType.EVENT, tag = name, message = message)
    )

    fun breadcrumb(message: String) = pushEvent(QaEvent(type = QaEventType.BREADCRUMB, message = message))

    /** Receives forwarded Timber logs (via QaLensTimberTree). Level is an android.util.Log priority. */
    fun timberLog(priority: Int, tag: String?, message: String) {
        val level = when (priority) {
            2 -> "VERBOSE"; 3 -> "DEBUG"; 4 -> "INFO"; 5 -> "WARN"; 6 -> "ERROR"; 7 -> "ASSERT"
            else -> "LOG"
        }
        pushEvent(QaEvent(type = QaEventType.LOG, tag = tag ?: "Timber", message = "[$level] $message"))
    }

    fun openPanel() = uiStateMutable.update { it.copy(isPanelOpen = true) }
    fun closePanel() = uiStateMutable.update { it.copy(isPanelOpen = false) }
    fun togglePanel() = uiStateMutable.update { it.copy(isPanelOpen = !it.isPanelOpen) }
    fun toggleInspectMode() = uiStateMutable.update {
        it.copy(isInspectMode = !it.isInspectMode, isTagMode = false)
    }
    fun setInspectMode(enabled: Boolean) = uiStateMutable.update {
        it.copy(isInspectMode = enabled, isTagMode = if (enabled) false else it.isTagMode)
    }

    /** QA-minimal panel (big colorful controls) vs the full developer panel. Persisted. */
    fun setPanelMinimal(minimal: Boolean) {
        uiStateMutable.update { it.copy(minimalPanel = minimal) }
        appContext?.let { com.qalens.android.QaLensAppSal.setPanelMode(it, if (minimal) "minimal" else "full") }
    }

    /** QA's "I saw it RIGHT THERE" button: starred breadcrumb + annotated screenshot in one tap. */
    fun markMoment(note: String = "Marked by QA") {
        breadcrumb("⭐ $note")
        takeScreenshot(share = false)   // straight to the gallery; no share sheet mid-flow
    }

    /** Tag mode — inspect's sibling: shows every visible automation tag drawn on its component. */
    fun toggleTagMode() = uiStateMutable.update {
        it.copy(isTagMode = !it.isTagMode, isInspectMode = false)
    }
    fun setTagMode(enabled: Boolean) = uiStateMutable.update {
        it.copy(isTagMode = enabled, isInspectMode = if (enabled) false else it.isInspectMode)
    }

    /** Overlay opacity for the panel / watch HUD (clamped 0.1–1.0). Persisted. */
    fun setOverlayAlpha(alpha: Float) {
        val clamped = alpha.coerceIn(0.1f, 1f)
        uiStateMutable.update { it.copy(overlayAlpha = clamped) }
        appContext?.let { QaLensPrefs.setOverlayAlpha(it, clamped) }
    }

    /** Move the panel / watch HUD between the top and bottom edge (frees the opposite edge). Persisted. */
    fun toggleDock() {
        var newValue = false
        uiStateMutable.update { newValue = !it.dockBottom; it.copy(dockBottom = newValue) }
        appContext?.let { QaLensPrefs.setDockBottom(it, newValue) }
    }

    /**
     * Master switch: attach or fully detach the QaLens overlay from the host app ("stop injecting").
     * Lets QA rule out overlay interference with the app's own navigation/gestures live, without a
     * rebuild. The notification and Control Room stay available to switch it back on. Persisted.
     */
    fun setOverlayEnabled(enabled: Boolean) {
        uiStateMutable.update { it.copy(overlayEnabled = enabled, isPanelOpen = false, isInspectMode = false) }
        appContext?.let { QaLensPrefs.setOverlayEnabled(it, enabled) }
        currentActivityRef?.get()?.let { activity ->
            if (enabled) QaLensActivityInstaller.attachOverlay(activity)
            else QaLensActivityInstaller.detachOverlay(activity)
        }
        breadcrumb(if (enabled) "Overlay injection enabled" else "Overlay injection disabled")
    }

    /**
     * One-tap recovery from any bad state: discards an in-flight recording (no share sheet),
     * leaves watch/inspect/panel modes, restores opacity, re-attaches and re-shows the overlay,
     * and refreshes the notification. Wired to the Control Room and the notification panic action.
     */
    fun panicRestore() {
        QaLensSessionRecorder.cancel()
        uiStateMutable.update {
            it.copy(
                isPanelOpen = false,
                isInspectMode = false,
                isWatchMode = false,
                isRecording = false,
                overlayAlpha = 1f,
                overlayEnabled = true
            )
        }
        appContext?.let {
            QaLensPrefs.setOverlayEnabled(it, true)
            QaLensPrefs.setOverlayAlpha(it, 1f)
            com.qalens.android.QaLensNotification.show(it, recording = false)
        }
        currentActivityRef?.get()?.let { activity ->
            QaLensActivityInstaller.attachOverlay(activity)
            QaLensScreenCapture.setOverlayVisible(activity, true)
        }
        log("Panic restore: overlay re-attached, recording discarded, state reset.")

        // A5: safety net — if a recording is somehow still flagged and the recorder reports no
        // recent frames (>15s — stuck capture, e.g. FLAG_SECURE), force-clear the flag.
        if (uiStateMutable.value.isRecording && !QaLensSessionRecorder.hasRecentFrames(15_000L)) {
            QaLensSessionRecorder.cancel()
            uiStateMutable.update { it.copy(isRecording = false) }
            log("Panic restore: stuck recording with no recent frames force-cleared.")
        }
    }

    /**
     * Arm a recording from the Control Room: it starts automatically on the next host-app activity
     * resume, so the recording captures the app — not the Control Room itself.
     */
    internal fun armRecording(video: Boolean) { pendingRecordingVideo = video }
    internal fun consumePendingRecording(): Boolean? = pendingRecordingVideo.also { pendingRecordingVideo = null }

    /**
     * Watch mode — a translucent, non-interactive live overlay. QA uses the real app (touches pass
     * through); the overlay keeps updating; only Stop and the dock header are tappable. Entering
     * closes the panel and drops opacity so the app is visible; exiting restores full opacity.
     */
    fun setWatchMode(on: Boolean) = uiStateMutable.update {
        it.copy(
            isWatchMode = on,
            isPanelOpen = if (on) false else it.isPanelOpen,
            overlayAlpha = when {
                on && it.overlayAlpha > 0.5f -> 0.15f
                !on -> 1f
                else -> it.overlayAlpha
            }
        )
    }
    fun toggleWatchMode() = setWatchMode(!uiStateMutable.value.isWatchMode)

    /** Called by QaLensOkHttpInterceptor's constructor so the Network tab knows capture is live. */
    fun markNetworkAvailable() = uiStateMutable.update { it.copy(networkAvailable = true) }

    fun logNetwork(event: NetworkEvent) {
        // OkHttp interceptors run on OkHttp's dispatcher thread; confine the state mutation to main
        // so the read-compute-write in recomputeAnalysis can't race with another concurrent call.
        mainHandler.post {
            uiStateMutable.update { old ->
                // 250: heavy apps fire 8+ calls per screen; 50 rolled over within a couple of screens.
                old.copy(networkEvents = (old.networkEvents + event).takeLast(250), networkAvailable = true)
            }
            markAnalysisDirty()
        }
    }

    fun clearNetworkLog() = uiStateMutable.update { it.copy(networkEvents = emptyList()) }
    fun clearLogs() = uiStateMutable.update { it.copy(events = emptyList()) }
    fun resetRecomposeCounters() {
        recomposeBuffer.clear()
        uiStateMutable.update { it.copy(recomposeCounts = emptyMap()) }
    }

    // Debounced — SideEffect fires on every recompose; flush to StateFlow max every 500 ms
    // so the overlay panel doesn't cascade into a recomposition storm.
    internal fun trackRecompose(name: String) {
        recomposeBuffer[name] = (recomposeBuffer[name] ?: 0) + 1
        val now = System.currentTimeMillis()
        if (now - lastRecomposeFlushMs > 500L) {
            lastRecomposeFlushMs = now
            val snapshot = recomposeBuffer.toMap()
            uiStateMutable.update { it.copy(recomposeCounts = snapshot) }
        }
    }

    fun navigate(deepLink: String) {
        val activity = currentActivityRef?.get() ?: return
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)))
            breadcrumb("Deep link → $deepLink")
        } catch (e: Exception) {
            pushError(ErrorKind.NAVIGATION, "Deep link failed: ${e.message}")
        }
    }

    /**
     * Annotated screenshot. ALWAYS saved to the system gallery (Pictures/QaLens, Android 10+);
     * [share] additionally opens the share sheet. The minimal panel and markMoment save silently.
     */
    fun takeScreenshot(share: Boolean = true) {
        val activity = currentActivityRef?.get() ?: run { pushError(ErrorKind.SCREENSHOT, "No active activity for screenshot"); return }
        val s = uiStateMutable.value
        QaLensScreenCapture.captureAndShare(activity, s.nodes, s.selectedNode, share = share)
    }

    fun restartActivity() {
        val activity = currentActivityRef?.get() ?: return
        val intent = activity.intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        activity.finish()
        activity.startActivity(intent)
        breadcrumb("Activity restarted")
    }

    // ── Session recording (.sal) ──────────────────────────────────────────────
    /**
     * Start a session recording. [video] = false (default) is the permission-free PixelCopy frame
     * recorder; [video] = true uses MediaProjection H.264 (shows a system consent dialog).
     */
    fun startRecording(video: Boolean = false) {
        val activity = currentActivityRef?.get() ?: run { pushError(ErrorKind.RECORDING, "No active activity to record"); return }
        closePanel() // get the panel out of the recording; stop via the notification
        QaLensSessionRecorder.start(activity, video)
    }

    /** Stop recording, package the `.sal`, and open the share sheet. */
    fun stopRecording() = QaLensSessionRecorder.stop()

    fun toggleRecording() {
        if (uiStateMutable.value.isRecording) stopRecording() else startRecording()
    }

    internal fun setSavingRecording(active: Boolean) {
        uiStateMutable.update { it.copy(isSavingRecording = active) }
    }

    internal fun setRecording(active: Boolean) {
        uiStateMutable.update { it.copy(isRecording = active) }
        // Reflect the record state in the persistent notification action. Use the app context so
        // the notification stays correct even when no activity is resumed (e.g. consent dialog up).
        (currentActivityRef?.get() ?: appContext)?.let {
            com.qalens.android.QaLensNotification.show(it, active)
        }
    }

    // ── Saved recordings (history / storage management) ─────────────────────────
    /** Re-scan the cache for saved `.sal` files and publish them to state (size + date). */
    fun refreshRecordings() {
        val context: Context = currentActivityRef?.get() ?: appContext ?: return
        val dir = QaLensSessionRecorder.recordingsDir(context)
        val list = (dir.listFiles { f -> f.isFile && f.name.endsWith(".sal") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .map { RecordingInfo(it.name, it.absolutePath, it.length(), it.lastModified()) }
        uiStateMutable.update { it.copy(recordings = list) }
    }

    fun shareRecording(info: RecordingInfo) {
        val context: Context = currentActivityRef?.get() ?: appContext ?: return
        val file = java.io.File(info.path)
        if (file.exists()) QaLensSessionRecorder.shareFile(context, file)
        else { pushError(ErrorKind.RECORDING, "Recording no longer exists", retry = ::refreshRecordings); refreshRecordings() }
    }

    fun deleteRecording(info: RecordingInfo) {
        runCatching { java.io.File(info.path).delete() }
        refreshRecordings()
    }

    fun deleteAllRecordings() {
        val context: Context = currentActivityRef?.get() ?: appContext ?: return
        QaLensSessionRecorder.recordingsDir(context)
            .listFiles { f -> f.name.endsWith(".sal") }
            ?.forEach { it.delete() }
        refreshRecordings()
        log("Cleared all recordings")
    }

    fun selectNode(node: InspectNode?) {
        uiStateMutable.update { it.copy(selectedNode = node, isPanelOpen = node != null || it.isPanelOpen) }
    }

    fun snapshot(): InspectionSnapshot {
        val state = uiStateMutable.value
        return InspectionSnapshot(
            screen = state.screen,
            device = state.device,
            nodes = state.nodes,
            warnings = state.warnings,
            testTags = state.testTags,
            events = state.events,
            selectedNode = state.selectedNode
        )
    }

    /** Records the outcome of the most recent screenshot capture for evidence completeness. */
    internal fun recordScreenshot(path: String?, available: Boolean, note: String? = null) {
        lastScreenshot = EvidenceAttachment("annotated_screenshot", path, available, note)
    }

    /** Assemble the full in-memory evidence bundle from everything observed so far. */
    fun evidenceBundle(attachments: List<EvidenceAttachment> = emptyList()): EvidenceBundle {
        flushAnalysis()  // never export a stale score/classification from a debounced recompute
        val s = uiStateMutable.value
        val cfg = configState.value
        val allAttachments = if (attachments.isNotEmpty()) attachments else listOfNotNull(lastScreenshot)
        return EvidenceBuilder.build(
            snapshot = snapshot(),
            network = s.networkEvents,
            config = cfg,
            featureFlags = s.featureFlags,
            attachments = allAttachments,
            networkInterceptorInstalled = s.networkAvailable,
            expectedEnvironment = cfg.expectedEnvironment,
            observedHost = s.networkEvents.lastOrNull()?.let { hostOf(it.url) },
            slowThresholdMs = cfg.slowNetworkThresholdMs,
            contractResult = s.contractResult,
            dataSources = s.dataSources
        )
    }

    // ── Exports (all redacted) ────────────────────────────────────────────────
    fun buildJiraReport(): String = QaLensReports.jira(evidenceBundle(), configState.value)
    fun buildSlackSummary(): String = QaLensReports.slack(evidenceBundle(), configState.value)
    fun buildReproSteps(): String = QaLensReports.repro(evidenceBundle(), configState.value)
    fun buildFullReport(): String = QaLensReports.full(evidenceBundle(), configState.value)
    fun buildSessionSummary(): String =
        QaLensReports.sessionSummary(uiStateMutable.value.screenQuality, configState.value)
    fun buildGitHubIssue(): String = QaLensReports.githubIssue(evidenceBundle(), configState.value)
    fun buildLinearIssue(): String = QaLensReports.linearIssue(evidenceBundle(), configState.value)
    fun buildMarkdownReport(): String = QaLensReports.markdown(evidenceBundle(), configState.value)

    /** Back-compat: the original report API now returns the full v2 report. */
    fun buildReport(): String = buildFullReport()

    /**
     * B15: Run a macro against the current UI state, returning pass/fail for each step.
     *
     * Steps that interact with the UI (tap, type, scroll) require a real driver and
     * are recorded as "pending" when run from the panel snapshot. Use this to validate
     * assertions and conditional logic without driving the UI.
     */
    fun runMacro(steps: List<MacroStep>): MacroResult {
        val state = uiStateMutable.value
        return MacroEngine.execute(steps, state, configState.value)
    }

    internal fun onActivityResumed(activity: Activity) {
        currentActivityRef = WeakReference(activity)
        updateDeviceAndScreen(activity)
        refreshInspection(activity.window.decorView)
        uiStateMutable.update { it.copy(isInstalled = true) }
    }

    internal fun onActivityPaused(activity: Activity) {
        val current = currentActivityRef?.get()
        if (current === activity) currentActivityRef = null
    }

    internal fun attachOverlay(activity: Activity) {
        currentActivityRef = WeakReference(activity)
        updateDeviceAndScreen(activity)
        QaLensActivityInstaller.attachOverlay(activity)
    }

    internal fun refreshInspection(rootView: View? = currentActivityRef?.get()?.window?.decorView) {
        val config = configState.value
        val autoNodes = rootView
            ?.takeIf { config.enableSemanticsReflection }
            ?.let { QaLensActivityInstaller.readVisibleNodes(it) }
            .orEmpty()

        val merged = mergeNodes(autoNodes, manualNodes.values.toList())
        val evaluated = QaLensRules.evaluate(merged, config)
        val warnings = QaLensRules.flattenWarnings(evaluated)

        uiStateMutable.update { old ->
            val selected = old.selectedNode?.let { selected -> evaluated.firstOrNull { it.id == selected.id } }
            old.copy(nodes = evaluated, warnings = warnings, selectedNode = selected)
        }
        markAnalysisDirty()
    }

    /**
     * Mark derived analysis as stale and schedule a debounced recompute on the main thread. Bursts
     * (e.g. 10 OkHttp calls in a screen) coalesce into a single pass [ANALYSIS_DEBOUNCE_MS] after
     * the first mark. Safe to call from any thread — the work always runs on main, so the
     * read-compute-write in [runAnalysis] can't race with a concurrent mutation.
     */
    private fun markAnalysisDirty() {
        if (!analysisDirty.compareAndSet(false, true)) return  // a recompute is already pending
        val r = Runnable {
            analysisPending = null
            if (analysisDirty.compareAndSet(true, false)) runAnalysis()
        }
        analysisPending = r
        mainHandler.postDelayed(r, ANALYSIS_DEBOUNCE_MS)
    }

    /**
     * Synchronously run any pending recompute. Call before reading derived state for an export
     * (evidence bundle / reports) so a 250ms-debounced update can't make a report stale. If called
     * on a background thread, blocks until the main-thread recompute completes (bounded).
     */
    private fun flushAnalysis() {
        if (!analysisDirty.get() && analysisPending == null) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            analysisPending?.let { mainHandler.removeCallbacks(it) }
            analysisPending = null
            if (analysisDirty.compareAndSet(true, false)) runAnalysis()
        } else {
            // Post and await so a report built on a background thread sees the fresh state.
            val latch = CountDownLatch(1)
            mainHandler.post {
                analysisPending?.let { mainHandler.removeCallbacks(it) }
                analysisPending = null
                if (analysisDirty.compareAndSet(true, false)) runAnalysis()
                latch.countDown()
            }
            runCatching { latch.await(2, TimeUnit.SECONDS) }
        }
    }

    /**
     * Recomputes all derived analysis (score, likely-cause, build safety, feature flags, and the
     * per-screen quality map) from the current observed state. Delegates to [analysisEngine] (A3)
     * — the pure pipeline extracted from this orchestrator so it's testable without Android.
     * MUST be called on the main thread — [markAnalysisDirty] enforces that.
     */
    private fun runAnalysis() {
        val cfg = configState.value
        val old = uiStateMutable.value
        val result = analysisEngine.analyze(old, cfg, old.screenQuality)
        uiStateMutable.update {
            it.copy(
                score = result.score,
                classification = result.classification,
                buildSafety = result.buildSafety,
                featureFlags = result.featureFlags,
                screenQuality = result.screenQuality,
                contractResult = result.contractResult,
                dataSources = result.dataSources
            )
        }
    }

    /** Bridge: AnalysisEngine reports provider failures here so the facade can surface them (A4). */
    private fun onAnalysisError(kind: ErrorKind, message: String) {
        pushError(kind, message)
    }

    private fun hostOf(url: String): String? = runCatching { java.net.URL(url).host }.getOrNull()

    internal fun registerManualNode(node: InspectNode) {
        manualNodes[node.id] = node
        refreshInspection()
    }

    internal fun unregisterManualNode(id: String) {
        manualNodes.remove(id)
        refreshInspection()
    }

    private fun pushEvent(event: QaEvent) {
        val config = configState.value
        val safe = event.copy(message = config.redact(event.message), tag = event.tag?.let(config::redact))
        // Confine to main so events appended from coroutines/IO threads can't race the analysis read.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            uiStateMutable.update { old ->
                old.copy(events = (old.events + safe).takeLast(config.maxEventHistory))
            }
        } else {
            mainHandler.post {
                uiStateMutable.update { old ->
                    old.copy(events = (old.events + safe).takeLast(config.maxEventHistory))
                }
            }
        }
    }

    /**
     * Surface a failure to QA instead of swallowing it into the event log. Renders a dismissible
     * banner in the panel header. Also logs the message so the Logs tab still has it. The [retry]
     * lambda, if provided, powers a ↻ button on the banner.
     */
    fun pushError(kind: ErrorKind, message: String, retry: (() -> Unit)? = null) {
        log("⚠ ${kind.name}: $message")
        val error = QaLensError(id = "${System.currentTimeMillis()}-${kind.ordinal}", kind = kind, message = message, retry = retry)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            appendError(error)
        } else {
            mainHandler.post { appendError(error) }
        }
    }

    private fun appendError(error: QaLensError) {
        uiStateMutable.update { old ->
            old.copy(errors = (old.errors + error).takeLast(20))
        }
    }

    /** Dismiss a single surfaced error by id (the banner's ✕ button). */
    fun dismissError(id: String) {
        uiStateMutable.update { old -> old.copy(errors = old.errors.filterNot { it.id == id }) }
    }

    /** Clear all surfaced errors. */
    fun clearErrors() {
        uiStateMutable.update { it.copy(errors = emptyList()) }
    }

    // ── C9: Bookmarks / annotations ──────────────────────────────────────────

    /** Drop a bookmark at the current timestamp. Visible in the panel and .sal marks.json track. */
    fun addBookmark(label: String, severity: BookmarkSeverity = BookmarkSeverity.INFO) {
        val bookmark = Bookmark(label = label, severity = severity)
        uiStateMutable.update { it.copy(bookmarks = (it.bookmarks + bookmark).takeLast(100)) }
        event("bookmark", "★ ${severity.name}: $label")
    }

    /** Remove a bookmark by its id. */
    fun removeBookmark(id: String) {
        uiStateMutable.update { it.copy(bookmarks = it.bookmarks.filter { b -> b.id != id }) }
    }

    /** Clear all bookmarks. */
    fun clearBookmarks() {
        uiStateMutable.update { it.copy(bookmarks = emptyList()) }
    }

    // ── Crashes & ANRs (B1+B13) ────────────────────────────────────────────────
    private var crashBridge: QaLensCrashBridge = QaLensNoopCrashBridge

    /** Called by [QaLensCrashHandler] to append a captured crash to state + fire the bridge. */
    internal fun appendCrash(crash: QaLensCrash) {
        val s = uiStateMutable.value
        uiStateMutable.update { old -> old.copy(crashes = (old.crashes + crash).takeLast(10)) }
        // Enrich the host crash reporter with QaLens evidence (score, classification, repro).
        runCatching {
            val evidence = buildString {
                s.classification?.let { append("Likely owner: ${it.category.display} (${it.confidence}). ") }
                s.score?.let { append("Score: ${it.score}/100. ") }
                crash.screen?.let { append("Screen: $it. ") }
                crash.lastNetworkSummary?.let { append("Last network: $it.") }
            }
            crashBridge.enrich(crash, evidence)
        }
    }

    /**
     * Register a bridge to an existing crash reporter (Sentry, Bugsnag, Crashlytics, …). QaLens
     * enriches the host report with its evidence bundle and surfaces host-caught crashes in its
     * own timeline. See [QaLensCrashBridge].
     */
    fun bridgeCrashes(bridge: QaLensCrashBridge) {
        crashBridge = bridge
        bridge.onCrash { crash ->
            // Host-reported crashes come from a vendor thread; hop to main.
            if (Looper.myLooper() == Looper.getMainLooper()) appendCrash(crash)
            else mainHandler.post { appendCrash(crash) }
        }
    }

    /** The most recent crash/ANR captured this session, or null. Surfaced in the Overview tab. */
    fun lastCrash(): QaLensCrash? = QaLensCrashHandler.peekLastCrash() ?: uiStateMutable.value.crashes.lastOrNull()

    /** Called by [QaLensFrameMetrics] to append a frame-timing sample (capped at 1000). */
    internal fun appendFrameMetrics(samples: List<FrameMetricsSample>) {
        uiStateMutable.update { old ->
            old.copy(frameMetrics = (old.frameMetrics + samples).takeLast(1000))
        }
        markAnalysisDirty()
    }

    // ── Connectivity (B5) ──────────────────────────────────────────────────────
    /** Called by [com.qalens.android.QaLensConnectivity] to update the current snapshot + transitions. */
    internal fun appendConnectivity(snapshot: ConnectivitySnapshot) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            uiStateMutable.update { old ->
                old.copy(
                    connectivity = snapshot,
                    connectivityTransitions = (old.connectivityTransitions + snapshot).takeLast(100)
                )
            }
        } else {
            mainHandler.post {
                uiStateMutable.update { old ->
                    old.copy(
                        connectivity = snapshot,
                        connectivityTransitions = (old.connectivityTransitions + snapshot).takeLast(100)
                    )
                }
            }
        }
    }

    /** The current connectivity snapshot (null if not started). Stamped onto each NetworkEvent. */
    fun currentConnectivity(): ConnectivitySnapshot? = uiStateMutable.value.connectivity

    /** Called by [QaLensMemoryMonitor] to append a memory sample (capped at 200). B8. */
    internal fun appendMemorySample(sample: MemorySample) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            uiStateMutable.update { old -> old.copy(memorySamples = (old.memorySamples + sample).takeLast(200)) }
        } else {
            mainHandler.post {
                uiStateMutable.update { old -> old.copy(memorySamples = (old.memorySamples + sample).takeLast(200)) }
            }
        }
    }

    // ── Coroutine exception bridge (B7) ────────────────────────────────────────
    /**
     * A [kotlinx.coroutines.CoroutineExceptionHandler] that forwards uncaught coroutine
     * exceptions into the QaLens timeline + crash buffer (with screen/route/network context).
     * Use it in your coroutine scopes:
     * ```kotlin
     * viewModelScope.launch(QaLens.coroutineExceptionHandler()) { … }
     * ```
     */
    fun coroutineExceptionHandler(): kotlinx.coroutines.CoroutineExceptionHandler =
        QaLensCoroutineExceptionHandler.capture()

    private fun updateDeviceAndScreen(activity: Activity) {
        val cfg = configState.value
        uiStateMutable.update { old ->
            old.copy(
                device = QaLensAndroidInfo.deviceSnapshot(activity, cfg),
                screen = QaLensAndroidInfo.screenSnapshot(activity, old.screen)
            )
        }
    }

    private fun mergeNodes(autoNodes: List<InspectNode>, manual: List<InspectNode>): List<InspectNode> {
        val byId = linkedMapOf<String, InspectNode>()
        autoNodes.forEach { byId[it.id] = it }
        manual.forEach { node ->
            val existing = node.testTag?.let { tag -> byId.values.firstOrNull { it.testTag == tag } }
            if (existing == null) {
                byId[node.id] = node
            } else {
                byId[existing.id] = existing.copy(
                    qaName = node.qaName ?: existing.qaName,
                    testTag = node.testTag ?: existing.testTag,
                    hiddenFromReports = node.hiddenFromReports,
                    source = NodeSource.MANUAL_QA_TAG
                )
            }
        }
        return byId.values
            .filter { it.bounds.width > 0 && it.bounds.height > 0 }
            .sortedWith(compareBy<InspectNode> { it.bounds.top }.thenBy { it.bounds.left })
    }
}
