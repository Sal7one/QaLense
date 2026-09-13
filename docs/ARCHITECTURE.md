# QaLens architecture

Current at the client-fix baseline `7a05bee`. Start with [HANDOVER.md](../HANDOVER.md) for product
context and verification. [next.md](../next.md) owns unresolved work.

## Module boundaries

| Module | Responsibility and principal files |
|---|---|
| `qalens-core` | Pure Kotlin models/config/redaction; `QaLensRules`, `QaLensScore`, `QaLensBugClassifier`, `QaLensEvidence`, reports, contracts/macros, `QaLensSalFormat`, `QaLensAnalysis`; recording lifecycle/window/journal and capture/upload policies |
| `qalens-android` | Device/build context, shake, notifications, FileProvider, persisted settings, `.appsal`, QA profiles |
| `qalens-compose` | Active `QaLens` facade, `AnalysisEngine`, lifecycle installer, overlay/panels, screenshot/session capture, MediaProjection, OkHttp/Timber adapters, Chucker launcher, crash/connectivity/memory observers, macro driver, SQL and uploader |
| `qalens-navigation-compose` | Navigation Compose wrappers and route reporting |
| `qalens-replay` | Independent Android archive reader and Compose/Media3 player; does not depend on the active facade |
| `qalens-noop` | Release API mirrors; no capture or UI; coroutine helpers still preserve host failure delivery |
| `sample-app` | Banking demo, debug Chucker coexistence, release no-op dependencies, instrumented regression runner |
| `integration-tests/consumer` | Separate composite-build application using normal coordinates; checks both API visibility and release isolation |
| `web` | v2 and classic viewers, shared `sal.js` reader, sample fixtures/generator and CLI |
| `backend` | Python stdlib mock upload/chunk server, persistence, dashboard and deterministic verdict |

Main dependency direction is core → Android → active Compose → navigation wrappers. Replay is
standalone. No-op shares core and UI/navigation types needed for its API; it is not dependency-free.
Coroutines are exposed by active/no-op modules because Flow/StateFlow/exception handlers are public.
OkHttp, Timber and Room are compile-only integration dependencies; Chucker stays optional.

## Observation and analysis

`QaLens.kt` owns public entry points and a `StateFlow<QaLensUiState>`. Its responsibility is still
broad: extracting internal services remains unfinished. `AnalysisEngine` already separates derived
analysis; avoid claiming a service-oriented rewrite is complete.

- Startup installs lifecycle observation once. Host activity resume tracks the activity, attaches
  the overlay and frame observers; internal Control Room/player/projection screens are excluded.
- `ComposeSemanticsReader` inside `QaLensActivityInstaller.kt` uses `RootForTest.semanticsOwner`
  and `getAllSemanticsNodes`, not the removed private-method reflection path. Legacy config naming
  (`enableSemanticsReflection`) remains. Route changes clear old nodes; layout/manual changes
  schedule coalesced inspection. Changes without layout still need broader validation.
- Network/log/crash adapters apply shared gating/redaction and feed bounded dashboard histories.
  Declared network sources describe wiring intent, not proof all traffic was observed.
- Dirty/debounced analysis derives warnings, score, likely owner, build safety, screen quality,
  flags and provider data. Providers are host code and should be lightweight.
- On-demand `EvidenceBuilder`/reports use the same scoring inputs, including frame metrics.
  Snapshot macros in core validate state; the Android named macro driver performs real semantics
  actions and waits for asynchronous outcomes. These are different APIs.

## Recording and media

`RecordingLifecycle` governs starting, awaiting consent, active capture, saving and cancellation.
Session identity rejects late callbacks. `RecordingEvidenceStore` retains session observations
independently of dashboard clearing/eviction. [Retention](RECORDING_RETENTION.md) defines its budgets.

Frame mode captures the current window approximately twice per second. It masks sensitive Compose
bounds before/after asynchronous capture and refuses `FLAG_SECURE`. Main owns View/semantics work;
frame scaling/JPEG writes run on the serialized writer. Screenshot PNG/storage uses a bounded
worker and atomic file publication; sharing/UI completion returns to main.

Video mode requires host `allowUnmaskedVideo=true` and Android consent. The projection service
encodes full-display H.264 without per-node masks. Sidecar frames provide a fallback if video is
unusable. Video consent/start time and session start time must remain distinct where applicable.
Stop freezes evidence and packages a v2 archive off main, publishing it atomically. Handled crashes
attempt finalization before delegating; abrupt process death can still lose the in-memory journal.

Stop controls include the optional system overlay chip, in-window control, notification command
service, shake and Control Room. `QaLensControlActivity` and `QaLensPlayerActivity` have separate
task affinities/singleTask behavior: putting them back in the host task regresses launcher behavior.

## Shutdown and external effects

Runtime disable gates new input, advances capture generation, stops/finalizes recording, cancels
macros/uploads, detaches UI/frame/shake listeners and suspends watchdog, memory, connectivity,
Room and DataStore collection. Re-enable restores registered observers. The installed exception
chain preserves the host handler even while capture is disabled.

`QaLensWebhook` uses two workers and an eight-item queue, with visible rejection, in-flight dedupe,
transient retry persistence and endpoint-bound queued items. Settings are captured per submission;
auth does not follow redirects or an imported origin change. Configured retries may run later;
“no automatic network calls ever” is not the contract. Full protocol: [backend](../backend/README.md).

Text redaction is a boundary policy, not a guarantee about arbitrary pixels. Screenshot gallery
copies and unmasked video are separate opt-ins. Chucker's storage is independent. `.appsal` can
contain user-authored SQL/macro literals even when webhook secrets are omitted. See
[client migration](CLIENT_SAFETY_FIXES.md) before changing these behaviors.

## Replay and format

The producer and readers share the [SAL contract](SAL_FORMAT.md). Android applies bounded ZIP/gzip
extraction, canonical path validation, streaming CRC checks and session-cache cleanup. Frame decode
is downsampled on IO, and event rows use lazy rendering. Web/backend do not yet share all Android
limits or CRC rejection behavior; do not assume uniform guarantees across readers.
