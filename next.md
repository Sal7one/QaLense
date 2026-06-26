# QaLens — Backlog

A living backlog. Each item links to the file/line where the work plugs in, notes whether it
touches the public API (→ requires a `qalens-noop` twin + `compileReleaseKotlin` check), and
flags any `.sal` format / `analysis.json` schema impact.

Conventions:
- **[API]** = changes the public `QaLens` surface → add a no-op twin in `qalens-noop`, then run
  `./gradlew :sample-app:compileReleaseKotlin`.
- **[SAL]** = changes the `.sal` artifact → bump `manifest.formatVersion` if breaking, update
  `SalTracks` (`qalens-core/.../QaLensSalFormat.kt`), the Android reader
  (`qalens-replay/.../QaLensSalReader.kt`), `web/sal.js`, `web/tools/sal_report.js`, and the
  spec at `docs/replay_backlog.md`. Add `analysis.json` fields under the existing
  `qalens-analysis/1` schema when additive.
- **[TEST]** = pure logic goes in `qalens-core` → add a unit test alongside the 49 existing ones.
- **[UI]** = touches Compose panel UI (`qalens-compose/.../QaLensInspectorPanel.kt` or
  `QaLensMinimalPanel.kt`).

Priority legend: **P0** (correctness/reliability, do first) · **P1** (high-value) · **P2** (polish).

---

## A — Architecture

### A1. Debounce `recomputeAnalysis()` (P0)
**Problem.** `recomputeAnalysis()` (`qalens-compose/.../QaLens.kt:563-605`) fires on every network
event, every semantics scan, every screen change, every config/contract/datasource mutation. Each
call runs `BuildSafetyCheck` + `ReleaseReadinessEngine.score` (iterates all warnings + all 250
network events) + `BugClassifier.classify` (iterates all network + warnings + history) +
`ScreenQualityStore.record` + `ScreenContractValidator.validate` + `resolveDataSources` +
`resolveFeatureFlags`. A burst of 10 OkHttp calls = 10 full recomputes → O(n²) over a session.
There is **no debounce** today (contrast `trackRecompose` which debounces at 500ms,
`QaLens.kt:372-380`).

**Plan.**
1. Add a `private val analysisDirty = AtomicBoolean(false)` and a `private var analysisPending: Runnable?`
   posted to the main `Looper` via `Handler(Looper.getMainLooper())`.
2. Replace direct `recomputeAnalysis()` call sites (`logNetwork:360`, `refreshInspection:555`,
   `setScreen:220`, `configure`, `contract`, `registerDataSource`, `setFeatureFlagProvider`) with
   `markAnalysisDirty()` which sets the flag and posts a 250ms-delayed runnable (deduping).
3. `recomputeAnalysis()` clears the flag, runs on `Dispatchers.Default`, and writes the result back
   to `uiStateMutable` on `Dispatchers.Main`.
4. Keep a synchronous fast path for `evidenceBundle()`/report builders so exports never read stale
   state (flush pending before assembling evidence).

**Verification.** No new public API. Add a `qalens-core` test that feeds N network events rapidly and
asserts `recomputeAnalysis` runs ≤ once per 250ms window.

---

### A2. Confine state mutation to a single dispatcher (P0)
**Problem.** `logNetwork` (`QaLens.kt:355-361`) can fire on OkHttp's IO dispatcher thread. It mutates
state and calls `recomputeAnalysis()`, which reads `uiStateMutable.value`, does synchronous work,
then writes back. Two concurrent calls race: both read old state, both compute, the second write
wins. `MutableStateFlow.update{}` is atomic for the *update* but the *computation* outside it isn't.
`manualNodes` (`:28`), `recomposeBuffer` (`:29`), `contracts` (`:36`), `dataSourceProviders` (`:37`)
have the same latent issue.

**Plan.**
1. Introduce a `private val mainHandler = Handler(Looper.getMainLooper())` and route all
   `uiStateMutable` mutations through
   `mainHandler.post { uiStateMutable.update { … } }` (or `Dispatchers.Main.immediate` via a scope).
2. `QaLensOkHttpInterceptor` (`qalens-compose/.../QaLensOkHttpInterceptor.kt:15`) already runs on
   OkHttp's thread — have it call `QaLens.logNetwork(...)` which internally posts to main.
3. Guard `manualNodes`/`recomposeBuffer`/`contracts`/`dataSourceProviders` with
   `Collections.synchronizedMap` or confine reads/writes to main (they're all touched from Compose's
   main thread today; make that explicit and enforced).
4. Fold derived-state computation inside a single `uiStateMutable.update {}` lambda so the
   read-compute-write is atomic once on main.

**Verification.** No public API change. Add a Robolectric test in `qalens-compose/src/test` that
fires `logNetwork` from a background thread concurrently and asserts no
`ConcurrentModificationException` and consistent final state.

---

### A3. Decompose the `QaLens` God Object (P1)
**Problem.** `QaLens.kt:25-673` is one `object` doing 8 jobs (config/state, screen tracking, event
ingestion, panel/inspect/tag/watch mode, recording orchestration, evidence/reports, analysis
recompute, activity lifecycle) with 12 mutable fields. It's also why the orchestrator is untestable
— all dependencies (`QaLensActivityInstaller`, `QaLensSessionRecorder`, `QaLensScreenCapture`,
`QaLensPrefs`, `QaLensNotification`) are hardcoded references.

**Plan.** Keep `object QaLens` as the **public facade** (unchanged signatures); extract `internal`
services that it delegates to. Phased so no single PR is enormous:
1. `internal class AnalysisEngine` — owns `recomputeAnalysis`, feature flags, data sources,
   contracts, screen quality. Takes `QaLensConfig` + observations, returns derived
   `QaLensUiState` slices. **[TEST]** This becomes unit-testable without Android.
2. `internal class ObservationCollector` — owns `event/log/breadcrumb/timberLog/logNetwork/setScreen`,
   the capped ring buffers, and the Room/DataStore observers (see A6).
3. `internal class RecordingController` — owns `startRecording/stopRecording/toggleRecording`,
   arming, the recordings list, and delegates to `QaLensSessionRecorder`.
4. `internal class PanelStateController` — owns open/close/inspect/tag/watch/dock/alpha +
   `setOverlayEnabled`/`panicRestore`.
5. `internal class EvidenceService` — owns `evidenceBundle` + all `buildXxxReport()` builders +
   `takeScreenshot` (delegates to `QaLensScreenCapture`).
6. `internal object ActivityBridge` — owns `currentActivity`, `onActivityResumed/Paused`,
   `attachOverlay`, `liveActivities`, `pendingRecordingVideo`.

Each service is instantiated lazily by the facade and injectable in tests via a
`internal interface QaLensBackend`. The facade keeps its 55 public members.

**Verification.** No public API change. After extraction, `./gradlew :qalens-core:test` still passes
49/49 and `:sample-app:compileReleaseKotlin` still proves parity.

---

### A4. Surface errors instead of swallowing them (P1)
**Problem.** Every error path uses `runCatching { }.onFailure { QaLens.log("… failed: ${it.message}") }`
(see `observeRoom:158`, `navigate:387`, `finalizeAndShare:357`, `shareFile:388`, `exportAppSal:562`,
`QaLensWebhook` catches). These logs go into `events` (capped 600) and are only visible if QA opens
the Logs tab and scrolls. There is **no error surface** — no panel banner, no "last error" indicator,
no retry button. If `.sal` packaging fails, the user sees nothing. The webhook is the one exception
(`QaLensWebhook.kt:33-39` has `UploadState`).

**Plan.**
1. Add `data class QaLensError(val id: String, val message: String, val kind: Kind, val ts: Long,
   val retry: (() -> Unit)?)` to `qalens-core/.../QaLensModels.kt`, with `enum class Kind {
   RECORDING, SCREENSHOT, EXPORT, WEBHOOK, NAVIGATION, DATA_SOURCE, OTHER }`.
2. Add `val errors: List<QaLensError>` to `QaLensUiState` (default `emptyList()`).
3. Replace `QaLens.log("… failed")` call sites with `pushError(kind, message, retry?)` which appends
   to `uiStateMutable.errors` (capped at 20, oldest evicted) and ALSO logs.
4. **[UI]** Render a dismissible banner above the panel tabs
   (`QaLensInspectorPanel.kt:142-191`) when `errors.isNotEmpty()`: red dot + count + expandable list,
   each row with a `↻ Retry` button if `retry != null` and a `✕` to dismiss (calls `dismissError(id)`).
5. Add `QaLens.dismissError(id: String)` + `QaLens.clearErrors()` **[API]** (+ no-op twins) and
   auto-expire errors after 60s via the main handler.

**Verification.** **[API]** — add no-op twins, run `compileReleaseKotlin`. Add a `qalens-core` test
for the error buffer cap + eviction.

---

### A5. Recorder watchdog + service-lifecycle binding (P1)
**Problem.** `QaLensSessionRecorder` (`qalens-compose/.../QaLensSessionRecorder.kt:24-396`) manages
`recording: Boolean` as a plain field set from 6 places (`start:72`, `stop:115`, `cancel:135`,
`onVideoStarted:160`, `onVideoComplete:177`, `onVideoConsentDenied:198`). If the projection service
is killed by the OS (low memory) without calling `onVideoComplete`, `recording` stays `true` forever
and `QaLens.state.value.isRecording` is stuck. `onActivityDestroyed` (`:142`) only cancels when
`liveActivities == 0` — but if the app is still alive (just the service died), the state leaks.

**Plan.**
1. Add a watchdog timer in `QaLensSessionRecorder`: on every captured frame (`tick:51`) reset a
   `lastFrameAt` timestamp; a repeating 10s check posts to main and, if `recording && now -
   lastFrameAt > 10_000`, calls `cancel()` with a logged reason "watchdog: no frames for >10s".
2. Bind to `QaLensProjectionService` lifecycle: in `onTaskRemoved` / `onDestroy` of the service
   (`qalens-compose/.../QaLensProjectionService.kt:33`), call
   `QaLensSessionRecorder.onVideoComplete(ok = false)` if a recording is active.
3. `panicRestore()` (`QaLens.kt:303`) already re-attaches the overlay — extend it to also force-clear
   `isRecording` if no frames arrived in the last 15s (the "stuck" case).
4. Surface the watchdog cancellation as an error (A4) with a `retry` that re-arms the recording.

**Verification.** No public API change. Manual: start HD recording, force-stop the projection
service via `adb shell am stopservice`, verify `isRecording` flips to false within ~10s and an error
banner appears.

---

### A6. Move Room/DataStore observation behind an interface (P1)
**Problem.** `QaLens.kt:9-10` imports `androidx.room.RoomDatabase` and
`androidx.room.InvalidationTracker`. The orchestrator (in `qalens-compose`) directly knows about
Room types. Room is `compileOnly` (correct, `qalens-compose/build.gradle.kts:49`), but the type
coupling means the observation logic can't move to `qalens-core` or be tested without an Android
emulator. Same for the DataStore `Flow` generic in `observeDataStore` (`:167`).

**Plan.**
1. Define `interface DataSourceObserver { fun start(emit: (String, Map<String,String>) -> Unit);
   fun stop() }` in `qalens-core/.../QaLensModels.kt` (or a new `QaLensDataSource.kt`).
2. Add `fun observeDataSource(name: String, observer: DataSourceObserver)` to the `QaLens` object
   **[API]** — the public, type-erased entry point (+ no-op twin).
3. Keep `observeRoom(db, *tables)` and `observeDataStore(name, flow, describe)` as
   `internal` thin wrappers in `qalens-compose` that build a `DataSourceObserver` and call
   `observeDataSource`. Their public signatures stay (they're already **[API]** with no-op twins) —
   only the implementation moves behind the interface.
4. `ObservationCollector` (from A3) owns the `DataSourceObserver` registry and the `emit` →
   `uiStateMutable.dataSources` update, confining it to main (A2).

**Verification.** **[API]** (additive — new `observeDataSource`). Existing `observeRoom`/
`observeDataStore` callers unchanged. Add a `qalens-core` test with a fake `DataSourceObserver` that
emits and asserts the state slice updates.

---

### A7. Enforce no-op parity with a test, not just a compile (P2)
**Problem.** The 55-member `QaLens` object + modifiers + interceptors are manually mirrored in
`qalens-noop` across 3 files (`NoopQaLens.kt`, `NoopOkHttpInterceptor.kt`,
`navigation/NoopNavigation.kt`). The only enforcement is
`./gradlew :sample-app:compileReleaseKotlin` — which only catches symbols the sample app *uses*. A
public symbol unused by the sample could drift unnoticed.

**Plan.**
1. Add `qalens-compose/src/test/kotlin/com/qalens/ParityTest.kt` (JVM test, no Android).
2. Use Kotlin reflection (`::class.memberFunctions`, `memberProperties`) on both
   `com.qalens.QaLens` (from `qalens-compose`) and `com.qalens.QaLens` (from `qalens-noop` on the
   test classpath via a `testImplementation(project(":qalens-noop"))`).
3. Assert the sets of public member names + parameter counts match. Also reflect on the modifier
   extensions (`Modifier.qaTag`, etc.) and `QaLensRoot`, `QaLensOkHttpInterceptor`,
   `QaLensTimberTree`, `QaLensNavHost`, `QaLensNavigator`.
4. Fail with a diff if either side has a symbol the other lacks.

**Verification.** The test itself is the verification. Run with `./gradlew :qalens-compose:test`.

---

## B — Features & hooks

### B1+B13. Crash & ANR capture + crash-reporter bridges (P0)
**What.** Capture uncaught exceptions (`Thread.setDefaultUncaughtExceptionHandler`) and ANRs (a
main-thread watchdog: post a tick on the main `Looper`, if it doesn't run within 5s, it's an ANR).
Fold them into the QaLens timeline + evidence bundle + `.sal`. Then bridge bidirectionally with
existing crash reporters (Sentry `beforeSend`, Bugsnag `addOnError`, Crashlytics `setCustomKeys`)
so QaLens enriches their reports with the evidence bundle (score, repro, screen context) and
surfaces their crashes in its own timeline.

**Why.** Today a crash that kills the app is invisible in the `.sal` and the Jira report — QaLens
only sees logs via Timber. Every crash reporter gets the crash in isolation; QaLens's unique angle
is the network/timeline/state context that *led* to it. The bridge means teams keep their existing
reporter and gain the context, instead of replacing anything.

**Plan.**
1. New `qalens-compose/.../QaLensCrashHandler.kt`:
   - Installed in `QaLens.install()` (`QaLens.kt:178`). Chains the previous
     `UncaughtExceptionHandler`.
   - On crash: capture `Thread`, stack trace, `QaLensUiState` snapshot (screen, route, last N
     network/events), write a `crash.json` into the active `.sal` if recording, emit a
     `QaLens.event("crash", ...)` into the timeline, then delegate to the previous handler.
   - ANR watchdog: a `Handler` posting a `Runnable` that sets `mainAlive=true`; a background thread
     checks every 1s; if `mainAlive` is false for 5s, emit `QaLens.event("anr", "main thread
     blocked 5s")` and bump the score (A1's `ReleaseReadinessEngine`).
2. New `interface QaLensCrashBridge` in `qalens-compose`:
   - `fun enrich(report: CrashReportBuilder)` — QaLens attaches its evidence (score, repro, screen,
     last network failure) to the host report.
   - `fun onCrash(cb: (QaLensCrash) -> Unit)` — the host reporter forwards crashes into QaLens.
   - Implementations: `QaLensSentryBridge` (`beforeSend` hook), `QaLensBugsnagBridge`
     (`addOnError`), `QaLensCrashlyticsBridge` (`setCustomKeys` + `recordException`).
3. **[API]** `QaLens.bridgeCrashes(bridge: QaLensCrashBridge)` (+ no-op twin). **[API]**
   `QaLens.lastCrash(): QaLensCrash?` (+ no-op twin).
4. **[UI]** "Crashes" row in the Overview tab (`QaLensInspectorPanel.kt` Overview section) showing
   `lastCrash` with a "Copy with evidence" button.
5. **[SAL]** Add `crashes.json` track: `[{ ts, thread, type: CRASH|ANR, throwable, stackTrace,
   screen, route, lastNetworkSummary }]`. Add to `SalTracks` (`qalens-core/.../QaLensSalFormat.kt`)
   + `analysis.json.coverage.crashes` + `analysis.json.anomalies` (kind `crash`/`anr`).
6. Sample app: wire a "Crash" button in the sample's Tools/Debug screen that throws, to demo the
   capture. Register a no-op Sentry bridge (compileOnly) to show the enrichment path.

**Verification.** **[API]** + **[SAL]**. Unit-test the crash buffer + the bridge interface in
`qalens-core` (the bridge contract is pure Kotlin). Manual: tap the sample's crash button, reopen
the app, verify the crash appears in the Overview + the next `.sal` includes `crashes.json`.

---

### B2. Frame-timing / jank track (P0)
**What.** Use `Window.OnFrameMetricsAvailableListener` (API 24+) to capture per-frame render times
(display, layout, draw, GPU, total) and flag jank (>16ms) and frozen frames (>700ms). Surface as a
"Performance" score dimension and a new `.sal` track.

**Why.** Jank ("it felt slow") is one of the most common QA-visible bugs and currently has zero
evidence. QaLens has recompose counts (`QaLensRecomposeTracker`) but no frame-level timing. Embrace
and Firebase Performance do this; QaLens's angle is jank *correlated with* the network/screen/state
timeline.

**Plan.**
1. New `qalens-compose/.../QaLensFrameMetrics.kt`:
   - Attached in `QaLensActivityInstaller.attachOverlay` (`QaLensActivityInstaller.kt:62-85`) to the
     host `Activity`'s `Window`.
   - On each `onFrameMetricsAvailable`, parse `FrameMetrics` into a `FrameMetricsSample(ts, totalMs,
     layoutMs, drawMs, gpuMs, jank: Boolean, frozen: Boolean)`.
   - Ring buffer (cap 1000 samples, oldest evicted — matches the events/network caps).
2. Add `FrameMetricsSample` + `val frameMetrics: List<FrameMetricsSample>` to `QaLensUiState` in
   `qalens-core/.../QaLensModels.kt`.
3. **[TEST]** New `qalens-core` engine `JankAnalyzer` (pure): given a list of samples, returns
   `{ jankCount, frozenCount, p95TotalMs, p99TotalMs, worstFrame }`. Add a
   `ScorePenalty` in `ReleaseReadinessEngine.score` (`qalens-core/.../QaLensScore.kt`) for
   frozen frames (−5 each, capped) and high jank rate (>30%, −10).
4. **[UI]** Fold into the Device tab's recompose section (`QaLensInspectorPanel.kt:506-563`): show
   jank/frozen counts, p95/p99, and a sparkline of totalMs over the session. Add a "Jank" pill to the
   Overview stat row.
5. **[SAL]** Add `performance.json` track: sampled `FrameMetricsSample`s + the `JankAnalyzer`
   digest. Add to `SalTracks` + `analysis.json.coverage.performance` + `analysis.json.stats.jank`.

**Verification.** **[SAL].** Unit-test `JankAnalyzer`. Manual: scroll a janky list in the sample,
verify the Device tab shows jank counts and the score drops.

---

### B3. Pluggable panel tabs (P0)
**What.** An extension point so apps can register custom tabs in the QaLens inspector panel — e.g. a
"My Feature Flags" dashboard, a "Backend Health" tab, or a "Cache Inspector".

**Why.** Every app has domain-specific things QA needs to see. Currently the only way to surface app
data is `registerDataSource` (a flat key-value map, `QaLens.kt:141`). A composable tab API lets apps
build rich, interactive surfaces without forking QaLens.

**Plan.**
1. Define `interface QaLensTabProvider { val title: String; @Composable fun Content(state:
   QaLensUiState, config: QaLensConfig) }` in `qalens-compose`.
2. **[API]** `QaLens.registerTab(provider: QaLensTabProvider)` (+ no-op twin). Also
   `QaLens.unregisterTab(title: String)`.
3. **[UI]** In `QaLensInspectorPanel.kt`'s tab row (`:223-237`), append registered tabs after the
   built-ins. The `when (tab)` dispatch renders `provider.Content(currentState, currentConfig)` for
   the selected custom tab.
4. Provide a `QaLensTabProvider` that renders a `registerDataSource` map as a simple table (so the
   existing flat-data path still works, but apps can upgrade to a custom tab).
5. Sample app: register a "Sample Backend" tab showing a live hit/miss counter from the sample's
   repository, to demonstrate the API.
6. **[API]** No-op twin: `QaLens.registerTab` is a no-op; `QaLensTabProvider` is still constructible
   in release (so app code compiles) but its `Content` is never invoked.

**Verification.** **[API].** Manual: the sample's custom tab renders and updates as state flows.

---

### B4. GitHub Issue / Linear / Markdown report exports (P0)
**What.** Beyond Jira (`QaLensReports.kt:21-103`) and Slack (`:106-124`), add report builders for
GitHub Issues (markdown body + YAML frontmatter labels), Linear (markdown), and a generic Markdown
export.

**Why.** Not every team uses Jira. GitHub Issues are the default for OSS and many startups; Linear is
common for product teams. The report structure is already there — it's formatting work.

**Plan.**
1. **[TEST]** Add to `qalens-core/.../QaLensReports.kt`:
   - `fun githubIssue(b: EvidenceBundle, c: QaLensConfig): String` — markdown body with a YAML
     frontmatter block (`labels: [qa, <likely-owner>]`, `assignee`, `build`, `device`) that GitHub
     Actions can parse, plus the repro steps as a checklist, network failures as a table, and a
     `?t=<sec>` deep link to the `.sal` moment if attached.
   - `fun linearIssue(b: EvidenceBundle, c: QaLensConfig): String` — Linear-flavored markdown
     (priority/label syntax).
   - `fun markdown(b: EvidenceBundle, c: QaLensConfig): String` — clean generic markdown.
2. **[API]** Expose `QaLens.buildGitHubIssue()`, `QaLens.buildLinearIssue()`,
   `QaLens.buildMarkdownReport()` on the facade (`QaLens.kt:512-520`) + no-op twins (return `""`).
3. **[UI]** Add buttons to the Bug Bundle tab (`QaLensInspectorPanel.kt:1097-1102`) and a
   "Copy as…" dropdown in the minimal panel. Each copies to clipboard with a toast.
4. Unit-test each builder against a fixture `EvidenceBundle` in `qalens-core`.

**Verification.** **[API]** + **[TEST].** `compileReleaseKotlin` + the new unit tests.

---

### B5. Network connectivity & type track + Chucker coexistence (P0)
**What.** Observe `ConnectivityManager.NetworkCallback` to track WiFi/Cellular/Offline transitions
and signal strength, surface them in the timeline + a `.sal` track, and use them in bug
classification (a failed request coinciding with a connectivity drop →
`Configuration/Environment`, not `Backend/API`). Integrate with
[Chucker](https://github.com/ChuckerTeam/chucker) so both QaLens and Chucker can intercept the same
OkHttp client simultaneously without conflicts.

**Why.** Today `NetworkEvent.isError` (`qalens-core/.../QaLensModels.kt`) can't distinguish "server
returned 500" from "device lost connection mid-request" — the interceptor catches the exception class
name only (`QaLensOkHttpInterceptor.kt:47`). Bugsnag and Embrace capture connectivity. Chucker is the
most popular on-device network inspector for Android; many teams already run it in debug. Forcing a
choice between Chucker's rich UI and QaLens's evidence bundle is a non-starter, so we make them
compose.

**Plan — connectivity:**
1. New `qalens-android/.../QaLensConnectivity.kt`:
   - `fun start(context)` registers a `NetworkCallback` with `NETWORK_CAPABILITIES` + signal
     strength. Emits `QaLens.event("connectivity", "<type> · <strength> bars")` on transitions.
   - Exposes `current(): ConnectivitySnapshot { type, strengthBars, hasVpn, isMetered }`.
2. Add `ConnectivitySnapshot` to `QaLensUiState` (default null). The `QaLensOkHttpInterceptor`
   stamps each `NetworkEvent` with the snapshot at request time (new field
   `NetworkEvent.connectivity: ConnectivitySnapshot?`).
3. **[TEST]** `qalens-core` `BugClassifier` (`QaLensBugClassifier.kt:38-133`): if a failed request's
   `connectivity.type == OFFLINE` or the failure ts is within ±2s of an OFFLINE transition, classify
   as `CONFIGURATION_ENVIRONMENT` (new `BugCategory`) instead of `Backend/API`. Add unit tests.
4. **[UI]** Connectivity chip in the Network tab header (`QaLensInspectorPanel.kt` Network section)
   + transitions in the timeline.
5. **[SAL]** Add `connectivity.json` track (transitions + per-request snapshots) +
   `analysis.json.coverage.connectivity`.

**Plan — Chucker coexistence:**
6. QaLens's interceptor (`QaLensOkHttpInterceptor`) must be safe to add **alongside** Chucker's
   `ChuckerInterceptor`. Verify ordering: Chucker should run first (it needs the raw request/response
   to build its body), QaLens second (it only reads metadata). Document the recommended order in
   `integration.md`:
   ```kotlin
   OkHttpClient.Builder()
       .addInterceptor(ChuckerInterceptor.Builder(context).build())  // first
       .addInterceptor(QaLensOkHttpInterceptor())                    // second
       .build()
   ```
7. Audit `QaLensOkHttpInterceptor` for anything that would break when Chucker is also intercepting:
   - QaLens must **not** read/seek the request/response body (it doesn't today — by design, bodies
     aren't captured). Confirm `QaLensOkHttpInterceptor` only touches `request.url`, `request.method`,
     `response.code`, `response.receivedResponseAtMillis - response.sentRequestAtMillis`, and
     `response.headers` (for sizes). No `peekBody`, no `source()`.
   - QaLens must **not** mutate the request or response (it doesn't). The interceptor returns
     `chain.proceed(request)` untouched.
8. Add a `compileOnly` Chucker dep to `qalens-compose` and a `QaLensChuckerBridge` that, if Chucker
   is on the classpath, surfaces a "Open Chucker" button in the Network tab (launches Chucker's
   activity via reflection so the dep stays optional). If Chucker is absent, the button is hidden.
9. Sample app: add both interceptors to the sample's OkHttp client to prove coexistence. Add a test
   that verifies `QaLensOkHttpInterceptor` + `ChuckerInterceptor` both pass in
   `:sample-app:assembleDebug`.
10. Document in `integration.md` + `docs/ONBOARDING.md` that QaLens and Chucker are complementary
    (Chucker = full body inspection on-device; QaLens = redacted evidence + `.sal` + AI digest).

**Verification.** **[SAL]** + **[API]** (the `NetworkEvent.connectivity` field is additive on a
core model — release-safe). Unit-test the classifier change. Manual: run the sample with both
interceptors, toggle airplane mode, verify the timeline shows the transition and a failed request is
classified as `Configuration/Environment`.

---

### B7. Coroutine exception bridge (P1)
**What.** A `CoroutineExceptionHandler` implementation that forwards uncaught coroutine exceptions
into the QaLens timeline (as error events) and optionally into the crash handler (B1).

**Why.** Most modern Android apps use coroutines. An uncaught exception in a `viewModelScope` or
`lifecycleScope` currently either crashes the app or is swallowed by a generic handler. QaLens has
`timberLog` but only if the app logs it.

**Plan.**
1. New `qalens-compose/.../QaLensCoroutineExceptionHandler.kt` implementing
   `CoroutineExceptionHandler`:
   - On `handleException(context, throwable)`: emit `QaLens.event("coroutine_exception",
     "${throwable::class.simpleName}: ${throwable.message}")`, capture the stack trace into the
     crash buffer (B1), and rethrow to the default handler unless the app opts out.
2. **[API]** `fun QaLens.coroutineExceptionHandler(): CoroutineExceptionHandler` (+ no-op twin
   returning a no-op handler). Also a `QaLens.installCoroutineExceptionHandler()` that installs it
   as the global default via `CoroutineExceptionHandler` + a `SupervisorJob` in installed scopes.
3. **[UI]** Surface coroutine exceptions in the Logs tab with a distinct `COROUTINE_EXCEPTION` type
   chip + in the Overview's error count.
4. Sample app: wire a button that launches a coroutine which throws, to demo.

**Verification.** **[API].** Manual: tap the sample's coroutine-crash button, verify the exception
appears in the Logs tab and Overview without killing the app.

---

### B8. Memory pressure & low-memory events (P1)
**What.** Hook `ComponentCallbacks2.onTrimMemory` / `onLowMemory` at the application level and record
the trim level as timeline events. Optionally sample `Runtime.totalMemory()` / `freeMemory()` /
`Debug.getNativeHeapAllocatedSize()` periodically during recordings.

**Why.** OOMs and memory pressure are common, hard-to-reproduce bugs. Embrace tracks this. QaLens
currently has zero memory visibility beyond what Timber logs.

**Plan.**
1. Register a `ComponentCallbacks2` in `QaLens.install()` (`QaLens.kt:178-190`):
   - On `onTrimMemory(level)`: emit `QaLens.event("memory_trim", "level=$level
     (${describeTrimLevel(level)})")`. Map `TRIM_MEMORY_RUNNING_LOW` etc. to human labels.
   - On `onLowMemory()`: emit `QaLens.event("low_memory", "onLowMemory")`.
2. During an active recording, sample `Runtime` + `Debug` heap stats every 2s into a
   `MemorySample(ts, totalKb, freeKb, nativeKb, trimLevel)`.
3. Add `MemorySample` to `qalens-core/.../QaLensModels.kt`.
4. **[SAL]** Add `memory.json` track (samples + trim events) + `analysis.json.coverage.memory` +
   `analysis.json.anomalies` (kind `memory_spike` if delta > 50MB between samples).
5. **[UI]** "Memory" card in the Device tab (`QaLensInspectorPanel.kt:506-563`): current heap,
   trim events count, and a sparkline during recordings.
6. **[TEST]** A `qalens-core` analyzer that detects memory spikes from the sample list.

**Verification.** **[SAL].** Manual: allocate a large array in the sample, verify the trim event +
heap growth appear in the Device tab and the `.sal`.

---

### B11. Session diff / compare (P1)
**What.** Open two `.sal` recordings and see what changed: screens visited, network calls, errors,
score, anomalies. Especially useful for "it worked yesterday, broken today" — compare the last
passing session vs the failing one.

**Why.** No competing tool does this well. It directly addresses the most common QA question:
"what changed?". The web player is the natural home; the Android player can show a summary diff.

**Plan.**
1. **[TEST]** New `qalens-core/.../QaLensDiff.kt` with `fun diff(a: SalSessionSummary, b:
   SalSessionSummary): SalDiff` where `SalSessionSummary` is a lightweight projection (manifest +
   counts + failed requests + anomalies + screen visits + score + likelyOwner). `SalDiff` returns:
   - `addedScreens`, `removedScreens`, `addedFailedRequests`, `resolvedFailedRequests`,
     `scoreDelta`, `newAnomalies`, `resolvedAnomalies`, `likelyOwnerChanged`.
   - Unit-test against two fixture sessions in `qalens-core`.
2. **Web:** Add a "Compare" mode to `web/`:
   - A second drop target appears when a session is open ("Drop another .sal to compare").
   - Renders a two-column diff view: left = baseline, right = current, with added/removed rows
     highlighted, score delta at the top, and a "what changed" summary card.
   - Deep-linkable: `?compare=a.sal&b=b.sal` (when served over http).
3. **Android player:** `QaLensPlayerActivity` accepts a second `.sal` via a second intent extra;
   if present, shows a diff summary screen instead of the single-session player.
4. **CLI:** `sal_report.js` gains `--compare baseline.sal` that prints a markdown diff and exits
   non-zero if the score dropped or new failures appeared.
5. **[SAL]** No format change — diff is computed from existing tracks. Optionally add
   `analysis.json.baselineRef` (a sessionId) when a recording is marked as the baseline for a flow.

**Verification.** **[TEST]** for the core diff. Manual: record the sample's "happy path", then the
"failure path", drop both into the web compare view, verify the failed request + score delta appear.

---

### B14. Global search across all tracks (P1)
**What.** A search bar in the panel that searches logs, network events, timeline, and test tags
simultaneously, showing a unified results list. Currently each tab has its own filter (e.g.
`LogsTab` filters logs only).

**Why.** In a long QA session, finding "that one error" across 600 events + 250 network calls + 50
nav events is painful. A unified search is a standard feature in dev tools.

**Plan.**
1. **[UI]** Add a search field to the panel header (`QaLensInspectorPanel.kt:142-191`) that, when
   focused and non-empty, switches the body to a unified results list:
   - Searches `events.title/detail`, `networkEvents.url/method/error`, `logs.message/tag`, and
     `nodes.qaTag/qaName/text` (from the last inspection snapshot).
   - Results grouped by track, each row tappable to seek (if a `.sal` is open) or to pin the
     playhead.
2. Debounce the search input by 150ms; reuse A1's main-thread confinement.
3. Clear-on-tab-switch; `Esc` exits unified search and restores the active tab.
4. **[API]** No public change. The search is internal to the panel.

**Verification.** Manual: type a URL fragment, verify it matches network + logs + any node tagged
with it.

---

### B15. Macro assertions & conditional steps (P1)
**What.** Extend the macro DSL with `assert <tag> exists`, `assert <tag> has-label <text>`,
`assert network no-errors`, `assert route = <route>`, and `if <condition> … else …`.

**Why.** Macros today are fire-and-forget (`QaLensMacros.kt:41-76`). Adding assertions turns them
into lightweight smoke tests that can pass/fail in CI — a macro can assert "checkout completed, no
failed requests" and the run logs a pass/fail.

**Plan.**
1. New verbs in `QaLensMacros.kt` (`qalens-compose/.../QaLensMacros.kt`):
   - `assert exists <tag|text>` — waits up to 5s, fails if not found.
   - `assert label <tag> <text>` — reads the node's label/content-description.
   - `assert route <route>` — checks `QaLens.state.value.route`.
   - `assert network no-errors` — checks `networkEvents.none { it.isError }`.
   - `assert network no-slow` — checks no request ≥ `slowNetworkThresholdMs`.
   - `if <condition> … else …` — condition is one of the assert predicates; branches are indented
     step blocks.
2. A macro run produces a `MacroResult { pass: Boolean, failures: List<AssertionFailure> }`.
3. **[API]** `QaLens.runMacro(name): MacroResult` (+ no-op twin returning a passing result).
   Currently macros are fire-and-forget; this makes them awaitable.
4. **[UI]** Surface pass/fail in the minimal panel's macro list + the timeline (`QaLens.event` per
   assertion). Failed assertions get a red row.
5. **[SAL]** Assertion results fold into `timeline.json` (kind `ASSERTION`) +
   `analysis.json.anomalies` (kind `assertion_failed`).
6. `sal_report.js` / the web player render assertion results with pass/fail icons.
7. **[TEST]** Unit-test the assertion evaluator against fixture `QaLensUiState`s in a new
   `qalens-compose` Robolectric test (the evaluator is pure given a state snapshot).

**Verification.** **[API]** + **[SAL].** Manual: write a macro with a failing assertion, run it,
verify the failure appears in the timeline and the run is marked failed.

---

### B16. Per-data-source redaction (P1)
**What.** Let `registerDataSource` accept a redaction policy: per-key redaction rules on top of the
global defaults.

**Why.** The current one-size-fits-all redaction (`QaLens.kt:607-613` folds all data source values
through `config.redact`) may over-redact (masking a theme string that looks like a number) or
under-redact (domain-specific PII the defaults don't catch — SSNs, account numbers, healthcare
fields). Apps with domain-specific data need per-field control.

**Plan.**
1. Extend `QaLens.registerDataSource` signature **[API]**:
   ```kotlin
   fun registerDataSource(
       name: String,
       provider: () -> Map<String, String>,
       redactKeys: List<String> = emptyList(),          // exact key match → always redact value
       redactPatterns: List<Regex> = emptyList(),       // applied to these values only
       redactAll: Boolean = false                        // redact every value from this source
   )
   ```
   (+ no-op twin with the same signature).
2. In `resolveDataSources` (`QaLens.kt:607-613`): for each source, apply `redactKeys` (if the key
   matches, replace the value with `[REDACTED]`), then `redactPatterns`, then `redactAll`, and
   finally the global `config.redact` (unchanged — global still applies as a backstop).
3. **[UI]** In the Device tab's data sources section (`QaLensInspectorPanel.kt` data sources),
   show a 🔒 badge next to keys that are redacted by a per-source rule, so QA knows why a value is
   masked.
4. **[TEST]** Extend `QaLensRedactorTest` (`qalens-core/src/test/.../QaLensRedactorTest.kt`) with
   cases for per-key, per-pattern, and `redactAll` — asserting the global rules still apply as a
   backstop.
5. Update `integration.md` and `docs/ONBOARDING.md` with the new signature + examples (e.g. a
   "User" data source with `redactKeys = listOf("ssn", "email")`).

**Verification.** **[API]** + **[TEST].** `compileReleaseKotlin` + the redactor tests.

---

## C — Web app

### C1. `?t=` seek fires before video metadata is ready (P0)
**Bug.** `onLoaded` (`web/app.js:433-436`) calls `seek(...)` synchronously inside the same function
as `setupMedia()`. For video mode, `syncMedia(true)` (`:599`) sets `els.video.currentTime =` but the
`<video>` may not have loaded the source yet (no `loadedmetadata`). The seek silently fails or lands
at 0.

**Fix.** In `setupMedia`, when assigning `video.src`, attach a one-shot `loadedmetadata` listener
that calls `syncMedia(true)` (which sets `currentTime`) and, if `pendingSeekSec != null`, the deep
link seek. Remove the listener after it fires. Guard `syncMedia`'s `currentTime` set with
`if (els.video.readyState >= 1)`.

---

### C2. `renderTrack` rebuilds the entire DOM every 200ms during playback (P0)
**Bug.** `updatePlayheadUI(heavy)` (`web/app.js:643-644, 660-669`) calls `renderTrack()` every ~200ms
when `follow` is on, which rebuilds the full `innerHTML` of `trackList` (`:891-916`). For 600 log
rows that's 600 DOM nodes destroyed and recreated 5×/second → jank + lost scroll position + lost
expanded `.row-detail` panels.

**Fix.** Add a `lastRenderSignature` (a hash of `activeTrack + search query + logLevel +
S.frames.length + total row count`). In `renderTrack`, if the signature is unchanged, skip the
rebuild and instead call a new `updateRowHighlighting()` that only toggles `row-cur`/`row-future`
classes on existing `.row` elements (and scrolls the current row into view if `follow`). Only
rebuild when the filter or track changes.

---

### C3. Keyboard shortcuts fire while typing in `<textarea>` (P0)
**Bug.** `web/app.js:1180-1181`:
```js
if (e.target.tagName === "INPUT" || e.target.tagName === "SELECT") return;
```
The `.appsal` editor uses `<textarea>` for SQL/macro steps (`web/index.html:304,321`). So pressing
Space while editing a macro pauses playback, and `s`/`e`/`t`/`x`/`1-6` all trigger. Also doesn't
guard `contenteditable`.

**Fix.**
```js
if (e.target.tagName === "INPUT" || e.target.tagName === "SELECT" ||
    e.target.tagName === "TEXTAREA" || e.target.isContentEditable) return;
```

---

### C4. Object URLs leak on session close (P0)
**Bug.** `objectUrls` (`web/app.js:150`) is populated in `onLoaded` (`:406-407`) and revoked only on
the *next* load (`:406`). `closeSession()` (`:440-449`) never revokes them. If a user opens a
session, closes it, and never opens another, the blob URLs leak until page reload (64+ URLs for a
frame session, plus the video URL).

**Fix.** Extract a `revokeObjectUrls()` helper; call it in both `closeSession()` and at the top of
`onLoaded` (the existing call site).

---

### C5. `screenVisits()` double-assigns `end` and can mis-report durations (P1)
**Bug.** `web/app.js:493-501`:
```js
for (const s of S.state) {
  const last = visits[visits.length - 1];
  if (last && last.name === name) last.end = s.ts;   // sets end to next sample ts
  else visits.push({ name, start: s.ts, end: s.ts });
}
visits.forEach((v, i) => { v.end = i + 1 < visits.length ? visits[i + 1].start : S.end; });
```
The first loop sets `last.end = s.ts` on each new sample (correct: the visit ends when the next
sample arrives). But the second loop overwrites `end` with the next *visit's* start — which, for
merged visits (same screen across multiple samples), is a later timestamp than the next sample,
inflating the duration. The Screens tab and the statbar's "screens" count derive from this.

**Fix.** Drop the second loop entirely. The first loop already sets correct `end` values. Only
fix-up needed: set the *last* visit's `end` to `S.end` if it was never advanced:
```js
const last = visits[visits.length - 1];
if (last) last.end = S.end;
```

---

### C6. `videoBase()` fallback path can seek video to 0 during early load (P1)
**Bug.** `web/app.js:591-595`:
```js
function videoBase() {
  if (S.manifest.videoStartMillis) return S.manifest.videoStartMillis;
  if (els.video.duration > 0) return S.end - els.video.duration * 1000;
  return S.start;
}
```
If `videoStartMillis` is absent (older files) and `video.duration` is `NaN`/0 (no metadata yet),
`videoBase()` returns `S.start`. `syncMedia(true)` then sets `currentTime = (playhead - S.start) /
1000`, which during early load (playhead ≈ S.start) is ~0 — fine, but combined with C1's early
seek, a `?t=` seek lands at 0.

**Fix.** In `syncMedia`, only set `video.currentTime` if `els.video.readyState >= 1` AND
`Number.isFinite(els.video.duration)`. Otherwise stash the desired `currentTime` and apply it on
`loadedmetadata`.

---

### C7. `stepEvent(-1)` is O(n) and stalls on equal timestamps (P1)
**Bug.** `web/app.js:656`:
```js
let p = null; for (const e of ev) { if (e.ts < playhead) p = e; else break; }
```
Linear scan (fine for small lists, but `allEvents()` can be 800+). If two events share the same
`ts`, stepping back from the second lands on the first (same ts) — no visible movement, confusing
QA.

**Fix.** Use a binary search (`allEvents()` is sorted by `ts`) to find the rightmost index with
`ts < playhead`, then step back one. If the previous event has the same `ts`, keep stepping back
until `ts` changes (or use `<` strictly with index arithmetic).

---

### C8. Keyboard help is undiscoverable (P1)
**Gap.** Shortcuts exist but are only documented in the settings drawer (`web/index.html:227-238`).

**Fix.** Add a `?` key handler that toggles a modal/overlay listing all shortcuts. Add a small
"⌨ Shortcuts" hint near the transport controls. Clicking outside or pressing `Esc`/`?` closes it.

---

### C9. No annotations / bookmarks in the timeline (P1)
**Gap.** The Android side has `QaLens.markMoment(note)` which drops a starred breadcrumb + screenshot,
but the web player can't display or create them.

**Fix.**
- Parse a `marks.json` track from the `.sal` (if present) into `S.marks` in `web/sal.js:parse`.
- Render marks as star icons on the scrubber (`renderScrubMarks`, `web/app.js:806`) + as a row in
  the Timeline track.
- Add a "⭐ Mark moment" button to the transport that, during playback, records a mark locally
  (in-memory, exportable to the markdown summary) with the current playhead ts + an optional note
  prompted via a small inline input.
- Surface marks in `exportSummary` (`web/app.js:957`).

---

### C10. No mobile / touch layout (P1)
**Gap.** The 3-column layout (`web/index.html:108-195`: `col-player` / `col-mid` / `col-info`) has no
`@media` breakpoints in `web/styles.css`. On a tablet or phone, the columns don't stack and the
player is unusable.

**Fix.** Add `@media (max-width: 1000px)` and `@media (max-width: 640px)` breakpoints:
- Collapse to single column (player → tracks → state) under 1000px.
- Under 640px: stack the transport controls vertically, hide the filmstrip behind a toggle, make the
  scrubber full-width, and switch the track tabs to a horizontally-scrollable row.
- Make rows tappable (they already seek on click) and add `touch-action: manipulation` to prevent
  double-tap zoom on the player.

---

### C11. Web player ignores `analysis.json.stats` and `.endpoints` (P1)
**Gap.** `insights()` (`web/app.js:507`) reads `analysis.anomalies` but ignores `analysis.stats`
(latency percentiles, totals) and `analysis.endpoints` (per-endpoint aggregates). The Android device
precomputed these; the web player recomputes worse heuristics or skips them.

**Fix.**
- In `renderStatbar` (`:698`) and `networkSubstats` (`:827`), prefer `S.analysis.stats` when present
  (e.g. `analysis.stats.p95LatencyMs`, `analysis.stats.totalBytes`) over the web's recomputed
  values.
- Add an "Endpoints" sub-view to the Network tab: a table from `analysis.endpoints` showing
  `host/path`, `count`, `failed`, `avgLatencyMs`, `p95LatencyMs` — tappable to filter the network
  list to that endpoint.
- Show `analysis.likelyOwner` in the Summary card alongside the `summary.json` version (they should
  match; if they diverge, show both with a note).

---

### C12. `for_ai.md` is never surfaced (P1)
**Gap.** Every `.sal` ships a `for_ai.md` that explains the archive to any AI, but `web/sal.js:parse`
(`:99-131`) doesn't even read it, and the web player has no "AI Brief" tab.

**Fix.**
- Add `forAi: textOf(files, "for_ai.md")` to `sal.js:parse`'s output.
- Add an "AI Brief" tab (or a section under the Report tab) that renders `for_ai.md` as preformatted
  text (it's markdown — a tiny markdown-to-HTML renderer, or just `<pre>` with basic styling).
- Add a "Copy AI brief" button in the export menu so a user can paste it into their AI tool along
  with the `.sal`.

---

### C13. No session compare/diff view in the web player (P1)
**Gap.** See B11 — the web player is the natural home for side-by-side `.sal` comparison, but there's
no second drop target or diff rendering today.

**Fix.** (Implemented as part of B11's web portion.) When a session is open, show a second drop
target ("Drop another .sal to compare"). On drop, parse the second session and render a two-column
diff view using `qalens-core`'s `QaLensDiff` logic (ported to JS, or computed in
`sal_report.js --compare` and rendered). Deep-link: `?compare=a.sal&b=b.sal`.

---

### C14. `sal_report.js` ignores `analysis.json` for the CI exit code (P1)
**Bug.** `web/tools/sal_report.js:115`:
```js
process.exit(failed.length || errors.length ? 1 : 0);
```
It recomputes failures from raw tracks but ignores `analysis.json.anomalies` (which the device
precomputed). If the device flagged an anomaly the web heuristic misses (e.g. a memory spike from
B8, a jank burst from B2), the CI gate passes when it shouldn't. The JSON output includes
`deviceAnalysis` verbatim (`:83`) but the exit code doesn't use it.

**Fix.**
- Define `const deviceFailures = (s.analysis?.anomalies || []).filter(a =>
  ["failed_request","error_burst","crash","anr","assertion_failed","memory_spike"].includes(a.kind));`
- Exit 1 if `failed.length || errors.length || deviceFailures.length`.
- In the markdown output, add an "On-device anomalies" section listing the device-flagged anomalies
  with their `tMs` (so CI consumers see *why* it failed even if the web heuristic missed it).

---

### C15. `sal.js` doesn't validate `formatVersion` (P2)
**Gap.** `parse()` (`web/sal.js:99-131`) reads `manifest.json` with no version check. A future
`formatVersion: 2` (gzip JSON tracks, WebP frames per the backlog) would be parsed as v1 and silently
fail or produce garbage.

**Fix.** In `parse`, read `manifest.formatVersion` (default 1 if absent). If it's > 1, throw a clear
error: `"Unsupported .sal formatVersion <X>. This QaLens web player supports v1. Update the player
or convert the file."` Also log the version to the console on load so mismatches are diagnosable.

---

### C16. Filmstrip click precision (P2)
**Minor.** `web/app.js:1125` seeks to `Number(img.dataset.ts)` — the frame's timestamp. With ~2fps
frames, that can be up to 500ms before the visual the user clicked. Not a crash, but a precision
mismatch with the sub-second timestamps advertised elsewhere.

**Fix.** Low priority. Optionally, on filmstrip click, seek to `frame.ts + (half the inter-frame
interval)` so the clicked frame stays visible until the next one, rather than jumping to its start.
Or leave as-is (seeking to the frame's exact ts is arguably correct).

---

## Sequencing

A pragmatic order, respecting dependencies:

1. **A1 + A2** (debounce + thread confinement) — unblocks everything else; without these, adding
   more observation sources (B2/B5/B7/B8) worsens the recompute storm.
2. **A4** (error surface) — small, and every later feature benefits from surfacing failures.
3. **C1–C4** (web P0 bug fixes) — independent of the Android work, quick wins.
4. **B1+B13** (crash capture + bridges) — the single highest-impact feature; needs A4.
5. **B2** (frame metrics) — needs A1's debounced recompute for the jank penalty.
6. **B5** (connectivity + Chucker) — independent; the classifier change is pure core.
7. **A3** (decompose) — mechanical, do once the new services' shapes are clear from B1/B2/B5.
8. **B3** (pluggable tabs) — independent; unlocks app-specific extensions.
9. **B4** (report exports) — independent, pure core + small UI.
10. **B7 + B8** (coroutine + memory) — both feed the timeline + `.sal`; do together.
11. **B11 + C13** (diff/compare) — web + core together.
12. **B14** (global search) — UI-only.
13. **B15** (macro assertions) — touches macros + `.sal` + web.
14. **B16** (per-source redaction) — small, pure core + tiny UI.
15. **A5 + A6 + A7** (recorder watchdog, Room interface, parity test) — hardening, do last.
16. **C5–C16** (remaining web fixes/features) — fill-in alongside the above.
