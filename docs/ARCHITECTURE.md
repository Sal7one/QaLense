# QaLens architecture

Describes the Android client and desktop/clip paths through 2026-10-05.
Start with [HANDOVER.md](../HANDOVER.md) for dated verification and [next.md](../next.md) for
unresolved work.

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
| `tools/local-bridge` | Python browser desktop, owned adb forwarding, component/recording libraries, fixed phone tasks and trusted component processors; serves the existing web sources |
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
- `ComposeSemanticsReader` inside `QaLensActivityInstaller.kt` uses public Compose semantics APIs
  across attached Activity roots and optionally registered Dialog/Popup roots, not the removed
  private-method reflection path. Root-scoped IDs and mapped window coordinates keep nodes distinct.
  Legacy config naming (`enableSemanticsReflection`) remains. Route changes clear old nodes;
  coalesced invalidation and polling while Inspect/Tag is open cover semantics-only changes.
  Other Compose versions and physical-device windows still need validation.
- Network/log/crash adapters apply shared gating/redaction and feed bounded dashboard histories.
  Declared network sources describe wiring intent, not proof all traffic was observed.
- Log/network admission into the recording journal is independent of the dashboard. A single
  scheduled publication drains bounded pending dashboard queues approximately every 100 ms.
  Log history has entry, text-budget and field-preview bounds; network history keeps 250 requests.
  Disable discards pending dashboard observations, and clearing one track also clears that track's
  pending queue. Exports flush pending observations before taking one consistent state snapshot.
- Dirty/debounced analysis derives warnings, score, likely owner, build safety, screen quality,
  flags and provider data. Room invalidations and DataStore Flow updates refresh registered cached
  snapshots for subsequent recording state samples. The archive counts observed changes and marks
  short temporal leads before failed requests; these do not prove causation or complete observation.
  Providers are host code and should be lightweight.
- On-demand `EvidenceBuilder`/reports use the same scoring inputs, including frame metrics.
  Snapshot macros in core validate state; the Android named macro driver performs real semantics
  actions and waits for asynchronous outcomes. These are different APIs.

Activity evidence, report formatting, crash previews, log grouping/filtering and global search run on background workers over
immutable inputs. Updates are conflated and processed serially, so continuous traffic cannot keep
restarting a computation before it finishes. The panels render visible rows lazily. Report copies
format off main and return to main for the clipboard operation; synchronous public report APIs
remain available. Semantics/View traversal, host snapshot providers and initial input redaction on
the calling thread remain explicit boundaries rather than being moved to an unsafe thread.

## Recording and media

`RecordingLifecycle` governs starting, awaiting consent, active capture, saving and cancellation.
Session identity rejects late callbacks. `RecordingEvidenceStore` retains session observations
independently of dashboard clearing/eviction. [Retention](RECORDING_RETENTION.md) defines its budgets.

Frame mode samples the current window at a duration-aware interval: approximately once per second
at the default 60-minute setting, with a 500 ms minimum. It masks sensitive Compose
bounds before/after asynchronous capture and refuses `FLAG_SECURE`. Main owns View/semantics work;
frame scaling/JPEG writes run on the serialized writer. Screenshot PNG/storage uses a bounded
worker and atomic file publication; sharing/UI completion returns to main.

Video mode requires host `allowUnmaskedVideo=true` and Android consent. The projection service
checks supported encoder sizes/alignment/rates and encodes full-display H.264 without per-node masks.
MediaRecorder preparation/stop run on a dedicated worker. Sidecar frames provide a fallback if video is
unusable. Video consent/start time and session start time must remain distinct where applicable.
Stop freezes evidence and packages a v2 archive off main, publishing it atomically. Handled crashes
attempt finalization before delegating; abrupt process death can still lose the in-memory journal.

An independent bounded recent journal supports marks while the master keeps earliest evidence.
`RecordingClipWindow` fixes each interval at its mark; the serialized writer pins available frames
and evidence, then exports separate archives after stop. `QaLensVideoClip` remuxes finalized video
from a previous keyframe, recording actual/requested timestamps. Sampling, retained media and marks
have explicit budgets; [recording clips](RECORDING_CLIPS.md) owns their current limits and coverage.

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
is downsampled on IO, and event rows use lazy rendering. Reader implementations have individual
budgets and compatibility rules; use the SAL contract for their current differences.


## Explicit local automation bridge

`QaLensLocalBridge` is an opt-in QA listener on device loopback. Its IO coroutine serves one bounded
connection at a time; core `QaLensBridgeProtocol` validates framing/authentication before any host
access. Main-dispatched reads reuse public Compose root discovery and root IDs, excluding hidden
nodes and password values. Commands resolve an exact visible tag or node ID and invoke public
semantics handlers. Main dispatch has a deadline and generation guard; disable closes sockets and
cancels pending dispatch. Already running synchronous host handlers cannot be safely interrupted.

`tools/local-bridge/server.py` binds PC loopback, creates/removes only its own adb forward and proxies
fixed authenticated endpoints to the desktop tree/bounds, component and recording UI. JSON/redaction remain off main;
cached observations never query host databases/providers on demand. Explicit desktop SQL is a
separate IO job using a read-only app-owned SQLite connection: bounded rows/cells, cancellation,
ten-second cancellation watchdog, one retained result and five-minute expiry. Its start/status/cancel
requests release the socket; Stop/disable clears the result and signals the worker. Database/prefs
targets use opaque catalog IDs and resolved own-directory boundaries. Explicit preference snapshots
mask full values; DataStore files remain metadata. Cached decoded values/status come from the host's
existing Flow/provider and have global budgets. `diagnostics.js` owns memory-only Follow/Pause,
search, pinned comparisons and SQL UI; no copied viewer, provider invocation or arbitrary file reader.
This is live debug automation,
not an archive-format change or a company service. The release no-op exposes identical facade APIs
without transport work. Pairing tokens are memory-only and host-owned.

`workbench.py` owns profiles, explicit component persistence and trusted pipeline jobs. `desktop.py`
owns shared viewer assets, bounded recording/Downloads transfers and installed scrcpy lifecycle.
Landing has resizable mirror/selection/tree panes. `mirror-controls.js` shares aspect-ratio geometry
for inspection, touch/wheel and pane layout. Explicit whole-phone adb preview issues two short-lived
frame leases; only Control accepts finite normalized touch input. Mode/stop/connection changes and
known rotation revoke leases. Preview is read-only; Inspect highlights without a host action.
The SDK's internal recording/inspection/screenshot endpoints retain phone approval, main dispatch
deadlines, recorder privacy/OS consent and existing masked PixelCopy with overlay restoration. PNG
encoding stays on IO. The GUI detects these capabilities, polls capture state, marks deferred clips
and can explicitly copy the exact saved master into replay. No public facade or archive schema changes.
The browser shell embeds both existing viewers through same-origin parent messages into their
existing reader paths. Capture status polls metadata while Landing/Recordings is visible. Automatic
archive copying is opt-in and resets with connection changes/errors; Replay after Stop is a separate,
explicit choice for that exact session.
Desktop files persist privately; pairing tokens and unsaved component previews do not.

The decor overlay is a `QaLensOverlayHost` containing its Compose surface. It routes two-finger
inspect/tag drags to the underlying Activity content after cancelling the overlay gesture; it never
re-dispatches through the decor into itself. Bubble/dock placement uses physical offsets inside safe
insets, independent of layout direction.
