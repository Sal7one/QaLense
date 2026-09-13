<!-- Latest follow-up: recording-owned retention shipped. See docs/RECORDING_RETENTION.md,
     docs/RELIABILITY_AUDIT.md and next.md before the historical sections below. -->
# QaLens — Handover for AI Agents

> **2026-09-13 current baseline:** Read `docs/CLIENT_SAFETY_FIXES.md`, `docs/OSS_INTEGRATIONS.md`
> and `next.md` before the historical sections below. Kotlin matrix: 184 tests across core,
> Compose/OkHttp, no-op and replay. Client capture privacy defaults, shutdown, bounded replay,
> credential isolation and observed macro/upload outcomes now have regression coverage.
> Screenshots default to private cache; full-display video requires host opt-in. Physical-device
> video/rotation/storage and other remaining validation limits are listed in the fix report.
> CI configuration is updated but has not yet run on GitHub.

> Read this FIRST when you inherit this repository. It is a snapshot of the state of the repo,
> how to verify it, the rules you must not break, and where the work should go next.

## 1. What this project is

QaLens is a **debug-only mobile release-evidence SDK** for Android Jetpack Compose apps: it observes
a QA session (semantics scan, navigation, OkHttp metadata, Timber logs, crashes/ANRs, jank,
connectivity, memory), turns it into a **redacted bug bundle** (Jira/Slack/GitHub/Linear/Markdown
one-tap exports), and records whole sessions into portable **`.sal`** archives (ZIP: frames or video
+ synced tracks + precomputed `analysis.json` + `for_ai.md`) that replay on-device, in the
zero-dependency web player, or gate CI via `sal_report.js`. A stdlib-only **mock Python backend**
receives webhook uploads from both the Android app and the web player and returns a deterministic
mock AI verdict. Release builds link a **no-op** with an identical API.

**Not in scope / honest limits:** Compose-only (no View system), Android-only (iOS roadmap
discussed but not built), metadata-only network by default (bodies are opt-in, capped, redacted),
no AccessibilityService (no global tap capture — the timeline never fabricates events).

## 2. Current state (verified)

**Recent commits (git log --oneline):**
```
13dd912 Rework the mark feature: details, severity, dedup, delete (both players)
fd7d749 Fix Watch-demo button: read the sample Response body once
93486c0 Add prominent v2-design buttons to all landing pages
99ba6c1 Web player v2: new design system + modular code, classic preserved; player bugfixes
4eb05ea Add integration_skill.md: AI-agent integration procedure
75546d1 QaLense 0.10: hooks end-to-end, backlog completion, capture flags, demo kit
```

**Verification matrix (all green at handover):**

| Suite | Command | Result |
|---|---|---|
| Core engines | `:qalens-core:test` | **115 tests / 0 failures** |
| No-op parity | `:qalens-noop:testDebugUnitTest` | **1 executed parity test** (A7 now runs, not only compiles) |
| Release parity | `:sample-app:compileReleaseKotlin` | green (this is the release-safety gate) |
| Web reader | `node web/test/read.test.js` | **ALL PASS (32 assertions**, incl. formatVersion-2 gzip/CRC32 coverage) |
| Mock backend | `python3 backend/tests/test_backend.py` | **15/15 OK** (incl. chunked/resumable flow) |
| CLI gate | `node web/tools/sal_report.js web/sample.sal` | exit 1 expected (demo has failures); `--json` is pure JSON |
| Demo kit | `./demo.sh` (quick / curl / test / android / kill) | verified end-to-end |

## 3. Repo layout (what lives where)

```text
qalens-core/            pure Kotlin engines — models, config+redaction, rules, score, classifier,
                        timeline/repro, evidence, reports, .sal encoders (v1+v2), AI digest,
                        macros engine (B15), diff, contracts/scenarios, capture flags (B17)
qalens-android/         device/build info, shake, notification, FileProvider, prefs (incl. the
                        webhook offline-retry queue)
qalens-compose/         THE debug implementation: QaLens facade, overlay + panels (incl. B14
                        global search), recorder (A5 watchdog, R9 v2 writer, R10 auto-finalize),
                        OkHttp interceptor (R8 bodies, capture flags), Timber tree, Chucker
                        public launcher + generic transport sink, webhook client (bounded pool,
                        retry, chunked/resumable, offline queue), crash handler, connectivity,
                        memory monitor, Control Room, macros runner (B15 assertions)
qalens-navigation-compose  QaLensNavHost route tracking
qalens-replay/          on-device .sal player + QaLensSalReader (v1+v2, gzip, CRC verify)
qalens-noop/            release mirror — parity is COMPILE-enforced AND runtime-executed
sample-app/             banking demo; Settings → QaLens Demos fires every pipeline feature
web/                    Mission Control v2 (index-v2.html + app-v2.js + styles-v2.css, modular)
                        + classic viewer (index.html + app.js — KEPT for the .appsal editor and
                        as fallback) + shared sal.js reader + sal_report.js CLI + landing page +
                        integration.html (Kotlin-syntax-highlighted guide) + tests
backend/                stdlib-only mock webhook backend: /webhook (multipart + JSON test ping),
                        /api/ingest, chunk protocol (start/status/chunk/finalize), dashboard,
                        mock AI verdict, 15 e2e tests
demo.sh, DEMO.md        one-command demo + showcase tour
integration.md          human integration guide
integration_skill.md    AI-agent skill for integrating the SDK into an app (inspect → ask →
                        apply → verify)
docs/                   ARCHITECTURE, ONBOARDING, CODE_REVIEW (frank findings), replay_backlog
                        (.sal spec + backlog status)
next.md                 the living backlog — statuses are current
```

## 4. Build & verify (exact commands)

**Environment traps (read before building):**
- Use **JDK 17** — Kotlin 2.0.21 cannot parse JDK 25/26. `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home`
  on this machine (any JDK 17 works).
- The Gradle wrapper pins 9.0.0 but its distribution download is slow; a **cached Gradle 9.1.0
  binary** is verified to work:
  `$HOME/.gradle/wrapper/dists/gradle-9.1.0-bin/9agqghryom9wkf8r80qlhnts3/gradle-9.1.0/bin/gradle`.
- If several agents build at once, serialize with a mkdir lock:
  `while ! mkdir /tmp/qalens-gradle-lock 2>/dev/null; do sleep 5; done` … `rmdir /tmp/qalens-gradle-lock`.
- `:qalens-noop:testDebugUnitTest` uses **JUnit4** (do not add useJUnitPlatform there).

```bash
# full kotlin verification (covers core tests + parity test + all compiles + release parity)
JAVA_HOME=…/temurin-17 …/gradle-9.1.0/bin/gradle \
  :qalens-core:test :qalens-noop:testDebugUnitTest :qalens-compose:compileDebugKotlin \
  :qalens-replay:compileDebugKotlin :sample-app:compileDebugKotlin :sample-app:compileReleaseKotlin

node web/test/read.test.js                 # web reader regression (needs Node 18+)
python3 backend/tests/test_backend.py      # backend e2e (stdlib only, Python 3.9+)
./demo.sh                                  # or ./demo.sh test / curl / android / kill
```

## 5. Hard rules (do not violate)

1. **No-op parity.** Every public `QaLens` symbol needs a twin in `qalens-noop` — enforced by
   `NoopParityCheck` (compile) + `NoopParityCheckTest` (runtime). After any API change run
   `:sample-app:compileReleaseKotlin` AND `:qalens-noop:testDebugUnitTest`.
2. **Redaction is a choke-point.** Nothing leaves the device without `QaLensRedactor`/
   `config.redact`. New exports route through `QaLensReports` or the redactor directly.
3. **`.sal` honesty.** `analysis.json.coverage` must say what is missing and WHY (not installed vs
   disabled-by-config and declared network sources). Never infer health from absent tracks.
4. **Schema discipline.** `.sal` is versioned (`formatVersion` 1 and 2 both supported by all
   readers; >2 is refused loudly). Breaking changes bump the version and update writer + both
   readers + CLI + spec (docs/replay_backlog.md) together.
5. **No secrets in the repo.** Public repository: scan before committing (`git grep` for token
   patterns), `backend/data/`, `local.properties`, `.idea/`, build dirs, `__pycache__` are
   gitignored. Demo data uses example.com only.
6. **Web player files are served from the repo root** by demo.sh (so ../DEMO.md links resolve).
   `web/` files must keep working over file:// too (drag-drop path) — no fetch of absolute paths.

## 6. Key-file map for common tasks

| Task | Where to look |
|---|---|
| Add a score penalty | qalens-core …/QaLensScore.kt |
| Add a bug-classifier signal | qalens-core …/QaLensBugClassifier.kt |
| Add an a11y/QA rule | qalens-core …/QaLensRules.kt |
| Add an export format | qalens-core …/QaLensReports.kt + QaLens facade + noop twin |
| Add a .sal track | qalens-core …/QaLensSalFormat.kt + recorder + QaLensSalReader + web/sal.js + CLI |
| Change the AI digest | qalens-core …/QaLensAnalysis.kt (schema qalens-analysis/1, additive) |
| Change panel UI | qalens-compose …/QaLensInspectorPanel.kt / QaLensMinimalPanel.kt |
| Change the web player (new) | web/app-v2.js (modular: util/prefs/store/media/derive/render/wiring) |
| Change the web player (classic) | web/app.js (kept for .appsal editor + fallback) |
| Change the webhook contract | qalens-compose …/QaLensWebhook.kt + backend/server.py + tests |
| Change capture flags | qalens-core …/QaLensConfig.kt (B17) + interceptor + timber tree + generic network capture policy |
| Macro DSL/assertions | qalens-core …/QaLensMacros.kt (engine+parser) + compose …/QaLensMacros.kt (runner) |

## 7. Known deferred / open work (prioritized)

- **A3 full decomposition** — only AnalysisEngine is extracted; ObservationCollector,
  RecordingController, PanelStateController, EvidenceService, ActivityBridge + an injectable
  backend interface remain in the QaLens facade.
- **R9-WebP** — v2 gzip tracks + CRC32 shipped; frame WebP still deferred (frames are JPEG).
- **Real-device validation** — A5 watchdog and R10 crash-finalize edge cases (consent dialog
  timing, OS-killed projection service) are compile-verified but not hardware-tested.
- **P2 polish list** — see docs/CODE_REVIEW.md (redaction regex ReDoS surface, percentile
  floor-index, EvidenceBuilder default threshold, unencoded query params, innerHTML-by-convention,
  unauthenticated local backend, hand-rolled peekBodyText vs okhttp 4.12).
- **Deferred UI** — Overview Crashes row, jank pill + sparkline, error-buffer eviction test,
  Robolectric race test, Sentry reference bridge.
- **iOS port** — planned, not started; the .sal/web/backend side is platform-neutral.

## 8. Handover checklist for the incoming agent

1. Read this file, then docs/ARCHITECTURE.md, next.md, docs/replay_backlog.md.
2. Run the verification matrix (section 4) and confirm it matches section 2 before changing anything.
3. Re-read the rules in section 5 before the first commit.
4. For app-integration work (not repo work), use integration_skill.md instead.
