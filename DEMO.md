# QaLens — the mobile release-evidence SDK (showcase + demo guide)

> **One line:** QaLens turns a manual QA session into a redacted, AI-ready bug bundle — and a
> replayable `.sal` recording — with zero Android Studio, then ships it to your analysis backend
> through hooks you can stand up in ten seconds.

This article is the fastest possible tour: what QaLens is, the one-command demo, the hooks, and a
scripted walkthrough that exercises **every feature** — on the web in 60 seconds, or on a device in
about five minutes.

---

## 1. The 60-second demo (no Android, no install)

```bash
./demo.sh            # starts the mock webhook backend + the web player, opens both
```

What just happened:

- `http://127.0.0.1:8000/` — the **mock backend dashboard**. It accepts every hook QaLens can send,
  stores uploads, parses them, and returns a mock AI verdict.
- `http://127.0.0.1:8100/web/landing.html` — the **showcase landing page** (web player, mobile SDK, backend, hooks, one-command quick start).
- `http://127.0.0.1:8100/web/index.html?sample&t=24.6` — **Mission Control**, the web player, opened on a bundled
  demo recording *exactly at the failing transfer* (score 58, likely owner Backend/API).

Now click around: scrub the timeline (every row seeks the video), switch tracks with `1`–`7`
(`7` = AI Brief), press `m` to star a moment, press `?` for shortcuts, open ⚙ Settings → set
**Backend URL** = `http://127.0.0.1:8000` → **⇪ Send to backend** → watch the upload appear on the
dashboard with a severity + likely-owner verdict. That is the frontend → backend hook, live.

```bash
./demo.sh curl        # then watch it drive ALL the hooks from the terminal
```

The `curl` run shows the exact wire contracts:

1. **Test ping** — `POST /webhook` with `{"qalens":"webhook-test"}` (what the app's *Test endpoint*
   button sends).
2. **Mobile webhook** — multipart `.sal` upload with `X-QaLens-App/-Env/-User/...` headers.
3. **Frontend ingest** — the web player's JSON summary to `/api/ingest`.
4. **Chunked/resumable upload (R7)** — a large `.sal` split into 1 MB chunks with CRC32, status
   polling, and resume — `scripts/demo_chunked.py`.
5. **What the backend knows** — the uploads list, each with parsed score, owner, and verdict.

---

## 2. The full mobile tour (~5 minutes)

Prerequisites: Android SDK + an emulator/device, JDK 17, `adb` on PATH.

```bash
./demo.sh android     # build, install, adb reverse, launch — then follow its printed script
```

The scripted **wow flow**:

1. Open **Account #2** via deep link, go to **Transfer**, enter 1,500, **Confirm Transfer** —
   the fake backend returns `POST /transfer → 500` twice.
2. Open the **Control Room** (the second launcher icon) → **Webhook** →
   `http://127.0.0.1:8000/webhook` → **Test endpoint** → the mock backend answers instantly.
3. Tap the **QA bubble** → **Overview**: the release score has dropped and *Likely Owner =
   Backend/API (HIGH)* — with reasons.
4. **Bug Bundle → Copy Jira Bug** → paste. Build, device, repro steps, network failure,
   accessibility, flags, app data — all redacted.
5. **Record Session** → reproduce → **Stop** → the `.sal` lands in the recordings list →
   **⇪ webhook** → the dashboard shows the upload and the mock AI verdict.
6. In the **Settings** screen of the sample app, the new *QaLens Demos* section lets you feed the
   pipeline on demand: failing transfer, ⭐ mark moment, BUG bookmark, 10-second recording,
   business event, **coroutine crash**, **ANR**, and a real **crash** — then watch each one appear
   in the panel's Overview/Logs and in the next `.sal`.

---

## 3. What QaLens actually is

```text
          observe                  analyze                     present
  ┌─────────────────┐     ┌──────────────────────┐    ┌────────────────────────┐
  │ semantics scan  │     │ rules → warnings     │    │ floating QA panel      │
  │ navigation      │ ──▶ │ score + classifier   │ ──▶│ Jira/Slack/GitHub copy  │
  │ network (OkHttp)│     │ timeline + repro     │    │ annotated screenshots  │
  │ Timber / events │     │ build safety         │    │ .sal recording → player│
  │ data sources    │     └──────────────────────┘    └────────────────────────┘
  └─────────────────┘              │                              │
                                   ▼                              ▼
                    everything redacted at every export boundary — plus hooks:
                    mobile → POST /webhook      web player → POST /api/ingest
                    (multipart .sal)            (JSON session summary)
```

QaLens is a **debug-only** Android SDK for Jetpack Compose apps. Release builds link a no-op with
the identical API, so it costs nothing in production. It never invents data it did not observe —
the timeline is built only from navigation, network, Timber logs, and explicit events — and every
byte that leaves the device passes one redaction choke-point (JWTs, bearer tokens, cookies, emails,
cards, phone numbers, long IDs).

**Integration is four lines and stops where you want:**

```kotlin
dependencies {
    debugImplementation(project(":qalens-compose"))
    debugImplementation(project(":qalens-navigation-compose")) // auto route tracking
    debugImplementation(project(":qalens-replay"))             // the .sal player
    releaseImplementation(project(":qalens-noop"))             // release = nothing
}
```

Level 0 is *zero code*: the bubble, shake-to-open, notification, and Control Room install
themselves. `QaLensRoot { App() }`, `QaLensNavHost(...)`, and `QaLensOkHttpInterceptor()` each add
one more evidence layer; enrichment APIs (feature flags, contracts, deep-link scenarios, macros,
bookmarks, crash bridges, data sources) are all one-liners.

---

## 4. The `.sal` artifact — a bug report that replays itself

A `.sal` is a ZIP: frames or H.264 video, synced `timeline/network/logs/state` tracks, a capture-
time `summary.json`, a precomputed **`analysis.json`** digest (coverage, stats, per-endpoint
aggregates, timestamped anomalies, likely owner), a **`for_ai.md`** that explains every schema to
whatever AI ingests it, and a human `report.txt`. Format v2 adds gzip tracks and per-entry CRC32
checksums; v1 files remain fully readable.

Three players, one format: on-device (**QaLens Player**), in the browser (**Mission Control**, the
zero-dependency web player), and in CI (`node web/tools/sal_report.js file.sal` — exits 1 on
failures, so a pipeline can gate on uploaded artifacts).

---

## 5. The hooks — same backend, three clients

```text
  Android Control Room ──multipart .sal + X-QaLens-* headers──▶  POST /webhook
  Web player (Mission Control) ──same multipart──▶               POST /webhook
  Web player (no file) ──JSON summary──▶                        POST /api/ingest
  Health check ......................▶                          GET  /ping
  Dashboard / uploads / verdicts ....▶                          GET  /  ·  /api/uploads
```

The mobile hook retries flaky Wi-Fi (3 attempts, backoff, no retry on 4xx), queues uploads while
offline, and switches to **chunked/resumable** transfer for large recordings. The mock backend is
stdlib-only Python (`backend/server.py`) — run it anywhere, then swap its deterministic verdict for
your real AI call. It ships with 11+ end-to-end tests (`python3 backend/tests/test_backend.py`).

---

## 6. Feature tour (what to look at while you are in there)

| Surface | What it proves |
|---|---|
| **Overview** | build safety, score, likely owner, crashes, jank — at a glance |
| **Bug Bundle** | one-tap Jira / Slack / Repro / GitHub / Linear / Markdown, all redacted |
| **Network** | latency waterfalls, endpoint aggregates, connectivity chips |
| **Screen Health** | per-screen scores + live contract checks |
| **Tools** | deep-link scenarios, macros (with assertions), saved SQL |
| **Inspect / Tag** | semantics bounds + warnings; every test tag drawn on its component |
| **Logs** | filterable, level chips, duplicate collapsing |
| **Record** | frames or HD video → `.sal` → share / replay / webhook |
| **Web player** | synced tracks, filmstrip, marks, AI Brief, compare two sessions, theatre mode |
| **CLI** | `sal_report.js` markdown / `--json` / `--for-ai` / `--compare baseline.sal` |

---

## 7. Prove it: every test suite

```bash
./demo.sh test       # or, individually:
node web/test/read.test.js                       # web .sal reader regression (32 assertions)
python3 backend/tests/test_backend.py            # mock backend end-to-end (15)
./gradlew :qalens-core:test                      # 69+ pure-engine unit tests
./gradlew :sample-app:compileReleaseKotlin       # THE no-op parity proof
node web/tools/sal_report.js web/sample.sal      # CI gate — exits 1 on the demo failure
```

(Build note: use JDK 17 — the repo pins Kotlin 2.0.21, which cannot parse newer JDKs. If the
Gradle wrapper is downloading slowly, any cached Gradle 9.x binary works.)

---

## 8. Where things are

```text
qalens-core/            pure engines: rules, score, classifier, redaction, .sal encoders, AI digest
qalens-compose/         the overlay, panel, recorder, Control Room, webhook client, crash handler
qalens-replay/          the on-device .sal player
qalens-noop/            the release-safe mirror (API parity is compile-enforced)
sample-app/             the banking demo — Settings → QaLens Demos drives every feature
web/                    Mission Control: zero-dependency player + sal_report CLI
backend/                the mock webhook backend + dashboard + e2e tests
demo.sh                 this tour, one command
docs/CODE_REVIEW.md     the frank engineering review (findings + next steps)
next.md                 the living backlog (most items now shipped)
```

---

## FAQ

**Does it upload anything by itself?** No. Nothing leaves the device unless QA shares, copies, or
configures a webhook URL. Reports and `.sal` tracks are redacted by default.

**What if my screen has no test tags?** QaLens scores *missing* tags as a testability finding —
that is signal, not noise. `Modifier.qaTag/qaName` fixes it in one line.

**Can I point it at my real backend?** Yes — the hook contract is two endpoints and a few
headers; the mock backend documents it exactly. Replace the verdict function with your LLM call.

**Does it slow my app down?** Debug builds only; release builds are the no-op artifact.

**Where do I file bugs found during this demo?** Run the failing-transfer flow, then Bug Bundle →
Copy Jira Bug — the report writes itself.
