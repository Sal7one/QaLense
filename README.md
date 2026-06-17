# QaLens — Mobile Release Evidence SDK (debug-only set of libraries)

QaLens is a **debug-only release evidence SDK** for Android Jetpack Compose apps. It automatically
captures *what happened, where, and why it may have happened*, then produces a clean, redacted,
Jira-ready bug bundle — and it records entire QA sessions into single shareable **`.sal`** files
that replay with the screen synced to state, network, and logs (on-device player, offline web
player, or CI). Every `.sal` ships with a precomputed `analysis.json` digest and a self-describing
`for_ai.md`, and the **Control Room** can POST it straight to your AI-analysis backend via webhook.

QA uses it without Android Studio. Developers integrate it once. Engineers stop asking "what build?",
"what screen?", "what steps?", "what API failed?", "what flags were on?", and "can you reproduce it?"

> **New here?** Read [`docs/ONBOARDING.md`](docs/ONBOARDING.md) — a comprehensive guide for QA testers,
> app developers, and library contributors. Integrating into your own app? Follow
> [`integration.md`](integration.md) — a copy-paste guide precise enough for an AI agent.
> History: [`CHANGELOG.md`](CHANGELOG.md).

> Release builds are a **no-op** with the identical public API — zero overlay, zero sensors, zero
> notification, zero capture, no PII, nothing uploaded.

---

## Modules

```text
qalens-core                 pure Kotlin: models, config, redaction, rules, scoring, classifier,
                            timeline/repro, evidence bundle, reports, .sal format encoders
qalens-android              Android device/build info, shake, notification, FileProvider
qalens-compose              the debug overlay, panel, network interceptor, recorder, screenshot,
                            Control Room (own launcher icon), control service, floating REC chip,
                            tag mode, webhook uploader
qalens-navigation-compose   automatic Navigation Compose route tracking
qalens-replay               the .sal session player (own task; opened from the Control Room or a
                            shared file — no launcher icon of its own)
qalens-noop                 release-safe no-op with the same public API
sample-app                  banking-style demo wired for the full flow
```

## Install (debug vs release)

```kotlin
dependencies {
    debugImplementation(project(":qalens-compose"))
    debugImplementation(project(":qalens-navigation-compose"))
    debugImplementation(project(":qalens-replay"))   // the .sal player (debug tool)
    releaseImplementation(project(":qalens-noop"))
}
```

## Integration levels (minimal developer impact)

- **L0 — dependency only:** AndroidX Startup installs the overlay; lifecycle, semantics scan,
  device/build, shake-to-open, bubble, and the foreground notification all work with zero code.
- **L1 — one root wrapper:** `QaLensRoot { App() }` for better semantics + `testTagsAsResourceId`.
- **L2 — one nav wrapper:** `QaLensNavHost(navController, startDestination = "home") { … }` for
  automatic route/back-stack/screen-map tracking.
- **L3 — one OkHttp interceptor:** `OkHttpClient.Builder().addInterceptor(QaLensOkHttpInterceptor())`
  for the network timeline + Backend/API classification.
- **L4 — enrichment (all optional):** feature flags, screen contracts, deep-link scenarios, custom
  redaction, `Modifier.qaName/qaTag`, `Modifier.qaLensRecompose`, Timber, DataStore/Room observers.

```kotlin
QaLens.configure {
    appName = "My App"
    appVersion = BuildConfig.VERSION_NAME
    buildVariant = BuildConfig.BUILD_TYPE
    environment = "staging"
    expectedEnvironment = "staging"   // flags wrong-build testing
    gitSha = BuildConfig.GIT_SHA
    featureFlags = mapOf("checkout_v2" to true)
    addRedaction("internal-token-[a-z0-9]+")   // custom redaction on top of the defaults
}
QaLens.install(application)
```

## Open the panel · the Control Room

Tap the floating bubble · **shake the device** · the **notification** ("Panel" action) ·
or `QaLens.openPanel()`.

The **QaLens Control** launcher icon opens the **Control Room** — a command & control surface in
its own task that works even if the in-app overlay is hidden or broken: start/stop recordings
(it arms and jumps into the app so the Control Room isn't recorded), **PANIC RESTORE**, the
"inject overlay" kill-switch, opacity/dock settings, permission grants, the recordings manager
(play / share / **webhook** / two-tap delete), and the webhook configuration.

## Panel tabs & modes

`Overview` (build safety + issue counts + likely owner + actions) · `Bug Bundle` · `Repro` ·
`Screen Health` (session map + live contracts) · `Network` (+ health summary) · `Navigation` ·
`Accessibility` · `Automation Tags` · `Device & Build` (+ flags + recompose counts + data sources)
· `Tools` · `Inspect` · `Logs` (filter + level chips + duplicate collapsing for log-heavy apps).

Two on-screen modes from the header: **Ins** (inspect: bounds + warnings, tap to select) and
**Tag** (automation mode: every visible test tag drawn on its component, untagged interactive
components flagged red, tap a tag to copy it).

## One-tap exports (all redacted)

`Copy Jira Bug` · `Copy Slack Summary` · `Copy Repro Steps` · `Copy Full QA Report` ·
`Share Annotated Screenshot` · `Copy Session Summary`.

---

## 🎬 Session Replay — the `.sal` file

Record a whole QA session and replay it with the screen synced to every track.

1. **● Record Session** — from the panel, the notification action, or the Control Room
   (frames by default, or **HD video** via MediaProjection).
2. Use the app — a stop control is always one tap away: the **floating REC chip** (with
   draw-over-apps), the in-app REC pill, the notification, or **shake to stop**.
3. **■ Stop** → the `.sal` hits the share sheet and the Control Room's recordings list.
4. **▶ Play** it on-device (player opens in its own task — Back always returns to your app),
   drop it on the **web player**, run `sal_report.js` in CI, or **⇪ webhook** it to your
   AI-analysis backend.
5. While replaying: every timeline/network/log row is **clickable and seeks the video to that
   exact moment** (forward too); rows ahead of the playhead are dimmed; fullscreen/theatre mode
   on both players.

A `.sal` is a ZIP: `manifest.json` (incl. `sessionId`, `videoStartMillis`, locale/timezone/screen
metrics), `summary.json`, **`analysis.json`** (precomputed digest: coverage, stats, per-endpoint
aggregates, screen spans, timestamped anomalies, likely owner), **`for_ai.md`** (the archive
explains itself to any AI), `timeline/network/logs/state.json`, `report.txt`, and `frames/*.jpg`
or `video.mp4`. Every text track is redacted before it touches disk. Full spec:
[`docs/replay_backlog.md`](docs/replay_backlog.md).

### Webhook → AI analysis

Configure once in the Control Room (endpoint, auth header, extra params), then any recording can
be POSTed as multipart `file` with `X-QaLens-*` metadata headers and `X-QaLens-Digest` (the file's
own `analysis.json.stats` — triage without unzipping). The backend's HTTP status + response body
are shown right under the recording. A **Test endpoint** button validates the URL with a
metadata-only ping.

### Web player (no Android needed)

`web/` is a **zero-dependency, offline** `.sal` viewer ("Mission Control") — open `web/index.html`
(or `cd web && python3 -m http.server 8000`), then drop a `.sal` or click **Watch demo session**.
Filmstrip, error-marked scrubber, playback speed, theatre/fullscreen, six synced tracks (incl.
**Screens** and auto-**Insights**), IndexedDB instant-replay recents, markdown **Export**, and
deep links (`?sample&t=24.6` opens the demo AT a moment). CLI:
`node web/tools/sal_report.js session.sal [--json]` prints a Jira-ready report and exits 1 on
failures — a ready-made CI gate. See [`web/README.md`](web/README.md).

## Optional enrichment APIs

```kotlin
// Network (L3)
OkHttpClient.Builder().addInterceptor(QaLensOkHttpInterceptor())

// Logs
Timber.plant(QaLensTimberTree())

// App-owned data → reports + .sal
QaLens.registerDataSource("Preferences") { mapOf("theme" to prefs.theme) }
QaLens.observeDataStore("Preferences", dataStore.data) { "${it[THEME_KEY]}" }   // change events
QaLens.observeRoom(db, "accounts", "transactions")                              // table-change events

// Feature flags
QaLens.setFeatureFlagProvider { mapOf("checkout_v2" to flags.isOn("checkout_v2")) }

// Live screen contracts (shown in Screen Health, no noise when absent)
QaLens.contract("Checkout") {
    requiresTag("checkout.submit.button")
    requiresLabel("Submit payment")
    requiresNoCriticalAccessibilityWarnings()
    requiresNoFailedNetwork()
}

// Deep-link scenario runner (launch + expected-route validation, no Android Studio)
QaLens.registerDeepLinkScenario("Open Recharge", "myapp://recharge", expectedRoute = "recharge")

// Component metadata + recomposition counter
Modifier.qaName("Recharge Button").qaLensRecompose("RechargeButton")
```

## "wow" demo (sample app)

1. Launch the sample, open **Home** (a balance API fires; Timber logs it).
2. Go to an account → **Transfer** → **Confirm Transfer** (a `POST /transfer` returns 500).
3. Open QaLens → **Overview**: the score drops, **Likely Owner = Backend/API (HIGH)**.
4. **Bug Bundle → Copy Jira Bug** → paste a complete, redacted report (build, device, repro,
   network failure, accessibility, flags, data sources).
5. Or **Record Session** across the flow, **Stop & Share**, and replay the `.sal` in the Player.

The sample also registers deep-link scenarios (`Tools` tab), live contracts (Transfer/Home/Payment
Failure), and a DataStore-style preferences flow (toggling dark mode emits a change event).

## What's deliberately honest (Android limits)

- Compose private state/function names aren't inspectable; QaLens reads semantics/test tags/
  accessibility, plus explicit `qaName/qaTag`.
- The timeline never fabricates taps it didn't observe — it's built from navigation, network,
  explicit `QaLens.event()/log()`, and Timber; raw taps/keystrokes aren't globally observable.
- Network needs the OkHttp interceptor; feature flags/contracts/scenarios/data sources need opt-in.
- Room exposes table-level invalidation (not row diffs); DataStore needs its `data` flow passed in.
- Default session recording is **frame-based (PixelCopy, ~2fps)** — no permission, no codec; fast
  glitches can fall between frames. Opt-in **MediaProjection H.264 video** shows a consent dialog
  and runs a foreground service; `videoStartMillis` keeps it aligned with the tracks.
- "Draw over other apps" is optional — it only powers the floating REC/stop chip.
- Screenshot/recording may fail on `FLAG_SECURE` windows. Nothing leaves the device unless QA
  explicitly shares or configures the webhook; reports/`.sal` are redacted by default.

## Build

```bash
./gradlew :sample-app:assembleDebug
./gradlew :qalens-core:test            # 49 unit tests: redaction, scoring, classifier, repro,
                                       # network health, contracts/scenarios, .sal encoders
node web/test/read.test.js             # web .sal reader regression test (reads web/sample.sal)
```

> Note: `assembleRelease` can fail in `lintVitalAnalyzeRelease` under **JDK 25** (an AGP-lint /
> Kotlin version-parser incompatibility, not a code issue). Verify release parity with
> `./gradlew :sample-app:compileReleaseKotlin`, or build under a JDK ≤ 21.


n of what this project does.

## License

This project is licensed under the MIT License.

Copyright (c) 2026 Saleh Alanazi

See the [LICENSE](LICENSE) file for details.
