# Changelog

## Unreleased — post-0.9.0

### Compose dialog inspection — 2026-10-06

- Automatically discover visible host Compose Dialog/AlertDialog/Popup windows on Android 10/API 29+
  through public `WindowInspector`; retain explicit roots for API 23–28 and deduplicate both paths.
- Put active phone inspection above extra windows, preserve host focus/geometry and direct
  two-finger scrolling into their actual content. Clean up on dismissal, pause, disable and recording;
  a stationary two-finger touch cancels instead of clicking the host.
- Resolve hits in the foremost containing window, then prefer tagged/actionable/labeled components
  over equal-sized empty containers. Add optional live window metadata and matching desktop hit rules.
- Exclude SDK selector/pairing windows from discovery, retain dialog search/attributes/XPath/actions
  and keep hidden/password filtering. Transform pixel-mask coordinates and share screenshot visibility
  ownership across inspection-window changes; propagate secure capture flags to SDK inspection/
  selector/pairing windows. Screenshots remain Activity-window capture.
- Add real dialog/search/popup/gesture/lifecycle/capture regressions and API-level integration guidance.

### Embedded scrcpy mirror — 2026-10-06

- Make official checksum-pinned scrcpy 5.0 H.264 the inline Landing mirror with WebCodecs decoding,
  continuous native touch/wheel/basic keyboard input and SDK-linked scaled Compose inspection.
- Preserve the old PNG/adb implementation behind `ENABLE_LEGACY_MIRROR`, false by default; offer
  deliberate code rollback and retain the separate optional native desktop window.
- Guard one viewer, stream/dimension/mode/connection ownership, canceled fingers, phone-auth
  heartbeats and scoped cleanup; bound video/decode/input queues and fail without replaying actions.
- Download only the official server into a private PC dependency cache with SHA-256 verification
  and its Apache license. Disable embedded audio/clipboard sync and retain explicit unmasked,
  memory-only whole-phone capture. Keep SDK recording, privacy/OS consent and `.sal` schema intact.
- Move long recording transfer/validation outside the shared workbench lock so video/input and
  heartbeat can progress. Add HTTP/controller/native input/rotation/HD-clip coexistence regressions
  and reproducible disposable emulator checks. New browser rendering/hardware performance remains
  unverified; this does not establish that the reported consuming-app HD failure is resolved.

### Desktop live diagnostics and data tools — 2026-10-05

- Add collapsible Landing Network/Logs/App values with local search, Errors only, bounded Follow/
  Pause, source status, ten pins and change baselines; clear stale/private comparisons on detected
  config/connection changes. Body previews keep the host's existing opt-in.
- Add bug notes to recent clips and retain them in the master BUG mark and clip analysis label.
- Add a Data tools SQL workbench with genuine read-only SQLite/Room connections, table reads,
  bounded masked results, async cancel/deadline/expiry and phone-shared saved-query management.
- Offer explicit redacted ordinary preference snapshots and DataStore file metadata/guidance;
  reuse decoded host Flows without creating stores, parsing arbitrary files or bypassing encryption.
- Extend authenticated internal bridge/data capabilities, approval disclosure and regression/
  integration guidance. Preserve public/no-op API and recording schema. New GUI visual acceptance
  remains open because desktop UI access was unavailable; protocol/controller/native checks pass.

### Desktop mirror and capture workspace — 2026-10-05

- Arrange Landing as resizable mirror, selected attributes and smaller semantics tree. Add height
  adjustment, keyboard controls, remembered layout/reset and responsive window layouts.
- Default to mirror Control with adb tap/swipe/wheel/long-press; add read-only Preview, Inspect
  outlines/selection and explicit return to Control. Reject stale frames/connections/modes and
  letterbox targets, pause hidden-page sampling and offer optional installed scrcpy.
- Add Start/Stop, Frames/host-approved HD, recent clip presets/custom seconds, Watch latest and
  opt-in exact-session replay after saving through the existing recorder and web player.
- Add masked app-window screenshot review/save/copy with optional overlay and secure-window
  refusal. Preserve overlay visibility during concurrent recording Start/Stop, bound binary
  transfers, recycle canceled captures and explain offline/older-SDK capability states.
- Extend authenticated internal SDK bridge capabilities, phone approval disclosure, HTTP/JS/native
  regressions and integration documentation; retain public/no-op API and recording format.

### Desktop startup recovery — 2026-10-05

- Replace raw permission/bind tracebacks with clear startup errors, nonzero exit status and quoted
  recovery commands. Write-check every workspace directory, including existing read-only folders,
  while preserving saved data and permissions. Explain file-versus-directory and config failures.
- Support `--port 0` for OS-selected loopback ports and print the actual HTTP URL. Keep fixed ports
  explicit; report occupied ports before terminal credentials or adb forwarding. Preserve scoped
  forward cleanup on shutdown and leave existing listeners intact.
- Add real subprocess/permission/listener/GUI/forward-cleanup tests to CI and document recovery.

### Compose host compatibility and quick actions — 2026-10-05

- Fix the reproduced Control Room `FlowRow` `NoSuchMethodError` with newer host Foundation:
  replace all three experimental calls with a small wrapping layout built on stable Compose UI.
  Preserve the host's dependency versions and action wrapping, including RTL placement. Reserve
  actual 48 dp touch size for Control Room small buttons to avoid overlapping expanded tap bounds.
- Widen quick actions and the evidence return control; keep Review evidence visible above the
  scrolling action list. Center report/Refresh labels with compact 48 dp minimum touch targets.
- Restore distinct Inspect elements and Inspect tags destinations. Give tags a named, inset-safe
  Done button alongside system Back; keep copy-on-component-tap and two-finger scrolling.
- Add side-by-side Record/Record HD buttons; frame capture remains masked, and HD requires host
  opt-in plus Android consent. Preserve saving/stop controls and expose the disabled HD state.
- Add a precompiled-SDK/newer-runtime consumer UI fixture and compile it in CI. Extend actual
  overlay tests for navigation, action dimensions, inspection exits and quick-button HD decoding.

### Control Room SQL and decoded app values — 2026-10-05

- Give SQL selection, query, Run, name and Save separate space with 48 dp actions; expose every
  database, saved Run/Delete and lazy bounded rows with horizontal columns. Reset preview on
  every run, show errors, retain cancellation and respect system bars/keyboard. Wrap adjacent
  recording/config action groups at narrow widths instead of squeezing their last button.
- Lead App data with searchable read-only host values and update/completion/error status.
  Add `observeDataStoreValues` for a host-owned decoded Flow: worker mapping/redaction, cached
  recording state, count-only change labels, credential masking and bounded fields. Preserve
  event-only observation; pause on disable and remove only observer-owned previews on stop.
- Keep file details optional, exclude SDK preferences, mask plain values before truncation and
  explain recognized encrypted envelopes and metadata-only DataStore files. Bound expanded
  results; keep stale privacy-policy previews hidden. Mirror the API in the non-collecting no-op.
- Add focused actual SQL/DataStore/storage UI checks, preview/privacy unit tests and external
  active/no-op consumer compilation. Document decoded Preferences/Proto/encrypted Flow wiring,
  QA acceptance and the limits of emulator/synthetic-envelope evidence in `docs/APP_DATA.md`.

### Task-based mobile overlay — 2026-10-05

- Replace 12 diagnostic tabs with Activity/Network/Logs/Elements/Device and retain host-provided
  extensions. Remove health scores, guessed owner/completeness cards, bookmark editor and duplicate
  report/tool/recording controls from the overlay; preserve public APIs and recording tracks.
- Give capture, element inspection, evidence review, PC connection and Control Room clear routes.
  Keep Close visible above a scrollable action list, respect safe insets and add inspector Done/Back.
  Clear evidence search on view changes; Back clears a query before returning to quick actions.
- Keep observed failure summaries/expandable redacted stacks, reports, concrete findings and
  integration facts. Use the host-selected palette for evidence and background work/lazy rows.
  Collapse element filters initially, scroll expanded options with results on short screens and
  explain loading, no matches and unavailable inspection.
- Open PC connection guidance separately; place manual credentials behind Manual pairing without
  changing approval, token lifetime or privacy. Move named macros to Control Room and wait for a
  resumed host before running their UI/capture actions; report launch/resume failures.
- Extend actual Android workflows for Back, search/selection, filters, report/stack copy, host tabs
  and Control Room macros. Verify continuous traffic, LTR/RTL/two-finger gestures and release
  isolation; document the current menus and remaining device/host validation limits.

### Mobile replay clock and event following — 2026-10-05

- Replay Timeline/Network/Logs chronologically with a current row that follows playback and seeking;
  allow manual browsing without pausing media and explicit Follow to return. Default to Timeline.
- Use monotonic elapsed time for frames and decoder position only inside video coverage. Preserve
  consent/clip offsets and legacy end alignment; disclose preroll/trailing gaps, freeze during
  buffering, restart at end, pause on background and preserve Play/Pause after slider scrubs.
- Step within the selected track and open the earliest error's track. Sort imported tracks once,
  cache merged events and use binary lookups; exclude future frames/state before their timestamps.
- Coalesce drag previews, request exact media seeks and detach fullscreen PlayerViews. Guard loop
  cleanup after release; expose control names/roles and bound long row previews with ellipses.
- Report decoder/frame failures; allow retained event playback after video failure. Stamp HD start
  on the encoder worker rather than in the later main-thread UI callback; keep capture opt-ins.
- Add 12 replay policy/index regressions and a focused Android runner with synthetic decoded color
  checks. Verify API 36 normal/small display/150% font, large details, actual tester workflow and
  HD notification Stop; update mobile replay guidance and preserve physical/host validation limits.

### Host integration documentation for AI agents — 2026-10-05

- Add a consumer-agent runbook with discovery/work order, public source index, privacy review,
  troubleshooting, acceptance routing and a concrete host handoff template.
- Expand integration recipes for custom QA/production variants, shared module dependencies,
  resolved production graph checks, Startup/manual lifecycle, existing navigation observation,
  stable tags/windows, actual OSS owners and phone/browser selection/collection workflows.
- Document external consumer fixture coverage and limits; update entry points/document ownership
  and remove stale sample-only desktop pairing guidance. Preserve unresolved host/device limits.
- Verify local document links/anchors/fences, external consumer debug/release/isolation, the
  documented production gate and consumer merged manifests; source-check public API examples.

### Linked QA inspection and selectors — 2026-10-05
- Link live selection between the Android inspector and desktop Landing. Phone selections load
  attributes/selectors automatically; browser tree/preview selection highlights the phone without
  tapping the app. Preserve optional captured Send/Receive, explicit actions and phone approval.
- Add searchable tags/text/roles/actions, tag/action filters in SDK More tools/Tools/inspector and
  Automation Tags, plus browser role filtering. Keep selectors collapsible in the movable dock.
- Generate scoped-tag, content/role and visible-position QaLens XPath with match counts; add browser
  builder, live matching and explicit redacted XML/selector JSON exports. Bound the evaluator;
  reject unsupported/ambiguous/truncated/stale targets and keep work off main after semantics copy.
- Guard stale device/selection replies and serialize rapid highlights, skipping superseded choices.
  Restore selection after browser refresh and clear desktop selection when the phone clears it.
- Resolve preview clicks once from fresh bounds instead of selecting again as the event bubbles.
- Fix the search field's accessible edit label and reveal results when checking a generated selector.
  Verify standard XPath equivalence/privacy, actual overlay search/copy and live phone/browser
  round trips; add browser wiring regressions to CI and update onboarding/protocol documentation.

### Android feature verification and tester UI — 2026-10-04
- Make system Back exit fullscreen Android replay and return to its controls.
- Keep the diagnostic title/Close reachable at large font sizes; move tools into their own
  scrollable row, name icon buttons and expose selected diagnostic tabs to accessibility.
- Add positive tester workflow instrumentation for screenshots/bug marks, actual Record/REC Stop,
  all diagnostic panes, Watch, successful macros, configuration, real profile upload, replay and
  Panic. Verify at 360 × 640 dp, 150% font and three-button navigation.
- Exercise all 10/20/60s presets and custom duration in frame/HD tests, and actual notification Stop
  with HD in the background. Add opt-in test-only handling of the real English OS consent dialog.
- Record the executed Android feature/build/OSS/PC/privacy matrix and remaining device/host limits
  in `docs/ANDROID_VERIFICATION.md`; update contributor commands and current handover.
- Fix desktop Receive polling starving connection health and automatic recording discovery at
  aligned timer intervals. Keep bounded reads independent and preserve generation/in-flight guards.
  Add an actual-app wiring regression to CI; verify owned-forward recovery with Receive/collection
  enabled, actual SDK Send, private master/clip copies, remembered-app fresh approval and replay/Back.
- Clear the connection hint on Disconnect so it cannot claim the phone is still connected.
- Configure CI Android setup to install `platform-tools` explicitly; the old default requested the
  retired `tools` package and failed before compilation.

### Landing workspace and phone-approved connection — 2026-10-04
- Bring screen preview, live semantics tree and selected-element attributes into Landing; load
  attributes on selection, add focused detail tabs and consolidate secondary tools/navigation.
- Discover SDK-enabled apps; primary Connect handles random credentials internally and opens a
  shared SDK phone approval prompt through a DUMP-protected receiver. Keep manual/terminal pairing.
- Bound pending approval, reject other app senders, allow Deny/cancel/expiry/disable and keep tokens
  out of Activity extras, saved profiles and reports. Preserve no-op release dependency isolation.
- Add opt-in remembered-app connection requests, session-preserving owned-forward reconnect and
  explicit memory-only whole-phone screen PNG preview (up to 1 fps); keep scrcpy optional.
- Pass 26 Python regressions, shared transfer-controller checks, SDK/sample/consumer builds/lint/
  isolation and real Android/adb/Python approval, screen, attributes, repair, paused-host transfer and
  token revocation. Later live checks pass redesigned browser selection/detail tabs, connection
  restart/recovery, recording collection, replay/Back and 1440px/390px layouts.

### Host lifecycle, SDK pairing and resilient PC transfers — 2026-10-04
- Let explicit `QaLensRoot` integrations establish application context and scoped Activity lifecycle
  callbacks when Startup installation is absent. This fixes foreground-host discovery for Send to PC
  and recording controls without requiring sample-app setup.
- Make the delayed clip acknowledgement retain a nullable-safe application context and guard its
  actual main-thread execution. Cover the reported old line-642 null-context path while recording.
- Preflight Control Room recording actions, establish lifecycle hooks before returning to the host,
  clear failed/expired requests and display disabled/privacy/launcher errors in the recording card.
- Wait for the consent Activity to resume before launching HD services, keep it visible until
  foreground promotion, start the encoder worker afterward and stop services without a background
  start. Report launch/promotion/encoder errors distinctly from denied permission. The consuming
  app's exact foreground exception still needs its trace; emulator success cannot identify it.
- Move PC inspector pairing into shared SDK Control Room/overlay controls: editable port, hidden
  token, sensitive clipboard marking, token rotation and Stop. Remove sample-only pairing UI.
  Captured inbox/ack and cached observations remain available with Control Room foreground;
  live tree reads/actions still require the host screen to be resumed.
- Keep automatic PC recording collection enabled through busy UI and transient discovery/copy
  failures, with bounded retries and deduplication. Revoke on device changes or authentication
  failures, and guard in-flight enable/disable/connection races. Component Receive also survives
  temporary failures. Run the transfer state-machine regressions in CI and add an opt-in real
  Android/adb/Python transfer check for outage recovery, paused-host copies and revoked tokens.

### Recording controls and capture memory — 2026-10-04
- Fix overlay recording controls/clip popups using an application context for visual APIs. Use a
  display/window context on API 30+ and SDK-owned native widget styles; preserve the host's StrictMode
  policy. This removes reproduced incorrect-context violations that can terminate strict QA builds.
- Dismiss clip menus/dialogs with the REC window and guard native control construction.
- Allocate recording/fallback frames at bounded size before PixelCopy, rather than allocating the
  full display and scaling afterward. Transform masks to output coordinates, round outward and reject
  resized/stale captures. Allocation failures skip a frame instead of escaping onto the host UI thread.
- Exercise actual preset/custom controls in HD as well as frame tests, with incorrect-context
  termination enabled, stop while a menu is visible, and decode every exported HD master/clip.
  The full device runner passes, including scaled partial-region masks; keep load-test tab discovery
  scoped to its strip and refresh stale accessibility cache under continuous updates.
  The reported consuming-app crash still needs its own trace to confirm the same cause.

### Product onboarding and documentation cleanup — 2026-10-02
- Add one onboarding guide for SDK/web/desktop/backend evaluation, data hooks, recording/clips,
  pairing, persistence, component processors, privacy, troubleshooting and team integration advice.
- Replace superseded handover verification history with the current dated baseline; retain older
  history in Git. Shorten the demo, link the guide from entry points and correct stale architecture,
  storage and resolved lint notes. Keep next.md as the only backlog.
- Use the Gradle wrapper/configured toolchain in contributor and demo flows, respect the selected
  backend port, and describe demo checks as a convenience subset rather than full CI/device coverage.
- Keep source/private capture data separate and ignore local log output consistently.

### Desktop launcher and retrospective bug clips — 2026-10-02
- Embed the existing modern/classic web clients directly in the Python GUI, with onboarding choices,
  sticky Back/Start, browser history and viewport-aware replay. Keep one source for reader/viewers.
- Add common connection-guarded adb actions, optional installed scrcpy and bounded Downloads push/pull.
  Discover finished recordings; explicitly copy/open or opt into new-session/clip collection on this
  connection. Stream privately, deduplicate by SHA-256 and reset automatic collection on failures/switches.
- Add `saveRecentClip` in active/no-op APIs and presets/custom intervals beside REC. Continue capture
  while marking; save independent `.sal` clips after stop with recent-buffer evidence and aligned
  keyframe video. Preserve master keep-earliest journals and disclose coverage/budget losses.
- Replace the five-minute stop with a configured duration (default hour), duration-aware JPEG/video
  sampling/bitrate and media/clip/history limits. Move recorder setup/stop and cleanup off main;
  check encoder alignment/ranges, fall back sizes and reject videos without readable samples.
- Verify API 36 real consent, HD master/clip replay, late evidence after log overflow, >5-minute
  continuity, GUI collection/file round trips and existing safety/integration/load checks. Full-hour,
  physical-phone encoder/TalkBack and actual scrcpy checks remain open; see the current handover.

### PC component workbench — 2026-10-02
- Add Send to PC beside Copy test tag, with asynchronous bounded memory previews, acknowledgement,
  overflow accounting and stop/disable cleanup. Capture public semantics attributes, values, bounds
  and visible ancestry/sibling context; omit hidden/password/custom values and protect spoofed keys.
- Expand the browser GUI with component attributes/viewport preview, explicit JSON save/import/export,
  a saved library and SHA-256 identity over stable content. Changed values/positions create new files;
  timestamps/live IDs do not. Profiles remember phones/packages/activities without pairing credentials.
- Add GUI adb discovery/package suggestions/owned forwarding and explicit no-reset launch. Import
  selected Appium-style profile fields, report ignored driver/reset options and version mismatches.
  Browser commands against a switched device require a new snapshot.
- Add trusted local argv pipelines with saved inputs, background execution, timeouts, failure results,
  cancellation and bounded output inspection. Include a summary processor and a browser-free CLI.
- Cover protocol/workspace/profile/hash/pipeline behavior with Python regressions and real API 36
  phone transfers, private/custom-key exclusions, queue overflow/ack/restart and existing gestures.
  The full recording/integration/load runner passes; physical phones/Windows remain unverified.

### Local PC bridge and inspector movement — 2026-10-02
- Add explicit `startLocalBridge` / `stopLocalBridge` and readiness state, mirrored by the release
  no-op. The authenticated device listener binds loopback, stops on disable, bounds requests and
  main dispatch, and rejects queued actions after cancellation. Socket/JSON/redaction work runs off main.
- Add a Python standard-library PC server with owned adb-forward cleanup and a browser tree/bounds
  inspector. Read visible, root-scoped parent relationships/test tags, select nodes and invoke public
  Compose tap/type/scroll actions. Ambiguous tags fail explicitly; hidden subtrees and password values
  are excluded. Recent bounded observations include network metadata and cached host-owned data sources.
- Expose a manual pairing control in the debug sample Settings. Pairing stays in process memory and
  is hidden from reports; no bridge, browser polling or recording starts automatically.
- Forward two-finger inspect/tag drags to the host as centroid-based single-finger drags. Cancel the
  pending inspect tap, end when a finger lifts and suppress the remaining finger's accidental click.
- Replace mirrored/unbounded bubble offsets with physical normalized positions and add a draggable
  inspector dock. Clamp both against system/IME safe bounds and preserve positions across size changes.
- Add protocol/proxy tests and real-device semantics/privacy/lifecycle/timeout plus LTR/RTL gesture
  checks. See `tools/local-bridge/README.md` for operation, API limits and coverage boundaries.

### Overlay responsiveness under continuous logging — 2026-10-01
- Batch log and network dashboard updates every 100 ms with bounded pending queues, instead of
  posting a main-thread task and copying history for each observation. Keep recording admission
  independent; clearing or evicting dashboard rows does not discard recording evidence.
- Bound dashboard logs to the requested history (20–10,000 entries), 1,048,576 text characters
  across messages/tags, and 16,384-character previews per field plus a truncation notice. Network
  dashboard history remains capped at 250 requests. These are text/entry limits, not a heap guarantee.
- Prepare Repro/Bug Bundle evidence, log filtering/grouping and global search on a background
  worker. Conflate updates so a stream that never stops cannot starve computation. Render Repro,
  Logs, Network and search results lazily; bound visible text layout for very long messages.
- Move overlay report/clipboard formatting off main, cap clipboard output with an explicit notice,
  remove the redundant semantics scan from the bubble tap, and index screen warnings by node.
  Preserve synchronous report APIs and flush queued observations when building an export.
- Add concurrency/queue/evidence regressions and a device load case covering a 12,000-line burst,
  continuous background and main-thread logs, network events, tab switching and main-thread heartbeats.

### Data hooks and recording insights — 2026-09-24
- Refresh registered app-data snapshots when Room invalidates a table or an observed DataStore
  Flow changes, so subsequent recording state samples do not retain stale values. Add explicit
  stop hooks for host-owned databases and flows, mirrored by the release no-op SDK.
- Tag Room and DataStore observations without reading rows or preference values. Count them in
  integration diagnostics and `analysis.json`; identify a change within five seconds before a
  failed request as a bounded temporal lead, explicitly not a causal conclusion.
- Verify real Room and Preferences DataStore writes/unsubscribe on the device, and assert a
  preference change reaches `state.json` and analysis. Keep sample event labels free of values.

### Compose inspection and visual controls — 2026-09-24
- Discover attached Compose roots in the host Activity plus optionally registered Dialog/Popup
  roots. Scope node IDs by root, map separate-window coordinates, drop offscreen nodes, and match
  repeated `qaTag` hints by bounds. Hidden subtrees no longer reappear through manual hints.
- Refresh semantics-only changes while Inspect/Tag is open, with an explicit invalidation hook for
  hosts. Add matching active/no-op APIs and a Compose root modifier for separate windows.
- Default the visual inspector to Actions and add All/Tagged/Issues filters plus a selected-node
  detail card with a copyable test tag. Use merged accessibility labels without duplicating child
  image descriptions onto already text-labeled parents.
- Expand Android device coverage for dialog roots, hidden content, label merging and state-only
  changes; compile the new API in the independent debug/release consumer.

### Tester accessibility and archive retention — 2026-09-24
- Give the tester sheet a named Close button, button roles for actions, an expanded/collapsed
  More tools state, and decorative symbols hidden from accessibility. Correct the English privacy
  note's punctuation when the host app uses RTL layout. Review the sheet on a Pixel emulator in
  RTL and both overlay color schemes; physical-device TalkBack remains to be checked.
- Store completed `.sal` archives in app-private files instead of evictable cache. Rescan on SDK
  startup, migrate older cache archives without deleting a failed move, and expose the new location
  through the existing sharing provider. Temporary frames and screenshots still use cache.
- Add device coverage for archive location, cache migration and share URI. Document that a host
  must exclude the archive directory from its own backup rules when evidence must stay only on the
  test device.

### Archive reader limits — 2026-09-23
- Bound ZIP entry counts, per-entry and total expansion, nested gzip text, and manifest size in the
  shared web/CLI reader and Python mock backend. Reject invalid paths, duplicates, ZIP CRC errors,
  missing listed entries and manifest CRC mismatches before replay or backend storage.
- Add v1/v2, nested compression, tampered checksum and expansion regressions. The backend remains
  a loopback-only development mock without authentication or tenant isolation.
- Make the recording stop chip expose an accessibility click action and release its in-app window
  when its Activity is destroyed. The projection notification opens recording controls on a body
  tap while its explicit action stops capture. Move chip/notification text into resources and show
  a short upload verdict in Control Room instead of truncating raw backend JSON.
- Build webhook app/device metadata from application context so uploads started in the standalone
  Control Room retain headers and query context even before a host Activity has resumed.
- Align the Gradle wrapper with CI at 9.1.0 and make the internal Maven zip script accept a version,
  clearing old generated publications before packaging.

### Tester flow and local upload demo — 2026-09-23
- Make the QA quick-actions sheet the default for new installs, with three primary actions: record a
  session, capture a screenshot, and mark a bug. Move macros, device/report copying, tags, Control
  Room, watch mode and developer diagnostics under **More tools**.
- Apply the existing overlay tokens to that sheet. Add a deliberate **Send latest session** action
  when a backend endpoint and saved recording are both available, with a short upload outcome.
- Make the local backend and demo web server bind to loopback by default. Restrict browser CORS to
  loopback or same-host pages; validate request sizes, JSON object bodies, chunk sizes/counts and
  upload IDs before accepting or using them. Remove chunk staging before returning finalize success;
  make the curl demo use the same 1 MB chunk contract as the Android client.
- Make the sample's debug-only cleartext exception explicit for loopback domains and align imported
  `.appsal` files that omit a panel preference with the tester-first default.
- Replace the handoff prompt duplicate with the repository's single current handover. Refresh the
  backend quick start and state clearly that the mock stores raw recordings and is not a shared
  production service. QaLens itself remains free and self-hostable under MIT.
- Validation: `./demo.sh test` passed (web reader, all 20 backend tests, Kotlin unit/release-parity
  checks and expected failing-session CLI smoke); `:sample-app:assembleDebug` and debug lint for the
  Compose, Android and sample modules passed. Lint warnings and the manual API 36 AVD and backend
  smoke results are recorded in HANDOVER.

### Host-adaptive overlay foundations — 2026-09-13
- Add `QaLensTokens`: host-adaptive palettes for the bubble, inspect/tag canvases, scrim and tag
  legend. A light host gets the dark overlay panel; a dark host gets an opaque light panel. The selector reads the
  configuration night mask because the overlay attaches at decor level, outside the host
  Material theme.
- Fix a real contradiction: `#E53935` meant "accessibility warning" in the inspect canvas and
  "untagged interactive component" in the tag canvas. Warning and untagged are now distinct.
- Add `QaLensTokensTest`, which recomputes WCAG contrast and fails below AA 4.5:1. Measuring the
  first draft showed its quoted 4.6–5.4:1 ratios were actually 2.2–3.8:1, because the signal
  colours had been chosen against a light surface and drawn on a dark one; all values were
  recomputed for the surface they are used on.
- Migrate the QA bubble, inspect and tag canvases, panel scrim, tag chip and tag-mode legend to
  tokens. The bubble keeps its original crisp white ring. Add `QaLensDimens` (4dp scale, one
  radius family, 48dp touch minimum) and `QaLensType`.
- Document the system, contrast tables and adoption order in docs/OVERLAY_DESIGN.md. The
  inspector panel, Control Room and REC chip still use their own literals and remain to be migrated.

### Handover consolidation — 2026-09-13
- Replace conflicting onboarding snapshots with one self-contained HANDOVER and a short takeover
  prompt; add root AGENTS navigation and a single current backlog.
- Preserve archive details in docs/SAL_FORMAT.md; correct architecture, privacy, retry, integration
  and verification guidance. Remove six superseded guides/snapshots; Git retains their history.
- Refresh documentation links, including the landing-page footer. No runtime behavior changed.

### Android client safety — 2026-09-13
- Preserve coroutine crash delivery in active and no-op SDKs; redact complete authorization values.
- Make runtime disable stop capture/collectors/uploads, finalize recording and detach overlays.
- Move JPEG/PNG file work off main; atomically publish screenshots; add Compose privacy masks,
  secure-window guards, private-cache defaults and explicit gallery/unmasked-video opt-ins.
- Skip streaming response previews; isolate imported webhook credentials; handle queue pressure,
  cancellation and persistent transient retries with settings captured per upload.
- Bound Android replay extraction, text decoding and CRC work; clean session caches and decode
  downsampled frames off main. Refresh route inspection, bound background SQL and report macro
  failures accurately. Wire periodic memory sampling and preserve frame penalties in exports.
- Export public coroutine types to consumers from both active and no-op artifacts.
- Add fourteen JVM regressions and expand device coverage. See docs/CLIENT_SAFETY_FIXES.md for
  migration behavior, executed checks and remaining validation limits.

### OSS integration and Kotlin hardening — 2026-09-08
- Replace invalid Chucker-as-source reflection with public launcher and interceptor coexistence;
  legacy configuration no longer silently suppresses network capture. Sample uses Chucker 4.1.0
  with matching release no-op, verified against the current Kotlin 2.0.21 toolchain.
- Add dependency-free network sinks, shared redaction/capture limits, declared source coverage,
  integration diagnostics and an Overview copy action. Deduplicate OkHttp observations per call.
- Add bounded/redacted external crash input and prevent echoing vendor-reported crashes back.
- Fix missing network-state permission, default-network transitions, notification permission
  race handling and Compose settings collection.
- Fix composite-build coordinates and add an independent consumer with debug/release checks,
  contribution and migration docs, issue/PR templates and a GitHub verification workflow.
- Add eleven core and eight real OkHttp tests; extend device regression coverage for Chucker,
  adapters and crash reporters. See HANDOVER.md for validation and limits.

### Recording retention — 2026-09-08
- Preserve early logs, requests, crashes, timings, connectivity, memory, bookmarks and state
  independently of dashboard history limits and log clearing.
- Bound each recording track by entries and estimated size; disclose omissions and Android
  dropped frame callbacks in analysis coverage, replay viewers and exported reports.
- Block clean CLI/backend conclusions and unverified fixes when recordings report evidence loss.
- Fix quadratic email redaction on long body tokens, discovered by the device overflow check.
- Add twelve core tests, fourteen web/CLI assertions, two backend tests and a dependency-free
  Android instrumentation runner for real archive retention and budget-overflow checks.

### Reliability audit — 2026-09-08
- Restore true debug/release SDK separation and enforce the release runtime dependency graph.
- Fix actual Android v2 archive decoding in web/CLI/backend and the upload digest header.
  Reject malformed archives and report insufficient evidence instead of a healthy verdict.
- Guard recording callbacks and saves by lifecycle/session; preserve stalled recordings,
  follow foreground activities, save off the UI thread, publish atomically, and stream CRCs.
- Restrict recorded evidence to the session window and include the crash during finalization.
- Correct Android frame timing units and batch UI updates to avoid a rendering feedback loop.
- Prevent recursive crash-handler installation; preserve host crash delegation.
- Bound body previews, skip one-shot/duplex requests, and preserve response streams.
- Fix screenshot bitmap cleanup and overlay restoration; redact annotation text.
- Add saving feedback, overview crash evidence/copy action, and jank sample counts.
- Expand verification with lifecycle, window, crash, stream and actual-layout archive regressions.
  See HANDOVER.md for results and remaining limitations.

### Correctness
- Use consistent nearest-rank p95/p99 calculations for network health, exported analysis,
  and frame timing. Fix off-by-one ranks at exact percentile boundaries and underestimated
  network p95 for small samples. Add seven core regression tests; preserve sample inclusion
  rules, public APIs, and archive schemas.

### Backlog completion, hooks & mock backend
- **Backlog completion.** Phases 2–5 shipped: B3 (pluggable tabs), B4 (GitHub/Linear/Markdown
  exports), B5 (connectivity + Chucker coexistence), B7 (coroutine exception handler), B8 (memory
  samples), B15 (macro assertions), B16 (per-source redaction), C8–C16 (keyboard help, ⭐ marks,
  mobile/touch layout, analysis.stats/endpoints, AI Brief, compare/diff, sal_report exit code,
  formatVersion validation, filmstrip precision), A6 (DataSourceObserver), A7 (NoopParityCheck);
  A3 (AnalysisEngine extraction) landed in part. See `next.md`.
- **Web player makeover.** AI Brief tab (`for_ai.md`, key `7`), ⭐ mark moments (key `m`, scrubber
  stars + `marks.json`), opt-in **Backend URL** + **⇪ Send to backend** hook, responsive
  mobile/touch layout.
- **Mock backend.** Stdlib-only `backend/server.py` (webhook + ingest + dashboard + uploads store +
  deterministic mock AI verdict) + `backend/tests/test_backend.py` (11 e2e tests) +
  `backend/README.md`.
- **Review.** An engineering review of core/compose/web/backend was added; current work is tracked in `next.md`.

## 0.9.0 — 2026-06

### Reliability overhaul (service-first)
- **Recording lock-out fixed.** Recording used to hide the entire overlay with no working stop
  control (the notification was silently dropped on Android 13+ without `POST_NOTIFICATIONS`,
  its actions died with the host activity, and shake only toggled a hidden panel). Stop is now
  layered: floating system REC chip (draw-over-apps, optional) → in-window REC pill (kept out of
  captured frames) → notification action via a manifest-declared `QaLensControlService` →
  shake-to-stop. Overlay visibility self-heals on every resume; `panicRestore()` recovers any
  stuck state.
- **Task separation.** The Control Room and the `.sal` player run in their own tasks
  (`taskAffinity` + `singleTask`); the player no longer hijacks the host app's task or its
  launcher icon.
- **Control Room** (`QaLensControlActivity`, own launcher icon): recording controls (arm-and-jump
  so the Control Room itself isn't recorded), panic restore, overlay inject kill-switch,
  recordings manager with two-tap delete, permission grants, persisted settings.

### `.sal` format & players
- New manifest fields: `sessionId`, `videoStartMillis` (precise video↔track alignment — video
  starts at consent-grant, not session start), platform/locale/timezone/screen metrics.
- **`analysis.json`** (schema `qalens-analysis/1`): precomputed digest — coverage (what's missing
  and why it matters), stats, per-endpoint aggregates, screen spans, timestamped anomalies,
  likely owner. **`for_ai.md`**: every archive explains itself to any AI, with a tasked analysis
  brief for dev / QA / management.
- Players (Android + web): all events listed from the start with the playhead row highlighted and
  future rows dimmed; click/tap any row to seek the video to that exact moment; sub-second
  timestamps; fullscreen/theatre modes. Fixed `org.json` null handling that flagged every request
  as an error.
- **Web "Mission Control"**: filmstrip, error-marked scrubber, playback speed, six synced tracks
  (incl. Screens visit map and auto-Insights), IndexedDB instant-replay recents, markdown export,
  deep links (`?sample&t=24.6`), settings drawer — all preferences persisted locally.
- **CLI**: `web/tools/sal_report.js` turns a `.sal` into a Jira-ready markdown/JSON report and
  exits non-zero on failures (CI gate). `web/tools/make_sample.js` regenerates the bundled demo.

### QA experience
- **QA Minimal panel** (switchable vs the full developer panel): big record/stop, screenshot,
  ⭐ Mark moment (starred breadcrumb + screenshot), copy bug report / device info, tag-mode
  toggle, tappable status chips, and the 5 most-recently-used macros.
- **Tag mode**: every visible test tag drawn on its component; untagged interactive components
  flagged; tap a tag to copy it; exit from the on-screen pill.
- **Macros**: step DSL (`deeplink/wait/tap/type/record/stop/screenshot/mark`); `tap`/`type` drive
  real Compose semantics actions and wait for their targets — a macro can complete a full login
  unattended.
- Screenshots save to the system gallery (Pictures/QaLens) with a confirmation toast; sharing is
  optional. Log-heavy apps: bigger buffers, log filter + level chips + duplicate collapsing.
- Draggable bubble position persists across panel open/close and process restarts.

### Data tooling & portability
- **Database card**: raw SQL into the app's own SQLite/Room databases — SELECTs render rows,
  writes report exactly how many rows were affected, every execution lands in the timeline.
  Saved queries per package. SharedPreferences/DataStore visibility.
- **`.appsal`** (`qalens-appsal/1`): one shareable JSON config per app package — panel style,
  overlay prefs, webhook, saved queries, macros, watched prefs. Export (secrets masked by
  default) / import on-device; full **web editor** (create, edit, download).
- **QA Profiles**: shared test phones, per-tester webhook identity (endpoint/bearer/user);
  uploads carry `X-QaLens-User`. Profiles deliberately never leave the device.
- **Webhook**: per-recording multipart upload to an analysis backend with `X-QaLens-*` metadata
  headers and `X-QaLens-Digest` (the file's own stats — triage without unzipping); backend
  response shown in the Control Room; test-ping button. Dependency-free (`HttpURLConnection`).

### Distribution
- `maven-publish` on all library modules: `./gradlew publishToMavenLocal` →
  `com.qalens:qalens-*:0.9.0`. See [`integration.md`](integration.md) for developer and AI-agent integration instructions.
