# Changelog

## Unreleased — post-0.9.0

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
  See docs/RELIABILITY_AUDIT.md for results and remaining limitations.

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
- **Review.** `docs/CODE_REVIEW.md` added — a frank engineering review of core/compose/web/backend.

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
  `com.qalens:qalens-*:0.9.0`. See [`integration_skill.md`](integration_skill.md) (AI agents) and [`integration.md`](integration.md) (humans).
