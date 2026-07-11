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
- **[TEST]** = pure logic goes in `qalens-core` → add a unit test alongside the existing ones.
- **[UI]** = touches Compose panel UI (`qalens-compose/.../QaLensInspectorPanel.kt` or
  `QaLensMinimalPanel.kt`).

Status legend: ✅ **DONE** · 🚧 **IN PROGRESS** · ⏳ **NEXT** · ⬜ **PLANNED**

Priority legend: **P0** (correctness/reliability) · **P1** (high-value) · **P2** (polish).

> **Build status (verified after phase 1):** `./gradlew :qalens-core:test` = 53 tests pass (was
> 49), `:qalens-compose:compileDebugKotlin` + `:sample-app:compileReleaseKotlin` (parity) both
> green, `node web/test/read.test.js` passes. JDK 25 build blocker fixed via
> `kotlin.daemon.jvmargs` in `gradle.properties`; build with
> `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew …`.

---

## Phase 1 — ✅ SHIPPED

### A1 + A2. Debounce `recomputeAnalysis()` + confine state mutation to main ✅

**What shipped:**
- New `markAnalysisDirty()` (`QaLens.kt`) replaces 7 direct `recomputeAnalysis()` call sites
  (`configure`, `setFeatureFlagProvider`, `contract`, `registerDataSource`, `setScreen`,
  `logNetwork`, `refreshInspection`). Bursts coalesce into one recompute `ANALYSIS_DEBOUNCE_MS`
  (250ms) later.
- `flushAnalysis()` runs any pending recompute synchronously before `evidenceBundle()` so reports
  never read stale state — hops to main + awaits (bounded) if called from a background thread.
- `recomputeAnalysis()` → renamed `runAnalysis()`; **always runs on the main `Handler`** (simpler
  than `Dispatchers.Default` — eliminates the read-compute-write race entirely since all state
  reads/writes are confined to main).
- `logNetwork()` and `pushEvent()` now post their `uiStateMutable.update{}` to `mainHandler`,
  fixing the OkHttp-IO-thread race (the main offender). `appendError`/`appendCrash`/
  `appendFrameMetrics` are likewise main-confined.
- Fields: `mainHandler`, `analysisDirty: AtomicBoolean`, `analysisPending: Runnable?`.
- Also fixed a pre-existing **JDK 25 build blocker**: Kotlin 2.0.21 can't parse JDK 25
  (`IllegalArgumentException: 25.0.2`). Added `kotlin.daemon.jvmargs` to `gradle.properties`.

**Deferred (non-blocking):**
- ⬜ Robolectric test firing `logNetwork` from a background thread asserting no race (A2's plan).
  The confinement makes a race structurally impossible; the test would be belt-and-suspenders.

---

### A4. Surface errors instead of swallowing them ✅

**What shipped:**
- `data class QaLensError(id, kind, message, timestampMillis, retry: (() -> Unit)?)` +
  `enum class ErrorKind { RECORDING, SCREENSHOT, EXPORT, WEBHOOK, NAVIGATION, DATA_SOURCE, OTHER }`
  in `qalens-core/.../QaLensModels.kt`.
- `val errors: List<QaLensError>` on `QaLensUiState` (default `emptyList()`, capped at 20).
- `QaLens.pushError(kind, message, retry?)` / `dismissError(id)` / `clearErrors()` on the facade
  **[API]** + no-op twins in `NoopQaLens.kt`. `pushError` also logs (so the Logs tab still has it).
- **6 swallowed-catch sites replaced** with `pushError`: `observeRoom`, `navigate`,
  `resolveDataSources`, `resolveFeatureFlags`, `takeScreenshot` (no-activity), `startRecording`
  (no-activity), `finalizeAndShare` (.sal write failure), `shareRecording` (file-gone, with retry).
- **[UI]** Dismissible error banner in the inspector panel header (`QaLensInspectorPanel.kt`):
  red `⚠ N issues` row, expandable list, `↻ Retry` button (when `retry != null`), `✕` dismiss,
  `Clear` all. Plus a `⚠ N issues →` chip in the minimal panel's status row.

**Deferred (non-blocking):**
- ⬜ Auto-expire errors after 60s (the banner currently persists until dismissed). Low priority —
  the cap at 20 prevents unbounded growth; QA dismissing is the expected flow.

---

### B1 + B13. Crash & ANR capture + crash-reporter bridges ✅

**What shipped:**
- `QaLensCrashHandler` (`qalens-compose/.../QaLensCrashHandler.kt`):
  - Chains `Thread.setDefaultUncaughtExceptionHandler`; on crash, records the crash with screen/
    route/last-network context, emits a timeline event, then delegates to the previous handler
    (the app still crashes normally — QaLens doesn't swallow it).
  - ANR watchdog: a `Handler` tick on the main `Looper` every 1s; a daemon background thread
    checks if it ran; if the main thread is blocked > `ANR_THRESHOLD_MS` (5s), an ANR event is
    recorded (throttled to one per 5s so a long freeze doesn't spam). Installed in `QaLens.install()`.
- `data class QaLensCrash(timestampMillis, type, thread, throwable, stackTrace, screen, route,
  lastNetworkSummary)` + `enum class CrashType { CRASH, ANR, COROUTINE_EXCEPTION }` + `display`
  extension in `qalens-core`. `val crashes: List<QaLensCrash>` on `QaLensUiState` (capped at 10).
- `interface QaLensCrashBridge` **in `qalens-core`** (moved from compose so the no-op can
  reference it without a cross-module dep): `enrich(crash, evidence)` + `onCrash(callback)`.
  `object QaLensNoopCrashBridge` provided. Apps implement it using their vendor's hooks
  (Sentry `beforeSend`, Bugsnag `addOnError`, Crashlytics `setCustomKeys`).
- `QaLens.bridgeCrashes(bridge)` + `QaLens.lastCrash()` **[API]** + no-op twins.
  `appendCrash(crash)` is `internal`; it appends to state + invokes `bridge.enrich()` with a
  pre-built evidence string (likely owner, score, screen, last network).
- **[SAL]** `crashes.json` track (`SalTracks.crashes`) + `analysis.json.coverage.crashes` +
  `analysis.json.stats.crashes` + crash/ANR anomalies (kind `crash`/`anr`/`coroutine_exception`).
  Wired into `QaLensSessionRecorder.finalizeAndShare`.

**Deferred (tracked below):**
- ⬜ **[UI]** "Crashes" row in the Overview tab showing `lastCrash` + "Copy with evidence" button.
- ⬜ Sample app "Crash" / "ANR" demo buttons in the Tools/Debug screen.
- ⬜ Concrete vendor bridge implementations (Sentry/Bugsnag/Crashlytics) — the interface +
  `QaLensNoopCrashBridge` are in place; apps implement their own. A reference Sentry bridge is a
  good follow-up once the sample app has the crash button.

---

### B2. Frame-timing / jank track ✅

**What shipped:**
- `QaLensFrameMetrics` (`qalens-compose/.../QaLensFrameMetrics.kt`): attaches
  `Window.OnFrameMetricsAvailableListener` (API 24+; no-op below) in `attachOverlay`, detaches in
  `onActivityPaused`. Parses `FrameMetrics` into `FrameMetricsSample`s. Sampled (jank + frozen +
  1-in-10 normal) to bound volume.
- `data class FrameMetricsSample(timestampMillis, totalMs, layoutMs, drawMs, gpuMs, jank, frozen)`
  in `qalens-core` with `JANK_THRESHOLD_MS = 16` + `FROZEN_THRESHOLD_MS = 700`. `val frameMetrics`
  on `QaLensUiState` (capped at 1000).
- `JankAnalyzer` pure engine (`qalens-core/.../JankAnalyzer.kt`): `analyze(samples) → JankDigest
  { sampleCount, jankCount, frozenCount, p95TotalMs, p99TotalMs, worstFrameMs, jankRate }`.
- Score penalties in `ReleaseReadinessEngine.score` **[TEST]**: `P_FROZEN_FRAME = 5` each (capped
  at 20), `P_HIGH_JANK_RATE = 10` when jankRate > 30%. New `frameMetrics` parameter (default
  empty — release-safe, no parity break).
- **[SAL]** `performance.json` track (`SalTracks.performance`: samples + digest) +
  `analysis.json.coverage.performance` + `analysis.json.stats.jank` (jank digest). Wired into the
  recorder.
- **[UI]** "Frame Timing / Jank" card in the Device tab: samples, p95/p99, jank count + rate
  (color-coded), frozen count, worst frame.
- **[TEST]** 4 new unit tests in `QaLensEnginesTest.kt` (empty list, counts, high-jank penalty,
  frozen-frame penalty). **Test count: 49 → 53.**

**Deferred (tracked below):**
- ⬜ Sparkline of `totalMs` over the session in the jank card (currently text stats only).
- ⬜ "Jank" pill in the Overview stat row.

---

### C1–C7. Web app P0/P1 bug fixes ✅

All applied in `web/app.js` (+ `web/sal.js` unchanged). `node web/test/read.test.js` still passes.

- **C1 ✅** `?t=` deep-link seek deferred to `loadedmetadata` for video mode; `setupMedia`
  attaches a one-shot `onloadedmetadata` that applies the pending seek. Frame mode seeks
  immediately (no async metadata).
- **C2 ✅** `renderTrack` signature check (`lastRenderSig`): when the track + filter + logLevel +
  count are unchanged, skip the `innerHTML` rebuild and call `updateRowHighlighting()` (toggles
  `row-cur`/`row-future` classes on existing nodes only). `setTrack` resets the signature. Kills
  the 600-row × 5/s DOM rebuild during playback.
- **C3 ✅** Keyboard guard now includes `TEXTAREA` + `isContentEditable` (was `INPUT`/`SELECT`
  only — broke the `.appsal` editor's SQL/macro textareas).
- **C4 ✅** `revokeObjectUrls()` helper; called in both `closeSession()` and `onLoaded()`. No more
  64+ leaked blob URLs when a session is closed without opening another.
- **C5 ✅** `screenVisits()`: dropped the second loop that overwrote every visit's `end` with the
  next *visit's* start (inflated merged-visit durations). Now only caps the last visit's `end` at
  `S.end`.
- **C6 ✅** `syncMedia`: `video.currentTime` set guarded with `readyState >= 1` +
  `Number.isFinite(duration)` (was dividing by NaN/seeking to 0 during early load).
- **C7 ✅** `stepEvent`: binary search (forward + backward) replacing the O(n) linear scan;
  backward step skips past clustered equal timestamps so it always visibly moves.

---

## Phase 2 — 🚧 NEXT

### B5. Network connectivity & type track + Chucker coexistence ⏳ P0

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
     `response.code`, timing, and `response.headers` (for sizes). No `peekBody`, no `source()`.
   - QaLens must **not** mutate the request or response (it doesn't). The interceptor returns
     `chain.proceed(request)` untouched.
8. Add a `compileOnly` Chucker dep to `qalens-compose` and a `QaLensChuckerBridge` that, if Chucker
   is on the classpath, surfaces a "Open Chucker" button in the Network tab (launches Chucker's
   activity via reflection so the dep stays optional). If Chucker is absent, the button is hidden.
9. Sample app: add both interceptors to the sample's OkHttp client to prove coexistence.
10. Document in `integration.md` + `docs/ONBOARDING.md` that QaLens and Chucker are complementary
    (Chucker = full body inspection on-device; QaLens = redacted evidence + `.sal` + AI digest).

**Verification.** **[SAL]** + **[API]** (the `NetworkEvent.connectivity` field is additive on a
core model — release-safe). Unit-test the classifier change. Manual: run the sample with both
interceptors, toggle airplane mode, verify the timeline shows the transition and a failed request is
classified as `Configuration/Environment`.

---

## Phase 3 — PLANNED (medium priority)

### A3. Decompose the `QaLens` God Object ⬜ P1

**Problem.** `QaLens.kt` is one `object` doing 8 jobs (config/state, screen tracking, event
ingestion, panel/inspect/tag/watch mode, recording orchestration, evidence/reports, analysis
recompute, activity lifecycle) with many mutable fields. It's also why the orchestrator is
untestable — all dependencies (`QaLensActivityInstaller`, `QaLensSessionRecorder`,
`QaLensScreenCapture`, `QaLensPrefs`, `QaLensNotification`, `QaLensCrashHandler`,
`QaLensFrameMetrics`) are hardcoded references.

**Plan.** Keep `object QaLens` as the **public facade** (unchanged signatures); extract `internal`
services that it delegates to. Phased so no single PR is enormous:
1. `internal class AnalysisEngine` — owns `runAnalysis`, feature flags, data sources, contracts,
   screen quality. Takes `QaLensConfig` + observations, returns derived `QaLensUiState` slices.
   **[TEST]** This becomes unit-testable without Android.
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
`internal interface QaLensBackend`. The facade keeps its public members.

**Verification.** No public API change. After extraction, `./gradlew :qalens-core:test` still
passes and `:sample-app:compileReleaseKotlin` still proves parity.

**Note.** Do this after B5 — the new services' shapes are clearer once all the observation
sources (crashes, frame metrics, connectivity) are in place.

---

### B3. Pluggable panel tabs ⬜ P0

**What.** An extension point so apps can register custom tabs in the QaLens inspector panel — e.g. a
"My Feature Flags" dashboard, a "Backend Health" tab, or a "Cache Inspector".

**Why.** Every app has domain-specific things QA needs to see. Currently the only way to surface app
data is `registerDataSource` (a flat key-value map, `QaLens.kt:141`). A composable tab API lets apps
build rich, interactive surfaces without forking QaLens.

**Plan.**
1. Define `interface QaLensTabProvider { val title: String; @Composable fun Content(state:
   QaLensUiState, config: QaLensConfig) }` in `qalens-core` (so the no-op can reference it).
2. **[API]** `QaLens.registerTab(provider: QaLensTabProvider)` (+ no-op twin). Also
   `QaLens.unregisterTab(title: String)`.
3. **[UI]** In `QaLensInspectorPanel.kt`'s tab row, append registered tabs after the built-ins. The
   `when (tab)` dispatch renders `provider.Content(currentState, currentConfig)` for the selected
   custom tab.
4. Provide a `QaLensTabProvider` that renders a `registerDataSource` map as a simple table (so the
   existing flat-data path still works, but apps can upgrade to a custom tab).
5. Sample app: register a "Sample Backend" tab showing a live hit/miss counter from the sample's
   repository, to demonstrate the API.
6. **[API]** No-op twin: `QaLens.registerTab` is a no-op; `QaLensTabProvider` is still constructible
   in release (so app code compiles) but its `Content` is never invoked.

**Verification.** **[API].** Manual: the sample's custom tab renders and updates as state flows.

---

### B4. GitHub Issue / Linear / Markdown report exports ⬜ P0

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
   `QaLens.buildMarkdownReport()` on the facade + no-op twins (return `""`).
3. **[UI]** Add buttons to the Bug Bundle tab and a "Copy as…" dropdown in the minimal panel. Each
   copies to clipboard with a toast.
4. Unit-test each builder against a fixture `EvidenceBundle` in `qalens-core`.

**Verification.** **[API]** + **[TEST].** `compileReleaseKotlin` + the new unit tests.

---

### B7 + B8. Coroutine exception bridge + memory pressure events ⬜ P1

**B7 — Coroutine exception bridge.** A `CoroutineExceptionHandler` implementation
(`qalens-compose/.../QaLensCoroutineExceptionHandler.kt`) that forwards uncaught coroutine
exceptions into the QaLens timeline (as `COROUTINE_EXCEPTION` crash-type events via
`QaLensCrashHandler.recordCoroutineException`, which already exists from B1) and rethrows to the
default handler unless the app opts out. **[API]**
`QaLens.coroutineExceptionHandler(): CoroutineExceptionHandler` + no-op twin. **[UI]** a
`COROUTINE_EXCEPTION` chip in the Logs tab. Sample app: a button that launches a throwing coroutine.

**B8 — Memory pressure events.** Register a `ComponentCallbacks2` in `QaLens.install()`:
`onTrimMemory(level)` → `QaLens.event("memory_trim", …)` with human labels;
`onLowMemory()` → `QaLens.event("low_memory", …)`. During recordings, sample
`Runtime.totalMemory()`/`freeMemory()`/`Debug.getNativeHeapAllocatedSize()` every 2s into a
`MemorySample`. Add `MemorySample` to `qalens-core`, `memory.json` `.sal` track +
`analysis.json.coverage.memory` + `memory_spike` anomalies (delta > 50MB). **[UI]** "Memory" card
in the Device tab. **[TEST]** a `qalens-core` analyzer that detects memory spikes.

**Why.** Most modern apps use coroutines (B7); OOMs/memory pressure are common hard-to-reproduce
bugs with zero visibility today (B8). Embrace tracks both.

**Verification.** **[API]** (B7) + **[SAL]** (B8). Manual: tap the sample's coroutine-crash button;
allocate a large array and watch the trim event + heap growth.

---

### B11 + C13. Session diff / compare ⬜ P1

**What.** Open two `.sal` recordings and see what changed: screens visited, network calls, errors,
score, anomalies. Especially useful for "it worked yesterday, broken today".

**Plan.**
1. **[TEST]** New `qalens-core/.../QaLensDiff.kt` with `fun diff(a: SalSessionSummary, b:
   SalSessionSummary): SalDiff` where `SalSessionSummary` is a lightweight projection (manifest +
   counts + failed requests + anomalies + screen visits + score + likelyOwner). `SalDiff` returns:
   `addedScreens`, `removedScreens`, `addedFailedRequests`, `resolvedFailedRequests`,
   `scoreDelta`, `newAnomalies`, `resolvedAnomalies`, `likelyOwnerChanged`. Unit-test against two
   fixture sessions.
2. **Web (C13):** Add a "Compare" mode — a second drop target when a session is open; renders a
   two-column diff view (left = baseline, right = current, added/removed highlighted, score delta,
   "what changed" card). Deep-link: `?compare=a.sal&b=b.sal`.
3. **Android player:** `QaLensPlayerActivity` accepts a second `.sal` via a second intent extra;
   if present, shows a diff summary screen.
4. **CLI:** `sal_report.js --compare baseline.sal` prints a markdown diff and exits non-zero if the
   score dropped or new failures appeared.
5. **[SAL]** No format change — diff is computed from existing tracks.

**Verification.** **[TEST]** for the core diff. Manual: record the sample's "happy path", then the
"failure path", drop both into the web compare view, verify the failed request + score delta appear.

---

### B14. Global search across all tracks ⬜ P1

**What.** A search bar in the panel that searches logs, network events, timeline, and test tags
simultaneously, showing a unified results list. Currently each tab has its own filter.

**Plan.**
1. **[UI]** Add a search field to the panel header that, when focused and non-empty, switches the
   body to a unified results list: searches `events.title/detail`,
   `networkEvents.url/method/error`, `logs.message/tag`, and `nodes.qaTag/qaName/text`. Results
   grouped by track, each row tappable.
2. Debounce the search input by 150ms; reuse A1's main-thread confinement.
3. Clear-on-tab-switch; `Esc` exits unified search and restores the active tab.
4. **[API]** No public change.

**Verification.** Manual: type a URL fragment, verify it matches network + logs + any node tagged
with it.

---

### B15. Macro assertions & conditional steps ⬜ P1

**What.** Extend the macro DSL with `assert <tag> exists`, `assert <tag> has-label <text>`,
`assert network no-errors`, `assert route = <route>`, and `if <condition> … else …`.

**Plan.**
1. New verbs in `QaLensMacros.kt`: `assert exists <tag|text>`, `assert label <tag> <text>`,
   `assert route <route>`, `assert network no-errors`, `assert network no-slow`,
   `if <condition> … else …`.
2. A macro run produces a `MacroResult { pass: Boolean, failures: List<AssertionFailure> }`.
3. **[API]** `QaLens.runMacro(name): MacroResult` (+ no-op twin returning a passing result).
4. **[UI]** Surface pass/fail in the minimal panel's macro list + the timeline. Failed assertions
   get a red row.
5. **[SAL]** Assertion results fold into `timeline.json` (kind `ASSERTION`) +
   `analysis.json.anomalies` (kind `assertion_failed`).
6. `sal_report.js` / the web player render assertion results with pass/fail icons.
7. **[TEST]** Unit-test the assertion evaluator against fixture `QaLensUiState`s.

**Verification.** **[API]** + **[SAL].** Manual: write a macro with a failing assertion, run it,
verify the failure appears in the timeline and the run is marked failed.

---

### B16. Per-data-source redaction ⬜ P1

**What.** Let `registerDataSource` accept a redaction policy: per-key redaction rules on top of the
global defaults.

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
2. In `resolveDataSources`: for each source, apply `redactKeys` → `redactPatterns` → `redactAll` →
   global `config.redact` (unchanged backstop).
3. **[UI]** Show a 🔒 badge next to keys redacted by a per-source rule.
4. **[TEST]** Extend `QaLensRedactorTest` with per-key, per-pattern, and `redactAll` cases.
5. Update `integration.md` + `docs/ONBOARDING.md` with the new signature + examples.

**Verification.** **[API]** + **[TEST].** `compileReleaseKotlin` + the redactor tests.

---

## Phase 4 — PLANNED (hardening + polish)

### A5. Recorder watchdog + service-lifecycle binding ⬜ P1

`QaLensSessionRecorder` manages `recording: Boolean` set from 6 places; if the projection service is
killed by the OS without `onVideoComplete`, `isRecording` sticks `true`. Add a watchdog (no frame
for >10s → auto-cancel), bind to `QaLensProjectionService` lifecycle (`onTaskRemoved`/`onDestroy` →
`onVideoComplete(ok=false)`), and extend `panicRestore()` to force-clear `isRecording` if no frames
in 15s. Surface the watchdog cancellation as an error (A4) with a retry. Manual: start HD recording,
`adb shell am stopservice`, verify `isRecording` flips within ~10s + error banner.

### A6. Move Room/DataStore observation behind an interface ⬜ P1

`QaLens.kt` imports `androidx.room.RoomDatabase`/`InvalidationTracker` — the observation layer can't
move to core or be tested. Define `interface DataSourceObserver` in `qalens-core`; add
`QaLens.observeDataSource(name, observer)` **[API]** + no-op twin. Keep `observeRoom`/
`observeDataStore` as `internal` thin wrappers (public signatures unchanged). **[TEST]** a fake
`DataSourceObserver` test in core.

### A7. Enforce no-op parity with a test ⬜ P2

Add `qalens-compose/src/test/.../ParityTest.kt` (JVM, no Android). Reflect on both `QaLens` objects
(`qalens-compose` + `qalens-noop` on the test classpath) and assert identical public member names +
parameter counts. Also reflect on `Modifier.qa*`, `QaLensRoot`, `QaLensOkHttpInterceptor`,
`QaLensTimberTree`, `QaLensNavHost`, `QaLensNavigator`. Fail with a diff. The test itself is the
verification — run with `./gradlew :qalens-compose:test`.

---

## Phase 5 — PLANNED (web polish)

### C8. Keyboard help overlay ⬜ P1
Add a `?` key handler that toggles a modal listing all shortcuts (currently only in the settings
drawer). "⌨ Shortcuts" hint near the transport. `Esc`/`?` closes.

### C9. Annotations / bookmarks in the timeline ⬜ P1
Parse a `marks.json` track into `S.marks`; render marks as star icons on the scrubber + Timeline
rows. Add a "⭐ Mark moment" transport button (local marks, exportable to the markdown summary).
Surface in `exportSummary`.

### C10. Mobile / touch layout ⬜ P1
Add `@media (max-width: 1000px)` + `(max-width: 640px)` breakpoints to `web/styles.css`: single
column under 1000px; stacked transport + hidden-behind-toggle filmstrip + full-width scrubber +
scrollable tabs under 640px. `touch-action: manipulation` on the player.

### C11. Surface `analysis.json.stats` + `.endpoints` ⬜ P1
`renderStatbar`/`networkSubstats` prefer `S.analysis.stats` (p95, totals) over recomputed values.
Add an "Endpoints" sub-view to the Network tab (`analysis.endpoints`: host/path, count, failed,
avg/p95 latency — tappable to filter). Show `analysis.likelyOwner` in the Summary card alongside
`summary.json`'s.

### C12. Surface `for_ai.md` ⬜ P1
Add `forAi: textOf(files, "for_ai.md")` to `sal.js:parse`. Add an "AI Brief" tab rendering it as
`<pre>`. "Copy AI brief" button in the export menu.

### C13. Session compare/diff view (web) ⬜ P1
See B11 — implemented as B11's web portion.

### C14. `sal_report.js` exit code uses `analysis.json` ⬜ P1
Exit 1 if `failed.length || errors.length || deviceFailures.length` where
`deviceFailures = analysis.anomalies` filtered to failure kinds. Add an "On-device anomalies"
section to the markdown output.

### C15. `sal.js` validates `formatVersion` ⬜ P2
In `parse`, read `manifest.formatVersion` (default 1). If > 1, throw a clear error. Log the version
on load.

### C16. Filmstrip click precision ⬜ P2
Optionally seek to `frame.ts + half inter-frame interval` so the clicked frame stays visible until
the next. Low priority — seeking to the frame's exact ts is arguably correct.

---

## Deferred follow-ups (from Phase 1)

These are small polish items carved out of completed features — not blocking, but worth tracking:

- ⬜ **[UI]** Overview tab "Crashes" row showing `lastCrash` + "Copy with evidence" (from B1).
- ⬜ **[UI]** "Jank" pill in the Overview stat row (from B2).
- ⬜ **[UI]** Sparkline of `totalMs` in the jank card (from B2 — currently text stats only).
- ⬜ **[TEST]** Error-buffer cap + eviction unit test (from A4).
- ⬜ **[TEST]** Robolectric `logNetwork`-from-background-thread race test (from A2).
- ⬜ Sample app "Crash" / "ANR" / "Coroutine crash" demo buttons (from B1/B7).
- ⬜ Reference vendor bridge implementation (Sentry `beforeSend`) to document the pattern (from B13).

---

## Sequencing (updated)

**Phase 1 — ✅ SHIPPED:** A1+A2, A4, B1+B13, B2, C1–C7. Build green, 53 tests, parity holds.

**Phase 2 — 🚧 NEXT:** B5 (connectivity + Chucker). Independent; the classifier change is pure core.

**Phase 3 — PLANNED (medium):**
1. A3 (decompose) — do after B5 so all observation sources are in place and the service shapes are
   clear.
2. B3 (pluggable tabs) — independent; unlocks app-specific extensions.
3. B4 (report exports) — independent, pure core + small UI.
4. B7 + B8 (coroutine + memory) — both feed the timeline + `.sal`; do together.
5. B11 + C13 (diff/compare) — web + core together.
6. B14 (global search) — UI-only.
7. B15 (macro assertions) — touches macros + `.sal` + web.
8. B16 (per-source redaction) — small, pure core + tiny UI.

**Phase 4 — PLANNED (hardening):** A5 (recorder watchdog), A6 (Room interface), A7 (parity test).

**Phase 5 — PLANNED (web polish):** C8–C16 (keyboard help, annotations, mobile, analysis.stats,
for_ai.md, compare view, sal_report exit code, formatVersion, filmstrip precision).

**Deferred follow-ups:** the small polish items from Phase 1 listed above — fold in opportunistically
when touching the relevant area.
