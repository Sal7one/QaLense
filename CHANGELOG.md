# Changelog

## Unreleased — post-0.9.0

### Archive reader limits — 2026-09-23
- Bound ZIP entry counts, per-entry and total expansion, nested gzip text, and manifest size in the
  shared web/CLI reader and Python mock backend. Reject invalid paths, duplicates, ZIP CRC errors,
  missing listed entries and manifest CRC mismatches before replay or backend storage.
- Add v1/v2, nested compression, tampered checksum and expansion regressions. The backend remains
  a loopback-only development mock without authentication or tenant isolation.
- Make the recording stop chip expose an accessibility click action and release its in-app window
  when its Activity is destroyed. The projection notification opens recording controls on a body
  tap while its explicit action stops capture. Move chip/notification text into resources and show
  a short upload verdict in Control Room instead of truncating raw backend JSON.
- Build webhook app/device metadata from application context so uploads started in the standalone
  Control Room retain headers and query context even before a host Activity has resumed.
- Align the Gradle wrapper with CI at 9.1.0 and make the internal Maven zip script accept a version,
  clearing old generated publications before packaging.

### Tester flow and local upload demo — 2026-09-23
- Make the QA quick-actions sheet the default for new installs, with three primary actions: record a
  session, capture a screenshot, and mark a bug. Move macros, device/report copying, tags, Control
  Room, watch mode and developer diagnostics under **More tools**.
- Apply the existing overlay tokens to that sheet. Add a deliberate **Send latest session** action
  when a backend endpoint and saved recording are both available, with a short upload outcome.
- Make the local backend and demo web server bind to loopback by default. Restrict browser CORS to
  loopback or same-host pages; validate request sizes, JSON object bodies, chunk sizes/counts and
  upload IDs before accepting or using them. Remove chunk staging before returning finalize success;
  make the curl demo use the same 1 MB chunk contract as the Android client.
- Make the sample's debug-only cleartext exception explicit for loopback domains and align imported
  `.appsal` files that omit a panel preference with the tester-first default.
- Replace the handoff prompt duplicate with the repository's single current handover. Refresh the
  backend quick start and state clearly that the mock stores raw recordings and is not a shared
  production service. QaLens itself remains free and self-hostable under MIT.
- Validation: `./demo.sh test` passed (web reader, all 20 backend tests, Kotlin unit/release-parity
  checks and expected failing-session CLI smoke); `:sample-app:assembleDebug` and debug lint for the
  Compose, Android and sample modules passed. Lint warnings and the manual API 36 AVD and backend
  smoke results are recorded in HANDOVER.

### Host-adaptive overlay foundations — 2026-09-13
- Add `QaLensTokens`: host-adaptive palettes for the bubble, inspect/tag canvases, scrim and tag
  legend. A light host gets the dark overlay panel; a dark host gets an opaque light panel. The selector reads the
  configuration night mask because the overlay attaches at decor level, outside the host
  Material theme.
- Fix a real contradiction: `#E53935` meant "accessibility warning" in the inspect canvas and
  "untagged interactive component" in the tag canvas. Warning and untagged are now distinct.
- Add `QaLensTokensTest`, which recomputes WCAG contrast and fails below AA 4.5:1. Measuring the
  first draft showed its quoted 4.6–5.4:1 ratios were actually 2.2–3.8:1, because the signal
  colours had been chosen against a light surface and drawn on a dark one; all values were
  recomputed for the surface they are used on.
- Migrate the QA bubble, inspect and tag canvases, panel scrim, tag chip and tag-mode legend to
  tokens. The bubble keeps its original crisp white ring. Add `QaLensDimens` (4dp scale, one
  radius family, 48dp touch minimum) and `QaLensType`.
- Document the system, contrast tables and adoption order in docs/OVERLAY_DESIGN.md. The
  inspector panel, Control Room and REC chip still use their own literals and remain to be migrated.

### Handover consolidation — 2026-09-13
- Replace conflicting onboarding snapshots with one self-contained HANDOVER and a short takeover
  prompt; add root AGENTS navigation and a single current backlog.
- Preserve archive details in docs/SAL_FORMAT.md; correct architecture, privacy, retry, integration
  and verification guidance. Remove six superseded guides/snapshots; Git retains their history.
- Refresh documentation links, including the landing-page footer. No runtime behavior changed.

### Android client safety — 2026-09-13
- Preserve coroutine crash delivery in active and no-op SDKs; redact complete authorization values.
- Make runtime disable stop capture/collectors/uploads, finalize recording and detach overlays.
- Move JPEG/PNG file work off main; atomically publish screenshots; add Compose privacy masks,
  secure-window guards, private-cache defaults and explicit gallery/unmasked-video opt-ins.
- Skip streaming response previews; isolate imported webhook credentials; handle queue pressure,
  cancellation and persistent transient retries with settings captured per upload.
- Bound Android replay extraction, text decoding and CRC work; clean session caches and decode
  downsampled frames off main. Refresh route inspection, bound background SQL and report macro
  failures accurately. Wire periodic memory sampling and preserve frame penalties in exports.
- Export public coroutine types to consumers from both active and no-op artifacts.
- Add fourteen JVM regressions and expand device coverage. See docs/CLIENT_SAFETY_FIXES.md for
  migration behavior, executed checks and remaining validation limits.

### OSS integration and Kotlin hardening — 2026-09-08
- Replace invalid Chucker-as-source reflection with public launcher and interceptor coexistence;
  legacy configuration no longer silently suppresses network capture. Sample uses Chucker 4.1.0
  with matching release no-op, verified against the current Kotlin 2.0.21 toolchain.
- Add dependency-free network sinks, shared redaction/capture limits, declared source coverage,
  integration diagnostics and an Overview copy action. Deduplicate OkHttp observations per call.
- Add bounded/redacted external crash input and prevent echoing vendor-reported crashes back.
- Fix missing network-state permission, default-network transitions, notification permission
  race handling and Compose settings collection.
- Fix composite-build coordinates and add an independent consumer with debug/release checks,
  contribution and migration docs, issue/PR templates and a GitHub verification workflow.
- Add eleven core and eight real OkHttp tests; extend device regression coverage for Chucker,
  adapters and crash reporters. See HANDOVER.md for validation and limits.

### Recording retention — 2026-09-08
- Preserve early logs, requests, crashes, timings, connectivity, memory, bookmarks and state
  independently of dashboard history limits and log clearing.
- Bound each recording track by entries and estimated size; disclose omissions and Android
  dropped frame callbacks in analysis coverage, replay viewers and exported reports.
- Block clean CLI/backend conclusions and unverified fixes when recordings report evidence loss.
- Fix quadratic email redaction on long body tokens, discovered by the device overflow check.
- Add twelve core tests, fourteen web/CLI assertions, two backend tests and a dependency-free
  Android instrumentation runner for real archive retention and budget-overflow checks.

### Reliability audit — 2026-09-08
- Restore true debug/release SDK separation and enforce the release runtime dependency graph.
- Fix actual Android v2 archive decoding in web/CLI/backend and the upload digest header.
  Reject malformed archives and report insufficient evidence instead of a healthy verdict.
- Guard recording callbacks and saves by lifecycle/session; preserve stalled recordings,
  follow foreground activities, save off the UI thread, publish atomically, and stream CRCs.
- Restrict recorded evidence to the session window and include the crash during finalization.
- Correct Android frame timing units and batch UI updates to avoid a rendering feedback loop.
- Prevent recursive crash-handler installation; preserve host crash delegation.
- Bound body previews, skip one-shot/duplex requests, and preserve response streams.
- Fix screenshot bitmap cleanup and overlay restoration; redact annotation text.
- Add saving feedback, overview crash evidence/copy action, and jank sample counts.
- Expand verification with lifecycle, window, crash, stream and actual-layout archive regressions.
  See HANDOVER.md for results and remaining limitations.

### Correctness
- Use consistent nearest-rank p95/p99 calculations for network health, exported analysis,
  and frame timing. Fix off-by-one ranks at exact percentile boundaries and underestimated
  network p95 for small samples. Add seven core regression tests; preserve sample inclusion
  rules, public APIs, and archive schemas.

### Backlog completion, hooks & mock backend
- **Backlog completion.** Phases 2–5 shipped: B3 (pluggable tabs), B4 (GitHub/Linear/Markdown
  exports), B5 (connectivity + Chucker coexistence), B7 (coroutine exception handler), B8 (memory
  samples), B15 (macro assertions), B16 (per-source redaction), C8–C16 (keyboard help, ⭐ marks,
  mobile/touch layout, analysis.stats/endpoints, AI Brief, compare/diff, sal_report exit code,
  formatVersion validation, filmstrip precision), A6 (DataSourceObserver), A7 (NoopParityCheck);
  A3 (AnalysisEngine extraction) landed in part. See `next.md`.
- **Web player makeover.** AI Brief tab (`for_ai.md`, key `7`), ⭐ mark moments (key `m`, scrubber
  stars + `marks.json`), opt-in **Backend URL** + **⇪ Send to backend** hook, responsive
  mobile/touch layout.
- **Mock backend.** Stdlib-only `backend/server.py` (webhook + ingest + dashboard + uploads store +
  deterministic mock AI verdict) + `backend/tests/test_backend.py` (11 e2e tests) +
  `backend/README.md`.
- **Review.** An engineering review of core/compose/web/backend was added; current work is tracked in `next.md`.

## 0.9.0 — 2026-06

### Reliability overhaul (service-first)
- **Recording lock-out fixed.** Recording used to hide the entire overlay with no working stop
  control (the notification was silently dropped on Android 13+ without `POST_NOTIFICATIONS`,
  its actions died with the host activity, and shake only toggled a hidden panel). Stop is now
  layered: floating system REC chip (draw-over-apps, optional) → in-window REC pill (kept out of
  captured frames) → notification action via a manifest-declared `QaLensControlService` →
  shake-to-stop. Overlay visibility self-heals on every resume; `panicRestore()` recovers any
  stuck state.
- **Task separation.** The Control Room and the `.sal` player run in their own tasks
  (`taskAffinity` + `singleTask`); the player no longer hijacks the host app's task or its
  launcher icon.
- **Control Room** (`QaLensControlActivity`, own launcher icon): recording controls (arm-and-jump
  so the Control Room itself isn't recorded), panic restore, overlay inject kill-switch,
  recordings manager with two-tap delete, permission grants, persisted settings.

### `.sal` format & players
- New manifest fields: `sessionId`, `videoStartMillis` (precise video↔track alignment — video
  starts at consent-grant, not session start), platform/locale/timezone/screen metrics.
- **`analysis.json`** (schema `qalens-analysis/1`): precomputed digest — coverage (what's missing
  and why it matters), stats, per-endpoint aggregates, screen spans, timestamped anomalies,
  likely owner. **`for_ai.md`**: every archive explains itself to any AI, with a tasked analysis
  brief for dev / QA / management.
- Players (Android + web): all events listed from the start with the playhead row highlighted and
  future rows dimmed; click/tap any row to seek the video to that exact moment; sub-second
  timestamps; fullscreen/theatre modes. Fixed `org.json` null handling that flagged every request
  as an error.
- **Web "Mission Control"**: filmstrip, error-marked scrubber, playback speed, six synced tracks
  (incl. Screens visit map and auto-Insights), IndexedDB instant-replay recents, markdown export,
  deep links (`?sample&t=24.6`), settings drawer — all preferences persisted locally.
- **CLI**: `web/tools/sal_report.js` turns a `.sal` into a Jira-ready markdown/JSON report and
  exits non-zero on failures (CI gate). `web/tools/make_sample.js` regenerates the bundled demo.

### QA experience
- **QA Minimal panel** (switchable vs the full developer panel): big record/stop, screenshot,
  ⭐ Mark moment (starred breadcrumb + screenshot), copy bug report / device info, tag-mode
  toggle, tappable status chips, and the 5 most-recently-used macros.
- **Tag mode**: every visible test tag drawn on its component; untagged interactive components
  flagged; tap a tag to copy it; exit from the on-screen pill.
- **Macros**: step DSL (`deeplink/wait/tap/type/record/stop/screenshot/mark`); `tap`/`type` drive
  real Compose semantics actions and wait for their targets — a macro can complete a full login
  unattended.
- Screenshots save to the system gallery (Pictures/QaLens) with a confirmation toast; sharing is
  optional. Log-heavy apps: bigger buffers, log filter + level chips + duplicate collapsing.
- Draggable bubble position persists across panel open/close and process restarts.

### Data tooling & portability
- **Database card**: raw SQL into the app's own SQLite/Room databases — SELECTs render rows,
  writes report exactly how many rows were affected, every execution lands in the timeline.
  Saved queries per package. SharedPreferences/DataStore visibility.
- **`.appsal`** (`qalens-appsal/1`): one shareable JSON config per app package — panel style,
  overlay prefs, webhook, saved queries, macros, watched prefs. Export (secrets masked by
  default) / import on-device; full **web editor** (create, edit, download).
- **QA Profiles**: shared test phones, per-tester webhook identity (endpoint/bearer/user);
  uploads carry `X-QaLens-User`. Profiles deliberately never leave the device.
- **Webhook**: per-recording multipart upload to an analysis backend with `X-QaLens-*` metadata
  headers and `X-QaLens-Digest` (the file's own stats — triage without unzipping); backend
  response shown in the Control Room; test-ping button. Dependency-free (`HttpURLConnection`).

### Distribution
- `maven-publish` on all library modules: `./gradlew publishToMavenLocal` →
  `com.qalens:qalens-*:0.9.0`. See [`integration.md`](integration.md) for developer and AI-agent integration instructions.
