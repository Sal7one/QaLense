# Contributing to QaLens

For a product overview read [ONBOARDING.md](ONBOARDING.md). Start development with
[HANDOVER.md](HANDOVER.md), the [integration guide](integration.md), [current backlog](next.md) and
[OSS integration contract](docs/OSS_INTEGRATIONS.md). A small reproduction and regression test are
more useful than a broad rewrite. Use synthetic data in public issues and fixtures.

Agents integrating into another app should use the [host integration runbook](docs/AI_INTEGRATION.md)
and [external consumer example](integration-tests/consumer/README.md). SDK tests and builds do not
establish that a different host's lifecycle, clients or production variants are correctly wired.

## Build locally

Use JDK 17, Android SDK 35 and Gradle 9.1.0. Set `JAVA_HOME` and `ANDROID_HOME` for your machine;
do not commit local SDK paths. Use the committed Gradle wrapper; no global Gradle installation
is required. The GitHub workflow records the automated verification matrix; instrumented device
checks are separate.

```sh
./gradlew :qalens-core:test :qalens-compose:testDebugUnitTest :qalens-replay:testDebugUnitTest :qalens-noop:testDebugUnitTest
./gradlew :qalens-compose:lintDebug :qalens-android:lintDebug :qalens-navigation-compose:lintDebug :qalens-replay:lintDebug :sample-app:lintDebug
./gradlew :sample-app:assembleDebug :sample-app:assembleRelease :sample-app:verifyReleaseIsolation
./gradlew -p integration-tests/consumer assembleDebug assembleRelease verifyReleaseIsolation
node web/test/read.test.js
node web/test/insights.test.js
node web/test/insights-ui.test.js
node web/test/recording-still.test.js
python3 backend/tests/test_backend.py
python3 tools/local-bridge/test_server.py
python3 tools/local-bridge/test_startup.py
python3 tools/local-bridge/test_workbench.py
python3 tools/local-bridge/test_connection.py
python3 tools/local-bridge/test_desktop.py
python3 tools/local-bridge/test_controls.py
python3 tools/local-bridge/test_scrcpy.py
python3 tools/local-bridge/test_insights.py
python3 tools/local-bridge/test_investigations.py
python3 tools/insights-tests/test_e2e.py
node --check tools/local-bridge/app.js
node --check tools/local-bridge/mirror-controls.js
node --check tools/local-bridge/diagnostics.js
node --check tools/local-bridge/scrcpy-stream.js
node --check tools/local-bridge/investigation-inbox.js
node tools/local-bridge/test_recording_transfer.js
node tools/local-bridge/test_polling.js
node tools/local-bridge/test_selectors.js
node tools/local-bridge/test_mirror.js
node tools/local-bridge/test_diagnostics.js
node tools/local-bridge/test_scrcpy_stream.js
node tools/local-bridge/test_investigation_inbox.js
```

The consumer fixture resolves normal `com.qalens:*` coordinates through `includeBuild`; it catches
integration failures that same-repository `project()` dependencies miss. Device checks are described
in [recording retention](docs/RECORDING_RETENTION.md), [OSS integrations](docs/OSS_INTEGRATIONS.md)
and [client safety](docs/CLIENT_SAFETY_FIXES.md).

For the expanded Android regression runner, run `adb devices` and replace `YOUR_DISPOSABLE_SERIAL`
with the intended disposable emulator serial:

```sh
./gradlew :sample-app:assembleDebug :sample-app:assembleDebugAndroidTest
adb -s YOUR_DISPOSABLE_SERIAL install -r sample-app/build/outputs/apk/debug/sample-app-debug.apk
adb -s YOUR_DISPOSABLE_SERIAL install -r sample-app/build/outputs/apk/androidTest/debug/sample-app-debug-androidTest.apk
adb -s YOUR_DISPOSABLE_SERIAL shell am instrument -w com.qalens.sample.test/com.qalens.sample.RecordingRetentionInstrumentation
```

Require the runner's `OK:` message. An adb process exit code alone does not prove assertions passed.
The runner changes sample activity contents/preferences and creates synthetic recordings; HTTP
fixtures bind loopback only.

### Compose dialog inspection

On a disposable API 29+ emulator with the debug/test APKs above:

```sh
adb -s YOUR_DISPOSABLE_SERIAL shell am instrument -w -e dialogsOnly true \
  com.qalens.sample.test/com.qalens.sample.RecordingRetentionInstrumentation
```

Require `OK: Automatic Compose Dialog/AlertDialog/Popup discovery` and no `FAIL:`. The fixture
uses unhooked host windows, a smaller background target underneath a dialog, and equal-sized popup
containers. It checks real phone taps, All filtering, tag copying, mobile selector search, PC
attributes/XPath/tap/type/scroll, hidden/password redaction, secure-flag inheritance/restoration,
unchanged dialog focus/geometry,
two-finger drag/no stationary click, screenshot ownership/dismissal, explicit-hook deduplication,
stale IDs, popup selection, background/resume and disable. Only synthetic screenshots go to the
sample's private cache. The full runner includes it on API 29+; API 23–28 still uses explicit roots
and needs its own device acceptance. [Integration recipe](integration.md#compose-inspection-across-host-windows).

### scrcpy mirror checks

Use the installed sample/debug-test APKs on a disposable emulator, Python desktop, adb and Chrome/
Edge with H.264 WebCodecs support. `python3 tools/local-bridge/scrcpy_mirror.py` optionally prepares
the checksum-verified 5.0 server cache; first Start mirror also sets it up. An installed native
scrcpy client is optional. Run desktop with an unused port (`--port 0` prints one), connect the
sample once, and keep that saved emulator/profile. Start this test-only fixture in another terminal:

```sh
adb -s YOUR_DISPOSABLE_SERIAL shell am instrument -w -e scrcpyGuiSeconds 300 \
  com.qalens.sample.test/com.qalens.sample.RecordingRetentionInstrumentation
python3 tools/local-bridge/check_scrcpy_device.py \
  --url http://127.0.0.1:YOUR_DESKTOP_PORT --adb /path/to/adb --fixture --hd
```

`--fixture` uses only the synthetic test token/port and a saved `com.qalens.sample` emulator profile;
it cannot target a physical/customer app. Wait for fixture UI/bridge readiness before the check.
`--hd` requires FFmpeg, chooses the real API 36 consent dialog and verifies copied master/clip MP4s.
Omit it for input/Frames/HTTP coverage; without `--fixture`, the tool needs an already approved normal
sample emulator. It tests real touch/text (fixture), wheel movement, rotation, SDK selection,
Preview/Inspect input refusal, screenshot/recording coexistence, encoded-video decoding when FFmpeg
is present and fresh stream restart. Before any phone input it reports first encoded HTTP arrival;
the disposable fixture also requires arrival within five seconds. This is separate from browser
click-to-display timing. Temporary PC media is removed; synthetic phone archives persist.
Let the fixture finish: require its `OK: Disposable scrcpy fixture restored` message. It restores
HD policy/UI/bridge after 1–600 seconds, and is absent from the SDK/release APK.

For manual browser acceptance, use the held fixture with Advanced manual pairing's same **synthetic**
test token `synthetic-scrcpy-desktop-0123456789`, device port `18766` and intended emulator/package.
Start mirror with the phone idle: verify startup stages, first-frame appearance without tapping,
and the reported click-to-display time. First-time download is a separate stage; an eight-second
browser first-frame stall must stop with an actionable message. Check idle rotation and Stop/restart
during startup as well. Then verify moving video, tap counter, actual text field, drag/wheel, focus keys, Back,
letterboxing, resizable panes and portrait/landscape. Inspect/tree selection should highlight/read
SDK attributes without a host click; Preview blocks input. Return to Control, test Frames/HD with
normal OS consent, recent clip marks, screenshots and master/clip replay. Stop/restart, hide/leave
the page, try a second tab and revoke the bridge on the phone: no stale gesture may target a new
stream or continue after cancellation. After failure choose Start explicitly; auto reconnect repairs
SDK pairing and never repeats input. Report browser pixels/hardware performance separately from
controller/transport/FFmpeg and Android evidence. The current browser rendering check is unverified.

For only the overlay/log-flood regression, use the same installed APKs on a disposable emulator:

```sh
adb -s YOUR_DISPOSABLE_SERIAL shell pm grant com.qalens.sample android.permission.POST_NOTIFICATIONS
adb -s YOUR_DISPOSABLE_SERIAL shell input keyevent KEYCODE_WAKEUP
adb -s YOUR_DISPOSABLE_SERIAL shell wm dismiss-keyguard
adb -s YOUR_DISPOSABLE_SERIAL shell am instrument -w -e overlayLoadOnly true com.qalens.sample.test/com.qalens.sample.RecordingRetentionInstrumentation
```

Require `OK: Overlay/Activity/Logs/Network remain responsive`. The case sends a 12,000-line burst,
then keeps background/main-thread logs and network observations flowing during tab switches.
`adb logcat -d -s QaLensLoadTest:I` shows its worst measured main-thread heartbeat delay; each
heartbeat must complete within 2.5 seconds. This is a synthetic regression, not a real-host ANR trace
or a frame-rate guarantee. The full runner includes the same case.

For only the local bridge and inspector gesture cases, install the same debug/test APKs and run:

```sh
adb -s YOUR_DISPOSABLE_SERIAL shell am instrument -w -e bridgeOnly true \
  com.qalens.sample.test/com.qalens.sample.RecordingRetentionInstrumentation
```

Require `OK: Local bridge semantics/actions/privacy/shutdown and LTR/RTL inspector gestures pass`.
The full runner includes this case. It injects real single/two-pointer gestures and validates filters
against current system/IME insets; run with gesture and three-button navigation. The
[PC bridge guide](tools/local-bridge/README.md) describes pairing and adb-forward ownership.

## Focused device checks

### Local-model insights checks

Lens 2.0 uses no Python/npm model dependency. Python 3.9+ and Node 18+ run the synthetic HTTP
workflow checks below. They execute the real `.sal` reader/evidence builder, both provider request
formats, authenticated desktop jobs, grounding and bounded image/flood behavior; they do not evaluate
an LLM's reasoning or a vision model's understanding.

```sh
node web/test/insights.test.js
node web/test/insights-ui.test.js
node web/test/recording-still.test.js
python3 tools/local-bridge/test_insights.py
python3 tools/local-bridge/test_investigations.py
python3 tools/insights-tests/test_e2e.py
./gradlew :qalens-core:test :qalens-replay:testDebugUnitTest
```

The phone-to-PC check below additionally starts its own synthetic provider and temporary PC
workspace, runs native Insights, and exercises the actual authenticated SDK inbox through an owned
adb forward. It requires the current sample/test APKs installed on the selected disposable emulator
and `adb` on PATH. It tests evidence/report/still transfer, explicit save/dedup and PC reanalysis;
it removes only its forward/workspace and performs no model download:

```sh
python3 tools/insights-tests/test_device_handoff.py YOUR_DISPOSABLE_SERIAL
```

For manual UI checks, prepare a disposable synthetic recording and controlled provider:

```sh
python3 tools/insights-tests/fixture_archive.py /tmp/qalens-synthetic-investigation.sal
python3 tools/insights-tests/model_fixture.py --port 0 --slow-model
# The fixture prints an unused loopback port. Model: qalens-synthetic-triage (NOT an LLM).
# --slow-model also exposes z-fixture-slow for actual UI cancellation checks.
python3 tools/local-bridge/server.py --gui --port 0 --data-dir /tmp/qalens-insights-desktop
```

Open the synthetic `.sal` in modern Replay → Insights, choose 52 seconds, connect the printed model
URL, review the context and Analyze. Check the HTTP 503/buffering citations, playback seeking,
question/window/model changes, Cancel and navigation while a slow request is pending. Compare with
standalone `/web/index-v2.html`. Fill Expected/Actual and check tester labels, captured action IDs,
empty-step disclosure and QA Markdown. Pair a disposable phone, use Send to PC with and without a
report, review Phone investigations, explicitly analyze/save and verify duplicate handling. Incoming
images must require fresh model opt-in; selected evidence must not pretend to contain playable video.
The fixture additionally accepts `fixture-invalid-json`,
`fixture-unknown-reference` and `fixture-slow` model identifiers through explicit test clients; they
must produce a malformed-response error, grounded omissions and cancellation respectively. These
are test behaviors, not diagnoses. Ctrl-C each process you started and remove only its temporary files.

The focused Android fixture requires the built debug/test APKs installed on a **disposable** emulator
and the synthetic provider running on the PC. Supply its actual port:

```sh
./gradlew :sample-app:assembleDebug :sample-app:assembleDebugAndroidTest
adb -s YOUR_DISPOSABLE_SERIAL shell am instrument -w -e insightsOnly true \
  -e insightsBaseUrl http://10.0.2.2:YOUR_FIXTURE_PORT \
  com.qalens.sample.test/com.qalens.sample.RecordingRetentionInstrumentation
```

Require the runner's `OK:` message and no `FAIL:`. It checks discovery without automatic upload,
explicit text/still consent, saved frame/HD extraction, original IDs, both investigation targets,
current-runtime provenance, citation seeking, cancellation and background/resume recovery. These
are the explicitly named synthetic cases, not arbitrary host/media behavior. Physical phones need
an authorized private endpoint or owned
adb reverse, and their own debug HTTP/TLS policy. Use an installed local chat/vision model to evaluate
report quality against known app failures; the canned provider cannot certify those results.
[Setup, contracts and host player-state recipe](docs/LENS_2.md).

Desktop recording and screenshots have a focused real-protocol runner:

```sh
adb -s YOUR_DISPOSABLE_SERIAL shell am instrument -w -e desktopCaptureOnly true \
  com.qalens.sample.test/com.qalens.sample.RecordingRetentionInstrumentation
```

Require `OK: Desktop recording/clip commands` and no `FAIL:`. It exercises frame Start/Stop/clip
exports and bug-note master/clip tracks, host HD rejection, awaiting-consent cancellation, authenticated commands, PixelCopy PNG
decoding/password masks, secure windows, exact overlay visibility and screenshot completion racing
phone recording Start/Stop. It is separate from the full runner. For actual browser HD QA, the same
runner accepts `-e desktopGuiHoldSeconds 300`: a temporary sample-only HD opt-in, normal app UI and
ordinary phone pairing/OS consent. It restores HD policy/access on expiry and does not assert
browser results itself. See the [desktop guide](tools/local-bridge/README.md#mobile-gestures-and-verification).

Desktop live data and read-only SQL have a separate real-protocol runner:

```sh
adb -s YOUR_DISPOSABLE_SERIAL shell am instrument -w -e desktopDataOnly true \
  com.qalens.sample.test/com.qalens.sample.RecordingRetentionInstrumentation
```

Require `OK: Desktop live evidence/decoded DataStore` and no `FAIL:`. It owns a real Preferences
DataStore and WAL SQLite database, verifies initial/foreground decoded updates, body/log masking,
standard preference/envelope coverage, read-only/write/multi-statement/path rejection, bounded
rows/blobs, shared saved-query identity, cancellation/timeout, responsive bridge/main, privacy
invalidation and Stop/disable/restart. No original consuming app or cryptography is exercised.
For manual browser QA, `-e desktopDataGuiSeconds 300` holds normal sample UI with the decoded store,
synthetic logs/network updates every eight seconds and a disposable database/preferences. Connect
and approve normally; verify Follow/Pause, search/Errors only, pin/comparison, SQL tables/results/
saved queries/cancel and optional file guidance. Expiry restores config/queries and removes only
fixture stores/files. This is test-APK code, absent from the SDK/release. It does not assert the
browser UI itself. Keep GUI checks distinct from protocol and no-op build evidence.

Control Room SQL and real decoded Preferences DataStore have a focused UI runner:

```sh
adb -s YOUR_DISPOSABLE_SERIAL shell am instrument -w -e dataUiOnly true \
  com.qalens.sample.test/com.qalens.sample.RecordingRetentionInstrumentation
```

Require `OK: Control Room SQL picker/actions/results/cancellation` and no `FAIL:`. The case
creates six long-named SQLite fixtures, verifies action dimensions, saved queries, lazy results,
syntax failure and cancellation. It uses a real app-owned Preferences DataStore for foreground
updates, credential/current-rule redaction, pause/resume, stop, completion/error and ownership.
Optional file checks cover redacted plain preferences and a synthetic encrypted-envelope marker.
See [app data](docs/APP_DATA.md). Repeat at 360×640 dp, 150% font and three-button navigation on a
disposable emulator; restore display/font/navigation afterwards. Private cache screenshots are
`qalens-control-sql-after.png`, `qalens-control-app-data-after.png` and, on failure,
`qalens-data-ui-failure.png`. Do not commit captures. This focused case is separate from the full runner.

Mobile replay synchronization has a focused synthetic frame/video runner:

```sh
adb -s YOUR_DISPOSABLE_SERIAL shell am instrument -w -e replayOnly true \
  com.qalens.sample.test/com.qalens.sample.RecordingRetentionInstrumentation
```

Require `OK: Android frame/video replay clock`, with no `FAIL:` output. It checks actual decoded
colors, event/state synchronization, auto-follow/manual browse, playing scrubs, fullscreen surfaces,
background pause, legacy alignment, trailing evidence and restart. No projection consent is needed;
this checks playback, not capture. See [mobile replay](docs/MOBILE_REPLAY.md).

Overlay selector search/copy uses actual accessible controls:

```sh
adb -s YOUR_DISPOSABLE_SERIAL shell am instrument -w -e selectorsOnly true \
  com.qalens.sample.test/com.qalens.sample.RecordingRetentionInstrumentation
```

Require `OK: Overlay tag search selects the host component and copies validated QaLens XPath`.
`bridgeOnly` also checks selector/XML privacy, unique/duplicate XPath, unsupported expressions and
inspection-only selection. For a manual live browser check, `-e desktopSelectors true` keeps the
synthetic sample available through explicit checkpoints in private `files/qalens-selector-test`:
`ready` → browser Connect; write `approve` to `signal` for the test fixture to press real phone
approval; `paired` → write `select-phone`; `phone-selected` → inspect automatic browser details,
choose another tag there, write that tag to `expected-tag` and `check-pc` to `signal`; `pc-selected`
→ write `done`. Each wait expires after three minutes; failure/finish revokes the bridge and cleans
checkpoints. These controls belong to the disposable test APK, never a consuming app. Browser
search/filter/Copy XPath/Check matches and preview are checked separately through their real UI.

Use [recording clips](docs/RECORDING_CLIPS.md) for preset/custom clips, longer sessions and real
HD consent approval/denial modes. HD checks require the OS dialog to be handled promptly on the
disposable device. Physical-device and full-hour endurance remain open.

The positive tester workflow covers quick screenshots/bug marks, actual Record/REC Stop,
the five evidence views and host extension, Back/search/report copy, PC connection guidance, Watch API mode,
successful macro typing/tapping/assertions/capture, `.appsal` round trips, a real profile-attributed
loopback upload, Android player controls and panic discard:

```sh
adb -s YOUR_DISPOSABLE_SERIAL shell am instrument -w -e workflowOnly true \
  com.qalens.sample.test/com.qalens.sample.RecordingRetentionInstrumentation
```

Require `OK: Quick screenshot/bug mark`. A failure preserves its synthetic sample screen in the
target app's private cache as `qalens-test-failure.png`; do not commit generated captures.

`workflowOnly` also requires both inspection modes/exits, the fixed Review/Quick actions links,
48–76 dp evidence action heights, a wider navigation button and side-by-side Record/Record HD
with HD disabled when the host disallows unmasked video. Actual HD from that quick button has
a separate consent-approved disposable-emulator case:

```sh
adb -s YOUR_DISPOSABLE_SERIAL shell am instrument -w -e quickVideoOnly true \
  -e projectionConsent approve \
  com.qalens.sample.test/com.qalens.sample.RecordingRetentionInstrumentation
```

Require `OK: Quick actions HD button` and no `FAIL:`. Test code deliberately enables unmasked
capture in the sample, handles the real English OS consent dialog, presses REC Stop and decodes
the saved MP4. It restores capture settings; production still requires host opt-in and consent.
For precompiled-SDK/newer-Compose Control Room coverage, use the
[external consumer runtime test](integration-tests/consumer/README.md#newer-compose-runtime-smoke-test).

Focused HD modes accept `-e projectionConsent approve` or `-e projectionConsent deny` to click
the real **English sample emulator** OS dialog. This is opt-in Android-test code, absent from the
SDK, and does not bypass production consent. Omit it and handle the dialog manually on other
devices. For example, exercise background capture and the actual notification Stop action:

```sh
adb -s YOUR_DISPOSABLE_SERIAL shell am instrument -w -e videoRecoveryOnly true \
  -e projectionConsent approve \
  com.qalens.sample.test/com.qalens.sample.RecordingRetentionInstrumentation
```

Require `OK: HD stays active in background`; the output must contain a decodable video and the
foreground recording notification must disappear. Repeat clip checks with draw-over-apps allowed
and denied, and inspector checks with three-button navigation and large fonts. Focused modes run
sequentially on a device, separately from the full runner.

The SDK pairing controls have a separate UI regression, including a host using only `QaLensRoot`
without Startup installation. It starts pairing through Connect to PC → Manual pairing, sends a
component, reads the inbox with Control Room foreground, rotates the token, then stops from the
overlay connection screen:

```sh
adb -s YOUR_DISPOSABLE_SERIAL shell am instrument -w -e pcUiOnly true -e manualRootOnly true \
  com.qalens.sample.test/com.qalens.sample.RecordingRetentionInstrumentation
```

Require `OK: SDK PC inspector pairs from overlay`. The focused Control Room recording tests in
[recording clips](docs/RECORDING_CLIPS.md) cover both recording buttons and the old null-context
clip callback. These focused modes are additional to the full runner.

For the complete Android → adb → Python → shared transfer-controller path, install the same
APKs and run the opt-in check on a disposable emulator (Node 18+ and Python 3):

```sh
node tools/local-bridge/test_device_transfer.js emulator-SERIAL
```

It owns a temporary PC server/storage and adb forward, simulates an interrupted forward, and
verifies automatic copy of a newly completed master/clip with Control Room foreground, hashes,
private file permissions, deduplication and revoked pairing. It is a transport/controller check;
use the actual browser to verify checkbox rendering and navigation.

## Review expectations

- Keep pure policies and models in core; Android behavior belongs in the Android modules.
- Give every public SDK API a release no-op twin. Extend the consumer fixture and parity check.
- Observation must preserve host requests, response streams, cancellation and exceptions.
- Keep optional tools optional. Use their public APIs and record tested versions; do not inspect
  private databases or invent reflection hooks. Add a compileable example and relevant tests.
- Preserve both replay viewers and old archives. Add coverage metadata when capture is partial.
- Distinguish tested behavior from roadmap items. Never infer app health from missing evidence.

## Distribution

Local/composite integration and a generated Maven repository are supported. This repository does
not establish that `com.qalens` artifacts are available from Maven Central. Run
`scripts/release_internal.sh --verify` for a checked local zip, or add `--version 0.9.0-preview1`
for a preview with matching coordinates across modules. The script clears stale generated
publications before packaging. Publishing externally is a separate step.

The Landing connection flow has opt-in disposable-emulator checks after installing debug/test APKs:

```sh
adb -s YOUR_DISPOSABLE_EMULATOR shell am instrument -w -e pcPairingOnly true \
  com.qalens.sample.test/com.qalens.sample.RecordingRetentionInstrumentation
python3 tools/local-bridge/test_device_pairing.py emulator-SERIAL --device-port 8767
```

The latter creates its own temporary server/storage/forward and uses actual SDK phone approval on
8767, exact-request status/port and authenticated lightweight health. It removes its forward during
approval to require truthful `connecting` and recovery without reapproval, then checks attributes/
save/dedup, default scrcpy H.264, forward repair, paused-host Send/recording copies and revoked access.
The focused `pcPairingOnly` runner additionally holds main busy to verify independent health,
recording-control timeout and sender/privacy gates. These do not test browser rendering; verify
Landing separately in the GUI.
