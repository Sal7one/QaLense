# QaLens engineering handover

Updated 2026-10-04. Read this before changing the repository. [ONBOARDING.md](ONBOARDING.md) is
the user/integrator overview; [next.md](next.md) is the only current backlog;
[CONTRIBUTING.md](CONTRIBUTING.md) owns portable build/device commands. This handover records
the current engineering baseline, including the recording-control fixes below. Git and CHANGELOG retain earlier history.
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
| Host roots and overlay | `QaLensActivityInstaller`, `QaLensOverlayHost`, `QaLensInspectorPanel`; registered dialog/popup roots |
| Capture and clips | `QaLensSessionRecorder`, `QaLensProjectionActivity`, `QaLensProjectionService`, `QaLensVideoClip`, `QaLensSystemChip` |
| Evidence ownership | Core `RecordingLifecycle`, `RecordingWindow`, `RecordingEvidenceStore`, `RecordingClipWindow` |
| Device/settings/transport | `qalens-android/`; active `QaLensLocalBridge`, `QaLensWebhook` and OSS adapters |
| Optional Android tools | `qalens-navigation-compose/`, `qalens-replay/` |
| Production API mirror | `qalens-noop/`, `NoopParityCheck`; independent `integration-tests/consumer/` |
| Browser/CLI | `web/app-v2.js`, `web/app.js`, shared `web/sal.js`, `web/tools/sal_report.js` |
| Desktop/automation | `tools/local-bridge/server.py`, `workbench.py`, `desktop.py`, `process.py`, `recording-transfer.js` |
| Upload test service | `backend/server.py`, `backend/tests/test_backend.py` |
| Executed device fixtures | `sample-app/src/androidTest/.../RecordingRetentionInstrumentation.kt` and focused checks |

[Architecture](docs/ARCHITECTURE.md) owns module/data/lifecycle detail. Read the source before
assuming a named class or public signature is unchanged.

## Latest local verification

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
The Mac relocked during this redesign; final Landing layout, browser click-to-select/auto-connect
rendering and responsive visual QA are pending. Do not infer those checks from the transport tests
or the earlier GUI run. Physical-device/OEM, TalkBack and high-FPS scrcpy remain unverified.

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

- AndroidX Startup normally installs the active SDK. The tester sheet is the default; advanced
  diagnostics remain under More tools. Control Room/player use separate task affinities.
- Public Compose semantics discover attached Activity roots and explicitly registered extra
  windows. Private state, native Views/WebViews and unregistered roots are outside this coverage.
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
- Two-finger inspector drags forward to Activity content after cancelling the inspector tap.
  Bubble/dock positions use physical coordinates and safe system/IME bounds in LTR/RTL.
  Registered dialog trees can be read; gesture forwarding stays in the Activity window.
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
Compose BOM 2024.12.01 / runtime 1.7.8, Gradle 9.1.0. Android modules use JUnit4.
The wrapper and CI request the tested Gradle version; other versions are not automatically verified.
Use the [contributor commands](CONTRIBUTING.md); set host paths through local environment variables.
Serialize Gradle processes sharing this checkout. Earlier work used atomic
`mkdir /tmp/qalens-gradle-lock` plus a cleanup trap; never delete another active build's lock.

At this handover baseline, `dev` also contains `90d669f` (recording visual contexts/budgets) and
`7059e29` (manual-root lifecycle/shared pairing/transfer recovery), plus the Landing/phone-approval
work described above. Earlier bridge/component/clip commits follow the `dea9048` master merge. Check remote state before pushing or
merging; this handover does not authorize merging a branch. Default coordinates are
`com.qalens:<module>:0.9.0` with a `-PqalensVersion` override. `qalensDist` builds a local Maven
repository; `scripts/release_internal.sh --verify` packages/checks it. Public artifact publication
and an authenticated company backend are separate work.

## Documentation ownership

| Document | Owns |
|---|---|
| [ONBOARDING.md](ONBOARDING.md) | Whole-product orientation, first sessions and practical team advice |
| [next.md](next.md) | Single prioritized backlog and acceptance criteria |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Build/lint/consumer/device/distribution commands |
| [integration.md](integration.md) | Host wiring, capture/privacy/backup policy and variants |
| [OSS integrations](docs/OSS_INTEGRATIONS.md) | Supported library contracts and version limits |
| [Architecture](docs/ARCHITECTURE.md) | Module and observation/analysis/capture/lifecycle boundaries |
| [Recording clips](docs/RECORDING_CLIPS.md), [retention](docs/RECORDING_RETENTION.md) | Timing, journal/media budgets and omissions |
| [SAL format](docs/SAL_FORMAT.md) | Writer/reader schema, compression/checksums and compatibility |
| [Desktop](tools/local-bridge/README.md), [web](web/README.md), [backend](backend/README.md) | Each tool's detailed operating/API/storage contract |
| [Client fixes](docs/CLIENT_SAFETY_FIXES.md), [overlay design](docs/OVERLAY_DESIGN.md) | Dated migration decisions and remaining UI token coverage |
| [DEMO.md](DEMO.md) | Short synthetic demo walkthrough |
| [CHANGELOG.md](CHANGELOG.md), Git | Historical changes and older verification records |

Start the next engineering task from [next.md](next.md), reproduce the relevant behavior and run
checks appropriate to that change. Report exactly what was checked and what remains unverified.
