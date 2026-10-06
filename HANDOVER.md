# QaLens engineering handover

Updated 2026-10-06. Read this before changing the repository. [ONBOARDING.md](ONBOARDING.md) is
the user/integrator overview; [AI integration](docs/AI_INTEGRATION.md) is the host-agent work order;
[next.md](next.md) is the only current backlog;
[CONTRIBUTING.md](CONTRIBUTING.md) owns portable build/device commands. This handover records
the current engineering baseline, including Compose host compatibility, overlay navigation,
Control Room app values and recording-control fixes below. Git and CHANGELOG retain earlier history.
Check the working tree, remote branches and CI before assuming publication or validation state.

## Product and priorities

QaLens is an MIT-licensed Android Jetpack Compose QA evidence SDK. QA/debug builds capture context;
production builds select `qalens-noop`. It provides a floating tester sheet, advanced diagnostics,
Control Room, Compose inspection, observed network/log/data/crash evidence, screenshots, reports
and portable `.sal` recordings. Android and web players replay files; a Node CLI reports/compares.

The Python desktop combines existing web replay with adb pairing, a component workbench, saved
files, trusted processing pipelines and optional phone tools. The separate Python backend is an
unauthenticated loopback upload mock with deterministic verdicts. No hosted AI, multi-tenant
company service, Maven Central publication or iOS capture is claimed.

The user wants a free, professional tool that companies can integrate easily. Prioritize host-app
reliability, clear tester controls, honest evidence coverage and supported OSS contracts. The user
reported overlay/Repro ANRs under endless logs/network traffic, without an original trace. Bounded
queues, batched dashboard publication, background evidence work and lazy rows passed synthetic
load checks; real-host responsiveness remains to validate. The newer-host HD failure also remains
undiagnosed. Do not infer that emulator capture proves that consuming app works.

## Current source map

| Area | Entry points |
|---|---|
| Public SDK and analysis | `qalens-compose/.../QaLens.kt`, `AnalysisEngine.kt`; pure policies/models in `qalens-core/` |
| Host roots and overlay | `QaLensActivityInstaller`, `QaLensInspectionWindows`, `QaLensOverlayHost`, `QaLensInspectorPanel`; automatic API 29+ dialog/popup roots and explicit legacy hooks |
| Capture and clips | `QaLensSessionRecorder`, `QaLensProjectionActivity`, `QaLensProjectionService`, `QaLensVideoClip`, `QaLensSystemChip` |
| Evidence ownership | Core `RecordingLifecycle`, `RecordingWindow`, `RecordingEvidenceStore`, `RecordingClipWindow` |
| Device/settings/transport | `qalens-android/`; active `QaLensLocalBridge`, `QaLensWebhook` and OSS adapters |
| Optional Android tools | `qalens-navigation-compose/`, `qalens-replay/` |
| Production API mirror | `qalens-noop/`, `NoopParityCheck`; independent `integration-tests/consumer/` |
| Browser/CLI | `web/app-v2.js`, `web/app.js`, shared `web/sal.js`, `web/tools/sal_report.js` |
| Desktop/automation | `tools/local-bridge/server.py`, `workbench.py`, `desktop.py`, `scrcpy_mirror.py`, `scrcpy-stream.js`, `diagnostics.js`, `process.py`, `recording-transfer.js` |
| Upload test service | `backend/server.py`, `backend/tests/test_backend.py` |
| Executed device fixtures | `sample-app/src/androidTest/.../RecordingRetentionInstrumentation.kt` and focused checks |

[Architecture](docs/ARCHITECTURE.md) owns module/data/lifecycle detail. Read the source before
assuming a named class or public signature is unchanged.

## Latest local verification

### Phone approval handoff — 2026-10-06

Desktop previously confirmed approval by reading `/v1/recordings`, which calls main-thread recorder
controls and scans storage. Any failure before the first success stayed labeled waiting for approval.
Current SDK adds authenticated `/v1/health` on IO with only protocol/package/port metadata; no host
Activity, main or disk dependency. Current desktop uses it, verifies identity and falls back to the
old recordings check only for health 404. All local phone HTTP bypasses system/company proxies.

On a failed initial handshake, the existing DUMP-protected receiver answers only the offered
credential's pending/starting/listening/inactive/disabled state and approved port. Desktop can adjust
to that exact request's port using a new owned forward, never port scans/another manual session.
`connecting` acknowledges consent while authenticated readiness is still false; ended requests
clear access instead of remaining pending. Cancel during handoff revokes only the matching pending/
approved phone session, including approval racing Cancel, preserving a newer prompt/listener.
Initial/visible-page checks run immediately. Rebuild/
reinstall the host SDK, restart Python and refresh the page together; do not create a new manual
token after Approve. No public facade/no-op API, manifest/dependency or `.sal` format change.

API 36 `pcPairingOnly` passes real shell/ordinary-app sender and query gates, pending/mismatched/
denied/disabled status, actual phone approval/port, health authentication and fast health while main
is deliberately held busy; the previous recordings probe cannot succeed during that hold.
`test_device_pairing.py` passes on **8767** with real approval, a forward removed during approval,
truthful `connecting` then recovery without reapproval, attributes/save/dedup, default scrcpy H.264,
another forward repair, paused-host Send/master/clip copies and token revocation. `bridgeOnly` also
passes existing semantics/actions/privacy/shutdown/RTL gestures. Current gates pass 37 Compose
units, Compose lint, sample debug/test/release/isolation, independent consumer debug/release/
isolation, 59 Python tests, six desktop JS suites and web-reader/CLI. Unchanged tasks were cached;
the earlier full Android/240-unit baseline was not entirely rerun for this narrow change.

Desktop JS executes the real pending/connecting/connected/visibility wiring in a controlled fixture;
live browser visuals are unverified because the UI tool exposes no usable browser. Physical/company
host acceptance still needs its own rebuild/test; the exact reported host trigger is unconfirmed.
See [pairing setup/recovery](tools/local-bridge/README.md#start-and-pair) and
[verification commands](CONTRIBUTING.md#focused-device-checks).

### Compose dialog inspection — 2026-10-06

Android 10/API 29+ discovers attached, visible host Compose Dialog/AlertDialog/Popup windows through
public `WindowInspector`; API 23–28 keeps `qaInspectionRoot`/paired root registration. Context must
resolve to the resumed host Activity. Both paths deduplicate; explicit registrations preserve order.
Active inspection/tag mode or QA panels get one owned nonfocusable application window above the
extra root, with Activity lifecycle owners, physical host coordinates and scoped dismissal/pause/
disable/recording cleanup. The normal Activity bubble remains below a modal dialog when inspection
is off; use Control Room → Open panel in app or the connected desktop for an already-open dialog.
Done exits inspection; system Back retains the host dialog's normal dismissal behavior.

Hit selection first chooses the foremost containing window and then the smallest meaningful node,
preferring tags/actions/labels on equal bounds. A tiny background element cannot steal a dialog hit,
and an empty popup container cannot steal its tagged child's hit. Snapshots add optional `windows`
and node `windowId`; desktop mirror selection uses them. Two-finger gestures target the actual
dialog/popup content, cancel the inspector tap, never fall through to a detached Activity and
cancel stationary two-finger touches. SDK selector/pairing windows are marked/excluded so they do
not become host roots or inspector anchors. No public facade/no-op API/dependency or `.sal` change.

Pixel masks map each root to host coordinates. Screenshot visibility uses scoped reference counts
and the inspection surface's prior visibility, including captures during window changes. Capture
is still Activity-window PixelCopy, not separate dialog pixels. Secure host flags propagate to the
inspection window and SDK selector/pairing dialogs at scans; no host flag is cleared. Rebuild/
reinstall the SDK in the host and refresh the desktop. [Integration](integration.md#compose-inspection-across-host-windows)
and [focused command](CONTRIBUTING.md#compose-dialog-inspection) own setup/acceptance.

The focused API 36 fixture passes real unhooked Dialog/AlertDialog/Popup discovery, geometry/focus,
foreground phone selection, All filtering, tags, mobile selector search, PC attributes/XPath/actions,
hidden/password filtering, two-finger scrolling/no stationary click, explicit-hook deduplication,
stale IDs, screenshot restoration/dismissal and background/resume/disable. Captured synthetic phone
screenshots were inspected. The existing capture and full SDK runners pass; unit/build/lint/release
gates are recorded in [Android verification](docs/ANDROID_VERIFICATION.md). Physical/OEM windows,
API 23–28 explicit-window runtime, other Compose runtimes, custom window layering/multiple displays
and live desktop visual acceptance remain separate. Secure propagation is a scan-time update,
not a synchronous subscription to arbitrary host flag mutations. Native Views/WebViews/private
state remain outside Compose inspection. These checks do not certify the consuming app's ANR/HD issue.

### Embedded scrcpy mirror — 2026-10-06

Landing now defaults to an inline scrcpy 5.0 H.264 stream decoded with WebCodecs; Control uses
continuous native touch/wheel/basic text and editing keys. Preview pauses input; Inspect/tree
selection still uses the SDK's semantics/attributes and scaled display/window geometry. The old
PNG/adb mirror is preserved behind `tools/local-bridge/mirror_flags.py: ENABLE_LEGACY_MIRROR = False`.
There is no UI/env/query/preference toggle or silent fallback. Restart Python and refresh the browser;
this change does not require new SDK APIs/dependencies or a host rebuild.

First explicit Start downloads the fixed official server into `~/.qalens/dependencies/scrcpy/5.0`
with a SHA-256 check and Apache license. `scrcpy_mirror.py` is also the offline-preparation installer.
The protocol is pinned: never upgrade just a version/digest without testing framing/control. The
optional native window uses an installed client or explicitly cached `native/` bundle; the GUI does
not install that client. Source/license/setup contracts are in the desktop guide and SCRCPY_NOTICE.

Whole-phone pixels remain unmasked/memory-only. Audio and automatic clipboard sync are disabled.
One page owns a random session/phone jar/forward; seven-second authenticated heartbeat expiry,
phone-auth revocation, mode/rotation revisions and current connection guard capture/input. Mode/Stop/
rotation cancel held fingers. Packet/transport/decode/input queues have explicit budgets; slow or
broken consumers stop. Pairing Auto reconnect repairs SDK transport but never retries input or
restarts video. Reconnect as needed and explicitly Start after stream failure. Long recording copies
now snapshot their source under the workbench lock and transfer/validate/publish separately, so they
do not monopolize that lock and starve mirror/heartbeat/input.

Current verification passes 52 Python tests, six desktop JS suites/syntax, web-reader/CLI and a
rebuilt sample test APK. Real API 36 emulator + desktop HTTP checks pass actual H.264/FFmpeg decode,
touch, editable text/Back, wheel moving content, portrait/landscape reconfiguration, linked SDK
selection/attributes, Inspect/Preview rejection, fresh restart IDs and stale-input rejection.
SDK Frames/last-ten-second marks/masked screenshots coexist with the stream. Actual OS-approved HD
recording and last-ten-second marking complete; all frames in both short master/clip `.sal` MP4s
decode after desktop copy, and input presentation timestamps increase strictly. The decode check
preserves the variable-rate source clock rather than rounding it to a null muxer's nominal rate.
The checksum-verified official native macOS aarch64 5.0 bundle also records a real headless MP4
that FFmpeg decodes. The disposable test-only fixture restores HD policy, UI and bridge on expiry.
Initial launch-before-foreground/bridge-ready smoke attempts were rejected (502/400); the harness
now waits for real connected/UI readiness. Only completed passing runs count as successful coverage.

**Live browser canvas/rendering acceptance remains unverified**: the UI tool reports locked Mac/
no browser surface. Controller/real transport/FFmpeg checks do not certify WebCodecs rendering,
hardware acceleration, frame rate or latency. Prior PNG-browser checks below do not validate this
new backend. Physical phones/OEM encoder concurrency, real USB loss, native window UI, other browsers/
OS and hour-long endurance remain open; the consuming app's separate ANR/HD problem is not certified
resolved. The 240 Kotlin units/full Android/build/lint/release gates below were verified in the
preceding SDK change, not all rerun for this PC adapter. Only instrumentation fixture code changes
in Android here. [Commands and manual acceptance](CONTRIBUTING.md#scrcpy-mirror-checks).

### Desktop live diagnostics, decoded values, clip notes and SQL — 2026-10-05

The selected desktop improvements are implemented: a collapsible Landing Network/Logs/App values
panel with search, Errors only and opt-in two-second Follow/Pause; decoded cache/status, ten pinned
fields and comparison baselines; bug notes attached to existing clip marks/master evidence; and a
separate Data tools page for read-only SQLite/Room, tables, shared phone saved queries and cancellation.
`tools/local-bridge/diagnostics.js` owns this UI/memory state; `QaLensBridgeDataTools` owns bounded
cached data, optional file/preference snapshots and async SQL jobs. No new public facade/no-op
subscription or `.sal` schema. Update/reinstall the host SDK, restart Python and refresh the browser.

DataStore backing files are metadata, not decoded values. Binary does not necessarily mean
encryption; the existing host-owned decoded Flow/serializer remains the integration path. Cache
responses retain at most 30 sources, 300 fields / 100k field characters, with omissions/status.
Network/logs retain 100 each; dashboard omissions are not complete recording coverage. Body previews
require existing host opt-in. Pins/comparisons/results stay in memory and invalidate on detected
privacy/config/connection changes; paused views cannot know about later host changes until refreshed.

Desktop SQL uses a distinct `OPEN_READONLY` connection and wrapped SELECT/CTE reads, catalog IDs and
resolved own-directory boundaries. A ten-second watchdog signals cancellation; one active job/result,
100 rows / 30 columns / 512-char cells / 60k cell characters and five-minute expiry bound it. It does
not hold the bridge socket or a main-thread stop monitor while querying/redacting. SQLite/file-open
behavior may delay cancellation completion. Existing Control Room writes remain explicit/separate.
Shared saved-query mutations preserve other entries; Control Room entry/Rescan refreshes them.
Raw SQL aliases/business data still need host policy. Source/literals are not logged in SQL crumbs.

Local verification passes 240 units (183/37/17/3), Compose/Android/sample lint with zero errors,
sample debug/test/release/isolation and independent consumer debug/release/isolation. All 42 Python
tests, five desktop JS suites/syntax and web-reader/CLI pass. API 36 `desktopDataOnly` passes real
WAL reads, actual Preferences DataStore initial/Control Room foreground updates, key/text masks,
preference/envelope metadata, typed writes/multi-statements/path rejection, bounded blobs/rows,
saved-query identity, cancellation/timeout, responsive main/socket, privacy invalidation and disable/
restart. The initial full-path-equality check incorrectly refused the emulator preference path;
resolved parent-directory containment fixes it. `desktopCaptureOnly` confirms bug notes in the master
marks and clip analysis using actual v2 GZIP tracks, alongside recorder/screenshot/consent checks.
`dataUiOnly` and the full Android runner pass existing Control Room/data/OSS/bridge/privacy/replay/
recording/traffic cases. These are synthetic device/build checks, not consuming-app certification.

The new panel/page has executable DOM/controller regressions, but its live visual browser check
is **unverified**: the UI tool reported a locked Mac and no browser surface; opening the local panel
was queued. Prior mirror/capture browser coverage below does not certify these new views. The manual
`desktopDataGuiSeconds` fixture and acceptance steps are in CONTRIBUTING/desktop guide. Physical
phones, other serializers/encryption/OS/Compose, the consuming app's ANR/HD behavior and hour-long
endurance remain separate. No real recordings, generated files or local credentials are committed.

### Desktop mirror and capture workspace — 2026-10-05

Landing now places mirror → selected element → smaller semantics tree in independently adjustable
panes, with a shared height handle, keyboard resizing, local layout preference and Reset layout.
Responsive layouts keep mirror/selection adjacent at medium width and stack at compact width.
Control is the default: explicit Start mirror enables whole-phone adb tap/drag/wheel/long-press;
Preview pauses input. Inspect shows all visible Compose outlines and selects without a host click.
Linked tree/phone selections enter inspection, with a visible return to Control. Letterbox margins
are not targets. Two short-lived frame leases, finite normalized coordinates, connection/mode guards
and single-flight input reject stale dispatch; known rotation revokes prior dimensions. Sampling is
about 1 fps, stops when hidden/leaving Landing and stays in memory; installed scrcpy is optional.

Desktop Start/Stop/10/20/60/custom clip/Watch use the existing SDK recorder and shared web player.
Internal bridge capabilities add recording/inspection commands and masked app-window screenshot
PNG; no new public API, no-op collection or `.sal` format. HD retains host opt-in and Android consent;
pending consent is distinct from recording and is cancelable without starting a service. Clips keep
capture running and export after Stop. Replay after Stop is opt-in and waits for the exact master;
navigation/device/error cancels it. Browser shutdown does not stop the phone recording. Screenshots
default to hiding/restoring the in-window overlay; Include overlay retains current visibility.
Existing masks/secure-window refusal apply, PNG encoding stays on IO, cancellation recycles bitmaps
and interrupted transfers fail cleanly. Capture now owns only its own visible-overlay change so
completion cannot undo a concurrent phone recording Start/Stop. Update/reinstall the host SDK and
restart Python for these capabilities; the desktop explains older SDKs and reconnection separately.

Verification passes 238 unit tests (183/35/17/3), Compose/sample lint with zero errors, sample
debug/test/release/isolation and independent consumer debug/release/isolation. The full API 36
Android runner and focused `desktopCaptureOnly` pass actual recorder/clip export, HD rejection/
pending/cancel, PixelCopy decoding/password masks, secure windows, exact overlay restoration and
concurrent screenshot/recording races. The test's reused singleTop activity needed asynchronous
resume instead of `startActivitySync`; the fixed fixture verifies foreground readiness by a read.
Python passes 40 tests (including five new HTTP/input cases); interrupted PNG/error/auth checks
pass after final transport changes. Four bridge JavaScript suites, syntax and web reader/CLI pass.

Actual desktop browser + emulator checks pass phone approval, mirror tap/navigation/wheel scrolling, Inspect outlines/
selection and center attributes, Control/Preview routing, pointer/keyboard resizing/persistence and
1440/900/621px layouts without page overflow. HD Start → real OS approval → ten-second mark while
recording → Stop → exact-master automatic replay and separate clip replay decode video at 862×1920.
Clean/Include overlay screenshot pixels are inspected. Copy image reports an accepted clipboard
write; binary pasting into another application is not verified. Browser PNG download completion
remains unconfirmed in Codex's in-app browser; do not present it as executed filesystem delivery. Physical
phones, Windows, installed scrcpy/latency, other host/OS/Compose versions, hour-long endurance and
the consuming app's separate ANR/HD behavior remain unverified. The disposable GUI HD fixture is
test-only, restores opt-in/access on expiry and is absent from SDK/release builds. Commands and
workflow details are in the [desktop guide](tools/local-bridge/README.md#landing-workspace-and-phone-control).

### Desktop startup recovery — 2026-10-05

The old CLI's raw permission and occupied-port tracebacks reproduce with disposable storage and
listeners. Workspace initialization now performs a private temporary write in the root and each
components/runs/recordings/transfers directory, including existing folders; probes are removed.
Failures identify the blocked directory, preserve permissions/saved data and print a quoted
restart command for a separate folder under the user's home. Restoring the original folder's
access retains its profiles/files; switching folders explicitly chooses a separate workspace.
File-versus-directory and pipeline JSON/setup failures are distinguished from write permissions.

`--port 0` selects an available loopback port and prints the actual HTTP URL. Fixed ports retain
their explicit meaning; an occupied port gives the existing URL and a free-port restart command.
The listener binds before terminal credentials/adb forwarding. SIGTERM/Ctrl-C close the listener
and retain scoped forward removal; startup never kills another process. Browser viewer state is
origin-scoped, while desktop profiles/files stay in the chosen data directory.

Local verification passes 35 Python regressions (5 server / 8 startup / 7 workbench / 8 connection /
7 desktop) and the three bridge JavaScript wiring suites plus syntax checks. Startup cases launch
actual CLI processes/GUI HTTP APIs and test real non-root macOS permissions, existing read-only
children, file collisions, copied recovery commands, occupied sockets, malformed pipeline JSON,
clean signals/rebinding and a synthetic adb forward's exact ownership/cleanup without token leaks.
Windows ACL/PowerShell recovery and a real phone through the reordered terminal startup remain
unverified; the permission/shell tests skip unsupported platforms/root. Android, web-reader/backend
and physical-device suites were not rerun for this Python startup change. Commands and recovery
are in the [desktop guide](tools/local-bridge/README.md#startup-recovery).

### Compose host compatibility and quick actions — 2026-10-05

The reported Control Room failure was reproduced in the independent consumer with the SDK
compiled against Foundation 1.7.6 and the host packaging runtime 1.8.2: `FlowRow` `NoSuchMethodError`
at 330/304/564. Foundation changed that experimental binary signature. Rescue, saved recording
and configuration groups now use a small stable Compose UI `Layout`, with relative placement for
RTL; no host dependency override or experimental `FlowRow` call remains. Small Control Room
buttons have a real 48 dp minimum touch size so expanded touch bounds do not overlap between rows.
The consumer's optional `qaComposeRuntime` changes only its debug/test runtime resolution, keeping
the SDK compile baseline unchanged; CI compiles the native consumer fixture, not a device run.

Quick actions is wider and has a fixed Review evidence link above the scrolling list; Review
has a wide Quick actions return control. Report/Refresh use centered compact labels and minimum
48 dp height. Inspect elements and Inspect tags are distinct destinations; tags have an inset-safe
named Done button and system Back. Frame and HD recording are two equal side-by-side buttons.
HD stays disabled without host unmasked-video opt-in and still requires Android consent. Stop and
saving states remain explicit. No SDK API, `.sal` schema or production privacy default changed.

The focused `quickVideoOnly` test uses the actual quick HD button, real test-approved Android
consent, native REC Stop and a decoded saved MP4. Its consent helper connects accessibility before
starting concurrent checks and scrolls the OS dialog when necessary. These are disposable test
controls, absent from the SDK. [Mobile overlay](docs/MOBILE_OVERLAY.md), the
[consumer fixture](integration-tests/consumer/README.md) and
[Android matrix](docs/ANDROID_VERIFICATION.md) own the commands and executed coverage.

Local checks pass 238 units (183/35/17/3), Compose/sample lint with zero errors, sample
debug/test/release/isolation and independent consumer debug/release/isolation plus newer-runtime
debug/test builds. The newer-runtime consumer, overlay workflow and quick-button HD decoder all
pass on API 36 at normal display and 360×640 dp / 150% font / three-button navigation. SQL/data UI
passes both displays; bridge/privacy/LTR/RTL/two-finger checks also pass the small setup. Private
screenshots were inspected and display/font/navigation restored. The full Android runner passes
retention/privacy/Room/DataStore/OSS/inspection/bridge/SQL/macros/replay/webhook and continuous
traffic; the load case measured 2,452 background iterations and an 8 ms worst main heartbeat.
These synthetic checks do not certify host responsiveness, other Compose/OS versions, physical
TalkBack or the separate consuming-app HD crash. Web/Python/backend/endurance checks were not
rerun for this Android UI change.

### Control Room SQL and app data — 2026-10-05

SQL now uses an all-database picker, separate query/Run/name/Save, 48 dp actions and equal saved
Run/Delete controls. Results start at 12 previews, allow all retained rows (100 maximum) through a
lazy vertical viewport and scroll columns horizontally; UI shows at most 30 columns/80-character
cells. Every execution resets the preview. Query errors/cancellation remain visible; cancellation
does not undo writes. Control Room has safeDrawing/IME padding and wrapping action groups.

App data leads with searchable read-only host snapshots/status. New public `observeDataStoreValues`
maps an existing decoded host Flow on a worker into a bounded redacted cache. Initial reads populate
state without a change; later labels contain counts only. Stop removes only its owned preview,
disable pauses, re-enable reads again, and completion/failure is explicit. No-op neither subscribes
nor evaluates mapping. Existing `observeDataStore` and manual cached providers remain supported.
Never create a second DataStore, parse arbitrary backing files or bypass the host's encryption.
File details are optional: SDK prefs excluded, plain values redacted before truncation, recognized
AndroidX encrypted envelopes explained, DataStore names/sizes disclosed as metadata only.
The UI offers 30 sources/100 retained fields each and renders at most 100 expanded fields.
See [app data](docs/APP_DATA.md) for the API, lifetime, privacy and QA contract.

Local checks pass 238 unit tests (183 core / 35 Compose / 17 replay / 3 no-op), changed Compose/sample
lint with zero errors, sample debug/test/release/isolation and independent consumer
debug/release/isolation. Unchanged tasks reuse outputs. The focused `dataUiOnly` API 36 test passes
actual SQL picker/actions/results/saved queries/error/cancellation and real Preferences DataStore
initial/foreground updates, redaction, disable/resume/stop, finite completion, failure and replacement
ownership. Plain preferences and a synthetic encrypted-envelope marker are checked; this is not
cryptography verification. Screenshots are reviewed from private cache and not committed.
The final focused case passes normal display and 360×640 dp / 150% font / three-button navigation;
the tester workflow also passes in the latter setup. The full Android runner passes recording
retention/real Room/DataStore state, privacy/lifecycle, SQL/macros/replay/webhook, Chucker/adapters,
inspection/bridge and continuous traffic. Its load case observed 2,431 background iterations and
a worst measured main heartbeat of 3 ms, a synthetic check rather than a consuming-app guarantee.
Physical phones/TalkBack, other serializers/OS/Compose versions and the consuming app still need
acceptance. No capture/recording-format or web/desktop/backend behavior changed in this work.

### Task-based overlay — 2026-10-05

Quick actions now presents Record/Screenshot/Mark a bug/Inspect elements, with separate Review
evidence, Connect to PC and Control Room routes. Close stays visible while the list scrolls;
inspection has Done/system Back. Review has Activity/Network/Logs/Elements/Device and existing
host extension tabs. Selecting evidence components opens their details on the app. Element filters
are collapsed initially; Back from checks returns to search, an active evidence query clears first,
then Back returns to actions/closes. The PC dialog leads with desktop discovery/phone approval;
manual pairing is optional. SDK public APIs, `.appsal` keys, `.sal` schema and no-op isolation remain.

Retired overlay UI includes Screen Health/scoring, owner/completeness claims, bookmark editor,
duplicate tools/reports/recordings, opacity/Lock/Watch shortcuts and arbitrary deep-link entry.
Captured failures and redacted expandable stacks remain in Activity; reports include retained
stacks. Concrete element/contract findings and device/integration facts remain. Evidence uses
the selected overlay palette and opaque surfaces. Named macros now run from Control Room only
after a resumed host is available; missing launchers, launch failures and resume timeouts surface.
The older menu descriptions in dated verification sections below are historical and superseded
by [mobile overlay](docs/MOBILE_OVERLAY.md).

Local checks pass 233 unit tests (183 core / 31 Compose / 17 replay / 2 no-op), Compose/sample lint
with zero errors, sample debug/test/release and independent consumer debug/release/isolation,
plus sample release isolation. Unchanged checks reuse outputs. The focused API 36 tester workflow
passes normal and 360×640 dp / 150% font: actual capture/REC Stop, five views/host tab, Back,
search/filters/inspect Done, crash stack/report copy, Control Room macro actions, upload feedback,
configuration, replay and Panic. Three-button navigation exposed expanded filters taking the whole
result viewport; options now scroll with results, and the same workflow checks visible matches.
Selector search/XPath copy and SDK manual-root PC controls pass.
Continuous 12k-burst/background/main-thread log/network navigation passes with 2,545 background
iterations and a worst measured main heartbeat of 6 ms. Bridge privacy/actions and actual
LTR/RTL/two-finger inspector gestures pass, including small/large-font three-button navigation.
These synthetic measurements do not prove the
reported consuming-app ANR resolved. Physical phones, TalkBack, other host/OS/Compose versions
and consuming-app capture failures remain unverified. See the [Android matrix](docs/ANDROID_VERIFICATION.md).

### Mobile replay synchronization — 2026-10-05

Android replay defaults to chronological Timeline. Timeline/Network/Logs keep the latest current
row visible, including event taps/seeks and track changes. User drags suspend following without
pausing playback; explicit Follow/Play/seeking restores it. Previous/Next step within the selected
track (Summary/State use merged events), and First error opens the earliest error's track. Import
sorts tracks once; binary frame/state/event lookups and cached merged events avoid repeated scans/
sorts. Long row details are bounded/ellipsized without changing retained evidence.

Frames use monotonic elapsed time; covered video uses decoder time, exact seeks and known video
offsets. Preparation/buffering freezes video time; pre/post video periods advance retained events
with explicit viewport coverage. Legacy files align video end to session end. Scrubbing coalesces
previews, commits the final seek and preserves Play/Pause; replay at end restarts. Backgrounding
pauses without auto-resume. Fullscreen detaches old surfaces and preserves position/Back; loop
cleanup avoids touching an already released decoder. Decoder errors are visible and allow explicit
event-only playback. Future frames/state are not shown before capture. New recordings stamp HD
start on the encoder worker before the UI callback; no sub-frame calibration claim or schema change.

Local checks pass: 233 unit tests (183/31/17/2), changed Compose/replay/sample lint with zero errors,
sample debug/test/release/isolation and independent consumer debug/release/isolation. Unchanged
checks reuse outputs. The new `replayOnly` API 36 runner checks actual rendered red/green/blue
video against offset seeks, sorted reverse-input tracks, current event/state, manual browse/follow,
playing scrubs, fullscreen surfaces/Back, background pause, trailing evidence, legacy alignment,
restart, close during playback and explicit decoder-error evidence playback. It passes at normal
emulator size and 360×640 dp / 150% font, including a 160k-character detail. Existing tester workflow
and real consent-approved HD background/notification Stop pass. The color fixture is synthetic,
not a recorded phone screen. Physical phones, rotation/process recreation, other OS/decoder
versions and the consuming-app failure remain unverified. See [mobile replay](docs/MOBILE_REPLAY.md).

### Integration documentation — 2026-10-05

Host agents now have a dedicated discovery/work order, privacy surface review, troubleshooting,
public source index and completion template. The integration recipes cover custom variants/shared
modules, a production graph gate, Startup/manual-install ordering, existing navigation observation,
stable tags/window roots, real OSS owners and host acceptance. The independent consumer has a
README separating compilation/isolation evidence from host runtime validation. Entry points route
agents to these contracts; stale sample-Settings desktop pairing guidance is removed. These are
documentation changes, not a new device audit or confirmation of the reported consuming-app crash.
Local checks pass: changed-document links/anchors/fences, consumer debug/release builds and its
existing isolation gate, plus the exact new documentation gate executed through a temporary init
script. Consumer merged manifests contain the active debug components and no QaLens-owned release
components. Most build tasks reuse prior outputs. Public examples were checked against current
source; not every snippet was compiled as a standalone host app.

### Two-way inspection and selectors — 2026-10-05

The current scope is smooth QA inspection between Android and the browser, without new Robot
integration, a recorder or broad Appium/native/WebView replacement. Landing links selections by
default: a browser tree/preview choice highlights the SDK inspector without invoking a host action;
a phone inspector/search choice loads browser attributes and selectors without Send to PC. A cheap
cached-ID read runs every 1.5s only on visible Landing. Changed selections read a fresh tree; manual
Refresh handles same-element content changes. Rapid highlights are serialized and superseded queued
choices skipped. Clearing phone selection clears the live desktop selection; generation/connection
guards reject old responses. Captured Send/Receive remains independent and optional.

Android More tools/Tools/inspector and Automation Tags share searchable redacted semantics with
tag/action filters. The inspector exposes supported actions and copyable selectors. Browser search
also filters roles; Selectors adds tag/ancestor-tag/content/position suggestions, match counts,
builder, live query results and explicit XML/JSON exports. The pure core engine is shared with the
SDK; Python only forwards the protocol. XPath addresses exported QaLens XML, not Appium XML;
the evaluator supports bounded node paths/positions/attribute equality/and/quoted concat literals.
It rejects arbitrary functions, ambiguity, truncated trees and tree changes before XPath dispatch.
See [desktop guide](tools/local-bridge/README.md#search-and-selectors) for schema/budgets. Password/
hidden values remain excluded; redaction/matching/XML run off main. Updated privacy settings
invalidate a pending selector export. Existing public facade/no-op and `.sal` formats are unchanged.

Local checks pass: 221 unit tests (183/31/5/2), five module/sample lint checks with zero errors,
sample debug/test/release and isolation, independent composite consumer debug/release/isolation,
27 Python tests and all browser wiring/transfer/polling regressions. Core selectors are compared
against a standard XML XPath engine, including quotes, unicode, XML specials, invalid host strings,
scoped duplicate tags, roots and protected values. The API 36 bridge test passes XML/query privacy,
duplicate rejection, inspect-only selection and existing gestures. Actual overlay search selects a
host element and copies XPath. Live Android/adb/Python/Codex-browser checks pass phone→browser,
browser→phone, page refresh restoration, search/action filtering, clipboard and live query builder.
The wider tester workflow also passes. Actual preview clicks select once; a new bubbling regression
prevents duplicate dispatch. Live 1440px/390px layouts have no page overflow with selector tools.
The search field's decoration lost its accessible edit label; an explicit label fixes that. Query
checks now reveal the results panel. Browser-download completion in Codex remains unconfirmed;
XML generation/standard evaluation and API responses are verified. Physical phones, TalkBack,
other Compose versions and the reported consuming-app capture crash remain outside these checks.

### Android feature audit — 2026-10-04

The [executed Android matrix](docs/ANDROID_VERIFICATION.md) records the current acceptance cases
and limits. The full API 36 runner, manual-root Control Room frame/HD and SDK PC controls, phone
approval, real PC transport, >5-minute frame capture, HD background notification Stop and real
consent denial pass. Actual 10/20/60s and custom 45s clip controls export while the master continues;
each HD master/clip is Android-decoded. Strict visual-context and 12,000-log overflow cases pass.
The full runner observed 3,138 background iterations and a worst main heartbeat of 6 ms; this
synthetic measurement cannot establish that the user's consuming-app ANR is resolved.

New positive tester coverage presses Screenshot/Mark/Record/REC Stop, opens all 12 diagnostic tabs,
stops Watch, runs successful tagged macro interactions/capture, round-trips configuration, checks
a real profile-attributed upload and Android replay, then discards capture through Panic.
It passes at 360 × 640 dp, 150% font and three-button navigation alongside the inspector gesture,
RTL and safe-inset cases. Replay's fullscreen Back behavior failed before the new Back handler;
large text exposed a cramped diagnostics header. The header now separates scrollable tools from
the title/Close and exposes spoken button labels and selected tab semantics.

Final local checks pass: 215 unit tests (177/31/5/2), five module/sample lint checks with zero errors,
sample debug/test/release and release isolation, independent consumer debug/release/isolation,
26 Python desktop/bridge tests, 23 backend tests, shared reader/CLI and six transfer-controller
scenarios. Some unchanged Gradle checks are cached. Focused tests now have `workflowOnly` and
`videoRecoveryOnly` modes, plus an explicit test-only English-emulator consent driver; see
[CONTRIBUTING.md](CONTRIBUTING.md). Physical devices, TalkBack, API 23, full-hour capture, rotation,
OS projection revocation and the actual consuming app remain unverified.
The prior remote run at `27821a7` failed in Android setup before any build because the action
requested the retired `tools` package. CI now explicitly requests `platform-tools`; inspect the
latest run before claiming remote verification.

### Landing desktop and phone approval — 2026-10-04

The desktop now starts on Landing with screen preview, semantics tree and automatic selected-element
attribute loading together. Detail tabs separate attributes, public semantics, tree position and
recent diagnostics; saved elements, recording library, processors, shared replay and advanced
phone tools remain reachable through compact navigation. Old home/inspect links resolve to Landing.

Connect discovers SDK-enabled packages through launcher activities, creates a memory-only random
credential and opens SDK Control Room approval through a DUMP-protected shell receiver. Ordinary
other app UIDs cannot offer requests. No listener starts before phone approval; stable pending
requests expire after two minutes. Deny/cancel/disable/expiry reject access. Credentials never enter
Activity extras, saved profiles or reports. Advanced manual token/terminal pairing remains compatible.
Opt-in auto connect remembers a profile ID and requests phone approval on the next desktop start;
it never stores credentials or approves access. Disconnect turns that remembered choice off.
Auto reconnect repairs only a missing owned adb forward within the same session; token revocation
clears access and collection choices. Host actions are never retried automatically.

Screen preview is a separate explicit whole-phone adb capture, memory-only and up to one frame/sec.
It is unmasked, including other apps. Leaving Landing/hiding the browser/disconnecting stops browser
sampling. Limits: one in-flight capture, four-second adb deadline, 16 MiB PNG, 24 million pixels.
SDK snapshot screen dimensions/window origin align selectable bounds; mismatches reject selection.
External scrcpy remains optional and is not required for the sampled preview.

Local Python tests (26) and the shared six-scenario transfer controller pass; Compose unit/build/lint,
sample debug/test/release and independent consumer debug/release/isolation pass (lint zero errors).
The API 36 phone approval test passes ordinary-other-app denial, no listener before approval,
Deny/cancel/expiry, authenticated host reads and disable/re-enable with Startup removed. An actual
Android/adb/Python check passes generated-credential approval, tree/attributes/save/dedup, real screen
PNG, owned-forward repair without a new session, paused-host SDK Send and master/clip copies, then
revoked authentication cleanup. Temporary servers/storage/forwards are removed.
The redesigned Landing now passes live Codex-browser checks: Connect and actual phone approval,
live preview rectangle selection, all four detail tabs, private JSON save/dedup, SDK Highlight and
Send received on Landing, a remembered-app desktop restart requiring fresh approval, recording
library/embedded clip replay/Back and 1440px/390px layouts. Narrow layout has no page overflow.
Receive and collection initially starved health/transfer ticks at the same timer cadence. Independent
bounded reads fix this; a new actual-app wiring regression fails against the old code and runs in CI.
With Receive and collection enabled, forced owned-forward loss now repairs and a new master/10s
clip copies automatically into private SHA-256 files. Preview/collection remain off on restart;
Disconnect clears remembered auto connect. Physical-device/OEM, TalkBack and scrcpy remain unverified.

### Host lifecycle, pairing and transfer regression — 2026-10-04

The user integrates with Application configuration and `QaLensRoot`. Control Room's old buttons
silently returned to the host without starting capture when Startup/Application installation was
absent; the new manual-root test reproduced that failure against the old SDK. Explicit roots now
establish application context and scoped lifecycle callbacks. Control Room recording actions
install callbacks before launching, preflight failure conditions and expire pending requests.
The old `QaLensSessionRecorder.kt:642` passed a nullable global context to Toast in a deferred
main callback; retaining a safe application context and guarding the posted work fixes that path.

PC pairing is now one SDK component shared by Control Room, minimal More tools and full Tools.
The sample-only implementation was removed. Tokens are hidden by default, stay in memory, and
are excluded from reports; rotation/Stop/disable revoke the old pairing. Already captured component
inbox/ack and cached observations require a valid bridge session rather than a foreground host.
Live semantics/actions keep their resumed-host guard. The manual-root PC UI test passes Send to PC,
paused-host inbox/recording discovery, token rotation/privacy, full-overlay Stop and disable/re-enable.

HD startup waits for lifecycle RESUMED, keeps the consent helper visible until foreground promotion,
and starts the encoder worker afterward. Startup errors retain their type/reason; service stop avoids
background starts. The manual-root HD Control Room test passes real approval, preset/custom marks,
continued capture and Android decoding of every master/clip. The HD clip/strict visual-context test
with floating-overlay permission also passes on the new service handoff; denial resets capture
without saving an archive and permits subsequent frame recording. Its initial test failure was a bad
manifest assertion (`videoFile` versus `video`), corrected before accepting the result. The consuming
app's exact foreground-service exception has not been reproduced or identified.

The shared desktop transfer controller queues enable during busy UI, keeps temporary failures enabled
with bounded retries, and rejects stale connection/disable responses. Six Node scenarios pass and
are added to CI. Python bridge/workbench/desktop tests (18), shared reader/CLI and backend tests (23)
pass. Gradle checks pass: core/Compose/replay/no-op (177/31/5/2), all module/sample lint (zero errors),
release isolation and independent consumer debug/release/isolation. Unchanged tests may be cached.
The full API 36 device runner passes (including the existing log-flood/OSS/privacy/bridge cases).
An additional end-to-end test uses the actual Android bridge, owned adb forward, Python server and
GUI's shared transfer controller. It passes queued busy enable, an interrupted/restored forward,
automatic master/clip copying with Control Room foreground, private SHA-256 files, deduplication
and revoked-token shutdown. Temporary servers/storage/forwards are removed by the check.
After unlocking, the actual Firefox GUI also passed the updated controls: automatic-copy checkbox
retention/recovery across an owned-forward outage, two master/clip pairs, embedded replay/Back,
a live tagged selection/highlight, actual SDK Send to PC received with Control Room foreground,
explicit private JSON save/dedup, and rotation disabling both Receive/automatic copy. These live
checks verify the earlier transfer UI; the new Landing redesign below needs its own visual check.

### Recording controls — 2026-10-04

The user reported HD and Last 10s crashes in a consuming app. A new sample regression reproduced
incorrect-context violations in `QaLensSystemChip.show` and deferred clip-popup layout/click callbacks
with overlay permission enabled. StrictMode's incorrect-context termination policy can kill these
hosts; catching popup construction is insufficient. The SDK now uses a visual window context and
its own native widget theme, dismisses menus/dialogs on stop and keeps the host policy intact.
Recording/fallback bitmap allocations now have a pre-capture pixel/dimension budget with scaled,
outward-rounded masks. The user's exact crash remains unconfirmed without that host's trace.

Compose's 31 unit tests and lint (zero errors), sample debug/test builds and release isolation passed.
The focused frame and HD tests exercise real preset/custom menu actions with StrictMode termination
enabled, both with and without overlay permission. They keep capture running at marks, stop with a
menu visible and validate saved evidence; HD decodes a frame from every master/UI/API clip.
The independent consumer debug/release builds and release isolation passed. The full API 36 runner
passed, including a partial-region privacy mask at the reduced frame size. Its load case observed
7,117 background events and a worst measured main heartbeat of 123 ms. The runner's tab discovery
now refreshes accessibility cache and searches the tab strip directly instead of repeatedly scanning
the changing Network list; its prior timeout was not accepted as a successful full run.
Full physical-device encoder/rotation/endurance coverage remains open.

### Desktop/clip baseline — 2026-10-02

These checks passed during the 2026-10-02 desktop/clip work. They are a dated local baseline,
not a remote CI result or a certification of arbitrary consuming applications.

| Check | Result |
|---|---|
| Kotlin core | 177 tests, zero failures/errors/skips |
| Compose, no-op, replay unit tests | 30 + 2 + 5, zero failures/errors/skips |
| Python bridge/workbench/desktop | 18 tests passed |
| Web shared reader and CLI | Regression script passed |
| Python mock backend | 23 tests passed |
| Sample debug/release and instrumentation APKs | Built; release dependency isolation passed |
| Independent composite consumer | Debug/release builds and release isolation passed |
| Compose/Android/sample lint | Zero errors; 25 warnings, 2 informational findings |
| Full disposable API 36 runner | Existing retention/privacy/OSS/bridge/gesture/replay/upload checks passed |
| Continuous-log device case | 3,305 background observations; worst measured main heartbeat 21 ms |
| Focused clips | Preset/custom native controls, no stop at mark, recent evidence after 12,000 logs, post-mark exclusion passed |
| Focused video | Actual consent approval/denial and readable H.264 master/clip tracks passed |
| Longer recording | More than five minutes of frame capture passed beyond the removed old limit |
| Real desktop round trips | Pairing/profile restart, attributes/save/dedup/processor, recording copy/replay, auto-copy and Downloads push/pull passed |

A copied HD clip decoded/played at 862 × 1920 in the embedded browser. The embedded viewer also
sent synthetic evidence to the local mock. Desktop/narrow layouts and Back navigation were checked.
The emulator is disposable; discover its availability with `adb devices` rather than assuming a
serial. Require instrumentation `OK:` output, not just adb exit zero.

Full-hour endurance, physical-phone encoders/rotation/recovery, TalkBack, other Compose versions,
real scrcpy mirroring and Windows processor cleanup remain unverified. Browser download completion
was not confirmed in the Codex browser; explicit PC Save produced the real component JSON. No
claim is made for crash/power-loss recovery of unsaved journals.

## Current behavior that matters

- AndroidX Startup normally installs the active SDK. Quick actions is the default; Review
  evidence has five built-in views and host extensions, and Connect to PC has its own screen.
  Control Room/player use separate task affinities.
- Public Compose semantics discover attached Activity roots and automatically discover the host's
  visible Compose extra windows on API 29+; API 23–28 requires explicit roots. Private state,
  native Views/WebViews and windows outside the resumed Activity are outside this coverage.
  IDs are live/root-scoped; tags must be unique for exact targeting. Hidden/password values and
  unsupported custom properties have explicit limits.
- Dashboard log/network queues are bounded and published in one roughly 100 ms batch. Repro,
  filtering/search and report formatting use background work; initial redaction stays on the caller.
  Host snapshot providers run on main and must return cached allowlisted data.
- Recording journals are independent of dashboard clearing/eviction. The master keeps earliest
  admitted observations; an independent bounded recent journal supports late clips. Missing evidence
  is partial coverage, never proof of a healthy app or a fixed regression.
- Default duration is 60 minutes, configurable to 1–180. Frame sampling scales with duration;
  3,600 frames / 192 MiB and saved history 30 archives / 1 GiB bound storage. Video has a 240 MiB
  limit, adaptive bitrate and 650 fallback frames. These are limits, not quality/endurance promises.
- `saveRecentClip` and ★ Clip mark 10/20/60s or custom 1–300s without stopping. Up to 20 bounded
  marks export after stop. Video remuxing begins at a previous keyframe; actual/requested timestamps
  are recorded. Frame-mode clips are JPEG sequences. Recent-buffer omission counters describe its
  lifetime, including outside the exported interval. Sources survive failed exports for diagnosis.
- MediaRecorder setup/stop, remuxing, image encoding, files and ZIP export run off main. Live
  View/semantics capture remains on main. HD requires host `allowUnmaskedVideo=true` and Android
  consent; this opt-in was introduced in the September audit. There are no video pixel masks.
  Recording/fallback bitmaps allocate at most 720 px width / 2 million pixels / 2,880 px height before
  capture. Window changes fail closed; privacy masks transform to those output coordinates.
- Completed archives live in app-private `files/qalens/recordings/`; legacy cache archives migrate.
  Host backup rules govern whether these files leave the device. Failed raw sources remain in cache.
- Chucker 4.1.0 coexistence uses both interceptors and its public launcher. There is no supported
  Chucker live transaction callback into QaLens; leave `networkFromChucker=false`. Other transports
  use host-owned sinks. Room records invalidations; DataStore observes later Flow changes. Cached
  providers add allowlisted values. Timing links in analysis are investigative leads, not cause.
- The device bridge is explicitly paired, authenticated and loopback-only. Disable closes it;
  re-enable requires restart. IO/JSON/redaction stay off main; semantics actions stay on main with
  timeout/generation guards. Already-running synchronous host actions cannot be safely interrupted.
- Two-finger inspector drags forward to Activity content or the foremost dialog/popup content
  after cancelling the inspector tap; a stationary two-finger touch cancels the host gesture.
  Bubble/dock positions use physical coordinates and safe system/IME bounds in LTR/RTL.
  Window membership/order and equal-bounds tie rules keep foreground selection meaningful.
- Desktop Landing/Back embeds the exact `web/` sources. Profiles and opt-in auto-connect profile IDs persist without tokens; every new automatic request still needs phone approval;
  previews are memory-only until Save. Component hashes ignore timestamps/live IDs and include
  exported values, bounds, package/viewport and visible tree position. Fixed adb tasks/Downloads
  transfers require the current connection nonce. Optional scrcpy is an owned external process.
- Finished recording copy is explicit; automatic copy is off by default, takes a baseline and
  resets on connection changes, revoked authentication and restarts. Temporary discovery/copy errors
  keep the choice enabled with bounded retries. SHA-256 archive files are private and deduplicated.
  The PC transfer limit is 400 MiB; mock backend uploads remain limited to 64 MiB.
- Component pipelines are trusted local argv programs, not sandboxed code. They require saved
  input, run serially off HTTP handlers, have timeouts/no retries and cancel on shutdown. Recording
  analysis through the Node CLI is a separate path.

## Engineering contracts

1. Preserve host requests/responses, cancellation and exception delivery. Do not consume response
   streams for evidence. Crash bridges must not swallow, recurse or echo failures to their vendor.
2. Mirror public SDK changes in `qalens-noop` and exercise the separate consumer. Verify debug and
   production dependency graphs. Active release AARs are not the production no-op artifact.
3. Keep capture privacy explicit. Text rules and limited pixel masks cannot sanitize arbitrary
   content. Gallery copies/unmasked video are separate opt-ins; Chucker storage is independent.
4. Disable stops collecting/observing/upload work and rejects stale callbacks. Re-enable remains
   deliberate. Previously exported data is not retroactively removed; configured upload retries
   may run later against their recorded destination.
5. Preserve recording-owned journals, admission bounds, frozen stop time and honest omissions.
   Keep callbacks from previous sessions out of new ones. Do not block main on disk/encoding work.
6. Preserve v1/v2 archive compatibility, nested gzip tracks, checksums and timestamps. Schema
   changes need writer/Android reader/shared web reader/CLI/backend coverage and format docs.
7. Use public OSS contracts and host-owned optional libraries. Compile-only hooks do not establish
   that every version or client is instrumented. Exercise a real write/request and inspect coverage.
8. Keep public Git clean: synthetic fixtures only; no pairing tokens, private recordings, local
   configuration, generated builds, backend data or emulator state. Preserve unrelated user edits.
   Never hide lint failures behind a new baseline to make a check pass.

## Build and publication state

Tested toolchain: JDK 17, Kotlin 2.0.21, AGP 8.7.3, compile/target SDK 35, minimum SDK 23,
Compose BOM 2024.12.01 / sample runtime 1.7.8, Gradle 9.1.0. Android modules use JUnit4.
The active SDK's Foundation compile dependency resolves to 1.7.6; the independent Control Room
fixture also passes with consumer runtime 1.8.2 without recompiling the SDK against that version.
The wrapper and CI request the tested Gradle version; other versions are not automatically verified.
Use the [contributor commands](CONTRIBUTING.md); set host paths through local environment variables.
Serialize Gradle processes sharing this checkout. Earlier work used atomic
`mkdir /tmp/qalens-gradle-lock` plus a cleanup trap; never delete another active build's lock.

At this handover baseline, `dev` contains the Control Room app-data changes above, the
task-based overlay at `ecc2d21` and
`9696976` (mobile replay synchronization), `56f8347` (host-agent integration guidance),
`a04b107` (linked inspection/selectors) as well as
`90d669f` (recording visual contexts/budgets) and
`7059e29` (manual-root lifecycle/shared pairing/transfer recovery), plus the Landing/phone-approval
work at `27821a7` and Android/browser audit fixes described above. Earlier bridge/component/clip
commits follow the `dea9048` master merge. Check remote state before pushing or
merging; this handover does not authorize merging a branch. Default coordinates are
`com.qalens:<module>:0.9.0` with a `-PqalensVersion` override. `qalensDist` builds a local Maven
repository; `scripts/release_internal.sh --verify` packages/checks it. Public artifact publication
and an authenticated company backend are separate work.
GitHub CI for `929b96f` completed successfully in
[run 37298957603](https://github.com/Sal7one/QaLense/actions/runs/37298957603). That result covers
that implementation revision; inspect the current HEAD's run separately.

## Documentation ownership

| Document | Owns |
|---|---|
| [ONBOARDING.md](ONBOARDING.md) | Whole-product orientation, first sessions and practical team advice |
| [next.md](next.md) | Single prioritized backlog and acceptance criteria |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Build/lint/consumer/device/distribution commands |
| [integration.md](integration.md) | Host wiring, capture/privacy/backup policy and variants |
| [AI integration](docs/AI_INTEGRATION.md) | Consumer-agent discovery/work order, diagnosis, acceptance routing and handoff template |
| [Consumer fixture](integration-tests/consumer/README.md) | External build/API/isolation example and the limits of its evidence |
| [OSS integrations](docs/OSS_INTEGRATIONS.md) | Supported library contracts and version limits |
| [Architecture](docs/ARCHITECTURE.md) | Module and observation/analysis/capture/lifecycle boundaries |
| [Recording clips](docs/RECORDING_CLIPS.md), [retention](docs/RECORDING_RETENTION.md) | Timing, journal/media budgets and omissions |
| [Android verification](docs/ANDROID_VERIFICATION.md) | Dated executed feature matrix and validation limits |
| [Mobile overlay](docs/MOBILE_OVERLAY.md) | Capture tasks, evidence/connection navigation and retired overlay controls |
| [Mobile replay](docs/MOBILE_REPLAY.md) | Android transport, event following, media/time coverage and focused playback checks |
| [App data](docs/APP_DATA.md) | SQL controls, decoded DataStore value wiring, privacy/lifetime and focused UI acceptance |
| [SAL format](docs/SAL_FORMAT.md) | Writer/reader schema, compression/checksums and compatibility |
| [Desktop](tools/local-bridge/README.md), [web](web/README.md), [backend](backend/README.md) | Each tool's detailed operating/API/storage contract |
| [Client fixes](docs/CLIENT_SAFETY_FIXES.md), [overlay design](docs/OVERLAY_DESIGN.md) | Dated migration decisions and remaining UI token coverage |
| [DEMO.md](DEMO.md) | Short synthetic demo walkthrough |
| [CHANGELOG.md](CHANGELOG.md), Git | Historical changes and older verification records |

Start the next engineering task from [next.md](next.md), reproduce the relevant behavior and run
checks appropriate to that change. Report exactly what was checked and what remains unverified.
