# QaLens: start here

Updated 2026-09-13. This is the authoritative handover for a contributor or AI with no prior
conversation context. It describes the implementation at `7a05bee`; the following documentation
cleanup changes documentation and links only. Check `git status` and `git log` on arrival because
local state and remote publication may have changed. Historical claims in CHANGELOG are not a
current verification matrix.

## Product and user priorities

QaLens is an open-source Android Jetpack Compose QA evidence SDK, licensed under MIT. It helps a
tester reproduce a bug, capture context, and give another engineer a useful report or recording.
The host app selects the active SDK in debug/QA builds and `qalens-noop` in release builds.

The user asked for a thorough Kotlin/client audit because the client app felt buggy, followed by
fixes. Their priority is a robust client and SDK that is useful and easy to integrate with Chucker
and other open-source tools. Continue with concrete client behavior and integration improvements.
Do not pivot to a website redesign, iOS port or speculative rewrite. The latest request was to
prepare this handover and remove stale docs before moving to a new AI.

The product provides:

- A floating QA bubble, inspect/tag modes, full and minimal panels, and a separate Control Room.
- Compose semantics inspection, route history, accessibility rules, build checks, deterministic
  readiness scores, likely-owner classification, contracts and reproducible event timelines.
- Opt-in OkHttp/Timber observations, generic network sinks, crash reporter bridges, Room/DataStore
  change events, feature flags and application-owned data providers.
- Text bug reports, screenshots and `.sal` session archives. Android replay, two web viewers and a
  Node CLI consume archives. `.appsal` is a separate JSON configuration format for macros/settings.
- An optional webhook uploader and a Python mock backend for local development. The backend's
  “AI” verdict is deterministic code, not a model or a hosted service.

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
| [Contributing](CONTRIBUTING.md) | Portable build/test/device commands and distribution workflow |
| [Demo](DEMO.md), [web](web/README.md), [backend](backend/README.md) | Operating the sample and replay/upload tools |
| [Changelog](CHANGELOG.md) | Historical changes; Git preserves deleted historical documents |

[TAKEOVER_PROMPT.md](TAKEOVER_PROMPT.md) is a short prompt to paste into another AI. It deliberately
points here instead of maintaining a second baseline. Root `AGENTS.md` also directs agents here.

## Verified implementation baseline

The following checks passed during the client-fix work on 2026-09-13. They were not rerun merely
for documentation cleanup. Reproduce the relevant checks before changing behavior; do not present
these historical results as a fresh run. GitHub CI is configured; remote CI execution and public
artifact publication were not verified.

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
| `python3 backend/tests/test_backend.py` | 20 tests passed |
| Expanded Android instrumentation | Passed on a disposable API 36 emulator |

The device runner checks 600-request/log retention, disclosed budget overflow, real Chucker 4.1.0
coexistence and public launcher, unchanged responses and duplicate interception, adapter privacy,
crash-vendor non-echo, hidden pixels in PNG/JPEG, `FLAG_SECURE`, disable/resume during recording,
route-node clearing, DataStore cancellation/restart, invalid macro outcomes, SQL row limits,
Android-produced archive replay, upload queue saturation/cancellation and exhausted 503 retries.
It uses synthetic data and loopback HTTP. This does not replace physical-device video testing.

## What changed recently

| Commit | Result |
|---|---|
| `7a05bee` | Fourteen Android client findings fixed; privacy defaults, real shutdown, bounded replay, reliable macro/upload outcomes; public coroutine dependency exposed in active and no-op SDKs |
| `095e150` | Valid Chucker coexistence/launcher, generic network sinks, consistent external observation policy, consumer fixture and CI |
| `022f38c` | Recording-owned histories survive dashboard eviction/clearing; omitted evidence is disclosed |
| `d5e735b` | Actual release isolation, recording recovery, correct frame units, real Android v2 replay compatibility |
| `e7c8adc` | Shared nearest-rank percentiles and regression tests |

Do not reintroduce fixes from older handovers: Chucker has no supported live transaction-listener
integration here, percentile ranks are fixed, response previews use OkHttp `peekBody`, and the
Overview already displays crash evidence and jank counts. Full facade decomposition and jank
sparklines remain open.

## Build environment and exact verification

Tested toolchain: JDK 17, Kotlin 2.0.21, AGP 8.7.3, Android compile/target SDK 35, minSdk 23,
Compose BOM 2024.12.01, Gradle 9.1.0. The wrapper still requests Gradle 9.0.0; the verified local/CI
path uses 9.1.0. Do not infer that any Gradle 9.x or newer Kotlin/JDK combination works.
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
   the default. Unmasked full-display video and gallery copies require separate host opt-ins.
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

The latest code changes were committed on `dev`; no push or public release was performed in the
client-fix or handover work. Start by inspecting the actual Git state. No code-fix task is left
running as an intentional background job. An emulator or local demo process may still be alive;
inspect before restarting or stopping anything. Shell logs under `/tmp` are disposable, not the
source of truth. The next work is explicitly listed in [next.md](next.md).

## First useful task for the incoming AI

Read this file, the architecture, client fix report and backlog. Inspect the relevant Kotlin paths
before proposing changes. Reproduce the baseline relevant to your chosen task, then extend the
client device tests for one concrete lifecycle/privacy gap. Preserve existing working behavior,
run checks appropriate to the change, update the single backlog and changelog, and commit a focused
change. Report executed evidence and remaining limitations separately. Do not require the user to
repeat decisions already captured here, and do not claim a whole-codebase audit proves absence of bugs.
