# QaLens Mission Control (web)

A zero-dependency, offline web viewer for `.sal` session recordings produced by QaLens.

## Use it

Just open `web/index.html` in a browser, or serve the folder:

```bash
cd web && python3 -m http.server 8000   # then open http://localhost:8000
```

Drag a `.sal` onto the window (or click **Open .sal**). Everything runs locally — nothing is uploaded.

To explore without a device, click **Watch demo session** (when served over http) or drag
**`web/sample.sal`** onto the window — a bundled 32s demo with real app frames (failing transfer,
score 58, Backend/API, a slow FX call and a mid-session feature-flag flip for the Insights tab).
Regenerate it with `web/tools/make_sample.js` (needs playwright-core on NODE_PATH; renders the fake
app screens with headless Chromium and packs the .sal with a built-in STORE-only ZIP writer).
Appending `?sample` to the URL auto-loads it, and `?t=<seconds>` opens AT that moment
(`?sample&t=24.6` lands right on the failing transfer) — paste these links in tickets.

## What you get

- **Video / frame viewport** with scrubber (error markers inline), filmstrip thumbnails,
  play/pause, step-to-next/prev-event, jump-to-first-error, and **playback speed** (0.5–4×).
  A live overlay shows the current screen name at the playhead.
- **Headline stat bar** — duration, score, requests, failed, slow, events, error logs, screens.
- **Summary** card — release score donut, likely owner + reasons, "why this score", repro steps.
- **Live State** at the playhead — screen, route, feature flags, DataStore/Room snapshots.
- **Six tracks**:
  - **Timeline / Network / Logs** — filterable, searchable, color-coded, expandable rows; with
    *follow playhead* they reveal in sync as you scrub.
  - **Network** adds per-request latency waterfalls and a stats strip (failed / slow / avg / p95 /
    bytes / hosts).
  - **Screens** — visit map derived from the state track, with durations and jump-to.
  - **Insights** — auto-detected anomalies: failed & slow requests, error bursts, feature-flag
    flips mid-session, rapid screen churn, heavy payloads. Each one jumps to its moment.
  - **Report** — the raw redacted full report.
- **Export** — one click copies a Jira/Slack-ready markdown summary of the session.
- **Instant-replay recents** — sessions are cached in **IndexedDB** (cap ~120 MB, oldest evicted),
  so clicking a recent card replays it without re-dropping the file. Toggle/clear in Settings.
- **Settings drawer** — theme, compact density, default speed, autoplay, follow, cache controls;
  every preference persists in localStorage.

## How it works

- `sal.js` reads the `.sal` ZIP with **no library**: it parses the central directory and inflates
  DEFLATE entries with the browser-native `DecompressionStream("deflate-raw")`. Needs a recent
  Chrome / Edge / Safari / Firefox.
- `app.js` renders the UI and keeps the media synced to the tracks.
- **localStorage** holds preferences + recents metadata; **IndexedDB** holds cached session payloads.

Every row in Timeline/Network/Logs/Screens is **clickable and seeks the player to that exact
moment** (forward too — rows ahead of the playhead are dimmed, not hidden). **Theatre mode** and
true **fullscreen** buttons live in the transport. Sessions recorded by current QaLens carry
`analysis.json`; the Insights tab shows those on-device anomalies directly (labeled "recorded on
device") instead of recomputing.

Keyboard: **Space** play/pause · **←/→** step event · **e** first error · **s** speed ·
**f** follow · **t** theatre · **x** fullscreen · **1–6** switch track.

## .appsal App Config editor

Mission Control also edits **`.appsal`** files (per-app QA configs: panel style, webhook, saved
SQL queries, macros, watched prefs). Drop one on the window, click **⚙ .appsal editor** for a
blank config, or open `?appsal` for the bundled demo (`web/sample.appsal`). Edit everything in
forms, then **⬇ Download** `<package>.appsal` and import it on-device
(Control Room → App Config → ⤓ Import). Webhook secrets stay masked (`•••`) in shared files.

## CLI (CI-friendly)

```bash
node web/tools/sal_report.js session.sal           # Jira-ready markdown report
node web/tools/sal_report.js session.sal --json    # machine digest (incl. analysis.json passthrough)
```

Exits **1** when the session contains failures (failed requests / error events) — use it to gate
a pipeline on uploaded `.sal` artifacts.

## Tests

```bash
node web/test/read.test.js
```
