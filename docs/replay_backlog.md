# The `.sal` session format & replay backlog

A `.sal` is a plain ZIP written on-device by `QaLensSessionRecorder` (encoders live in
`qalens-core/QaLensSalFormat.kt` + `QaLensAnalysis.kt`). It is the single artifact of a QA
session: media + synced tracks + precomputed analysis, fully redacted before it touches disk.

## Format v1 (current)

| Entry | Contents |
|---|---|
| `manifest.json` | `formatVersion`, app/build/device context, `startMillis`/`endMillis` (t0/t1), `fps`, `frameIndex` (epoch-ms → `frames/NNNNNN.jpg`), `files`, `counts`, `video` + **`videoStartMillis`** (epoch-ms when MediaProjection actually began — map video position through this, NOT `startMillis`), **`sessionId`** (UUID), `platform`, `sdkInt`, `locale`, `timezone`, `screenWidthDp/HeightDp`, `density`, `fontScale` |
| `summary.json` | capture-time verdict: likely owner + confidence + reasons, penalties, repro steps, expected vs actual |
| `analysis.json` | **schema `qalens-analysis/1`** — precomputed digest: `coverage` (which tracks exist / are empty and why that matters), `stats`, per-`endpoints` aggregates, `screens` visit spans, timestamped `anomalies` (`tMs` relative to t0), `likelyOwner` |
| `for_ai.md` | self-describing guide: file schemas, join rules, coverage rules, tasked analysis brief (dev/QA/management), webhook contract |
| `timeline.json` | `{ts, kind: NAVIGATION\|SCREEN\|NETWORK\|ACTION\|ERROR\|LOG, title, detail, isError}` |
| `network.json` | `{ts, method, url(redacted), status, latencyMs, requestBytes, responseBytes, error}` — no bodies, by design |
| `logs.json` | `{ts, type: LOG\|EVENT\|BREADCRUMB, tag, message}` |
| `state.json` | sampled `{ts, screen, route, featureFlags, dataSources}` |
| `report.txt` | the full human-readable report |
| `frames/*.jpg` **or** `video.mp4` | frame mode: PixelCopy ~2fps, ≤720px wide, JPEG q60; video mode: MediaProjection H.264 |

Rules consumers must follow:
- All `ts` are epoch ms; join any two tracks by comparing `ts`. `anomalies[].tMs` are relative to t0.
- **JSON nulls are real nulls** — `org.json` consumers must use `isNull()` guards (the literal
  string `"null"` from `optString` was a real bug; see `QaLensSalReader.strOrNull`).
- Unknown entries must be ignored (forward compatibility) and missing entries must degrade
  gracefully (`analysis.json.coverage` tells you what's absent and why).
- Frame `ts` keys can repeat a frame file (held screens reuse one JPEG).

## Readers/writers in this repo

- **Writer**: `QaLensSessionRecorder` (frame + video modes), retention keeps newest 5.
- **Android player**: `qalens-replay` (`QaLensSalReader` + `QaLensPlayerActivity`).
- **Web player**: `web/sal.js` (native `DecompressionStream`, STORE + DEFLATE) + `web/app.js`.
- **CLI**: `web/tools/sal_report.js` — markdown/JSON report, exit 1 on failures (CI gate).
- **Demo generator**: `web/tools/make_sample.js` → `web/sample.sal`.
- **Webhook**: `QaLensWebhook` POSTs the file (multipart `file`) + `X-QaLens-*` headers +
  `X-QaLens-Digest` (the file's own `analysis.json.stats`).

## Shipped backlog (history)

- **R1** frame-based recorder + ZIP packaging + share sheet ✅
- **R2** synced tracks (timeline/network/logs/state) + summary ✅
- **R3** Android player + web player ✅
- **R4** retention/storage management, recordings manager ✅
- **R5** opt-in MediaProjection H.264 video + `videoStartMillis` alignment ✅
- **R6** analysis layer: `analysis.json` + `for_ai.md` + richer manifest + webhook upload ✅

## Open backlog (next)

- **R7** chunked/resumable webhook uploads + retry queue for flaky QA-lab Wi-Fi.
- **R8** optional request/response **body capture with strict redaction + size caps** (today: none, by design).
- **R9** `formatVersion: 2` umbrella: gzip JSON tracks, frame WebP, per-entry checksums.
- **R10** crash-handler integration → a `.sal` automatically finalized on crash.
- **R11** web player: side-by-side compare of two `.sal`s (before/after a fix).
