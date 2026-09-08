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

> **Build status (verified 2026-09-08, full matrix):** JAVA_HOME=temurin-17 + Gradle 9.1 ->
> **BUILD SUCCESSFUL (163 tasks)**: `:qalens-core:test` = **122 tests, 0 failures**, `:qalens-noop:`
> `testDebugUnitTest` = **1 executed parity test** (A7 now RUNS, not only compiles), `:qalens-compose:`
> `compileDebugKotlin`, `:qalens-replay:compileDebugKotlin`, `:sample-app:compileReleaseKotlin` all
> green. `node web/test/read.test.js` = **ALL PASS (32 assertions**, incl. v2 gzip/CRC32 coverage);
> `python3 backend/tests/test_backend.py` = **15/15 OK** (incl. the chunked/resumable flow);
> `sal_report.js` markdown has On-device anomalies + Bookmarks, `--json` is pure JSON. JDK 25 blocker
> fixed via `kotlin.daemon.jvmargs` in `gradle.properties`. The whole thing demos with `./demo.sh`
> (tour: `DEMO.md`, showcase page: `web/landing.html`).

---

## P2 — Percentile correctness ✅ (2026-09-08)

Network health, the exported analysis digest, and jank p95/p99 now share a pure core
nearest-rank implementation (`ceil(percent * count / 100)`, converted to a zero-based index).
Seven regression tests cover exact/fractional ranks, empty/singleton/repeated samples,
invalid percentages, and all three consumers. Existing sample inclusion rules are preserved;
there is no public API or archive schema change. A3 full decomposition remains next.


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
- ✅ Sample app QaLens Demos section in Settings: failing transfer, mark moment, BUG bookmark, 10s recording, business event, coroutine crash, ANR, and real crash buttons (from B1/B7).
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
  frozen-frame penalty). **Test count at the time: 49 → 53** (the suite has since grown — 69 today).

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

## Phase 2 — ✅ SHIPPED

### B5. Network connectivity & type track + Chucker coexistence ✅ P0

**What shipped (was ⏳ P0 NEXT):**
- `QaLensConnectivity` (`qalens-compose/.../QaLensConnectivity.kt`): registers a
  `ConnectivityManager.NetworkCallback`, emits timeline events on type transitions, exposes
  `current(): ConnectivitySnapshot` (type / strengthBars / hasVpn / isMetered). Started in
  `QaLens.install()`. Signal "bars" are a 0–4 bandwidth heuristic (API 29+), not real
  `SignalStrength` — noted in `docs/CODE_REVIEW.md`.
- `ConnectivitySnapshot` on `QaLensUiState` + `NetworkEvent.connectivity` stamping in
  `QaLensOkHttpInterceptor`; `NetworkEvent.failedDueToConnectivity` derived property.
- **[TEST]** `BugClassifier.classify` reclassifies a failed request whose snapshot was
  `OFFLINE` as `CONFIGURATION_ENVIRONMENT` before the generic transport rule (B5's core change).
- **[SAL]** `connectivity.json` track (`SalTracks.connectivity`) +
  `analysis.json.coverage.connectivity` + `connectivity_failure` anomalies.
- **Chucker coexistence**: `QaLensChuckerBridge` (reflection-based `isAvailable`/`launch`, so the
  dep stays optional) + the documented interceptor order (Chucker first for raw bodies, QaLens second
  for metadata-only) in its KDoc and `integration.md`.

---

## Phase 3 — SHIPPED (A3 partial, B14 still open)

### A3. Decompose the `QaLens` God Object 🚧 (partial)

**Shipped:** `internal class AnalysisEngine` (`qalens-compose/.../AnalysisEngine.kt`) — owns
`analyze(state, config, previousScreenQuality)` and derives score/classification/build-safety/
feature-flags/screen-quality/contract/data-sources from injected providers, with **no Android type**
so it's unit-testable in isolation. `QaLens.runAnalysis()` delegates to `analysisEngine.analyze`
(`QaLens.kt`).

**Remaining (open):** the other services the plan called out — `ObservationCollector`,
`RecordingController`, `PanelStateController`, `EvidenceService`, `ActivityBridge` — are still
inline in the facade. See "Open backlog" below.

---

### B3. Pluggable panel tabs ✅ P0

**What shipped:** `interface QaLensTabProvider { val title; @Composable fun Content(state, config) }`
(`qalens-compose/.../QaLensTabProvider.kt`), `QaLens.registerTab/unregisterTab` **[API]** +
no-op twins, tabs appended after the built-ins in `QaLensInspectorPanel` via
`registeredTabs()` (`QaLens.kt`).

---

### B4. GitHub Issue / Linear / Markdown report exports ✅ P0

**What shipped:** `QaLensReports.githubIssue` (YAML frontmatter labels), `.linearIssue`
(priority/confidence), and `.markdown` (generic) in `qalens-core/.../QaLensReports.kt`; exposed as
`QaLens.buildGitHubIssue()/buildLinearIssue()/buildMarkdownReport()` **[API]** + no-op twins
(return `""`/DISABLED), wired to the Bug Bundle / export UI.

---

### B7 + B8. Coroutine exception bridge + memory pressure events ✅ P1

**B7:** `QaLensCoroutineExceptionHandler` (`qalens-compose/.../QaLensCoroutineExceptionHandler.kt`)
forwards uncaught coroutine exceptions into `QaLensCrashHandler.recordCoroutineException` (a
`COROUTINE_EXCEPTION` crash event) and doesn't rethrow by default; `QaLens.coroutineExceptionHandler()`
**[API]** + no-op twin.

**B8:** `QaLensMemoryMonitor` (`qalens-compose/.../QaLensMemoryMonitor.kt`) registers a
`ComponentCallbacks2` in `QaLens.install()` (`onTrimMemory`/ `onLowMemory` → timeline events)
and samples `Runtime` + `Debug` heap stats into `MemorySample`s (capped at 200);
`memory.json` track (`SalTracks.memory`) + `analysis.json.coverage.memory` + `memory_spike`
anomalies.

---

### B11 + C13. Session diff / compare ✅ P1

**What shipped:** `QaLensDiff` (`qalens-core/.../QaLensDiff.kt`) — pure `diff(baseline, current)
→ SalDiff` with added/removed screens & failures, score/crash/error deltas, new/resolved anomalies,
`isRegression`, and `diffMarkdown`. `web/tools/sal_report.js --compare baseline.sal` prints the
markdown diff and exits 1 on regression; the web player's compare mode + `?compare=` deep link are
wired in `web/app.js`.

---

### B15. Macro assertions & conditional steps ✅ P1

**What shipped:** `MacroEngine` (`qalens-core/.../QaLensMacros.kt`) with `AssertExists`,
`AssertRoute`, `AssertNetwork`, `If`/`else` + `MacroCondition`s (`Exists`/`RouteEquals`/
`NetworkOk`/`NoErrors`/`ScoreAbove`) → `MacroResult`; `QaLens.runMacro(steps)` **[API]** +
no-op twin.

**Gap (tracked):** the on-device **string-DSL** runner (`qalens-compose/.../QaLensMacros.kt`,
the `AppSalMacro` executor) still only parses `deeplink/wait/tap/type/record/stop/screenshot/mark`
— `assert`/`if` lines hit the `unknown step` branch. See "Open backlog" (finish B15 wiring).

---

### B16. Per-data-source redaction ✅ P1

**What shipped:** `QaLens.registerDataSource(name, redactKeys, redactPatterns, redactAll, provider)`
**[API]** (+ no-op twin); `AnalysisEngine.resolveDataSources` applies per-key → per-pattern →
`redactAll` → global `config.redact` as backstop (`AnalysisEngine.kt`). `DataSourceEntry`
carries the per-source policy.

---

## Phase 4 — SHIPPED (A5 partial)

### A5. Recorder watchdog + service-lifecycle binding 🚧 (partial)

**Shipped:** the frame-stall watchdog — `WATCHDOG_TIMEOUT_MS = 10_000L` in
`QaLensSessionRecorder.kt` auto-stops a recording with no frame for 10s and surfaces it via
`pushError`; `onVideoComplete` does the "stop came from outside QaLens" bookkeeping so
`isRecording` can't stick true; the projection service's `onDestroy` calls `stopRecording()`
(`QaLensProjectionService.kt`).

**Remaining (open):** explicit `onTaskRemoved` binding in `QaLensProjectionService` (the service
killed without `onDestroy` is currently only caught by the 10s watchdog, not an immediate
`onVideoComplete(ok=false)`).

---

### A6. Move Room/DataStore observation behind an interface ✅ P1

**What shipped:** `interface DataSourceObserver` + `enum ChangeType` in `qalens-core/.../
QaLensDataSourceObserver.kt`; `QaLens.registerDataSourceObserver/unregisterDataSourceObserver/
notifyDataChange/notifyDataError` **[API]** + no-op twins. `observeRoom`/`observeDataStore`
remain internal thin wrappers.

---

### A7. Enforce no-op parity with a test ✅ P2

**What shipped:** `qalens-noop/src/test/.../NoopParityCheck.kt` — a compile-time parity check that
references every public `QaLens` method, so a missing/mismatched no-op twin is a compile error at
the exact method (more reliable than reflection given Room/Android classpath constraints).

---

## Phase 5 — ✅ SHIPPED (web polish)

### C8. Keyboard help overlay ✅ P1
`?` toggles a shortcut modal in `web/app.js` (`toggleHelpOverlay()`); `Esc`/`?` closes;
"⌨ Shortcuts" hint near the transport.

### C9. Annotations / bookmarks in the timeline ✅ P1
`marks.json` parsed into `S.marks` (`web/sal.js`); star icons on the scrubber +
Timeline rows + in-viewer "⭐ Mark moment" (`markNow()`); `Bookmark`/`BookmarkSeverity` +
`SalTracks.marks` on-device; surface in `exportSummary`.

### C10. Mobile / touch layout (web) ✅ P1
`@media (max-width: 1000px)` + `(max-width: 640px)` breakpoints in `web/styles.css`
(single-column, stacked transport, toggleable filmstrip, full-width scrubber, scrollable tabs) +
`touch-action: manipulation` on the player.

### C11. Surface `analysis.json.stats` + `.endpoints` ✅ P1
`networkSubstats`/`renderStatbar` prefer `S.analysis.stats` and render the per-endpoint
breakdown (`S.analysis.endpoints`) in the Network tab; `analysis.likelyOwner` shown alongside
`summary.json`'s.

### C12. Surface `for_ai.md` ✅ P1
`forAi: textOf(files, "for_ai.md")` in `web/sal.js`; the "AI Brief" tab (track key 7) renders it
via `textContent` (synthesizes a brief for pre-for_ai recordings); "Copy AI brief" button +
`sal_report.js --for-ai`.

### C13. Session compare/diff view (web) ✅ P1
See B11 — compare drop-target, two-column diff, `?compare=`, and `--compare` in `sal_report.js`.

### C14. `sal_report.js` exit code uses `analysis.json` ✅ P1
Exit 1 when `failed.length || errors.length || deviceFailures.length` where `deviceFailures` =
`analysis.anomalies` filtered to failure kinds; "On-device anomalies" folded into the output.

### C15. `sal.js` validates `formatVersion` ✅ P2
`parse()` throws a clear error if `manifest.formatVersion > 1`; logs the version on load.

### C16. Filmstrip click precision ✅ P2
`filmstripHalfStep()` adds half the mean inter-frame interval; the filmstrip click handler seeks
to `frame.ts + halfStep` so the clicked frame stays visible until the next.

---

## NEW — ✅ SHIPPED: mock backend + frontend hook

### Mock Python webhook backend (`backend/`) ✅

- `backend/server.py`: stdlib-only `ThreadingHTTPServer` with `POST /webhook` (multipart `.sal`
  from the Control Room **or** the `{"qalens":"webhook-test"}` ping), `POST /api/ingest` (web
  player JSON summary), `GET /` dashboard, `GET /ping`, `GET /api/uploads`,
  `GET /api/uploads/<id>`, `GET /uploads/<id>/download`, `DELETE /api/uploads/<id>`; CORS
  enabled. Parses the `.sal` (`zipfile`+`json`), produces a deterministic mock AI verdict from
  `analysis.json`/`summary.json`, stores under `backend/data/`.
- `backend/tests/test_backend.py`: 11 end-to-end tests (boot the real server, exercise the full
  contract). Docs: `backend/README.md`.

### Web player "Send to backend" ✅

- Opt-in **Backend URL** setting + **⇪ Send to backend** button in `web/app.js`: multipart
  `file` → `<base>/webhook`, JSON summary (`buildWebSummary()`) → `<base>/api/ingest` when no
  raw file is available; `X-QaLens-*` headers mirror the mobile contract.

---

## Open backlog

### A3 (full decomposition) ⏳ P1
Only `AnalysisEngine` is extracted. Remaining: `ObservationCollector`, `RecordingController`,
`PanelStateController`, `EvidenceService`, `ActivityBridge`, and the injectable
`internal interface QaLensBackend` so the orchestrator is testable without Android.

### A5. Recorder watchdog + service-lifecycle binding ✅ P1
2s watchdog tick auto-cancels stalled recordings (pushError + retry); projection service
onTaskRemoved/onDestroy stop cleanly; panicRestore() gained a >15s no-frames force-clear.

### B15. Macro assertions — DSL wiring + pass/fail surface ✅ P1
parseMacroLine/parseMacroScript (assert exists/label/route/network no-errors/no-slow +
if/then/else blocks) run through MacroEngine in the on-device AppSalMacro runner; failures stop
the macro, emit ASSERTION timeline events, and the minimal panel shows per-macro ✓/✕ chips.
35 new core tests.

### B14. Global search across all tracks ✅ P1
Panel-header search (150ms debounce, Esc exits) over events/network/logs/nodes, grouped by track;
node hits select in Inspect, network hits open the Network tab, event/log hits open Logs.

### R7. Chunked / resumable uploads + offline retry queue ✅ P2 (fully landed)
Bounded 2-thread executor (no more thread-per-upload), 3-attempt retry with 1.5s/3s backoff,
chunked uploads (start → status → 1MB chunks with CRC32 → finalize; idempotent/resumable across
restarts) for recordings > 2MB, and an offline retry queue (cap 20, drained on reconnect and on
the next upload). Backend implements the full protocol + 4 new tests (15 total). Demo: ./demo.sh curl.

### R8. Optional body capture ✅ P2
QaLensConfig.captureNetworkBodies (OFF by default): request bodies buffered-and-rebuilt, response
bodies peeked without consuming the stream, text-ish types only, 64KB cap with …[truncated],
redacted at capture AND encode, never breaks the pipeline. 2 core tests.

### R9. formatVersion: 2 ✅ P2 (WebP deferred)
Recorder defaults to v2: gzip JSON tracks, files[] as {name, crc32, compressed} objects (CRC32
over uncompressed content); frames stay JPEG (WebP deferred). Readers (Android QaLensSalReader +
web/sal.js + CLI) accept v1+v2, verify CRCs with warn-and-continue, refuse >v2 loudly. 4 core
tests + 15 web assertions.

### R10. Auto-finalize .sal on crash ✅ P2
QaLensCrashHandler calls QaLensSessionRecorder.autoFinalize() before delegating — the in-flight
recording is saved to the recordings dir (no share sheet) with a log line.

### R11. Web side-by-side compare ✅ P2 (verified)
Web player compare mode (drop a second .sal → diff view) + sal_report.js --compare baseline.sal
(markdown diff, exit 1 on regression).

## Deferred follow-ups (from Phase 1)

- ⬜ **[UI]** Overview tab "Crashes" row showing `lastCrash` + "Copy with evidence" (from B1).
- ⬜ **[UI]** "Jank" pill in the Overview stat row (from B2).
- ⬜ **[UI]** Sparkline of `totalMs` in the jank card (from B2 — currently text stats only).
- ⬜ **[TEST]** Error-buffer cap + eviction unit test (from A4).
- ⬜ **[TEST]** Robolectric `logNetwork`-from-background-thread race test (from A2).
- ⬜ Sample app "Crash" / "ANR" / "Coroutine crash" demo buttons (from B1/B7).
- ⬜ Reference vendor bridge implementation (Sentry `beforeSend`) to document the pattern (from B13).

---

## Sequencing (updated)

**Shipped:** Phase 1 (A1+A2, A4, B1+B13, B2, C1–C7) · Phase 2 (B5) · Phase 3 (B3, B4, B7+B8, B11+C13, B15 full, B16; A3 partial) · Phase 4 (A5 full, A6, A7 executed) · Phase 5 (C8–C16) · B14 global search · R7 full (pool + retry + chunked/resumable + offline queue) · R8 · R9 (WebP deferred) · R10 · R11 verified · mock backend + frontend hook + demo kit (demo.sh, DEMO.md, web/landing.html, sample-app demo triggers).

### B17. Capture feature flags (Chucker / Timber / interceptor) ✅
QaLensConfig.captureNetwork (interceptor becomes a pure pass-through when false),
QaLensConfig.captureLogs (the Timber tree drops every line when false), and
QaLensConfig.networkFromChucker (a Chucker TransactionListener registered via reflection becomes
the network source — no QaLens interceptor needed, nothing double-counted; falls back with a
clear log when Chucker is absent). analysis.json.coverage records all three modes with
DISABLED-via-config / sourced-from-Chucker notes so a gated track is never mistaken for a
missing one. 5 core tests.

**Next (P1):** A3 full decomposition → deferred follow-ups below (Crashes row, jank pill/sparkline, error-buffer eviction test, Robolectric race test, Sentry reference bridge). R9-WebP and real-device validation of A5/R10 edge cases ride along opportunistically.

---

### B16. Per-data-source redaction ✅ P1

**What shipped:** `QaLens.registerDataSource(name, redactKeys, redactPatterns, redactAll, provider)`
**[API]** (+ no-op twin); `AnalysisEngine.resolveDataSources` applies per-key → per-pattern →
`redactAll` → global `config.redact` as backstop (`AnalysisEngine.kt`). `DataSourceEntry`
carries the per-source policy.

---

## Phase 4 — SHIPPED (A5 partial)

### A5. Recorder watchdog + service-lifecycle binding 🚧 (partial)

**Shipped:** the frame-stall watchdog — `WATCHDOG_TIMEOUT_MS = 10_000L` in
`QaLensSessionRecorder.kt` auto-stops a recording with no frame for 10s and surfaces it via
`pushError`; `onVideoComplete` does the "stop came from outside QaLens" bookkeeping so
`isRecording` can't stick true; the projection service's `onDestroy` calls `stopRecording()`
(`QaLensProjectionService.kt`).

**Remaining (open):** explicit `onTaskRemoved` binding in `QaLensProjectionService` (the service
killed without `onDestroy` is currently only caught by the 10s watchdog, not an immediate
`onVideoComplete(ok=false)`).

---

### A6. Move Room/DataStore observation behind an interface ✅ P1

**What shipped:** `interface DataSourceObserver` + `enum ChangeType` in `qalens-core/.../
QaLensDataSourceObserver.kt`; `QaLens.registerDataSourceObserver/unregisterDataSourceObserver/
notifyDataChange/notifyDataError` **[API]** + no-op twins. `observeRoom`/`observeDataStore`
remain internal thin wrappers.

---

### A7. Enforce no-op parity with a test ✅ P2

**What shipped:** `qalens-noop/src/test/.../NoopParityCheck.kt` — a compile-time parity check that
references every public `QaLens` method, so a missing/mismatched no-op twin is a compile error at
the exact method (more reliable than reflection given Room/Android classpath constraints).

---

## Phase 5 — ✅ SHIPPED (web polish)

### C8. Keyboard help overlay ✅ P1
`?` toggles a shortcut modal in `web/app.js` (`toggleHelpOverlay()`); `Esc`/`?` closes;
"⌨ Shortcuts" hint near the transport.

### C9. Annotations / bookmarks in the timeline ✅ P1
`marks.json` parsed into `S.marks` (`web/sal.js`); star icons on the scrubber +
Timeline rows + in-viewer "⭐ Mark moment" (`markNow()`); `Bookmark`/`BookmarkSeverity` +
`SalTracks.marks` on-device; surface in `exportSummary`.

### C10. Mobile / touch layout (web) ✅ P1
`@media (max-width: 1000px)` + `(max-width: 640px)` breakpoints in `web/styles.css`
(single-column, stacked transport, toggleable filmstrip, full-width scrubber, scrollable tabs) +
`touch-action: manipulation` on the player.

### C11. Surface `analysis.json.stats` + `.endpoints` ✅ P1
`networkSubstats`/`renderStatbar` prefer `S.analysis.stats` and render the per-endpoint
breakdown (`S.analysis.endpoints`) in the Network tab; `analysis.likelyOwner` shown alongside
`summary.json`'s.

### C12. Surface `for_ai.md` ✅ P1
`forAi: textOf(files, "for_ai.md")` in `web/sal.js`; the "AI Brief" tab (track key 7) renders it
via `textContent` (synthesizes a brief for pre-for_ai recordings); "Copy AI brief" button +
`sal_report.js --for-ai`.

### C13. Session compare/diff view (web) ✅ P1
See B11 — compare drop-target, two-column diff, `?compare=`, and `--compare` in `sal_report.js`.

### C14. `sal_report.js` exit code uses `analysis.json` ✅ P1
Exit 1 when `failed.length || errors.length || deviceFailures.length` where `deviceFailures` =
`analysis.anomalies` filtered to failure kinds; "On-device anomalies" folded into the output.

### C15. `sal.js` validates `formatVersion` ✅ P2
`parse()` throws a clear error if `manifest.formatVersion > 1`; logs the version on load.

### C16. Filmstrip click precision ✅ P2
`filmstripHalfStep()` adds half the mean inter-frame interval; the filmstrip click handler seeks
to `frame.ts + halfStep` so the clicked frame stays visible until the next.

---

## NEW — ✅ SHIPPED: mock backend + frontend hook

### Mock Python webhook backend (`backend/`) ✅

- `backend/server.py`: stdlib-only `ThreadingHTTPServer` with `POST /webhook` (multipart `.sal`
  from the Control Room **or** the `{"qalens":"webhook-test"}` ping), `POST /api/ingest` (web
  player JSON summary), `GET /` dashboard, `GET /ping`, `GET /api/uploads`,
  `GET /api/uploads/<id>`, `GET /uploads/<id>/download`, `DELETE /api/uploads/<id>`; CORS
  enabled. Parses the `.sal` (`zipfile`+`json`), produces a deterministic mock AI verdict from
  `analysis.json`/`summary.json`, stores under `backend/data/`.
- `backend/tests/test_backend.py`: 11 end-to-end tests (boot the real server, exercise the full
  contract). Docs: `backend/README.md`.

### Web player "Send to backend" ✅

- Opt-in **Backend URL** setting + **⇪ Send to backend** button in `web/app.js`: multipart
  `file` → `<base>/webhook`, JSON summary (`buildWebSummary()`) → `<base>/api/ingest` when no
  raw file is available; `X-QaLens-*` headers mirror the mobile contract.

---

## Open backlog

### A3 (full decomposition) ⏳ P1
Only `AnalysisEngine` is extracted. Remaining: `ObservationCollector`, `RecordingController`,
`PanelStateController`, `EvidenceService`, `ActivityBridge`, and the injectable
`internal interface QaLensBackend` so the orchestrator is testable without Android.

### A5. Recorder watchdog + service-lifecycle binding ✅ P1
2s watchdog tick auto-cancels stalled recordings (pushError + retry); projection service
onTaskRemoved/onDestroy stop cleanly; panicRestore() gained a >15s no-frames force-clear.

### B15. Macro assertions — DSL wiring + pass/fail surface ✅ P1
parseMacroLine/parseMacroScript (assert exists/label/route/network no-errors/no-slow +
if/then/else blocks) run through MacroEngine in the on-device AppSalMacro runner; failures stop
the macro, emit ASSERTION timeline events, and the minimal panel shows per-macro ✓/✕ chips.
35 new core tests.

### B14. Global search across all tracks ✅ P1
Panel-header search (150ms debounce, Esc exits) over events/network/logs/nodes, grouped by track;
node hits select in Inspect, network hits open the Network tab, event/log hits open Logs.

### R7. Chunked / resumable uploads + offline retry queue ✅ P2 (fully landed)
Bounded 2-thread executor (no more thread-per-upload), 3-attempt retry with 1.5s/3s backoff,
chunked uploads (start → status → 1MB chunks with CRC32 → finalize; idempotent/resumable across
restarts) for recordings > 2MB, and an offline retry queue (cap 20, drained on reconnect and on
the next upload). Backend implements the full protocol + 4 new tests (15 total). Demo: ./demo.sh curl.

### R8. Optional body capture ✅ P2
QaLensConfig.captureNetworkBodies (OFF by default): request bodies buffered-and-rebuilt, response
bodies peeked without consuming the stream, text-ish types only, 64KB cap with …[truncated],
redacted at capture AND encode, never breaks the pipeline. 2 core tests.

### R9. formatVersion: 2 ✅ P2 (WebP deferred)
Recorder defaults to v2: gzip JSON tracks, files[] as {name, crc32, compressed} objects (CRC32
over uncompressed content); frames stay JPEG (WebP deferred). Readers (Android QaLensSalReader +
web/sal.js + CLI) accept v1+v2, verify CRCs with warn-and-continue, refuse >v2 loudly. 4 core
tests + 15 web assertions.

### R10. Auto-finalize .sal on crash ✅ P2
QaLensCrashHandler calls QaLensSessionRecorder.autoFinalize() before delegating — the in-flight
recording is saved to the recordings dir (no share sheet) with a log line.

### R11. Web side-by-side compare ✅ P2 (verified)
Web player compare mode (drop a second .sal → diff view) + sal_report.js --compare baseline.sal
(markdown diff, exit 1 on regression).

## Deferred follow-ups (from Phase 1)

- ⬜ **[UI]** Overview tab "Crashes" row showing `lastCrash` + "Copy with evidence" (from B1).
- ⬜ **[UI]** "Jank" pill in the Overview stat row (from B2).
- ⬜ **[UI]** Sparkline of `totalMs` in the jank card (from B2 — currently text stats only).
- ⬜ **[TEST]** Error-buffer cap + eviction unit test (from A4).
- ⬜ **[TEST]** Robolectric `logNetwork`-from-background-thread race test (from A2).
- ⬜ Sample app "Crash" / "ANR" / "Coroutine crash" demo buttons (from B1/B7).
- ⬜ Reference vendor bridge implementation (Sentry `beforeSend`) to document the pattern (from B13).

---

## Sequencing (updated)

**Shipped:** Phase 1 (A1+A2, A4, B1+B13, B2, C1–C7) · Phase 2 (B5) · Phase 3 (B3, B4, B7+B8,
B11+C13, B15, B16; A3 partial) · Phase 4 (A6, A7; A5 partial) · Phase 5 (C8–C16) · mock backend +
web "Send to backend".

**Next (P1):** finish B15's DSL wiring → A5 lifecycle remainder → A3 full decomposition → B14.

**Next (P2):** R7-remainder → R8/R9/R10/R11 (see `docs/replay_backlog.md`) → deferred Phase-1
polish, folded in opportunistically when touching the relevant area.
