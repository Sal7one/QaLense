# QaLens web replay and CLI

The primary viewer is `index-v2.html`, with `app-v2.js` and `styles-v2.css`. The classic
`index.html`/`app.js` viewer remains supported and contains the `.appsal` configuration editor.
Both share `sal.js`. Start at [the project handover](../HANDOVER.md) for contributor context.

## Open a recording

From the repository root:

```sh
python3 -m http.server 8100 --bind 127.0.0.1
# Open http://127.0.0.1:8100/web/index-v2.html
```

Drop a `.sal` onto the viewer or use its file picker. Offline file:// drag-drop works without a
server; buttons that fetch the bundled sample need HTTP. `demo.sh quick` starts the local demo
services and opens v2. `?sample&t=24.6` loads the synthetic sample at a failing-transfer moment.

Playback has synced media, timeline/network/log/state views, insights, report/AI brief, bookmarks,
filmstrip, event seeking, fullscreen and comparison. The interfaces differ; changes affecting shared
behavior need checks in both viewers. Classic keyboard shortcuts include Space, arrows, `e`, `s`,
`f`, `t`, `x`, `m`, `?` and `1–7`; use each viewer's help for its current controls.

## Data, privacy and compatibility

LocalStorage stores preferences; IndexedDB supports cached recents and can be cleared in settings.
Reading an archive does not upload it. Explicit **Send to backend** uses the configured Backend URL:
raw archives post as multipart `file` to `/webhook`; summary-only sessions post JSON to `/api/ingest`.
The bundled [backend](../backend/README.md) is an unauthenticated development mock.

The reader parses ZIP locally using native `DecompressionStream`, then recognizes gzip-compressed
JSON inside ZIP entries. Both v1 and v2 are supported; newer versions are refused. The bundled
`sample.sal` is v1, so it alone cannot prove Android v2 compatibility. v2 nested-compression
fixtures cover that path. ZIP and listed manifest CRC mismatches, oversized expansion and missing
listed entries are rejected. The reader has per-entry and total expansion budgets; see
[the SAL contract](../docs/SAL_FORMAT.md) for exact limits and remaining differences.

Recorded text and pixels have different privacy boundaries. Review artifacts before sharing;
Android masks known sensitive Compose regions but opt-in video is unmasked. The web viewer does
not provide automatic OCR/redaction of arbitrary recorded media.

## App configuration editor

Open the classic `index.html` and choose **.appsal editor**, drop a `.appsal`, or use `?appsal`.
Edit panel style, webhook settings, saved SQL, macros and watched preferences, then download JSON
for **Control Room → App Config → Import**. This is configuration, not a session archive.

A masked webhook header placeholder is not a guarantee that the file has no secrets. Macro steps,
SQL and other user-entered fields can contain literals. Android secret-free export also omits
webhook userinfo/query/fragment and extra params; do not assume every web editor field follows that
same policy. [Client migration](../docs/CLIENT_SAFETY_FIXES.md) explains credential isolation.

## CLI

```sh
node web/tools/sal_report.js session.sal
node web/tools/sal_report.js session.sal --json
node web/tools/sal_report.js session.sal --for-ai
node web/tools/sal_report.js candidate.sal --compare baseline.sal
```

Exit 1 means observed failures or a comparison regression. Exit 2 means invalid input or reported
partial evidence without an observed failure, including partial comparisons. Otherwise the current
rules return 0; this does not establish full instrumentation coverage. `--json` keeps diagnostics
off stdout. The built-in demo intentionally produces exit 1.

## Verification and files

```sh
node web/test/read.test.js
```

The last client-fix baseline passed 58 assertions, including Android-style gzip-inside-DEFLATE,
reader validation, retention warnings and CLI behavior. Dated verification lives in
[HANDOVER.md](../HANDOVER.md); [next.md](../next.md) owns open work.

`web/tools/make_sample.js` regenerates the synthetic demo using headless Chromium and
`playwright-core` supplied through NODE_PATH. It is not needed for normal replay or reader tests.
Keep both viewers usable over file:// drag-drop and HTTP; avoid absolute fetch paths.
