# QaLens onboarding

Updated 2026-10-05. This guide is for QA testers, Android integrators and engineers evaluating
QaLens for a team. It explains the Android SDK, browser players, Python desktop and local test
backend, then walks through capture, inspection, replay and sharing. Start with synthetic data.

QaLens is free under the [MIT license](LICENSE). It runs in an Android QA/debug build and produces
portable evidence. Production builds use a separate no-op SDK. Teams can use their own upload and
analysis services; the included backend is a local development mock.

## Choose where to start

| Your goal | Start here | Android needed |
|---|---|---|
| Explore a recording and try sending it | Web demo below | No |
| Replay files and manage a QA phone from one window | Python desktop below | Only for phone tools |
| Add QaLens to your app | SDK setup below, then [integration](integration.md) | Yes |
| Have an AI agent integrate into an existing app | [AI integration runbook](docs/AI_INTEGRATION.md), [consumer example](integration-tests/consumer/README.md) | For runtime acceptance |
| Inspect components or run your processors | Desktop pairing and automation below | Yes for live capture |
| Change the project | [HANDOVER.md](HANDOVER.md), [CONTRIBUTING.md](CONTRIBUTING.md), [next.md](next.md) | Depends on the change |

Use commands from the repository root unless a step says otherwise. The current development work
is on `dev`; pin an evaluated commit or an internal artifact version for repeatable team builds.
No Maven Central publication is claimed. The default local coordinates are version `0.9.0`;
that version alone does not identify which Git commit a team published internally.

```sh
git clone --branch dev https://github.com/Sal7one/QaLense.git
cd QaLense
```

## Understand the parts

| Part | What you get | Source |
|---|---|---|
| Active Android SDK | QA bubble, tester actions, Control Room, Compose inspection, diagnostics, capture and upload | `qalens-compose/`, supported by `qalens-core/` and `qalens-android/` |
| Optional Android integrations | Navigation Compose route reporting and an on-device archive player | `qalens-navigation-compose/`, `qalens-replay/` |
| Release SDK | Matching public entry points with capture and UI disabled | `qalens-noop/` |
| Sample app | Synthetic banking flows, OSS examples and device regression fixtures | `sample-app/` |
| Web app | Modern/classic replay, comparison, reports, explicit backend send; classic configuration editor | `web/` |
| Python desktop | Browser GUI with onboarding, pairing, components, recording library, adb tools and processors | `tools/local-bridge/` |
| Python test backend | Upload/chunk endpoints, local dashboard and deterministic verdicts | `backend/` |
| Node CLI | Reports and comparisons for scripts or CI | `web/tools/sal_report.js` |

The Python desktop serves the existing web viewer files directly. There is one replay reader and
no copied viewer implementation in the Python directory. Its GUI opens in your browser; it is not
a native desktop application. The Python backend is a separate process with a different purpose.

## Try the web demo

You need Python 3.9+ and a modern browser. Node.js is needed for CLI reports/tests, not browser
playback. The Python tools use the standard library; normal use needs no pip or npm installation.

```sh
./demo.sh quick
```

This starts the local mock dashboard on `http://127.0.0.1:8000` and the web app on
`http://127.0.0.1:8100/web/index-v2.html`. The bundled sample deliberately contains failures.
In the viewer, set **Settings → Backend URL** to `http://127.0.0.1:8000`, then choose
**Send to backend**. Check the received upload on the backend dashboard.

Stop the services started by that helper with `./demo.sh kill`. See [DEMO.md](DEMO.md) for a
short mobile walkthrough. The demo exercises selected paths; it does not validate every feature
or reproduce your host app's capture behavior.

For standalone replay without the backend:

```sh
python3 -m http.server 8100 --bind 127.0.0.1
# Open http://127.0.0.1:8100/web/index-v2.html
```

Drop a `.sal` or use the file picker. Offline file opening supports drag/drop; fetching the sample
requires HTTP serving. Modern and classic viewers share `web/sal.js`. The classic viewer also
edits `.appsal` configuration files. Full usage: [web guide](web/README.md).

## Set up the Android SDK

The tested repository toolchain is JDK 17, Kotlin 2.0.21, AGP 8.7.3, Android SDK 35,
Gradle 9.1.0 and Compose 1.7.8; minimum Android SDK is 23. The wrapper selects Gradle.
Set `JAVA_HOME` and `ANDROID_HOME` for your machine. Check compatibility with your host toolchain
before changing optional dependencies. [CONTRIBUTING.md](CONTRIBUTING.md) owns build commands.

A source/composite integration avoids publishing while evaluating. In the host settings:

```kotlin
includeBuild("../QaLense") // adjust to the checkout location
```

Use normal coordinates in the host app module and enable AndroidX in its `gradle.properties`:

```kotlin
dependencies {
    debugImplementation("com.qalens:qalens-compose:0.9.0")
    debugImplementation("com.qalens:qalens-navigation-compose:0.9.0") // optional
    debugImplementation("com.qalens:qalens-replay:0.9.0")             // optional
    releaseImplementation("com.qalens:qalens-noop:0.9.0")
}
```

Map custom QA/production variants deliberately, and check their dependency graphs. The active
library's release AAR still contains capture code; production safety comes from selecting
`qalens-noop`. The independent [consumer fixture](integration-tests/consumer/README.md) checks this separation.
Local Maven and internal repository distribution are covered in [integration.md](integration.md).

AndroidX Startup normally installs the active SDK. Run the QA build and check the bubble or
**QaLens Control** launcher. Identify the build in your application's setup:

```kotlin
QaLens.configure {
    enabled = BuildConfig.DEBUG // custom QA builds may need their own defined flag
    appName = "Example App"
    appVersion = BuildConfig.VERSION_NAME
    buildVariant = BuildConfig.BUILD_TYPE
    environment = "staging"
    allowUnmaskedVideo = false // frame recording is the default
}
```

Add `QaLensRoot { App() }` around the Compose root when useful, keep unique `Modifier.testTag`
values, and observe the existing controller with `QaLensNavigationObserver` or report routes through
`QaLens.setScreen`. `QaLensNavHost` is an optional wrapper with a limited signature; keep the
existing typed-route/transition NavHost when observing. Ordinary Activity Compose
roots and separate Compose Dialog/AlertDialog/Popup windows are discovered on Android 10/API 29+;
use `qaInspectionRoot` for separate windows on API 23–28 as shown in the integration guide.
Active inspection sits above a dialog and two-finger scrolling targets its content. Private application state and arbitrary native/WebView controls
are not available through Compose semantics.

If Startup was removed, the wrapper now tracks its own Activity and supplies the application
context. Call `QaLens.install(this)` from `Application.onCreate()` for application-wide hooks.
`QaLens.configure` only configures options. Control Room installs hooks on an explicit record action.
Startup runs before `Application.onCreate()`; see the integration guide for removing only QaLens
initializer metadata when deliberately owning startup. Preserve the existing Application, DI,
theme, navigation and insets. The AI runbook supplies discovery, troubleshooting and a handoff
template; sample code is an example, not a replacement app architecture.

## Connect useful evidence sources

Start with a small set of hooks and prove each one with a request/write and a short recording.
Use `QaLens.integrationReport()` or **Copy integration check** to see declared sources and retained
counts. Declaring a source does not prove it is connected to the client that does real work.

| Source | Supported path | Practical advice |
|---|---|---|
| OkHttp | `QaLensOkHttpInterceptor()` on the executing client | Metadata is default; body previews require a separate opt-in |
| Chucker | Its own interceptor plus QaLens on the same client, and its public launcher | Separate storage/privacy; no supported live Chucker transaction feed into QaLens |
| Other transports | `QaLens.networkSink("Name")` from completed-request callbacks | Native Ktor/Cronet/Apollo adapters are not shipped; avoid double reporting |
| Timber | `QaLensTimberTree()` beside the host trees | Keep messages small and exclude credentials |
| Room | `QaLens.observeRoom(db, "orders")` | Records table invalidations, not SQL queries or row contents |
| DataStore values | `QaLens.observeDataStoreValues("Settings", dataStore.data) { ... }` | Maps allowed decoded fields on a worker; live Control Room values and recording state |
| DataStore changes only | `QaLens.observeDataStore("Prefs", dataStore.data)` | Describes later changes without a value snapshot |
| App state | `QaLens.registerDataSource(...)` with cached allowlisted values | Providers run on main; never perform disk/database/network work inside one |
| Crash vendors | `reportCrash` or a host-owned crash bridge | Preserve the host handler and avoid forwarding the same crash back to its vendor |

The executed Chucker baseline is 4.1.0 with this repository's Kotlin toolchain. Do not assume a
newer Chucker version is compiler-compatible. Keep the legacy `networkFromChucker` flag false.
Dispose Room/DataStore hooks when their owners end. Detailed examples and privacy boundaries:
[OSS integrations](docs/OSS_INTEGRATIONS.md).

## Investigate evidence and configure tools

Keep the tester sheet simple; open the deeper tools when investigating a specific failure.

| Tool | Useful for | Limit to remember |
|---|---|---|
| Review evidence → Activity | Copy observed steps and a bug report; inspect captured failure stacks | Exports format evidence and do not file tickets or send messages |
| Review evidence → Elements / Device | Inspect concrete element findings, registered contracts and installed build/device facts | Automatic checks can miss problems; there is no overlay health score |
| Network and Logs | Inspect retained calls, latency, errors, filtering and timelines | Bounded histories and missing hooks can omit evidence |
| Crash, ANR, jank, memory and connectivity | Correlate captured failures and performance observations with a session | Coverage depends on lifecycle/device callbacks; a blank track is inconclusive |
| Control Room → Macros | Drive supported Compose actions and evaluate assertions after returning to the app | Named Android macros and pure snapshot macros are different APIs; unsupported steps fail |
| Control Room Database · Raw SQL and App data | Choose this app's SQLite files; search live allowed settings from existing host Flows | SQL can change data; file metadata alone does not expose decoded DataStore values |
| QA profiles and App Config | Import/export panel settings, webhook setup, macros and saved SQL | `.appsal` can contain user-entered secrets even when webhook credentials are omitted |

The integration guide owns registration examples. Stop observation/capture through the SDK's
runtime controls when testing ends; previously saved or shared artifacts remain where they were stored.

Control Room → **App data** leads with expandable, searchable values and their update status.
**Show storage files** is optional; encrypted/protobuf backing files require the app's decoded
value hook. SQL has separate Run/Save controls, a full database picker and scrollable bounded
results. See the [app data guide](docs/APP_DATA.md) for integration and QA acceptance.

## Record and mark a bug

Open the QA bubble for side-by-side **Record / Record HD**, **Screenshot**, **Mark a bug**,
**Inspect elements** or **Inspect tags**. HD requires the app's unmasked-video opt-in and Android
consent. Tags draws visible automation tags on the app; tap a component to copy, then Done to exit.
**Review evidence** provides Activity/Network/Logs/Elements/Device; **Connect to PC** opens
connection guidance, and **Control Room** provides recordings and configuration.
Quick actions keeps **‹ Review evidence** above its scrolling list; evidence keeps a wider
**‹ Quick actions** control, so neither screen requires scrolling to return.
Mark a bug adds a timestamped breadcrumb and screenshot; it does not create a ticket.
See the [mobile overlay guide](docs/MOBILE_OVERLAY.md) for the current menus. Recording saves
locally first. Stop from the REC control or the available recording controls, then replay/share/send.

During capture, **★ Clip** beside REC offers the last 10, 20 or 60 seconds and a custom 1–300 seconds.
It ends the clip at the mark and leaves the master recording running. Stop normally later; each
accepted mark becomes a separate `.sal` containing its available media and observations.

```kotlin
QaLens.configure {
    recordingMaxDurationMinutes = 60 // default, supports 1–180
    recordingClipPresetsSeconds = listOf(10, 20, 60, 120)
}
QaLens.startRecording()
QaLens.saveRecentClip(seconds = 20, label = "Checkout failed")
// Later: QaLens.stopRecording()
```

Full sessions preserve earliest observations within their budgets. Clips use an independent recent
buffer so later failures can survive a full master journal. Heavy logging can still exhaust the
recent buffer; coverage reports omissions. Clips are not continuous, lossless evidence guarantees.

Frame mode contains masked sampled JPEGs. HD mode contains full-display H.264 video and has no
per-node pixel masks. Enable it only when the host deliberately permits that capture:

```kotlin
QaLens.configure { allowUnmaskedVideo = true }
QaLens.startRecording(video = true) // Android screen-sharing consent still required
```

Older SDK versions did not require this host opt-in. If no consent dialog appears, check the flag,
SDK enabled state, foreground Activity and surfaced recording error. If capture starts but is black,
check protected/secure content. If it is empty or replay fails, check encoder/start/stop errors and
the actual archive. The sample passed HD on an API 36 emulator at 862 × 1920; this does not establish
that your app/phone works. Test your exact app, Android version and phone with the same SDK build.
The 2026-10-04 recording-control fix removes incorrect visual-context use that can terminate apps
under StrictMode, and reduces fallback-frame allocations. Use a build containing that fix; keep
host diagnostics enabled. A remaining crash needs its stack trace to distinguish UI, codec and host errors.

Current limits include 3,600 frames / 192 MiB, a 240 MiB video ceiling, 20 marks per session and
30 saved archives / 1 GiB. Encoding quality adapts to duration; video clips can start slightly earlier
at a preceding keyframe. Frame clips contain JPEG sequences, not a new MP4. Full-hour endurance,
physical-device encoders and abrupt-death recovery remain open. Exact budgets and timing:
[recording clips](docs/RECORDING_CLIPS.md), [retention](docs/RECORDING_RETENTION.md).

## Use the Python desktop

For replay alone, start unpaired; no connected device is required:

```sh
python3 tools/local-bridge/server.py --gui
# Open http://127.0.0.1:8765
```

The desktop opens **Landing** with connection and capture controls above adjustable panes:
**screen mirror → selected element → semantics tree**. Drag the separators/bottom height handle;
Reset layout restores defaults. Recordings, Data tools, Saved elements, Automation, Replay and Device tools are one click
away; Back/browser history work between pages. Both existing web players are embedded, and leaving
replay pauses video. Phone tools need adb, an authorized device/emulator and the active QA build.
After updating, rebuild the QA app and restart Python before testing.

Open **Live diagnostics** below the workspace for searchable Network/Logs with Errors only and
Follow/Pause. **App values** reads the host's decoded cache: pin up to ten fields and compare against
the first read or a new baseline while using the mirror. DataStore's binary file may simply need
the app's serializer; wire its existing decoded Flow with `observeDataStoreValues` instead of
trying to decrypt a backing file. Unconnected stores show metadata/guidance in **Data tools**.

**Data tools → SQL workbench** provides database/table selection, shared phone saved queries,
bounded masked result tables and Cancel. Desktop connections are read-only, run on IO and have a
ten-second cancellation watchdog. Control Room still owns its separate explicit write controls.
In Landing's **Keep a bug**, enter an optional note before Mark clip; it follows the mark into the
master timeline and exported clip. [The desktop guide](tools/local-bridge/README.md#live-diagnostics)
documents privacy, bounds, lifecycle and protocol limits.

If startup reports an occupied port, use the existing QaLens instance or add `--port 0` and open
the printed HTTP URL. Unwritable storage reports the blocked folder and a restart command using a
different data folder; restore access to the original folder to keep using its saved profiles/files.
See [startup recovery](tools/local-bridge/README.md#startup-recovery).

1. Connect by USB and approve Android USB debugging. Landing discovers phones and QaLens apps;
   choose the intended app and **Connect**. **Approve desktop** in the phone’s SDK Control Room.
   Credentials are generated and handled internally. This works in consuming QA apps without
   sample Settings or custom host code. **Device tools → Advanced manual pairing** supports older SDKs.
2. Profiles remember the phone/app without credentials. **Auto reconnect** repairs temporary USB
   outages within this approved session. A restart, device switch or revoked token requires approval
   again. **Stop PC inspector** on the phone revokes access; no app data is reset.
3. Keep **Link phone & web selection** checked. Select in the phone inspector and its details load
   in the browser; choose a tree element or **Inspect** mirror rectangle and the phone highlights it.
   Selection never taps the app. **Attributes**, **Selectors**, **Semantics**, **Tree position** and
   **Diagnostics** separate the details; tap/type/scroll use explicit buttons. Refresh after changes.
   Search tags/text/roles/actions and filter by action, role or tag presence.
4. **Start mirror** streams scrcpy 5.0 H.264 into Landing, including other apps. Use Chrome/Edge
   with WebCodecs; first Start downloads and verifies the official pinned server into the PC cache.
   It is explicit, memory-only, unmasked and stops when leaving Landing/hiding the tab/disconnecting.
   **Control** is the default: tap, continuous drag, wheel scroll, long-press and basic focused text/
   editing keys operate the phone through scrcpy over adb.
   **Preview** pauses input; **Inspect elements** shows all visible Compose outlines and selects
   without clicking the host. Selecting a linked tree item enters inspection; Control/Done returns
   to touch. **Open desktop window** optionally opens a native scrcpy client. A failed video stream
   requires Start mirror again; pairing Auto reconnect never repeats phone input.
5. On the phone use **Inspect elements → Search selectors & tags**, or
   **Review evidence → Elements → Search**. Select a result; **Actions & XPath selectors** offers copyable
   selectors with match counts. In the browser **Selectors** adds a builder, live match checking and
   explicit XML/JSON exports. XPath targets QaLens's visible Compose XML; prefer unique tags over
   changing text/tree positions. Full contract: [desktop guide](tools/local-bridge/README.md#search-and-selectors).
6. **Send to PC** beside **Copy test tag** remains an optional captured transfer to **Receive sent
   components**. Review values/bounds/ancestry, then **Save JSON** to persist the deduplicated component.

Hidden/password values are excluded according to capture policy; custom properties have limited
coverage. A component document is attributes and tree position, not a screenshot or recreated widget.
Its SHA-256 identity ignores live IDs/timestamps and changes when exported content/position changes.

Single taps inspect; two-finger drags scroll Activity content in inspect/tag mode. Drag
**Move inspector** to reposition filters/details. The bubble/dock use physical bounds in RTL/LTR.
Separate-window gesture forwarding and physical-device behavior still need broader testing.

Device tools offer Back/Home/Wake/Settings, explicit app launch, an optional installed/cached native
scrcpy window, and file push/pull through Downloads. Files are limited to 32 MiB; push replaces a
same-name file, and pull writes under PC `transfers/`. Embedded video installs only the pinned server;
it adds no Android SDK dependency. The previous PNG mirror remains behind a hidden code-only flag.
[Setup, offline preparation, limits and rollback](tools/local-bridge/README.md#scrcpy-setup-and-fallback)
also explain browser requirements and unverified platform/performance coverage.

Landing also offers **Start recording / Stop**, Frames or host-enabled HD with Android consent,
10/20/60-second or custom 1–300-second **Mark clip**, and **Watch latest**. Clips keep capture running
and become separate files after normal Stop; HD may start at a preceding keyframe. **Replay after
Stop** explicitly waits for this master to save, copies it and opens the shared web player. These
controls require the updated SDK in the consuming app; no sample-only code is needed.

**Take screenshot** temporarily hides/restores the in-window overlay by default; **Include overlay**
keeps its current visibility. This is a masked foreground app-window PNG, while the mirror is an
unmasked whole-phone stream. Review, Save PNG or Copy image where supported; no automatic gallery
or PC workspace save. Secure windows reject capture. Phone recording continues if the browser closes.

In **Recordings**, check completed phone archives, **Copy to PC** or **Watch** to open replay.
Landing’s **Collect finished recordings** is off by default. Polling runs while the desktop page is visible, so keep it open for collection.
Enabling takes the existing phone library as a baseline and copies new
completed files, including clips after stop. Temporary discovery/copy failures keep it enabled and
retry with a bounded delay. Device switches, disconnects and revoked pairing stop it.
Copy does not delete phone recordings. Default PC storage is `~/.qalens/bridge`; `--data-dir`
selects another directory. Profiles, saved components, recordings and processor outputs persist.
Previews, pairing and automatic-copy choices do not survive a restart. A recording transfer allows
400 MiB; the separate mock backend's upload limit is 64 MiB.

Both bridge listeners bind loopback and use explicit pairing. PC-to-device discovery/control uses
an owned `adb forward`; server exit removes its own forward and mirror. Keep the local OS/adb
session trusted. Full commands/API/storage policy: [desktop guide](tools/local-bridge/README.md).

## Run your own component automation

Start the desktop with a trusted local pipeline configuration:

```sh
python3 tools/local-bridge/server.py --gui \
  --pipeline-config tools/local-bridge/examples/pipelines.json
```

Save a component first, then select **Automation → Run on saved component**. The included
`component-summary` processor produces readable attributes and a tag check. You can replace it
with a program that consumes the saved component path and writes to its run directory. Configured
argv steps run sequentially off the request handler, with timeouts and no automatic retries.
Processors run with your user privileges; configure programs you trust.

The same pipeline works without a phone/browser:

```sh
python3 tools/local-bridge/process.py --component /path/to/component.json \
  --pipeline-config tools/local-bridge/examples/pipelines.json --pipeline component-summary \
  --data-dir /path/to/local/output
```

These pipelines process component JSON. Archive reporting is a separate CLI:

```sh
node web/tools/sal_report.js session.sal --for-ai
node web/tools/sal_report.js candidate.sal --compare baseline.sal --json
```

CLI exit 1 means observed failures/regressions; exit 2 means invalid input or reported partial
coverage without an observed failure. Exit 0 reflects the current rules, not complete QA coverage.
A `.sal` is an archive of media and evidence; `.appsal` is application configuration; component JSON
is a captured semantics document. Keep those file types separate in processors and imports.

## Send to the local test backend

The desktop library can work entirely without a backend. To test explicit upload separately:

```sh
python3 backend/server.py --port 8000
# For Android, replace the serial with your intended device:
adb -s YOUR_DEVICE_SERIAL reverse tcp:8000 tcp:8000
```

Android **Control Room → Webhook** uses `http://127.0.0.1:8000/webhook`. Test the endpoint, save a
session and choose **Send latest session** or its recording's **Webhook** action. The web viewer's
Backend URL uses the base URL `http://127.0.0.1:8000`. Check the local dashboard for receipt.
`adb reverse` carries device-to-PC backend traffic; it is different from the desktop bridge forward.
Your host controls cleartext policy; the SDK does not relax it. See [backend setup](backend/README.md).

The mock stores raw uploads and returns deterministic rules, not an AI model response. It has no
login, tenant isolation, TLS or company retention controls. Keep it local. For company deployments,
use an owned service with the required access, storage and deletion policies. Structured insights
identify leads and likely owners; temporal relationships do not prove cause.
Long HD archives can exceed the mock's 64 MiB upload limit. Use a shorter clip/local desktop copy,
or an owned backend whose validated limits suit your recordings.

## Advice before sharing with a team

- Prove one real request, Room write, preference change and short replay before adding more hooks.
  Check the archive's coverage, not just the live panel or score.
- Keep providers cached and logs small. Redaction starts on the caller; large payloads/custom regex
  or blocking host providers can still stall an app despite bounded background dashboard work.
- Review recordings/configurations before sharing. Pixel masks cover known Compose regions only;
  video, custom content, SQL/macro literals and Chucker data have separate privacy boundaries.
- Completed Android archives live in app-private files. Exclude them from host backups when they
  must stay local. PC backups and retention also belong to the operator.
- Verify all production variants resolve no-op dependencies. Source parity does not establish
  compatibility with every previously compiled client or toolchain.
- Use each team's evaluated commit/internal artifact version. Do not treat emulator success as
  a certification for every company's app, physical phone or Android version.

## Find the detailed contract

| Topic | Document |
|---|---|
| Android wiring, variants, media and backup exclusions | [integration.md](integration.md) |
| AI agent discovery, work order, troubleshooting and completion report | [AI integration runbook](docs/AI_INTEGRATION.md) |
| External active/no-op build example and its limits | [Consumer fixture](integration-tests/consumer/README.md) |
| Chucker, transports, Room, DataStore, Timber and crash hooks | [OSS integrations](docs/OSS_INTEGRATIONS.md) |
| Desktop pairing, API, files, profiles and processors | [Desktop guide](tools/local-bridge/README.md) |
| Browser replay, config editor and CLI | [Web guide](web/README.md) |
| Android capture actions, evidence views and connection | [Mobile overlay](docs/MOBILE_OVERLAY.md) |
| Android player synchronization, seeking and event following | [Mobile replay](docs/MOBILE_REPLAY.md) |
| SQL controls and useful decoded DataStore settings | [App data](docs/APP_DATA.md) |
| Test upload protocol and dashboard | [Backend guide](backend/README.md) |
| Archive schema, reader bounds and coverage | [SAL format](docs/SAL_FORMAT.md), [retention](docs/RECORDING_RETENTION.md) |
| Clip timing and long recording limits | [Recording clips](docs/RECORDING_CLIPS.md) |
| Build, device verification and local distribution | [Contributing](CONTRIBUTING.md) |
| Current engineering baseline and remaining work | [Handover](HANDOVER.md), [next.md](next.md) |
