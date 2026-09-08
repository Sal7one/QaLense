# QaLens — Code Review (frank, engineering-first)

Scope: `qalens-core` (models/config/redaction/rules/score/classifier/reports/evidence/.sal
format/analysis/jank/macros), `qalens-compose` (`QaLens` facade, `QaLensWebhook`,
`QaLensSessionRecorder`), the web player (`web/app.js`, `web/sal.js`,
`web/tools/sal_report.js`), and the mock backend (`backend/server.py`). This is a
debug-only QA SDK, so severities are judged against that blast radius: a bug here annoys a QA
engineer or loses a recording — it can't corrupt production data. That said, the SDK is *evidence
for release decisions*, so correctness of the analysis path still matters.

---

## (a) Executive summary

The codebase is in unusually good shape for a fast-moving QA tool. Its two defining ideas — a
single redaction choke-point and a self-describing `.sal` artifact whose `analysis.json.coverage`
section explicitly says *"absence is not evidence"* — are the right instincts and are consistently
executed. The concurrency story in the `QaLens` facade (debounced recompute + main-thread
confinement) is a real fix for a class of race that most Android SDKs ignore. The mock backend is
stdlib-only, deterministic, and well-tested.

**Both items this review originally flagged above P2 are now RESOLVED** (kept below under
strikethrough headers for the record):

- **B15's assertion verbs are wired into the on-device macro runner.** `parseMacroLine` /
  `parseMacroScript` parse `assert`/`if` lines, execute them via `MacroEngine`, and surface
  pass/fail chips in the minimal panel plus `ASSERTION` timeline events — with 35 new core tests.
- **`QaLensWebhook` is now production-shaped (R7 full).** A bounded 2-thread executor, 3-attempt
  retry (no retry on 4xx), chunked/resumable uploads, and an offline retry queue all landed.

Nothing open today rises above **P2**. Verified numbers for this pass: `:qalens-core:test` =
**122 tests / 0 failures**; `node web/test/read.test.js` = **32 assertions** (ALL PASS);
`python3 backend/tests/test_backend.py` = **15 tests** (OK); full Gradle run = **BUILD SUCCESSFUL,
163 tasks**, including `:sample-app:compileReleaseKotlin` no-op parity.

---

## (b) What's genuinely good

- **Redaction is a single choke-point.** `QaLensRedactor` (`qalens-core/.../QaLensRedactor.kt`)
  folds every export through `config.redact`; default rules are ordered most-specific-first
  (JWT before long numbers, cards before plain digit runs) in
  `RedactionRule.defaultRules()` (`QaLensConfig.kt`). `redactUrl` keeps the URL shape but
  strips the query — the right trade-off for network evidence.
- **Honesty-by-design in the AI digest.** `QaLensAnalysis.digest` (`QaLensAnalysis.kt`) emits a
  `coverage.notes` list that tells a consuming AI *what it cannot see* ("empty network.json with
  `networkInterceptorInstalled=false` means blind, not 'no traffic'"). This is the single most
  valuable property of the whole artifact, and it's applied to every track (crashes, frame metrics,
  connectivity, logs).
- **The debounce + confinement fix (A1/A2) is genuinely correct.** `markAnalysisDirty()` coalesces
  bursts into one recompute 250ms later; `runAnalysis()` always runs on the main `Handler`;
  `flushAnalysis()` drains a pending recompute before any export. `logNetwork()`/`pushEvent()`
  post their state writes to main. This eliminates the read-compute-write race structurally rather
  than papering over it (`qalens-compose/.../QaLens.kt`).
- **A3's first extraction landed well.** `AnalysisEngine` (`qalens-compose/.../AnalysisEngine.kt`)
  owns the pure derive-slices pipeline and takes no Android types — it's testable with fixture
  `QaLensUiState`s, and `QaLens.runAnalysis()` delegates to `analysisEngine.analyze`.
- **Upload streaming.** `QaLensWebhook.post` uses `setFixedLengthStreamingMode` and streams the
  file from disk — recordings are never buffered in memory. It also reads `X-QaLens-Digest` from
  the file's *own* `analysis.json.stats`, so the triage header is correct for old recordings too.
- **Zero-dependency .sal reader with correct ZIP strategy.** `web/sal.js` reads the central
  directory for authoritative sizes (local headers may use data descriptors), inflates with native
  `DecompressionStream('deflate-raw')`, and validates `formatVersion` up front (C15).
- **The CLI reuses the browser reader and gates on device anomalies.** `web/tools/sal_report.js`
  requires `web/sal.js` verbatim and its exit code considers `analysis.json.anomalies` failure
  kinds (C14), not just the web-side heuristic.
- **No-op parity is now compile-time AND runtime (A7).** `NoopParityCheck.kt` references every
  public method so a missing no-op twin is a *compile error* at the exact method, and it is now
  **also executed** as a JUnit4 test (`:qalens-noop:testDebugUnitTest`, 1 test) — strictly better
  than reflection alone.
- **The mock backend is a model citizen.** `backend/server.py` is stdlib-only, deterministic (same
  .sal → same verdict), has an auto-refreshing dashboard, and `backend/tests/test_backend.py`
  exercises the full contract end-to-end (15 tests: ping, test-ping, multipart upload, metadata
  parse, detail, byte-for-byte download round-trip, JSON ingest, delete, dashboard, bad upload, and
  the newer hook variants).

---

## (c) Real findings

### ~~P1 — B15's assertion verbs aren't in the on-device macro runner~~ ✅ RESOLVED

> **Resolution:** `parseMacroLine` / `parseMacroScript` now parse `assert`/`if` lines from
> the `.appsal` DSL and execute them through `MacroEngine`; the minimal panel renders pass/fail
> chips and `ASSERTION` timeline events. 35 new core tests cover the evaluator. The original
> finding is kept below for the record.

`qalens-core/.../QaLensMacros.kt` shipped a real `MacroEngine` with `AssertExists`,
`AssertRoute`, `AssertNetwork`, and `If`/conditions, and `QaLens.runMacro(steps)` exposed it.
But the *string-DSL* runner that QA actually uses — `qalens-compose/.../QaLensMacros.kt` (the
`AppSalMacro` executor) — only understood `deeplink/wait/tap/type/record/stop/screenshot/mark`.
Any `assert`/`if` line hit the `else -> "unknown step … (skipped)"` branch and was treated as
success. A tester who authored `assert network no-errors` got a green macro with the assertion
silently dropped.

### ~~P2 — `QaLensWebhook` uses an unbounded thread-per-upload~~ ✅ RESOLVED

> **Resolution (R7 full):** a bounded **2-thread executor**, **3-attempt retry** (no retry on 4xx),
> **chunked/resumable uploads**, and an **offline retry queue** all landed in `QaLensWebhook.kt`.
> The original finding is kept below for the record.

`QaLensWebhook.upload()` called `thread(name = "qalens-webhook") { … }` for every upload (and a
separate thread for `test()`) — no pool, no cap, and each attempt could hold
`connectTimeout=15s + readTimeout=60s`. Rapid re-taps on a flaky network stacked idle threads, and
a single attempt meant an IOException/5xx was surfaced as `Failed`/rejected with no backoff.

### P2 — Custom redaction regexes run unanchored with no timeout on every export

`QaLensConfig.Builder.addRedaction(pattern)` wraps the user string in `Regex(pattern)` and
`RedactionRule.apply` runs `value.replace(pattern, replacement)` — global, unanchored, no
timeout, no iteration cap. A QA engineer's well-meant `"([a-z]+)+"`-style rule is a live ReDoS
foot-gun that fires on every single export path (reports, .sal tracks, URLs, feature flags), and an
over-broad rule silently over-redacts evidence. The default rules are safe and ordered; only the
*user-supplied* surface is unbounded. Consider anchoring defaults, documenting the risk, or capping
the rule count.

### ~~P2 — Percentiles use a floor-index approximation~~ ✅ RESOLVED (2026-09-08)

`QaLensAnalysis`, `JankAnalyzer`, and `NetworkHealthEngine` now use one internal core
nearest-rank helper: `ceil(percent * count / 100) - 1` as the zero-based index, calculated
with integer arithmetic. For samples 1–20, p95 is 19; for 1–100, p95/p99 are 95/99.
Small samples can correctly return the maximum. Empty input still returns zero.
Seven regression tests cover boundaries and all consumers, preserving their existing
sample populations (network health excludes HTTP failures; the digest includes measured
HTTP failures but excludes transport errors). No public API or `.sal` schema change.

### P2 — `EvidenceBuilder` default `slowThresholdMs = 2000L` (drift risk only)

`EvidenceBuilder.build(… slowThresholdMs: Long = 2000L)` hard-codes a second default
(`QaLensEvidence.kt`). `QaLens.evidenceBundle()` **does** pass `cfg.slowNetworkThresholdMs`, so
the on-device path is correct — this is only a hazard for future direct callers of
`EvidenceBuilder.build` (e.g. the no-op, tests) that forget the arg and silently drift from the
configured threshold. No action needed today; a one-line comment would prevent the future foot-gun.

### P2 — `QaLensWebhook.buildUrl` appends user query params unencoded

The metadata params are `URLEncoder.encode`d, but the user's `webhookParams` string is appended
raw (`parts += it`). A param like `team=QA & Release` breaks the query string. This is a
*de facto* documented contract (users supply `team=payments&pipeline=nightly`, pre-encoded) — it
works as designed but will bite anyone who pastes a space or a stray `&`.

### P2 — `web/sal.js` trusts central-directory sizes (fine for trusted files)

`readCentralDirectory()` reads `compSize`/`uncompSize`/`localOffset` and
`unzip()` slices `u8.subarray(dataStart, dataStart + compSize)` with no bounds check against the
buffer. A malformed/fuzzed .sal can throw `RangeError` mid-read (caught at the `SAL.read`
boundary by the caller). Acceptable for files QA drops in by hand; note it if the player ever loads
untrusted archives.

### P2 — `web/app.js` `innerHTML` is `esc()`-disciplined, but only by convention

The pattern is consistently followed today — `renderRecents()`, `renderScrubMarks()`
(`title="${esc(...)}"`), `networkSubstats()` (`esc(ep.endpoint)`), `statusPill()`
(`esc(code)`) all escape user-derived strings before interpolation. The risk is future: any new
`innerHTML` that interpolates a `NetworkEvent.url`, log `message`, or `detail` raw is an
XSS sink in a page that renders attacker-influenced input (URLs/logs from a recording). Worth a
lint rule or a review checklist, not a rewrite.

### P2 — Mock backend is unauthenticated and CORS-open (correct for its purpose)

`backend/server.py` sends `Access-Control-Allow-Origin: *`, has no auth on any route, and
`DELETE /api/uploads/<id>` is unauthenticated. Anyone who can reach the port can read/delete
uploads. This is *fine and intended* for a local dev mock — but it must never be pointed at a
real endpoint or exposed beyond localhost, and the README should say so.

### P2 — Connectivity "signal strength" is a bandwidth heuristic

`QaLensConnectivity.snapshot()` maps `linkDownstreamBandwidthKbps` to 0–4 "bars" (API 29+), not
real `SignalStrength` dBm. It's a reasonable proxy for "is the pipe usable" and the comment says
so, but it's mislabeled as signal strength in the UI — a WiFi network can show 4 bars with no
internet. Minor, but it feeds a classifier that makes release decisions.

### P2 — Interceptor hand-rolls a non-consuming `peekBodyText()` (okhttp 4.12.0)

`ResponseBody.peekBody(long)` is absent in okhttp 4.12.0, so the interceptor implements its own
non-consuming `peekBodyText()` to read response-body size without disturbing the stream. It's a
reasonable workaround and worth keeping an eye on: it duplicates okhttp's own semantics, so a future
okhttp bump that *does* expose `peekBody` should switch back to the library version rather than
maintaining two body-reading paths.

### Note (docs lag, not a defect) — A5's watchdog is already in the code

`QaLensSessionRecorder.kt` ships a `WATCHDOG_TIMEOUT_MS = 10_000L` watchdog that auto-stops a
recording if no frame lands for 10s (and surfaces it via `pushError`), and `onVideoComplete`
does the "stop came from outside QaLens" bookkeeping so `isRecording` can't stick true.
`next.md` tracks A5 as **partial (🚧)** — the remaining piece is the projection-service
`onTaskRemoved` binding (the service's `onDestroy` already calls `stopRecording()`).

---

## (d) What to do next (prioritized, mapped to next.md)

| # | Priority | Task | Maps to |
|---|---|---|---|
| 1 | P1 | Finish A5: `onTaskRemoved` → `onVideoComplete(ok=false)` in the projection service | A5 (remainder) |
| 2 | P1 | Continue A3: extract the remaining services (`ObservationCollector`, `RecordingController`, `EvidenceService`, …) | A3 (full) |
| 3 | P1 | Global search across tracks | B14 |
| 4 | P2 | Anchor/document user `addRedaction` regexes; cap rule count; note ReDoS risk | — |
| 5 | P2 | Bounds-check `web/sal.js` central-directory sizes | C15 hardening |
| 6 | P2 | ✅ Shared nearest-rank percentiles with seven regression tests | Resolved 2026-09-08 |
| 7 | P2 | Revisit `peekBodyText()` when okhttp exposes `peekBody` | — |
| 8 | P2 | Optional body capture w/ redaction + size caps | R8 |
| 9 | P2 | `formatVersion: 2` (gzip tracks, WebP frames, checksums) | R9 |
| 10 | P2 | Auto-finalize a `.sal` on crash | R10 |
| 11 | P2 | Web side-by-side compare of two `.sal`s | R11 |
| 12 | P2 | Deferred Phase-1 polish (Overview crash/jank rows, error-cap test, sample crash buttons) | deferred |

**Net:** no release-blocking defects, and the two non-P2 items originally flagged here are now
resolved. The highest-leverage remaining work is finishing A5's lifecycle binding and A3's full
decomposition — the rest is hardening that pays for itself only as the SDK moves toward production
use.
