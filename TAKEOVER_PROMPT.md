# Takeover Prompt — for the AI agent continuing QaLens

> This prompt contains a historical matrix. Use HANDOVER.md’s current baseline and
> docs/RELIABILITY_AUDIT.md, docs/OSS_INTEGRATIONS.md and CONTRIBUTING.md for the current
> verification commands. Chucker-as-source was removed; the supported path uses both interceptors.

> Paste this whole file into a fresh agent/session. It is self-contained: identity, first
> actions, environment, rules, the open work, and a definition of done for your first session.

---

You are taking over development of **QaLens**, a debug-only mobile release-evidence SDK for
Android Jetpack Compose apps, located at **/Users/salehalanazi/Desktop/QaLense** (a public GitHub
repo — treat every commit as public).

The product: a QA SDK that observes a running app (semantics scan, navigation, OkHttp metadata,
Timber logs, crashes/ANRs, jank, connectivity, memory), produces redacted bug bundles
(Jira/Slack/GitHub/Linear/Markdown), and records sessions into portable `.sal` archives (ZIP:
frames-or-video + synced tracks + precomputed `analysis.json` + `for_ai.md`) that replay in a
zero-dependency web player, an on-device player, and a CI CLI. A stdlib-only mock Python backend
receives webhook uploads from the Android app and the web player and returns a deterministic mock
AI verdict. Release builds link a no-op with an identical API.

## Your first actions (in order)

1. Read `HANDOVER.md` (the authoritative handover), then `docs/ARCHITECTURE.md`, `next.md`, and
   `docs/replay_backlog.md`.
2. Reproduce the verification matrix below and confirm every row matches — BEFORE changing
   anything. If a row fails, fix the environment first, not the code.
3. Read `docs/CODE_REVIEW.md` to see the frank findings and what has already been resolved.

## Verification matrix (must be green before you start, and after every change)

```bash
cd /Users/salehalanazi/Desktop/QaLense

# Kotlin (JDK 17 REQUIRED — Kotlin 2.0.21 cannot parse JDK 25/26).
# The wrapper pins 9.0.0 but its download is slow; a cached 9.1.0 binary is verified to work.
GRADLE_BIN=$HOME/.gradle/wrapper/dists/gradle-9.1.0-bin/9agqghryom9wkf8r80qlhnts3/gradle-9.1.0/bin/gradle
# Serialize parallel builds with a mkdir lock:
# while ! mkdir /tmp/qalens-gradle-lock 2>/dev/null; do sleep 5; done   (then rmdir when done)
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home $GRADLE_BIN :qalens-core:test :qalens-noop:testDebugUnitTest :qalens-compose:compileDebugKotlin :qalens-replay:compileDebugKotlin :sample-app:compileDebugKotlin :sample-app:compileReleaseKotlin
# Expected: core 115 tests / 0 failures · noop 1 parity test · BUILD SUCCESSFUL

node web/test/read.test.js                 # ALL PASS (32 assertions)
python3 backend/tests/test_backend.py      # 15/15 OK
node web/tools/sal_report.js web/sample.sal   # exit 1 is CORRECT (the demo has failures)
./demo.sh quick; ./demo.sh kill            # backend + v2 player + landing page must come up
```

## Hard rules (violating any of these is a regression)

1. **No-op parity** — every public `QaLens` symbol needs a `qalens-noop` twin; parity is
   compile-enforced AND runtime-executed (`NoopParityCheckTest`; that module is JUnit4-only).
2. **Redaction is a choke-point** — nothing leaves the device without `QaLensRedactor` /
   `config.redact`; new exports route through it.
3. **`.sal` honesty** — `analysis.json.coverage` must state what is missing and WHY (not
   installed / disabled-by-config / declared network sources). Never infer health from absent tracks.
4. **Schema discipline** — `.sal` formatVersion 1 and 2 are both supported by all readers
   (Android, web, CLI); >2 is refused loudly. Breaking changes update writer + readers + CLI +
   spec together.
5. **Public repo hygiene** — no secrets, no real user data, no `local.properties` / build dirs /
   `backend/data` in commits. Demo data uses example.com. Scan with git grep before committing.
6. **Web player dual-track** — v2 (`index-v2.html` + modular `app-v2.js` + `styles-v2.css`) is
   the primary viewer; classic (`index.html` + `app.js`) is kept for the `.appsal` editor and as
   a fallback. Feature fixes generally belong in BOTH. Files must keep working over file://
   (drag-drop) and over http (repo-root serving via demo.sh).
7. **Pure logic goes in qalens-core** (no Android imports) so it stays unit-testable.

## Where the code lives (cheat sheet)

- Engines (score, classifier, redaction, reports, `.sal` encoders, AI digest, macros):
  `qalens-core/src/main/kotlin/com/qalens/`
- Overlay, panels, recorder, webhook client, Chucker coexistence, capture flags (B17):
  `qalens-compose/src/main/java/com/qalens/`
- Web player v2 modules: `web/app-v2.js` (util / prefs / store / media / derive / render / wiring)
- Mock backend: `backend/server.py` (+ chunk protocol, dashboard, verdict)
- Demo: `demo.sh`, `DEMO.md`, `web/landing.html`, `web/integration.html`, sample-app
  Settings → QaLens Demos

## Open work — start with ONE of these (in priority order)

1. **A3 — decompose the QaLens facade.** Only AnalysisEngine is extracted. Extract
   ObservationCollector, RecordingController, PanelStateController, EvidenceService, and
   ActivityBridge behind an injectable internal backend interface. No public API change; verify
   with the full matrix.
2. **P2 list from docs/CODE_REVIEW.md** — the redaction-regex ReDoS surface, percentile
   floor-index, EvidenceBuilder default threshold, unencoded webhook query params, innerHTML-
   by-convention, unauthenticated local backend, and the hand-rolled peekBodyText vs okhttp 4.12.
3. **R9-WebP** — frame WebP encoding in the `.sal` v2 writer (gzip tracks + CRC32 already
   shipped).
4. **Deferred UI** — Overview Crashes row, jank pill + sparkline, error-buffer eviction test,
   Robolectric race test, Sentry reference bridge.
5. **iOS port planning** — the `.sal` / web / backend side is platform-neutral; the Android SDK
   is the only capture side today.

Pick one item, implement it with tests, update `next.md` and `docs/CODE_REVIEW.md` statuses,
run the full matrix, and commit with a clear message.

## Definition of done for your first session

- Verification matrix green on a clean tree.
- One backlog item shipped with tests (Kotlin items need core tests; web items need
  `read.test.js` additions or equivalent).
- `next.md` / `docs/CODE_REVIEW.md` / `CHANGELOG.md` updated to match reality.
- Commits contain no secrets or build artifacts (git status clean, forbidden paths absent).
- Report: what you shipped, the exact commands and results, and what you left for the next agent.
