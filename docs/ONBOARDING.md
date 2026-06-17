# QaLens — Onboarding

Welcome. This guide gets three audiences productive fast:

- **QA testers** — use QaLens on a debug build with no Android Studio.
- **App developers** — integrate it and enrich it.
- **Library contributors** — understand the codebase and extend it safely.

> **One-line identity:** QaLens is a *debug-only Mobile Release Evidence SDK* for Android Jetpack
> Compose. It observes a running app, turns that into a redacted, AI-ready bug bundle, and records
> whole sessions into shareable **`.sal`** files you replay with the screen synced to
> timeline/network/logs/state — on-device, on the web, or via a CI report.

> **In a hurry?** [`integration_llm.md`](../integration_llm.md) is the copy-paste integration guide
> (written so even an AI agent can follow it mechanically).

---

## Table of contents

1. [5-minute start](#5-minute-start)
2. [Mental model](#mental-model)
3. [Module map](#module-map)
4. [Integration levels (L0–L4)](#integration-levels)
5. [Role: QA tester](#role-qa-tester)
6. [Role: app developer](#role-app-developer)
7. [Role: library contributor](#role-library-contributor)
8. [The data pipeline](#the-data-pipeline)
9. [The `.sal` format & players](#the-sal-format--players)
10. [Redaction & safety guarantees](#redaction--safety-guarantees)
11. [Build, test, run](#build-test-run)
12. [Extending QaLens](#extending-qalens)
13. [Gotchas & troubleshooting](#gotchas--troubleshooting)
14. [Glossary](#glossary)

---

## 5-minute start

Add the dependencies (debug gets the real thing, release gets a no-op):

```kotlin
dependencies {
    debugImplementation(project(":qalens-compose"))
    debugImplementation(project(":qalens-navigation-compose")) // optional, auto nav tracking
    debugImplementation(project(":qalens-replay"))             // optional, the .sal player
    releaseImplementation(project(":qalens-noop"))
}
```

That's it for **Level 0** — AndroidX Startup auto-installs the overlay. Run the debug build:

- A floating **QA bubble** appears. Tap it (or **shake** the device, or tap the **notification**) to open the panel
  — either the **QA Minimal** sheet (big actions + macros, for testers) or the full developer panel; switchable.
- A second launcher icon, **QaLens Control**, opens the Control Room: recording controls, panic restore,
  raw-SQL/data tooling, QA profiles, webhook upload, and `.appsal` import/export.

Optional but recommended config (in your `Application`):

```kotlin
QaLens.configure {
    appName = "My App"
    appVersion = BuildConfig.VERSION_NAME
    buildVariant = BuildConfig.BUILD_TYPE
    environment = "staging"
    gitSha = BuildConfig.GIT_SHA
}
QaLens.install(this) // no-op if Startup already installed; safe to call
```

Try the **web viewer** without any device: `cd web && python3 -m http.server 8000`, open it, click
**Load sample**.

---

## Mental model

QaLens has one rule that explains everything: **it never invents data it didn't observe.**

```
        observe                analyze                       present
  ┌───────────────┐     ┌────────────────────┐     ┌────────────────────────┐
  │ semantics scan │     │ rules → warnings   │     │ Overview / panel tabs   │
  │ navigation     │ ──▶ │ classifier+digest  │ ──▶ │ Jira / Slack / Repro    │
  │ network (OkHttp)│    │ bug classifier     │     │ annotated screenshot    │
  │ Timber / events │    │ timeline + repro   │     │ .sal recording → player │
  │ data sources    │    │ build safety       │     └────────────────────────┘
  └───────────────┘     └────────────────────┘
            │                       │
            └──── all redacted at every export boundary ────┘
```

- Everything flows into one immutable `QaLensUiState` (a `StateFlow`).
- Derived analysis (score, classification, build safety, screen-quality map) is recomputed from that
  state on every scan / network event.
- Reports and `.sal` tracks are produced on demand from the same state — and pass through
  `QaLensRedactor` before they can leave the device.

---

## Module map

```
qalens-core    pure Kotlin/JVM — no Android. Models, config+redaction, rules, scoring, classifier,
               timeline/repro, build safety, screen-quality, evidence bundle, reports, .sal encoders.
               (Has the only unit tests today — 49 of them.)

qalens-android Android device/build info, shake detector, foreground notification, FileProvider,
               MediaProjection helpers live in qalens-compose (need QaLens), not here.

qalens-compose THE debug implementation: the QaLens singleton (orchestrator), overlay + inspector
               panel (Compose), modifiers, OkHttp interceptor, Timber tree, screenshot capture,
               session recorder + .sal writer, projection service/activity.

qalens-navigation-compose   QaLensNavHost / QaLensNavigationObserver / QaLensNavigator — auto route tracking.

qalens-replay  The .sal player: a standalone Activity + Compose UI (frame or Media3 video) + a
               dependency-light .sal reader. Debug tool, opened from the Control Room or a shared file.

qalens-noop    Release-safe no-op with the IDENTICAL public API. Every public symbol in the debug
               artifacts must have a no-op twin here or release builds break.

web/           Zero-dependency browser .sal viewer (HTML/CSS/JS). Unzips with native DecompressionStream.

sample-app     A banking-style demo wired for the full wo flow.
```

Dependency direction: `core ← android ← compose ← {navigation-compose, replay}`. `noop` depends only
on `core` (+ compileOnly stubs). Nothing depends on `noop` except the app's release variant.

---

## Integration levels

Each level is one more line of setup; each unlocks more evidence. Stop at any level.

| Level | You add | You get |
|------|---------|---------|
| **L0** | the dependency | overlay, semantics scan, device/build, shake/bubble/notification, score, a11y rules |
| **L1** | `QaLensRoot { App() }` | better semantics + `testTagsAsResourceId` mapping |
| **L2** | `QaLensNavHost(navController, startDestination="home") { … }` | auto route/back-stack/screen-map + nav timeline |
| **L3** | `OkHttpClient.Builder().addInterceptor(QaLensOkHttpInterceptor())` | network timeline + Backend/API classification + network-health score |
| **L4** | enrichment APIs (below) | feature flags, contracts, deep-link scenarios, Timber, DataStore/Room, custom redaction, recompose counters |

L4 enrichment APIs:

```kotlin
Timber.plant(QaLensTimberTree())                                  // logs → timeline
QaLens.setFeatureFlagProvider { mapOf("checkout_v2" to flags.on) } // flags → evidence
QaLens.registerDataSource("Preferences") { mapOf("theme" to t) }   // DataStore/Room snapshots
QaLens.observeDataStore("Preferences", dataStore.data) { "$it" }   // change events
QaLens.observeRoom(db, "accounts", "transactions")                 // table-change events
QaLens.contract("Checkout") { requiresTag("checkout.submit"); requiresNoFailedNetwork() }
QaLens.registerDeepLinkScenario("Open Recharge", "myapp://recharge", expectedRoute = "recharge")
Modifier.qaName("Recharge").qaTag("home.recharge").qaLensRecompose("RechargeBtn")
```

---

## Role: QA tester

You never need Android Studio. On a debug build:

1. **Open the panel** — tap the bubble, shake hard, or tap the QaLens notification.
2. **Overview** tells you at a glance: build-safety warnings (are you even on the
   right build?), critical/warnings/missing-tags/failed-API counts, and the likely bug owner.
3. **Reproduce a bug**, then **Bug Bundle → Copy Jira Bug** (or Slack / Repro / Full report). Paste it.
   It already contains build, device, repro steps, network failures, accessibility issues, flags, and
   app data — all redacted.
4. **Capture Evidence** = an annotated screenshot (test-tagged + warning components outlined) shared
   with the report attached.
5. **Record a session**: Overview → **Record Session** (frames, no permission) or **Record HD Video**
   (asks for screen-capture permission once). Stop → share the **`.sal`**. A reviewer opens it in the
   **QaLens Player** (Control Room → ▶ Play) or the **web viewer** and scrubs the screen with
   every track in sync.
6. **Deep-link scenarios** (Tools tab): launch named flows and see pass/fail against the expected route.
7. **Screen Health**: per-screen quality across your session + live contract checks.

Keyboard in the web player: **Space** play/pause, **←/→** step event.

---

## Role: app developer

Where to put what:

- **`Application.onCreate`** — `QaLens.configure { … }`, `QaLens.install(this)`, `Timber.plant`,
  `setFeatureFlagProvider`, `registerDataSource`, `observeDataStore/observeRoom`, `contract(...)`,
  `registerDeepLinkScenario(...)`.
- **Compose root** — `QaLensRoot { … }` and/or `QaLensNavHost(...)`.
- **Networking** — add `QaLensOkHttpInterceptor()` to your client.
- **Composables** — `Modifier.qaName(...)`, `.qaTag(...)`, `.qaLensRecompose("Name")` where useful.
- **Business events** — `QaLens.event("checkout_submitted")`, `QaLens.breadcrumb(...)`, `QaLens.log(...)`
  for things QaLens can't observe (dialogs, domain milestones).

Manual breadcrumbs are **fallback/enrichment** — prefer the automatic layers (nav, network, Timber).

The whole public surface lives on the `QaLens` object (see `qalens-compose/.../QaLens.kt`). Anything
you call there must also exist in `qalens-noop` — that's enforced only by the release compile, so run
it (see below).

---

## Role: library contributor

Read these files first, in order:

1. `qalens-core/.../QaLensModels.kt` — the data model, incl. `QaLensUiState` (the single source of truth).
2. `qalens-compose/.../QaLens.kt` — the orchestrator: how observations enter state and how
   `recomputeAnalysis()` derives score/classification/build-safety/screen-quality/data-sources.
3. `qalens-core/.../QaLensRules.kt` + `QaLensScore.kt` + `QaLensBugClassifier.kt` — the analysis engines.
4. `qalens-core/.../QaLensReports.kt` + `QaLensEvidence.kt` — how evidence is assembled & exported.
5. `qalens-compose/.../QaLensInspectorPanel.kt` — the entire panel UI (one file, tab-based).
6. `qalens-compose/.../QaLensSessionRecorder.kt` + `qalens-core/.../QaLensSalFormat.kt` — recording + `.sal`.

Golden rules:

- **Pure logic goes in `qalens-core`** (no Android imports) so it stays unit-testable.
- **Android specifics** go in `qalens-android` / `qalens-compose`.
- **Every new public `QaLens` method / modifier / class gets a no-op twin in `qalens-noop`.**
- **No export bypasses redaction** — go through `QaLensReports` / `config.redact` / `QaLensRedactor`.
- **Be honest about limits** — don't fabricate data Android can't give us (see Gotchas).

---

## The data pipeline

1. **Observation** enters via `QaLens.*` (or the installer's semantics scan):
   - `refreshInspection()` reads the Compose semantics tree → `nodes` → `QaLensRules.evaluate` → `warnings`.
   - `logNetwork`, `event/log/breadcrumb/timberLog`, `setScreen` push into `events`/`networkEvents`/`screen`.
2. **`recomputeAnalysis()`** (called after every scan / network event / config change) derives:
   - `ReleaseReadinessEngine.score(...)`, `BugClassifier.classify(...)`, `BuildSafetyCheck.check(...)`,
     resolved feature flags + data sources, and updates the `ScreenQualityStore` map.
   - All stored back into `QaLensUiState` so the panel is reactive.
3. **On demand**, `QaLens.evidenceBundle()` calls `EvidenceBuilder.build(...)` to assemble an
   `EvidenceBundle` (snapshot + timeline + repro + score + classification + build safety + flags +
   data sources + completeness). `QaLensReports` turns that into Jira/Slack/Repro/Full text.
4. **Recording** (`QaLensSessionRecorder`) samples frames + state over time and, at stop, writes the
   same tracks + a `summary.json` into a `.sal` via `SalTracks`/`QaLensSalWriter`.

---

## The `.sal` format & players

A `.sal` is a **ZIP** (written by `java.util.zip`) with extension `.sal`:

```
manifest.json     format version, app/device/build, start/end, fps, frame index, file list, counts, video?
summary.json      capture-time score / likely-owner / repro
timeline.json     merged sorted TimelineEvents
network.json      NetworkEvents (redacted URLs)
logs.json         QaEvents (incl. Timber)
state.json        periodic StateSamples (screen, flags, data sources)
report.txt        the full redacted report
frames/000001.jpg …   (frame mode)   OR   video.mp4   (MediaProjection mode)
```

Two capture modes:
- **Frame** (default): PixelCopy ~2fps, no permission, perfect per-frame timestamps. Player shows the
  nearest frame to the playhead.
- **Video** (opt-in, `startRecording(video = true)`): MediaProjection H.264 → `video.mp4`; player uses
  Media3/ExoPlayer. Requires a consent dialog + foreground service.

Two players, same format:
- **Android** — `qalens-replay`'s `QaLensPlayerActivity` (Control Room → ▶ Play, or open a shared `.sal`).
- **Web** — `web/index.html`, zero-dependency, offline; unzips with native `DecompressionStream`.

---

## Redaction & safety guarantees

These are non-negotiable invariants — preserve them in any change:

- **Debug-only.** Release uses `qalens-noop`: no overlay, sensors, notification, interceptor, recorder,
  or state retention; no behavior change.
- **Nothing leaves the device automatically.** No network calls, no analytics, no uploads. Exports go
  to the clipboard or the system share sheet only when the user acts.
- **Redaction by default.** `RedactionRule.defaultRules()` covers email, JWT, bearer/Authorization,
  cookies, `key=value` secrets, credit cards, Saudi phones, long numeric IDs. Add more via
  `configure { addRedaction("regex", "[REPLACEMENT]") }`. Every report/track/feature-flag/URL is folded
  through it before export. Tests live in `QaLensRedactorTest`.
- **No bodies.** The OkHttp interceptor records metadata only (method/URL/status/latency/sizes/error).
- **No private state.** Only what Compose exposes (semantics/test tags/accessibility) + explicit
  `qaName/qaTag` is inspectable.

---

## Build, test, run

```bash
# Build the debug app (full debug dependency path)
./gradlew :sample-app:assembleDebug

# Core unit tests (the engines) — 49 tests
./gradlew :qalens-core:test

# Release / no-op API parity (THE check that release still compiles)
./gradlew :sample-app:compileReleaseKotlin

# Web .sal reader regression test
node web/test/read.test.js
```

Always run `compileReleaseKotlin` after touching the public API — it's how you catch a missing no-op twin.

> ⚠ `assembleRelease` may crash in `lintVitalAnalyzeRelease` under **JDK 25** (an AGP-lint version-parser
> bug, not our code). Use `compileReleaseKotlin`, or build under JDK ≤ 21.

---

## Extending QaLens

**Add an accessibility/QA rule:** add a `QaWarning` in `QaLensRules.evaluateNode/evaluateScreen`
(`qalens-core`). It automatically feeds warnings → score → classifier → reports → panel. Add a test.

**Add a score penalty:** add a `ScorePenalty` in `ReleaseReadinessEngine.score` with a clear reason
string (surfaces in the web player's Summary and in `.sal` files). Keep penalties explainable.

**Add a report/export format:** add a builder to `QaLensReports` (redaction-aware), expose a
`QaLens.buildXxx()` (+ no-op twin), and add a panel button.

**Add a captured data source:** call `QaLens.registerDataSource(name) { map }` from the app — no library
change needed. For a new *kind* of auto-capture, push into `events`/`networkEvents` via existing entry
points so it flows through the pipeline.

**Add a contract rule:** extend the `ContractRule` sealed class + `ScreenContractValidator` + the
`ScreenContractBuilder` DSL in `qalens-core/QaLensContracts.kt`. Add a test.

**Add a `.sal` track:** add an encoder in `SalTracks` (`qalens-core`), write it in
`QaLensSessionRecorder.finalizeAndShare`, and parse it in both players (`QaLensSalReader.kt` and
`web/sal.js`).

---

## Gotchas & troubleshooting

- **`internal` doesn't cross modules.** A symbol used from another Gradle module must be `public`
  (this bit us early with the notification/shake detector). The release compile catches the fallout.
- **No-op parity.** If `:sample-app:compileReleaseKotlin` fails with "unresolved reference", you added
  a public API without a `qalens-noop` twin.
- **JDK 25 lint crash** — see Build section; use `compileReleaseKotlin`.
- **`FLAG_SECURE` screens** capture as black frames (screenshots/recording) — expected, no crash.
- **MediaProjection (HD video)** is compile-verified but needs on-device validation (consent dialog,
  API-34 callback ordering, encoder sizes). The frame recorder is the safe default.
- **Web viewer needs `DecompressionStream`** — a recent Chrome/Edge/Safari/Firefox. The "Load sample"
  button needs the folder served over http (file:// blocks `fetch`); dragging the file works on file://.
- **Overlay touches** — the inspector ComposeView must NOT set `isClickable=false`/`isFocusable=false`
  (it blocks Compose gesture detectors). It uses a tap-absorber that lets scroll/drag pass through.

---

## Glossary

- **Evidence bundle** — the in-memory assembly of everything observed, the basis for every export.
- **Release readiness score** — deterministic 0–100 with explainable penalties (per `ScoreDimension`);
  shown in the web player / `.sal` summary (deliberately not in the on-device panel).
- **Likely owner** — heuristic bug category (Backend/API, Android/UI, Navigation, Accessibility, …) with
  confidence + reasons; returns *Unknown* when evidence is weak.
- **Repro timeline** — navigation + network + events merged + sorted; `ReproStepGenerator` turns it into
  numbered steps with expected/actual.
- **Screen quality map** — in-memory per-screen scores accumulated across a QA session.
- **Contract** — an optional per-screen checklist (tags/labels/route/a11y/network) shown in Screen Health.
- **`.sal`** — a QaLens session recording (ZIP of tracks + frames or video).
- **No-op artifact** — `qalens-noop`, the release build's API-compatible do-nothing implementation.
