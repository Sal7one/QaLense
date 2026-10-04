# Android verification — 2026-10-04

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
