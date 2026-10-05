# Android verification — 2026-10-04

## Desktop live data and SQL addendum — 2026-10-05

API 36 `desktopDataOnly` passes actual decoded Preferences DataStore initial/foreground updates,
bounded redacted log/network/body observations, explicit plain preference snapshots/envelope guidance
and metadata-only DataStore files. A real WAL database verifies read-only SELECT/CTE, write/PRAGMA/
ATTACH/multi-statement/path rejection, 100 retained rows/blob placeholders, shared saved-query IDs,
cancel/ten-second watchdog and responsive main/socket. Privacy changes invalidate previous results;
disable/Stop/restart clears the job. The resolved-directory check handles the emulator preference
path after the first full-path-equality refusal. Synthetic envelope markers are not cryptography.

`desktopCaptureOnly` additionally decodes saved v2 GZIP `marks.json` and `analysis.json`, confirming
the exact bug note in the master mark and exported clip. `dataUiOnly` and the full Android runner
pass existing shared Control Room/Room/DataStore/recording/bridge/OSS/privacy/replay/traffic cases.
Local checks pass 240 units (183/37/17/3), Compose/Android/sample lint with zero errors, sample and
independent consumer build/release-isolation gates, 42 Python tests and five desktop JS suites/syntax
plus web-reader/CLI. SDK public facade/no-op subscription and `.sal` format stay unchanged.

Live visual acceptance for the new desktop diagnostics/Data tools views is unverified: desktop UI
access reported locked Mac/no browser. Prior mirror/capture browser checks below remain separate.
Use the documented disposable `desktopDataGuiSeconds` fixture; see [commands](../CONTRIBUTING.md#focused-device-checks).
Physical phones, other runtimes/encryption/serializers, full-hour endurance and real consuming-app
ANR/HD behavior remain separate acceptance work.

## Desktop capture addendum — 2026-10-05

The focused `desktopCaptureOnly` runner passes on API 36 with the actual SDK socket protocol and
recorder. It verifies authentication, typed/rejected commands, frame Start/Stop and separate marked
clip export, host HD rejection and pending-consent cancellation without capture, PixelCopy PNG
decoding/password masks, secure-window refusal and exact visible/invisible overlay restoration.
Additional captures completing during phone recording Start/Stop preserve recorder visibility.
The fixture resumes its singleTop host asynchronously after the share chooser and checks readiness
with a bridge read; synchronous instrumentation launch would wait for an activity Android reuses.

Real desktop browser → Python → adb → SDK checks separately pass Android approval, mirror Control
tap/navigation/wheel scrolling, Inspect outlines/selection and selected attributes, Control/Preview routing, pointer/
keyboard pane resizing and 1440/900/621px layouts. With the temporary sample-only GUI HD fixture,
desktop Start opens actual OS consent; test approval starts capture, a ten-second mark keeps it
running and Stop exports the master/clip. Exact-master automatic replay and separate clip replay
decode 862×1920 video in the shared web player. Clean and Include overlay screenshot pixels are
inspected; Codex browser PNG download completion is unconfirmed. The fixture restores HD opt-in/
bridge access on expiry; neither pairing nor consent helpers are part of a consuming SDK app.

The final full Android regression runner passes retention/storage/Compose/bridge/privacy/OSS/
Room/DataStore/SQL/macros/replay/webhook and continuous traffic. Local checks pass 238 units
(183/35/17/3), Compose/sample lint with zero errors, sample debug/test/release/isolation and
independent consumer debug/release/isolation, plus 40 Python tests, four bridge JS suites/syntax
and web-reader/CLI regressions. No public facade or `.sal` schema change. Physical phones, Windows,
installed scrcpy, other runtimes, full-hour endurance and reported consuming-app ANR/HD failures
remain separate acceptance work. See [desktop verification](../tools/local-bridge/README.md#mobile-gestures-and-verification)
and [focused commands](../CONTRIBUTING.md#focused-device-checks).

## Compose host compatibility and quick actions addendum — 2026-10-05

The old SDK's Control Room crash reproduced in the independent consumer on API 36: SDK compile
Foundation 1.7.6, consumer runtime 1.8.2, `FlowRow` `NoSuchMethodError` at lines 330/304/564.
All three calls now use stable Compose UI `Layout`; the host and SDK dependency baselines stay
unchanged. The focused consumer is built with `-PqaComposeRuntime=1.8.2`; dependency reports
confirm separate SDK compile and host runtime versions. CI builds this fixture but does not run
it on an emulator. Source-only compilation would not have found this binary failure.

| Focused case | Executed checks |
|---|---|
| Newer-runtime consumer, normal and 360×640 dp / 150% font / three-button navigation | SDK-owned Control Room launches, rescue and configuration groups render, all saved-recording buttons are visible with at least 48 dp touch bounds and no overlap; only the owned synthetic placeholder is deleted and settings are restored before test completion |
| `workflowOnly`, normal and small/large-font/three-button | Wide Quick actions return button; fixed Review evidence survives scrolling; centered 48–76 dp report/Refresh controls; both inspection modes, named Done/system Back and exclusive mode state; side-by-side recording choices with disabled HD when host opt-in is false; actual frame REC Stop, capture/config/macros/upload/replay/Panic |
| `quickVideoOnly`, normal and small/large-font/three-button | Actual quick HD button after test-only host opt-in, real Android consent approval, native REC Stop, saved `video.mp4` and decoded video frame; settings restored |

The small consumer test found overlapping expanded touch bounds despite separated visual buttons;
Control Room small buttons now reserve a real 48 dp height. The fixture measures one stable
viewport rather than comparing bounds from different scroll positions. The sample workflow
dismisses its synthetic macro's keyboard before checking the separate upload task. The HD test
connects accessibility before concurrent consent/UI checks and scrolls the OS dialog as needed;
these helpers are test-only and preserve production consent. Private screenshots were inspected,
not committed. Emulator display/font/navigation settings were restored after the small cases.

Local checks pass 238 unit tests (183 core / 35 Compose / 17 replay / 3 no-op), Compose/sample
lint with zero errors, sample debug/test/release/isolation and independent consumer release
build/isolation plus default and newer-runtime debug/test builds. Unchanged outputs may be reused.
No public SDK API, recording format or web/desktop/backend implementation changed. Other Compose/
OS versions, physical-device TalkBack and the consuming app's separate HD failure still need their
own acceptance; reproducing this Control Room crash does not establish every host failure's cause.
See [mobile overlay](MOBILE_OVERLAY.md), [commands](../CONTRIBUTING.md#focused-device-checks)
and the [external consumer](../integration-tests/consumer/README.md#newer-compose-runtime-smoke-test).

The final full runner passes recording retention/storage, real Room/DataStore state, privacy,
Chucker/adapters, inspection/bridge/LTR/RTL/two-finger gestures, SQL/macros/replay/webhook and
continuous traffic. That load case observed 2,452 background iterations and an 8 ms worst main
heartbeat, a synthetic check rather than a consuming-app responsiveness guarantee. Focused
SQL/app-data UI passes normal and small/large-font/three-button setups; the bridge/gesture runner
also passes the small setup. Historical desktop/backend/clip/endurance cases below were not rerun.

## Control Room data addendum — 2026-10-05

The focused `dataUiOnly` runner uses actual accessible Control Room controls, six private SQLite
fixtures and an app-owned real Preferences DataStore. It passes at normal API 36 emulator size
and 360×640 dp / 150% font / three-button navigation. Private SQL/app-data screenshots are inspected;
generated captures are not committed. The UI test requires Run/Save/Cancel to be brought fully into
view with minimum 48 dp height and 100 dp width, rather than measuring a clipped scroll boundary.

| Case | Executed checks |
|---|---|
| SQL | Sixth long database beyond the old four-chip cap; editable SQL/name, action dimensions, first 12/all 30 retained rows via lazy scrolling, saved Run/Delete, table listing, syntax error, canceled recursive read and a subsequent successful query |
| Live values | Initial decoded state without a change event, actual preference update while Control Room is foreground, searchable values, credential masking and no raw value in the count-only change label |
| Privacy/lifetime | New global rules hide old previews, disable pauses/hides, re-enable refreshes without a change label, stop removes only owned values, finite Flow keeps its last value, newer manual provider survives stop, throwing mapper surfaces unavailability |
| Storage details | Plain preferences mask credential-like fields and redact full text before preview truncation; SDK prefs are excluded; a synthetic AndroidX envelope marker shows decoded-hook guidance instead of ciphertext; DataStore file names/sizes remain metadata |

A saved-query rerun originally reused its expanded-row toggle when equal results arrived; every
new execution now resets the preview. File fixtures do not test cryptography or arbitrary encrypted
formats. Local builds pass 238 units (183/35/17/3), Compose/sample lint with zero errors, sample
debug/test/release/isolation and independent consumer debug/release/isolation. Unchanged tasks
reuse outputs. No `.sal` schema or capture-consent changes; web/Python/backend checks were not
rerun for this Android work. Physical devices/TalkBack, other serializers/OS versions and the
reported consuming-app failures remain unverified. See [app data](APP_DATA.md) and
[focused commands](../CONTRIBUTING.md#focused-device-checks).

The final `workflowOnly` case passes at 360×640 dp / 150% font / three-button navigation,
including actual capture, Control Room macros/configuration, loopback upload and replay. The
full Android runner passes 600-request/log retention, archive storage, real Room/DataStore and
recorded preference state, client privacy/disable/resume, SQL/macros/replay/webhook, Chucker and
adapter contracts, Compose/bridge/gesture cases and continuous traffic. The latest load case
observed 2,431 background iterations and a worst measured main heartbeat of 3 ms. This does
not prove the consuming-app ANR is fixed. Focused projection consent/HD, clip/endurance and
replay-color modes were not rerun for this app-data change. Emulator display/font/navigation
settings were restored after checking the narrow layout.

## Overlay cleanup addendum — 2026-10-05

The current overlay has quick capture/inspection actions and five evidence views; the historical
12-tab menu in the matrix below is superseded. See [mobile overlay](MOBILE_OVERLAY.md) for the
actual menus, removed controls and compatibility/privacy contract.

Focused API 36 checks pass on the final overlay:

| Case | Executed checks |
|---|---|
| `workflowOnly`, normal and 360×640 dp / 150% font | Fixed Close, Back through search/checks/actions, actual search/filter results, global component selection and Done, synthetic captured stack/report copy, five built-in views and host extension; screenshots/marks/REC Stop, resumed-host Control Room macro, config/profiles, real loopback upload feedback, replay and Panic |
| `overlayLoadOnly` | 12,000-log burst then continuous background/main-thread log/network observations during Activity/Network/Logs selection; actual selected pane and main heartbeat required |
| `bridgeOnly` | Redacted semantics/selectors/XML, rejection/privacy/shutdown, actual tap/type/scroll, LTR/RTL bubble/dock bounds and two-finger host scrolling |
| `selectorsOnly` | Actual overlay element search/selection and validated QaLens XPath copy |
| `pcUiOnly` with `manualRootOnly` | SDK-owned manual pairing, component send with paused host, token rotation, Stop, disable/re-enable; no Startup or sample settings dependency |

The small workflow also passes with three-button navigation. Expanded filters originally left
the result list without usable height in that configuration; filters now scroll with results,
and the regression requires the filtered row to be visible. Inspector gesture tests choose travel
away from the actual safe top/bottom boundary instead of assuming a fixed fraction of screen height.

The final load run observed 2,545 background iterations and a worst measured main heartbeat of
6 ms. This is a synthetic sample check, not a frame-rate guarantee or consuming-app ANR trace.
Workflow preview images were inspected from private cache; none are committed. The test reports
must contain `OK:` and no `FAIL:`. Continuous updates can invalidate an accessibility node; the
load fixture falls back to an actual touch and still requires the selected tab's content.

Local verification passes 233 unit tests (183/31/17/2), Compose/sample lint with zero errors,
sample debug/test/release and independent consumer debug/release/isolation, plus sample release
isolation. Unchanged tasks reuse outputs. This cleanup does not rerun every historical capture,
backend, desktop or endurance case below. Physical phones/TalkBack, other host/OS/Compose versions
and actual consuming-app capture failures remain unverified.

## Mobile replay addendum — 2026-10-05

The focused `replayOnly` API 36 runner passes on the normal emulator display and at 360×640 dp
with 150% font. Synthetic archives deliberately reverse input track order; a six-second H.264
red/green/blue fixture proves actual decoded pixels match seeks, not only reported timestamps.
It checks chronological/current visible Timeline rows, Network/Logs/State sync, selected-track
Previous/Next, first error, manual browse/Follow, continued Play after slider drag, paused seeking,
fullscreen surface recreation/Back, background pause, trailing events after video end, legacy
end alignment, restart and explicit evidence playback after decoder failure. A 160k-character
detail stays bounded in its row. Closing while playback is active also completes.

The existing `workflowOnly` recording/config/upload/player/Close path and consent-approved
`videoRecoveryOnly` real HD background/notification Stop pass with the encoder-worker start
timestamp. That timestamp avoids UI-queue delay; it does not prove device-specific sub-frame
calibration. The playback runner itself does not request capture consent or exercise projection.

Local checks pass 233 unit tests (183 core / 31 Compose / 17 replay / 2 no-op), changed Compose/
replay/sample lint with zero errors, sample debug/test/release and both sample/independent consumer
release gates. The consumer's debug/release builds pass. Unchanged tasks may be cached. Commands
and coverage contracts are in [mobile replay](MOBILE_REPLAY.md) and [CONTRIBUTING.md](../CONTRIBUTING.md).
Physical phones, rotation/process recreation, other OS/decoder versions and actual consuming-app
failures remain unverified. The earlier matrices below were not all rerun for replay.

## Selector and linked inspection addendum — 2026-10-05

The API 36 focused bridge test passes selector/XML/query privacy, scoped/duplicate tag counts,
unsupported XPath rejection and inspect-only selection without host taps, alongside existing
tap/type/scroll and inspector LTR/RTL/two-finger gestures. Actual accessible overlay controls search
`home.total.balance`, select its host node, expand selectors and copy the validated XPath.
The decoration initially left the edit field without an accessible label; the final field is labeled.

A live Android → adb → Python → Codex-browser test passes generated-credential phone approval,
phone selection appearing without Send, browser selection highlighting the phone, page refresh
restoring selection, tag/action search filters, clipboard copy, live generated-selector checks and
builder tag+action matches. No host tap is invoked by selecting a tappable component. Query checks
open their results panel. Browser-download completion remains unconfirmed in Codex; XML and
generated XPath are validated through device API and the standard JVM XML XPath engine.
The final preview click selects its current component once; 1440px/390px selector layouts have
no page overflow. The focused wider tester workflow also passes on this build.
Physical phones/TalkBack/other Compose versions and consuming-app failures remain unverified.
The historical matrix below records the broader 2026-10-04 run; it was not all rerun for selectors.

At the selector baseline, local builds passed 221 unit tests (183 core / 31 Compose / 5 replay / 2 no-op), five
module/sample lint checks with zero errors, sample debug/test/release, independent consumer
debug/release and both release isolation gates; 27 Python tests and selector/transfer/polling
browser wiring checks pass. Unchanged checks may be cached. Commands are in
[CONTRIBUTING.md](../CONTRIBUTING.md).

Executed against the active SDK in the disposable API 36 arm64 sample emulator, using real Android
UI actions, files, HTTP requests and video decoding. This is the tested feature matrix, not a
certification of arbitrary host applications or every Android version. Commands live in
[CONTRIBUTING.md](../CONTRIBUTING.md); remaining work lives only in [next.md](../next.md).

## Fixes found during testing

- Android Back from fullscreen replay did not return to replay controls. The player now handles
  Back while fullscreen; the test enters fullscreen, presses system Back, then closes normally.
- Large fonts squeezed the diagnostic title between toolbar buttons and made the header consume
  excessive space. Close now stays beside a single-line title; the tools scroll separately.
  Buttons have spoken labels, and the diagnostic tabs expose selection and tab roles.

The positive workflow fixture restores configuration/profiles, uses synthetic data and a bounded
loopback upload receiver. It presses the actual REC chip to stop: recording deliberately hides the
Compose overlay. Share-sheet assertions wait for its real window before pressing Back.

## Executed feature matrix

All rows below passed. Focused instrumentation must print its `OK:` result; Android can return a
successful adb exit even when the runner reports `FAIL:`.

| Feature | Executed acceptance check |
|---|---|
| Tester sheet | Screenshot saves private PNG; bug mark includes screenshot/breadcrumb; Record starts capture; REC Stop saves a new archive and opens the share sheet |
| Advanced diagnostics | Open all 12 tabs and assert their pane content; Watch HUD Stop restores the overlay |
| Narrow/large-font UI | Repeat complete tester workflow and inspector checks at 360 × 640 dp, 150% font and three-button navigation |
| Compose inspection | Separate dialog roots, hidden subtrees, duplicate tags and semantics-only updates; actual bridge tap/type/scroll |
| Inspector movement | Real two-finger host scroll without accidental click; LTR/RTL bubble bounds; movable filter dock respects system/IME insets |
| Frame recording | Media/evidence persist, share correctly and migrate from old cache storage; dashboard clearing does not clear recording journals |
| HD recording | Real OS approval, readable video samples and Android-decoded frame in the master and every exported clip; privacy-disabled request explains why it cannot start |
| Control Room | Actual frame/HD buttons start capture with Startup removed and only a manual `QaLensRoot`; disabled requests explain the failure |
| Retrospective clips | Actual 10/20/60s presets and 45s custom input keep the master running and export separate archives; null-global-context acknowledgement path survives |
| Clip stress/privacy | 12,000-log burst, recent failure retained after master-budget saturation, post-mark event excluded; strict visual-context checks with overlay permission allowed/denied |
| Longer capture | More than five minutes of frame capture and a late clip pass the former duration limit; a full-hour run was not executed |
| Consent recovery | Real Deny creates no HD archive, clears recording/saving and allows subsequent frame capture |
| Background HD | Home leaves capture active; actual foreground-notification Stop finalizes decodable video and removes the notification |
| Heavy live traffic | Continuous background/main-thread logs and network observations during Repro/Network/Logs navigation; bounded history and responsive main-thread heartbeat |
| Recording coverage | 600 requests/logs survive UI clearing; byte-budget loss is reported, earliest admitted evidence remains, readers accept produced archives |
| Screenshots/privacy | PNG/JPEG masks, scaled partial-region masks, hidden/password content and secure-window rejection; gallery/unmasked-video opt-ins remain separate |
| Room/DataStore | Actual database/preference writes reach analysis; observer unsubscribe/restart and cached preference freshness verified |
| Networking/OSS | Real OkHttp/Chucker coexistence preserves host response; host-owned adapters and crash bridges preserve delivery without vendor echo |
| Macros/configuration | Successful tagged typing/tapping/assertion/record/mark/screenshot/stop; malformed/missing commands reject; `.appsal` round trip preserves queries/macros/watch preferences |
| Upload/profiles | Real multipart recording upload shows accepted result and synthetic QA attribution; timeout/retry/cancel/queue saturation/503 cases also covered |
| SQL/navigation | Read limit of 100 rows; disabled/resumed collection and route clearing verified |
| Android replay | Actual Play/Pause, previous/next, five tracks, fullscreen/system Back and Close |
| Panic/disable | Discard capture, restore overlay/opacity; disable stops observers/capture/bridge and rejects stale work |
| SDK PC controls | Pair from overlay/Control Room, select/Send with host paused, hidden tokens, rotation, Stop and disable/re-enable with manual root |
| Phone approval | Ordinary other-app sender denied; no listener before approval; Deny/cancel/expiry/mismatch/disable gates; authenticated reads after approval |
| Real PC transport | Android → owned adb forward → Python/shared transfer controller; attributes/private JSON save/dedup, screen PNG, forward outage repair, paused-host master/clip copies and revoked access |

The final full-run load fixture observed 3,138 background iterations and a worst measured main
heartbeat of 6 ms. This is a synthetic sample measurement, not a frame-rate promise or proof that
the reported consuming-app ANR is resolved.

## Build and companion checks

- 215 unit tests passed: core 177, Compose 31, replay 5, no-op 2; no failures/errors/skips.
- Sample debug, release and instrumentation APKs built. Five Android/sample lint checks reported
  zero errors; existing warnings remain. Sample and independent consumer release isolation passed.
- Independent consumer debug/release builds passed, preserving host integration and no-op wiring.
- Python desktop/bridge 26 tests, mock backend 23 tests, shared recording reader/CLI regressions and
  six shared transfer-controller scenarios passed. The new actual-app polling regression verifies
  competing timers, copy/dedup, stale-connection responses and hidden/busy guards.

The live desktop Landing also passed phone approval, preview click selection, detail tabs,
SDK Highlight/Send, JSON dedup, remembered-app restart with fresh approval, owned-forward recovery
with Receive/collection enabled, automatic private master/10s clip copies and embedded replay/Back.
Testing the actual browser found and fixed inbox polling starving both health and archive discovery;
the new wiring test fails against the previous app code. Wide/narrow layouts were checked at
1440px/390px, with no horizontal page overflow in the narrow layout.

Tests use only synthetic sample evidence. Logs, recordings, screenshots, local server data and
machine configuration are not committed. The consent driver is opt-in test-APK code for the real
English emulator dialog; the SDK still requires the user's Android consent.

## Still outside this verification

The user's consuming app/crash trace, physical phones/OEM encoders, full-hour endurance, rotation,
OS-initiated projection revocation, late consent, disk-full/interrupted-save behavior, TalkBack,
API 23 screenshot fallback, other Compose/library versions and real scrcpy remain to validate.
Do not infer private View/WebView state or complete evidence from public Compose semantics.
Desktop transport checks do not establish every browser interaction; Landing GUI results are
recorded separately in [HANDOVER.md](../HANDOVER.md).
