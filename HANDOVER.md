# QaLens: start here

Updated 2026-10-02. This is the authoritative handover for a contributor or AI with no prior
conversation context. It covers the tester flow, local backend, Compose inspection and data hooks
through `a16ca21`, plus the continuous-log responsiveness and local PC bridge/inspector movement and component workbench work below. Check `git status` and `git log` because code and publication state
may have changed. Historical claims in CHANGELOG are not a current verification matrix.

## Product and user priorities

QaLens is an open-source Android Jetpack Compose QA evidence SDK, licensed under MIT. It helps a
tester reproduce a bug, capture context, and give another engineer a useful report or recording.
The host app selects the active SDK in debug/QA builds and `qalens-noop` in release builds.

The user says the app feels buggy and the tester overlay is too complex. They want QaLens to be
robust, easy for professional QA teams at multiple companies to share for free, simpler to
integrate with Chucker and other OSS tools, and able to send real test recordings to a backend.
QaLens is MIT-licensed and self-hostable; the included backend is a local mock, not a hosted or
multi-tenant service. Prioritize the Android client and tester workflow, preserve host-app behavior,
and make the local end-to-end send path concrete. Keep advanced diagnostics for engineers. Avoid
speculative rewrites and never describe the mock as safe for company data.

The product provides:

- A floating QA bubble, inspect/tag modes, a tester quick-actions sheet (record, screenshot, mark a
  bug), an optional full developer panel, and a separate Control Room. New installs default to the
  tester sheet; advanced tools stay under More tools.
- Compose semantics inspection, route history, accessibility rules, build checks, deterministic
  readiness scores, likely-owner classification, contracts and reproducible event timelines.
- Opt-in OkHttp/Timber observations, generic network sinks, crash reporter bridges, Room/DataStore
  change events, feature flags and application-owned data providers.
- Text bug reports, screenshots and `.sal` session archives. Android replay, two web viewers and a
  Node CLI consume archives. `.appsal` is a separate JSON configuration format for macros/settings.
- An optional webhook uploader and a Python mock backend for local development. The backend binds
  to loopback by default, stores raw recordings, and returns a deterministic verdict; it is not an
  authenticated or multi-tenant company service.

The SDK cannot inspect arbitrary private Compose state, infer unobserved taps, recover events
never delivered by Android, or certify health from empty tracks. Android capture is implemented;
iOS capture and native Ktor/Cronet/Apollo adapters are not. Generic transport callbacks are available.

## Read these documents

| Document | Owns |
|---|---|
| [next.md](next.md) | The single prioritized backlog and acceptance criteria |
| [Architecture](docs/ARCHITECTURE.md) | Modules, observation/analysis/recording flows, lifecycle constraints |
| [Client fixes](docs/CLIENT_SAFETY_FIXES.md) | Fourteen audit fixes, migration behavior and device-test limits |
| [Integration](integration.md) | Host-app setup, including instructions for AI integrators |
| [OSS integrations](docs/OSS_INTEGRATIONS.md) | Supported Chucker, transport, Timber and crash-reporter contracts |
| [SAL format](docs/SAL_FORMAT.md) | Archive versions, compression, tracks, timestamps and reader differences |
| [Recording retention](docs/RECORDING_RETENTION.md) | Recording-owned journal budgets and evidence-loss accounting |
| [Overlay design](docs/OVERLAY_DESIGN.md) | Token coverage and remaining UI migration |
| [Contributing](CONTRIBUTING.md) | Portable build/test/device commands and distribution workflow |
| [Demo](DEMO.md), [web](web/README.md), [backend](backend/README.md) | Operating the sample and replay/upload tools |
| [PC bridge](tools/local-bridge/README.md) | Pairing, adb forwarding, browser inspector, HTTP API and coverage limits |
| [Changelog](CHANGELOG.md) | Historical changes; Git preserves deleted historical documents |

Root `AGENTS.md` directs contributors here; this file and `next.md` are the only current project
handover and backlog.

## Historical client-fix baseline

The following checks passed during the client-fix work on 2026-09-13. Later 2026-09-24 checks are
recorded below; these counts are retained only as historical evidence. GitHub CI is configured,
but remote CI execution and public artifact publication were not verified here.

| Check | Last result |
|---|---|
| `:qalens-core:test` | 162 tests, zero failures/errors/skips |
| `:qalens-compose:testDebugUnitTest` | 15 tests, zero failures/errors/skips |
| `:qalens-noop:testDebugUnitTest` | 2 tests, zero failures/errors/skips |
| `:qalens-replay:testDebugUnitTest` | 5 tests, zero failures/errors/skips |
| Sample debug/release and instrumentation APKs | Built successfully |
| Compose, Android, navigation and replay lint | Zero errors; 24 warnings and 3 informational findings |
| Sample and independent-consumer release dependency gates | Passed |
| Independent consumer debug/release builds | Passed |
| `node web/test/read.test.js` | 58 assertions passed, including CLI regressions |
| `python3 backend/tests/test_backend.py` | 20 tests passed at this historical baseline |
| Expanded Android instrumentation | Passed on a disposable API 36 emulator |

The device runner checks 600-request/log retention, disclosed budget overflow, real Chucker 4.1.0
coexistence and public launcher, unchanged responses and duplicate interception, adapter privacy,
crash-vendor non-echo, hidden pixels in PNG/JPEG, `FLAG_SECURE`, disable/resume during recording,
route-node clearing, DataStore cancellation/restart, invalid macro outcomes, SQL row limits,
Android-produced archive replay, upload queue saturation/cancellation and exhausted 503 retries.
It uses synthetic data and loopback HTTP. This does not replace physical-device video testing.

The `f9b2672` overlay-token commit separately reports passing token contrast/scheme tests, sample
debug build, release-isolation verification and an API 36 overlay check. Later sections describe
verification after the tester-sheet and backend changes.

## Takeover validation — 2026-09-23

- `:qalens-compose:compileDebugKotlin :sample-app:compileDebugKotlin` passed with the documented
  Gradle 9.1.0/JDK 17 toolchain.
- `:sample-app:assembleDebug` passed. The quick-actions sheet was opened on the Pixel 8 Pro API 36
  AVD; the three core actions, local privacy note, conditional upload action and collapsed/expanded
  More tools were visually reviewed. An earlier review restored the AVD's full-panel preference;
  the current visible AVD was later set to quick actions for tester validation.
- `./demo.sh test` passed: web reader, all 23 backend tests, Kotlin unit/release-parity checks and
  the expected failing-session CLI smoke. The backend round-trip test now also checks that completed
  chunked uploads remove their temporary files before returning success.
- An ephemeral loopback backend smoke sent `web/sample.sal`, confirmed it in `/api/uploads`, checked
  loopback CORS allow / external-origin denial, and confirmed an oversized request returns 413. The
  `scripts/demo_chunked.py` send path was also run against temporary storage and its upload appeared
  in the backend store.
- `:qalens-compose:lintDebug :qalens-android:lintDebug :sample-app:lintDebug` passed with zero
  errors after making debug cleartext domains explicit. The latest run reports 24 warnings across
  those modules, down from 28; the active stop-chip View is still held until stop/destroy and lint
  flags that static reference. Sample metadata and dependency freshness also remain.
- `git diff --check` and Python syntax parsing passed. The physical-device capture, accessibility
  and recovery matrix remains open.
- On a separate disposable API 36 emulator, the Android instrumentation runner returned `OK:` for
  retention, Chucker, privacy, lifecycle, replay and webhook checks. A manual frame recording used
  the in-app REC chip to stop and opened the share sheet. Its 4.9 MB synthetic `.sal` reached the
  temporary loopback backend through the five-chunk resume protocol; the app showed HTTP 200 and
  `/api/uploads` listed the mobile session. Starting the Control Room directly after reinstall
  exposed blank upload app/device metadata; the webhook now derives it from application context.
  A repeat post-reinstall upload confirmed populated headers and query fields. The visible Pixel 8
  Pro tester sheet was also checked at 150% system font size and at a 360 × 640 dp small-phone
  override. Primary actions remained readable, and expanded More tools scrolled to its lower
  actions. The original display and font settings were restored. Physical-device and TalkBack
  verification remain open.
- `scripts/release_internal.sh --verify` built six modules into a local Maven repo, verified its
  `SHA-256SUMS` and produced `dist/qalens-0.9.0-repo.zip`. The wrapper now reports Gradle 9.1.0.

## Continuation validation — 2026-09-24

- The visible Pixel 8 Pro emulator (`emulator-5554`) stayed running. The tester sheet was checked
  in RTL using the sample app's `ar-SA` locale and in both overlay color schemes using Android
  night mode. The default locale, light mode and display settings were restored. UIAutomator showed
  a focusable Close button and a labeled More tools button; TalkBack speech/focus order and physical
  phones remain untested. English privacy copy now wraps correctly in RTL.
- Reinstalling the sample exposed a real stale-state issue: five `.sal` archives remained in
  `cache/qalens/`, but the quick sheet no longer showed Send latest session because the process
  had not refreshed its recording list. Completed archives now go to
  `files/qalens/recordings/`, startup scans and migrates older cache archives, and the share
  provider allows the new path. On the visible emulator, all five old archives moved without loss
  and Send latest session returned after reinstall. Host backup policy still governs files storage;
  see [integration.md](integration.md) for the required exclusion when evidence must stay local.
- `./demo.sh test` (including 23 backend tests), `:sample-app:assembleDebug :sample-app:assembleDebugAndroidTest
  :sample-app:verifyReleaseIsolation :qalens-compose:lintDebug` passed. The disposable API 36
  runner returned `OK:` after adding durable-location, legacy-migration and share-URI assertions.
  The first runner attempt found an old test assumption that archives were in cache; it was fixed
  and the runner passed from cleared app state.

## Compose inspection continuation — 2026-09-24

- Inspect now reads all attached Compose roots in the Activity window and optional separately
  registered Dialog/Popup roots using public Compose semantics APIs. IDs are root-scoped, window
  coordinates are aligned, hidden subtrees are excluded, and repeated `qaTag` hints match by bounds.
  See [integration.md](integration.md) for host hooks and honest capture limits.
- The visible Pixel emulator showed a calmer Actions-first inspector with All/Tagged/Issues
  filters. Tapping a transaction outline now leaves the visual inspector open and shows a detail
  card with its tag; previously `selectNode` opened the tester sheet. Merged child labels no longer
  raise spurious duplicate-description warnings on the sample transaction rows.
- `./demo.sh test`, Compose/Android/sample debug lint, sample debug/release and instrumentation APK
  builds, sample release dependency isolation, and the independent consumer debug/release and
  isolation checks passed. The expanded runner on a disposable API 36 emulator returned `OK:` for
  dialog roots, hidden subtrees, duplicate-tag reconciliation, accessibility label enrichment and
  semantics-only updates, along with its existing capture/integration regressions. Physical-device
  TalkBack, dialog-window pixel capture, and other Compose versions remain unverified.

## Data integration continuation — 2026-09-24

- Confirmed Chucker 4.1.0 and QaLens's OkHttp interceptor run beside each other in the sample and
  device runner; Chucker has no supported live transaction callback into QaLens. Other transports
  still use the host-owned `networkSink` callback. Room and DataStore were already optional hooks,
  but their change events did not refresh app-data snapshots before `state.json` sampling.
- Room invalidations and DataStore Flow updates now mark analysis dirty, so registered cached
  snapshots update for the next recording sample. Explicit stop hooks allow owners to release
  their database/Flow observations; debug and no-op APIs compile in the independent consumer.
  The integration report lists retained dashboard counts for both hooks and snapshot sources.
- `analysis.json` now counts observed Room/DataStore changes and adds a bounded temporal lead when
  one precedes a failed request by at most five seconds. It says timing does not prove cause and
  never interprets an empty track as proof of no writes. The sample's event label names preference
  keys only; snapshot providers still require host allowlisting/redaction.
- `./demo.sh test`, Compose/Android/sample lint, sample debug/release and release isolation, and
  independent-consumer debug/release/isolation checks passed. The disposable API 36 runner returned
  `OK:` with real Room and Preferences DataStore writes/unsubscribe, plus a recorded preference
  snapshot update in `state.json` and change count in `analysis.json`. Physical-device and broader
  library-version compatibility remain open.

## Continuous-log responsiveness — 2026-10-01

The user reported ANRs in a consuming app when opening the overlay and Repro while logs/network
traffic kept arriving. No original ANR trace was available. Source review found synchronous evidence
construction in composition, eager timeline/log/network rows and one main-thread post per background
observation. Dashboard log/network publication now uses bounded queues and one 100 ms batch;
evidence/filter/search work runs in the background with conflated inputs, and large tracks render
lazily. Report copies format off main. Bubble taps no longer force a second semantics scan, screen
warning lookup is indexed by node, and reproduction steps avoid allocating an unlimited intermediate
list. See [integration.md](integration.md) for limits and synchronous API boundaries.

- Final checks: 168 core, 30 Compose and 2 no-op unit tests passed without failures/skips. Compose,
  Android and sample debug lint passed with zero errors, 26 warnings and 2 informational findings
  across those modules; remaining warnings concern dependency freshness and existing platform/UI work.
- Sample debug/release and instrumentation APKs, sample release isolation, and independent-consumer
  debug/release/isolation checks passed with the documented JDK 17/Gradle 9.1.0 toolchain.
- The full runner returned `OK:` on a disposable API 36 emulator, preserving 600-request/log
  recording retention, privacy, disable/resume, real Room/DataStore, Chucker, replay and uploads.
  Its new load case emitted a 12,000-line burst followed by 4,409 continuous background logs,
  periodic 100-line main-thread bursts and network events while switching Repro/Network/Logs.
  The worst measured main-thread heartbeat delay was 46 ms. It also checked immediate exports
  flush pending observations. This is synthetic coverage; retest the reported host app.
- Initial input redaction and host snapshot providers retain their caller/main-thread contracts;
  custom regexes, huge individual messages and blocking providers can still be expensive.

## Local PC bridge and inspector movement — 2026-10-02

Remote `master` (`dea9048`) was the latest baseline and had merged remote `dev` (`9e52fc9`);
their code trees were identical. The local `dev` checkout was fast-forwarded to that baseline.

- Explicit `startLocalBridge(token, port)` / `stopLocalBridge()` and `localBridgeStatus` expose an
  authenticated loopback device endpoint; no-op builds remain inert. It stops on disable, rejects
  stale queued commands and requires explicit restart. IO/JSON/redaction run off main; live semantics
  reads/actions stay on main. A running synchronous host action cannot be interrupted safely.
- The Python standard-library PC server owns its adb forward and serves a browser semantics forest,
  test-tag search, bounds selection, device highlight and tap/type/scroll controls. Recent diagnostics
  read bounded dashboard observations and cached host-owned data sources, without querying providers
  on demand or exposing network bodies. Exact tags reject duplicates; IDs are live/root-scoped.
- Hidden subtrees and password values stay out of snapshots. The debug sample Settings provides
  explicit pairing; its token is process-memory only, stable across navigation/rotation and hidden
  from reports. This is Compose control within a QA build, not universal Appium or a company backend.
- A decor `QaLensOverlayHost` forwards two-finger inspect/tag drags to host content as a one-finger
  centroid drag, cancels the pending inspector tap and suppresses residual fingers. The bubble and
  movable inspector dock use physical normalized coordinates within system/IME-safe bounds in RTL/LTR.
- 172 core, 30 Compose and 2 no-op unit tests passed. Python proxy tests (4) and JS syntax passed.
  Sample debug/release/androidTest and independent-consumer debug/release builds and both release
  isolation gates passed. Compose/Android/sample debug lint passed with zero errors; after removing
  the new View-constructor warning, 26 warnings and 2 informational findings remain for existing dependency/platform metadata.
- The full disposable API 36 runner passed with three-button navigation, including real bridge
  semantics/auth/hidden/password/duplicate-tag/timeout/disable/restart checks and injected LTR/RTL
  two-finger scrolling, bubble/dock movement and filters above current system/IME insets. The existing
  continuous-log case produced 4,202 background logs, with a 27 ms worst main-thread heartbeat.
- The real PC → owned adb forward → sample round trip read 50 Settings nodes, excluded the pairing
  token, changed the theme by exact tag and verified Dark Mode in the next tree; diagnostics also
  returned through the proxy. No real host tokens/data or recordings are part of the changes; tests use synthetic fixtures.

See the PC guide for exact limits and commands. The focused bridge/gesture runner also passed at 360 × 640 dp with three-button navigation.
Physical devices, TalkBack, separate-window gesture
forwarding and Compose versions beyond 1.7.8 remain unverified. The endpoint reads registered dialog
roots; touch forwarding stays within the host Activity content window.

## PC component workbench — 2026-10-02

- Inspector **Send to PC** sits beside Copy test tag and works for untagged components. Networking,
  redaction/JSON and queue accounting run on IO; public semantics reads remain on main. Inbox is
  memory-only (10 / 1 MiB); dropped counts, idempotent acknowledgements and stop cleanup are explicit.
- `qalens.component` v1 documents include public attributes/values, bounds, package/activity,
  viewport and visible ancestry/sibling indices. Unsupported/custom values/actions are name-only.
  Known keys are matched by identity, preventing custom keys named Text from bypassing policy.
  Password text and QA names are omitted; ancestry beyond the budget is conservatively value-masked.
- The Python GUI supports pairing/device/package discovery, no-reset launch, remembered profiles,
  incoming/readable/bounds previews, explicit content-addressed file save/import/export and a library.
  Data defaults to `~/.qalens/bridge`; no tokens are persisted. Canonical content hashes exclude
  timestamps/live IDs, normalize integral numbers and distinguish changes in values/tree position.
  GUI commands require the current PC connection nonce after a device switch.
- Local owner-configured argv processors run sequentially off the HTTP handler, with one pipeline
  at a time, per-step timeouts and cancellation. Require saved input; output directory/result files
  persist, with bounded UTF-8 GUI previews excluding outside paths/symlinks. `process.py` runs the
  same contract without adb/browser. Processes use the owner's privileges, not a sandbox.
- Validation: 172 core, 30 Compose and 2 no-op unit tests; 4 proxy + 7 workbench Python cases; JS syntax;
  sample debug/release/androidTest, sample release isolation and independent-consumer debug/release/
  isolation passed. Compose/Android/sample lint still reports zero errors, 26 existing warnings and
  2 informational findings. No public facade or archive format changes.
- The full disposable API 36 runner passed, including real phone button transfers, queue overflow,
  non-destructive reads/ack/restart, private/custom/spoofed key exclusions and prior gestures and
  integration/recording/replay/upload regressions. Its continuous-log case emitted 3,000 background
  logs; worst measured main-thread heartbeat was 21 ms. The final focused runner separately passed
  password QA-name protection after that addition. Physical devices/TalkBack/other Compose versions
  and Windows processor cleanup remain unverified.
- Real GUI → adb → sample testing verified profile reload after PC restart, capture, phone transfer,
  readable attributes/tree/bounds, file deduplication, library/import and successful processor output.
  Standalone processing CLI also succeeded. Desktop/narrow layouts were visually checked. The
  Codex browser reported a Blob download request but did not provide a download completion event;
  PC Save wrote the actual JSON file, and browser download completion remains to check in Chrome/Safari.

The [workbench guide](tools/local-bridge/README.md) owns usage, storage, API and processor limits.

## What changed recently

| Commit | Result |
|---|---|
| `7a05bee` | Fourteen Android client findings fixed; privacy defaults, real shutdown, bounded replay, reliable macro/upload outcomes; public coroutine dependency exposed in active and no-op SDKs |
| `095e150` | Valid Chucker coexistence/launcher, generic network sinks, consistent external observation policy, consumer fixture and CI |
| `022f38c` | Recording-owned histories survive dashboard eviction/clearing; omitted evidence is disclosed |
| `d5e735b` | Actual release isolation, recording recovery, correct frame units, real Android v2 replay compatibility |
| `e7c8adc` | Shared nearest-rank percentiles and regression tests |
| `f9b2672` | Add overlay color/spacing tokens for the bubble and inspect/tag surfaces; not a whole-UI redesign |
| `6d824d0` | Simplify the tester sheet and exercise real uploads against the loopback backend |
| `d98895a` | Retain completed archives in app-private files and improve tester accessibility |
| `de2af59` | Inspect multiple Compose roots and add visual filters and node details |
| `a16ca21` | Refresh data snapshots from Room/DataStore changes and add bounded recording insights |

Do not reintroduce fixes from older handovers: Chucker has no supported live transaction-listener
integration here, percentile ranks are fixed, response previews use OkHttp `peekBody`, and the
Overview already displays crash evidence and jank counts. Full facade decomposition and jank
sparklines remain open.

## Build environment and exact verification

Tested toolchain: JDK 17, Kotlin 2.0.21, AGP 8.7.3, Android compile/target SDK 35, minSdk 23,
Compose BOM 2024.12.01, Gradle 9.1.0. The wrapper and CI both request the verified 9.1.0 version.
Do not infer that any other Gradle 9.x or newer Kotlin/JDK combination works.
JUnit4 is used for Android-module unit tests; do not switch no-op tests to JUnit Platform.

On the original Mac, the checkout is `/Users/salehalanazi/Desktop/QaLense` (folder spelling differs
from the product name). Local tool locations, which must not become required paths for contributors:

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
export ANDROID_HOME=/Users/salehalanazi/Library/Android/sdk
QALENS_GRADLE="$HOME/.gradle/wrapper/dists/gradle-9.1.0-bin/9agqghryom9wkf8r80qlhnts3/gradle-9.1.0/bin/gradle"
```

On another machine, install the tested prerequisites and set `QALENS_GRADLE` to its Gradle 9.1.0
executable. Set `ANDROID_HOME` explicitly: the independent consumer does not inherit the root
checkout's `local.properties`. Run from the repository root:

```sh
"$QALENS_GRADLE" \
  :qalens-core:test :qalens-compose:testDebugUnitTest \
  :qalens-replay:testDebugUnitTest :qalens-noop:testDebugUnitTest \
  :qalens-compose:lintDebug :qalens-android:lintDebug \
  :qalens-navigation-compose:lintDebug :qalens-replay:lintDebug \
  :sample-app:assembleDebug :sample-app:assembleRelease \
  :sample-app:assembleDebugAndroidTest :sample-app:verifyReleaseIsolation

"$QALENS_GRADLE" -p integration-tests/consumer assembleDebug assembleRelease verifyReleaseIsolation
node web/test/read.test.js
python3 backend/tests/test_backend.py
```

Serialize Gradle processes sharing this checkout. Previous work used atomic
`mkdir /tmp/qalens-gradle-lock` and a shell `trap 'rmdir /tmp/qalens-gradle-lock' EXIT`. If the lock
already exists, check running builds before deciding it is stale; never delete another build's lock.
The lock is local coordination, not a repository file. Run independent Node/Python checks separately.

Use `adb devices` before device work and explicitly select the disposable device. The prior emulator
was `Pixel_8_Pro`, serial `emulator-5554`, API 36; its continued availability is not guaranteed.
[CONTRIBUTING.md](CONTRIBUTING.md) contains installation/runner commands. Require the runner's
`OK:` output; an adb command returning zero alone does not mean the test assertions passed.

## Contracts that must survive future changes

1. **Release isolation is a dependency property.** `compileReleaseKotlin` alone is insufficient.
   Sample release must contain QaLens/Chucker no-op artifacts and exclude active capture, replay
   and Chucker modules. Check both dependency gates and build variants after API/dependency changes.
   The active library's own release AAR still contains capture code; it is not the no-op artifact.
2. **Preserve host behavior.** Interceptors must preserve requests/responses and skip unsafe body
   reads. Coroutine helpers delegate the original failure to the host uncaught handler in both
   variants. Crash registration must not recurse or swallow host exceptions. Recoverable error
   handling belongs to the host application.
3. **Privacy is explicit and limited.** Text boundaries redact configured patterns; screenshots
   mask known sensitive Compose regions and respect secure windows. Private-cache screenshots are
   the default; completed archives use app-private files, so host backup rules must exclude them
   when recordings must stay on the device. Unmasked full-display video and gallery copies require
   separate host opt-ins.
   These controls do not redact arbitrary pixels, user-authored SQL/macro literals or Chucker's
   independent storage. Never promise all data is automatically safe to share.
4. **Disable means stop collecting.** Stop/finalize capture and cancel active observation/upload
   work. Reject stale callbacks, preserve explicit re-enable behavior, and do not remove previously
   exported artifacts retroactively. Lifecycle coordination occurs on main; encoding/file work
   should not block it.
5. **Evidence belongs to its recording.** Dashboard clearing/eviction must not discard the
   recording's early observations. Respect bounded journals, closed-session admission and coverage
   accounting. Evidence loss is not a passing session or proof that a regression was fixed.
6. **Archives are a shared contract.** Keep v1/v2 compatibility. The Android producer gzip-compresses
   JSON inside ZIP DEFLATE entries. Preserve checksums/timestamps and update writer, Android reader,
   shared web reader, CLI, backend and format docs together for schema changes.
7. **Use real OSS contracts.** Keep optional libraries host-owned. Chucker 4.1.0 is the executed
   baseline; use both interceptors and its public launcher. Generic sinks are adapter seams, not
   shipping native integrations. Avoid duplicate reporting and crash-vendor echo.
8. **Public repository hygiene.** Use synthetic fixtures; never commit credentials, private
   recordings, `local.properties`, build output, backend data or emulator state. Preserve unrelated
   user changes. Pure policies belong in core, with meaningful behavioral tests. Do not hide lint
   failures behind new baselines merely to get a green build.

## Distribution and session state

Default coordinates are `com.qalens:<module>:0.9.0`, defined in root `build.gradle.kts`, with a
`-PqalensVersion` override. Historical “0.10” prose is not the publication version. Composite builds
and local Maven distribution exist; no Maven Central availability is claimed. `qalensDist` writes
`build/qalens-repo`; `scripts/release_internal.sh` packages it. External publishing is separate work.

The development commits through this handover are on `dev`. Check the remote branch and CI before
assuming they are published or validated there. No Maven Central or production backend release is
claimed. Test backends, emulator state and shell logs are local and disposable; use `adb devices`
to discover an available emulator rather than relying on a prior serial. The next work is listed
in [next.md](next.md).

## First useful task for the incoming AI

Read this file and [next.md](next.md). Start with the remaining tester-flow device/accessibility
review, then continue the backend/archive and SDK integration backlog. Keep company deployment
claims tied to an owned, authenticated backend; the included mock is for local upload testing.
Preserve release/no-op isolation, capture privacy and host behavior. Report exactly what was checked
and what remains open; do not claim a whole-codebase audit proves absence of bugs.
