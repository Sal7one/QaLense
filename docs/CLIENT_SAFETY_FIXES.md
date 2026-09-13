# Android client audit fixes — 2026-09-13

This change addresses the fourteen findings from the Kotlin client audit. It prioritizes host-app
behavior, capture privacy, background work and trustworthy outcomes. It does not certify every
Android device, every custom UI or the web/backend readers.

## Findings and resulting behavior

| Audit finding | Fix | Verification |
|---|---|---|
| Coroutine helpers consumed failures in debug and release | Forward the original exception to the current thread's uncaught handler; retain the existing constructor/factory APIs | Core delegation test plus active and no-op handler tests |
| Authorization redaction left the credential after its scheme | Redact the complete authorization line; preserve subsequent headers | Basic, Bearer, custom scheme and repeated-redaction tests |
| Disabling the SDK left capture running | Stop/finalize recording, detach overlays/frame/shake observers, cancel macros and uploads, suspend Room/DataStore observation and watchdog/memory/connectivity collection; reject stale capture callbacks | Device disable-during-recording, blocked upload cancellation, DataStore suspension/resume, ignored capture/events and overlay resume |
| Frame JPEG and screenshot file work blocked main | Serialize frame scaling/JPEG on the recording writer; encode/store screenshots on a bounded worker and atomically publish completed files | Device produces masked PNG/JPEG and a playable archive; source review of worker ownership |
| Response previews waited on streaming bodies | Skip SSE, unknown-length and bodies larger than 64 KiB without opening their source | Throw-on-read streaming fixtures plus unchanged-response tests |
| Imported destinations reused local credentials | Clear auth when scheme/host/effective port changes; imported header-name changes also clear it; snapshot each upload's settings and disable redirects | Core origin tests and real Android preferences import test |
| Replay could expand archives without bounds | Enforce canonical paths, duplicate/entry/expanded-byte limits, bounded nested gzip/text and streaming CRC; clean failed/closed sessions, downsample image decode on IO and lazily render event rows | Five archive tests and device replay of the actual Android producer |
| Screenshots exposed raw pixels and always copied to Photos | Mask password, explicitly hidden and redaction-matched Compose regions before storage; refuse secure windows on every API path; private cache by default; require host opt-in for unmasked video | Device checks central pixels in PNG and JPEG, secure-window refusal and default video macro refusal |
| Inspection could retain the previous route's nodes | Clear old nodes/selection on route change and coalesce refreshes after layout/manual-node changes | Device route change clears old nodes; further same-layout/custom semantics coverage remains below |
| SQL ran on main and consumed all result rows | Dispatch query work to IO, add cancellation, apply a SQL LIMIT for SELECT/WITH and cap returned rows/cells | Device query returns 100 rows; UI exposes cancel |
| Macros reported unsupported/failed actions as passing | Fail unknown commands and malformed assertions; observe recording start/save and screenshot completion; deep links must dispatch successfully | Device invalid-command/assertion/video cases; existing core macro assertions |
| Rejected uploads stuck in Uploading; transient HTTP failures disappeared | Explicit queue-rejection state, per-file in-flight dedupe and persistent retries for 408/429/5xx; bind queued work to its original endpoint | Device saturates two workers plus eight slots and verifies rejection/cancel; loopback 503 retry exhaustion stays queued |
| Periodic memory sampling was never called | Sample from recording ticks, at most every two seconds | Device records periodic memory samples |
| Exported readiness score omitted frame metrics | Pass frame metrics into EvidenceBuilder and session report generation | Exact live/export score comparison including frozen-frame penalty |

## Integration changes

The independent consumer also exposed hidden coroutine types in the public API; both artifacts
now export their coroutine dependency so callers can use StateFlow/Flow/exception helpers. Existing
source integrations continue to compile against both active and no-op artifacts. Keep
Chucker's public interceptor and launcher integration as documented in [OSS integrations](OSS_INTEGRATIONS.md).
QaLens privacy settings do not control Chucker's separate storage.

```kotlin
QaLens.configure {
    enabled = BuildConfig.DEBUG
    captureNetworkBodies = false
    saveScreenshotsToGallery = false
    allowUnmaskedVideo = false
}
```

`QaLens.takeScreenshot(share = false)` now saves in private cache. Enable
`saveScreenshotsToGallery` only when a persistent Photos copy is intended. Use
`Modifier.qaHiddenFromReports()` around sensitive Compose content. Password semantics and text
matched by the configured redaction rules also produce opaque masks; masks are collected before
and after asynchronous capture. This is not OCR or a guarantee for arbitrary pixels. Protect custom
Canvas/View content and other sensitive windows with `FLAG_SECURE`, or mask an enclosing Compose
region. Screenshot annotation text still passes through configured redaction.

`startRecording(video = true)` now requires `allowUnmaskedVideo = true` before Android's consent
dialog can start. MediaProjection records the display without per-node masks; frame recording is
the default. Revoking the video opt-in stops an active recording. Previously saved/shared artifacts
are not retroactively removed by disabling the SDK.

The coroutine helper no longer provides exception suppression, even when its legacy constructor
receives `false`. Handle recoverable failures in the application's own coroutine code. The helper
forwards to the host's uncaught handler in debug and release; the installed debug process handler
can observe that crash before delegating. Vendor-specific thread handlers remain host-owned.

Secret-free `.appsal` export now also removes webhook userinfo, query and fragment, and extra query
parameters. It still contains user-authored macro steps and SQL: review those for embedded literals
before sharing. Importing a different webhook origin requires entering its credential again.
Queue entries created by older SDKs without a recorded destination require an explicit new upload.

## Limits and verification

Android replay limits are 4,096 ZIP entries, 256 MiB per entry, 512 MiB total expanded ZIP data,
16 MiB per expanded text/JSON track, 1 MiB manifest and 40,000 entries per parsed track. Recording
time ranges must be ordered, nonnegative and at most 24 hours. Frames decode to at most 2,048 pixels
on their longest side. Over-limit or CRC-invalid archives fail instead of partially playing.
Large videos within the budgets are checksummed as streams. These limits apply to Android replay;
web/backend expansion budgets are still separate work.

Validation uses JDK 17, Gradle 9.1.0 and the sample's disposable API 36 emulator. The expanded
instrumentation runner uses synthetic data and loopback HTTP only. Run it with the commands in
[CONTRIBUTING.md](../CONTRIBUTING.md). CI also executes the new replay unit suite.

- 184 Kotlin tests: 162 core, 15 active Compose/OkHttp, 2 no-op and 5 replay.
- Debug/release sample builds, instrumented APK, four Android lint gates and release dependency gate.
  Lint has zero errors, 24 warnings and 3 informational findings; no new baseline suppressions.
- Independent consumer debug/release builds and its release dependency gate.
- Expanded device runner: existing retention/Chucker/adapters/crash checks plus client checks above.
- Web reader/CLI and backend regressions remain part of the full verification matrix.

Remaining validation: physical-device video consent/rotation/background/storage-pressure cases;
API 23 screenshot fallback runtime coverage (lint checks its API availability); Room observer and repeated concurrent DataStore
restart stress; semantics changes that do not cause layout; SQL cancellation under very
long writes; interrupted replay import/low-memory stress. SELECT/WITH result limiting cannot bound
the computation cost of aggregates, sorting or arbitrary writes; those run off main and read queries
accept cancellation. Main-thread window capture and semantics traversal remain required Android UI
work. Custom redaction regexes still have no execution-time budget.
