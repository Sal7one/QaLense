package com.qalens

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
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

object QaLens {
    private val configState = MutableStateFlow(QaLensConfig())
    private val uiStateMutable = MutableStateFlow(QaLensUiState())
    private val manualNodes = linkedMapOf<String, InspectNode>()
    private val recomposeBuffer = mutableMapOf<String, Int>()
    private var lastRecomposeFlushMs = 0L

    private var featureFlagProvider: (() -> Map<String, Boolean>)? = null
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastScreenKey: String? = null
    private var lastScreenshot: EvidenceAttachment? = null
    private val contracts = linkedMapOf<String, ScreenContract>()
    private val dataSourceProviders = linkedMapOf<String, () -> Map<String, String>>()
    private var pendingScenario: DeepLinkScenario? = null
    private var lastNavMs = 0L

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
        recomputeAnalysis()
    }

    /**
     * Register a provider that returns the current feature flag values. Evaluated safely on every
     * analysis pass — a throwing provider is caught and ignored, never crashing the host app.
     */
    fun setFeatureFlagProvider(provider: () -> Map<String, Boolean>) {
        featureFlagProvider = provider
        recomputeAnalysis()
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
        recomputeAnalysis()
    }

    // ── Custom data sources (DataStore prefs / Room snapshots) ─────────────────
    /**
     * Register a read-only snapshot of app-owned data (DataStore preferences, Room counts, etc.)
     * to appear in the panel, evidence bundle, reports, and recorded sessions. Evaluated safely on
     * every analysis pass — a throwing provider is caught and ignored. Values are redacted.
     *
     *   QaLens.registerDataSource("Preferences") {
     *       mapOf("theme" to prefs.theme, "onboarded" to prefs.onboarded.toString())
     *   }
     */
    fun registerDataSource(name: String, provider: () -> Map<String, String>) {
        dataSourceProviders[name] = provider
        recomputeAnalysis()
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
            .onFailure { log("observeRoom failed: ${it.message}") }
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
        recomputeAnalysis()
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
        uiStateMutable.update { it.copy(dockBottom = !it.dockBottom) }
        appContext?.let { QaLensPrefs.setDockBottom(it, uiStateMutable.value.dockBottom) }
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
        uiStateMutable.update { old ->
            // 250: heavy apps fire 8+ calls per screen; 50 rolled over within a couple of screens.
            old.copy(networkEvents = (old.networkEvents + event).takeLast(250), networkAvailable = true)
        }
        recomputeAnalysis()
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
            log("Deep link failed: ${e.message}")
        }
    }

    /**
     * Annotated screenshot. ALWAYS saved to the system gallery (Pictures/QaLens, Android 10+);
     * [share] additionally opens the share sheet. The minimal panel and markMoment save silently.
     */
    fun takeScreenshot(share: Boolean = true) {
        val activity = currentActivityRef?.get() ?: run { log("No active activity for screenshot"); return }
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
        val activity = currentActivityRef?.get() ?: run { log("No active activity to record"); return }
        closePanel() // get the panel out of the recording; stop via the notification
        QaLensSessionRecorder.start(activity, video)
    }

    /** Stop recording, package the `.sal`, and open the share sheet. */
    fun stopRecording() = QaLensSessionRecorder.stop()

    fun toggleRecording() {
        if (uiStateMutable.value.isRecording) stopRecording() else startRecording()
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
        else { log("Recording no longer exists"); refreshRecordings() }
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
        val s = uiStateMutable.value
        val cfg = configState.value
        val allAttachments = if (attachments.isNotEmpty()) attachments else listOfNotNull(lastScreenshot)
        return EvidenceBuilder.build(
            snapshot = snapshot(),
            network = s.networkEvents,
            config = cfg,
            featureFlags = resolveFeatureFlags(cfg),
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

    /** Back-compat: the original report API now returns the full v2 report. */
    fun buildReport(): String = buildFullReport()

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
        recomputeAnalysis()
    }

    /**
     * Recomputes all derived analysis (score, likely-cause, build safety, feature flags, and the
     * per-screen quality map) from the current observed state. Pure core engines do the work;
     * this just feeds them and stores the result. Cheap enough to call on every scan/network event.
     */
    private fun recomputeAnalysis() {
        val cfg = configState.value
        val old = uiStateMutable.value
        val flags = resolveFeatureFlags(cfg)
        val observedHost = old.networkEvents.lastOrNull()?.let { hostOf(it.url) }
        val buildSafety = BuildSafetyCheck.check(old.device, cfg.expectedEnvironment, observedHost)
        val score = ReleaseReadinessEngine.score(
            warnings = old.warnings,
            screen = old.screen,
            network = old.networkEvents,
            buildSafetyIssues = buildSafety.issues,
            slowThresholdMs = cfg.slowNetworkThresholdMs
        )
        val classification = BugClassifier.classify(
            network = old.networkEvents,
            warnings = old.warnings,
            screenHistory = old.screen.history,
            slowThresholdMs = cfg.slowNetworkThresholdMs,
            buildSafetyIssues = buildSafety.issues
        )
        val key = ScreenQualityStore.keyFor(old.screen)
        val newVisit = key != lastScreenKey
        lastScreenKey = key
        val screenQuality = ScreenQualityStore.record(old.screenQuality, old.screen, score, newVisit)

        val contractResult = contracts.values
            .firstOrNull { ScreenContractValidator.appliesTo(it, old.screen) }
            ?.let { ScreenContractValidator.validate(it, old.nodes, old.warnings, old.screen, old.networkEvents) }

        val dataSources = resolveDataSources(cfg)

        uiStateMutable.update {
            it.copy(
                score = score,
                classification = classification,
                buildSafety = buildSafety,
                featureFlags = flags,
                screenQuality = screenQuality,
                contractResult = contractResult,
                dataSources = dataSources
            )
        }
    }

    private fun resolveDataSources(cfg: QaLensConfig): Map<String, Map<String, String>> =
        dataSourceProviders.mapNotNull { (name, provider) ->
            val snapshot = runCatching { provider() }
                .onFailure { log("Data source '$name' failed: ${it.message}") }
                .getOrNull() ?: return@mapNotNull null
            name to snapshot.mapValues { (_, v) -> cfg.redact(v) }
        }.toMap()

    private fun resolveFeatureFlags(cfg: QaLensConfig): Map<String, Boolean> {
        val provided = runCatching { featureFlagProvider?.invoke() }
            .onFailure { log("Feature flag provider failed: ${it.message}") }
            .getOrNull()
            .orEmpty()
        return cfg.featureFlags + provided
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
        uiStateMutable.update { old ->
            old.copy(events = (old.events + safe).takeLast(config.maxEventHistory))
        }
    }

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
