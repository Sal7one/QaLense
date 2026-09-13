# SAL session archive contract

Current producer baseline: `7a05bee`. A `.sal` is a ZIP containing media, structured observations
and derived reports. A `.appsal` is unrelated JSON for app/tester configuration; do not feed it to
the session reader. Open work lives only in [next.md](../next.md).

## Versions and encoding

The Android producer writes `formatVersion: 2`; readers retain v1 support and reject versions above
2. Missing legacy version fields are treated as v1 by current readers. Do not bump versions for
additive optional coverage metadata; coordinate breaking changes across every producer/consumer.

- v1 uses plain JSON/text tracks in a ZIP; `manifest.files` can contain filenames as strings.
- v2 gzip-compresses JSON entries, **including manifest.json**, inside ordinary ZIP entries. The
  Android ZIP writer uses DEFLATE, so decoding requires ZIP inflation followed by gzip detection
  on JSON bytes. v2 is not “gzip instead of ZIP compression.”
- v2 `manifest.files` contains `{name, crc32, compressed}` objects. CRC32 is lowercase, eight-digit
  hexadecimal over decoded JSON bytes or the original binary/text bytes. The manifest excludes
  itself from that list to avoid a self-referential checksum.
- Media stays binary; reports/AI brief stay text. Reader support must include both STORE and DEFLATE
  ZIP entries and gzip detection by magic bytes, not filename assumptions alone.

## Entries

| Entry | Content |
|---|---|
| `manifest.json` | Format version; app/build/device context; session ID; platform; start/end epoch milliseconds; fps; frame index; file list/counts; optional video filename and video start time; screen/locale/timezone metadata |
| `summary.json` | Captured score, likely owner, confidence/reasons, penalties and reproduction summary |
| `analysis.json` | Schema `qalens-analysis/1`: coverage, statistics, endpoint aggregates, screen spans, anomalies and likely-owner signals |
| `for_ai.md` | Generated guide to the archive, schemas, joins, coverage and an analysis brief |
| `timeline.json` | Timeline observations with `ts`, `kind`, `title`, `detail`, `isError` |
| `network.json` | Completed requests: `ts`, method, redacted URL, status, latency, sizes, error and optional bounded body previews |
| `logs.json` | `ts`, type, optional tag and message |
| `state.json` | Sampled screen/route, flags and registered application data |
| `crashes.json` | Captured crash/coroutine/ANR evidence, including context |
| `performance.json` | Frame timing samples and jank-related evidence |
| `connectivity.json` | Observed connectivity transitions |
| `memory.json` | Sampled managed/native memory and trim context |
| `marks.json` | Explicit QA bookmarks and their metadata |
| `report.txt` | Human-readable report |
| `frames/*.jpg` or `video.mp4` | JPEG frame sequence or MediaProjection H.264 video |

This table is a navigation aid, not a replacement for the field encoders in
`qalens-core/src/main/kotlin/com/qalens/QaLensSalFormat.kt` and `QaLensAnalysis.kt`. Check those
before implementing another reader. Optional/legacy tracks may be absent; required structural
entries must not be silently invented. Unknown optional entries should not break compatible readers.

## Time, media and privacy

Observation `ts` and manifest start/end fields are epoch milliseconds. Analysis anomaly `tMs`
values are relative to session start. `videoStartMillis` maps video position to observations;
do not assume it equals the time the tester first requested consent. Frame index keys are epoch
milliseconds and values are relative filenames; held frames may be reused.

Android frames are approximately 2 fps, scaled to at most 720 pixels wide, JPEG quality 60, bounded
by the recorder's 600-frame/five-minute limits. They cannot preserve every animation glitch.
Video is host-opt-in and requires Android consent. Recorded pixels are not universally text-redacted:
Compose frame masks cover known sensitive regions; full-display video has no per-node masking.
See [client privacy](CLIENT_SAFETY_FIXES.md).

JSON nulls are real nulls: Android `org.json` readers need `isNull()` handling to avoid displaying
literal `"null"`. Text tracks pass configured redaction before export; absence of a matching rule
is not proof a value is non-sensitive.

## Evidence completeness and errors

[Recording retention](RECORDING_RETENTION.md) defines `coverage.recording`, entry/estimated-byte
budgets, dropped callbacks and the keep-earliest policy. Dashboard clearing must not change recorded
history. Explicit bookmark removal is accounted separately. Missing coverage in older archives
means unknown retention, not loss-free capture.

Reader behavior is currently different:

| Consumer | Current boundary |
|---|---|
| Android replay | Canonical path and duplicate checks; 4,096 entries; 256 MiB/entry and 512 MiB expanded ZIP total; 16 MiB expanded text/JSON tracks; 1 MiB manifest; 40,000 parsed track observations; rejects CRC mismatches and invalid/over-24-hour time ranges; cleans failed/closed imports |
| Shared web reader / CLI | Validates structure/version and decodes Android v2 layering; currently warns and continues on per-entry CRC mismatch; Android-equivalent expansion budgets remain open |
| Python backend | Rejects malformed/unsupported archives and avoids healthy verdicts for missing/partial evidence; Android-equivalent byte/CRC policy is not established |

CLI exits: 1 for observed failures (or a comparison regression); 2 for invalid input or disclosed
partial evidence without an observed failure (also partial comparisons); otherwise 0 under its
current rules. Exit 0 is not a claim that every relevant path was instrumented. Known failures take
precedence over partial-evidence status. `--json` keeps diagnostics off stdout.

## Implementations and regressions

- Android writer: `qalens-compose/src/main/java/com/qalens/QaLensSessionRecorder.kt`.
- Android reader/player: `qalens-replay/src/main/java/com/qalens/replay/`.
- Shared browser reader: `web/sal.js`; viewers: `web/app-v2.js` and `web/app.js`.
- CLI: `web/tools/sal_report.js`; sample generator: `web/tools/make_sample.js`.
- Backend: `backend/server.py`; upload contract: [backend guide](../backend/README.md).
- Tests: core format/analysis/journal tests, replay `BoundedArchiveTest`, device
  `RecordingRetentionInstrumentation`, `web/test/read.test.js`, `backend/tests/test_backend.py`.

Use real Android-produced files in compatibility tests. Synthetic plain-JSON or STORE-only fixtures
alone missed the previous gzip-inside-DEFLATE regression. The built-in `web/sample.sal` is v1;
passing that demo alone does not validate v2.
