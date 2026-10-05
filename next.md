# Current backlog

Updated 2026-10-05. This is the only current backlog. [HANDOVER.md](HANDOVER.md) owns verified
baselines and project context. Older changelog entries are historical, not uncompleted work.

## Completed 2026-10-05: desktop startup recovery

- [x] Reproduce permission and occupied-port tracebacks with disposable storage/listeners; replace
  them with precise startup messages, nonzero exits and quoted recovery commands.
- [x] Write-check existing workspace folders, preserve permissions/data, support `--port 0` and
  bind before credentials/adb. Verify real GUI startup, stop and scoped terminal forward cleanup.
- [x] Add CLI regressions to CI and update desktop/onboarding recovery guidance. POSIX permissions
  are tested on a non-root account; Windows ACL behavior still needs platform validation.

## Completed 2026-10-05: overlay compatibility and quick actions

- [x] Reproduce the exact Control Room crash in an external consumer: SDK Foundation 1.7.6 versus
  host runtime 1.8.2, `FlowRow` failure at 330/304/564. Replace all experimental calls with stable
  wrapping layout without changing host dependency versions.
- [x] Widen overlay actions/navigation; center compact report/Refresh controls and keep Review
  evidence fixed on quick actions. Restore separate elements/tags modes with visible exits.
- [x] Add side-by-side frame/HD recording with existing privacy/OS consent contracts, plus an
  actual quick-button HD decoding test and newer-runtime consumer fixture compiled by CI.
- [x] Complete final normal/small/150%/three-button workflow, HD decoding, external runtime,
  SQL/data UI and bridge gesture checks; inspect screenshots, pass full runner/build/lint/release
  gates and document physical-device/other-runtime limits.

## Completed 2026-10-05: usable SQL and app data

- [x] Reproduce the narrow SQL controls in screenshots at 360×640 dp / 150% font / three-button
  navigation; separate fields/actions, expose all databases, saved Run/Delete and scrollable lazy
  result previews. Respect system/keyboard insets and wrap narrow action groups.
- [x] Add searchable decoded host values/status to Control Room. Connect existing DataStore Flows
  through a worker-mapped, bounded, redacted cache; retain event-only API and release/no-op parity.
- [x] Verify foreground writes, current privacy rules, initial-read semantics, pause/resume/stop,
  finite/error status and owned-preview replacement. Keep file metadata optional and distinguish
  recognized encrypted preference envelopes from decoded values; exclude SDK config preferences.
- [x] Add actual UI/real Preferences DataStore fixtures, unit/no-op/consumer coverage and integration
  guidance; verify normal/small-display focused data UI, small-display tester workflow and full
  Android runner. Physical/TalkBack/other serializers and consuming-app acceptance remain separate.

## Completed 2026-10-05: overlay task cleanup

- [x] Replace the 12-tab diagnostics panel with Activity/Network/Logs/Elements/Device and keep host
  extensions. Remove scores, guessed owner/completeness cards, bookmark editor and duplicate tools.
- [x] Give quick actions direct Inspect/Review evidence/Connect to PC/Control Room destinations;
  make Close fixed and Back/search navigation predictable. Keep manual pairing off the first PC screen.
- [x] Move named macros to Control Room and wait for host resume before capture/interaction.
  Keep actual failure stacks, concrete findings, async processing and existing public/evidence contracts.
- [x] Complete final small-screen, continuous-load and integration/isolation verification; document
  actual executed coverage. Physical-phone/TalkBack and consuming-app validation remain separate.

## Completed 2026-10-05: mobile replay synchronization

- [x] Use elapsed time for frame playback and decoder time only within HD coverage; handle seeks,
  buffering, replay from end and pause when leaving the player.
- [x] Follow the active event chronologically across Timeline/Network/Logs, preserve manual browsing
  with an explicit Follow control, and synchronize event taps/scrubbing with media and State.
- [x] Add clock/index regressions and focused Android frame/video playback/scroll/seek checks;
  verify builds/lint/release isolation and document actual device coverage.
- [x] Step within the selected track, open the first error's track, bound long row previews and keep
  retained events playable after decoder failure. Stamp new HD start metadata on the encoder worker.
- API 36 frame/video, decoded seek colors, selected events/state, manual browse/follow, playing scrub,
  fullscreen/Back, background pause, legacy alignment and restart pass, including 360×640 dp at
  150% font and a 160k-character detail. Existing tester workflow and real HD notification Stop pass.
- Physical phones, rotation/process recreation, other OS/decoder versions and sub-frame calibration
  still need their own validation; the reported consuming-app crash is not established as resolved.

## Completed 2026-10-05: AI host integration documentation

- [x] Add an agent discovery/work order, privacy surface review, failure diagnosis, public source
  index and host completion template; link it from repository entry points.
- [x] Expand variant/shared-module mapping, production graph gate, Startup/manual lifecycle,
  existing navigation observation, tag/window design and real OSS/data owner recipes.
- [x] Document phone/browser inspection, connection/collection choices, artifact identity and
  host acceptance separately from SDK tests; clarify external consumer fixture limits.
- Reported consuming-app HD/ANR behavior and remaining device/toolchain checks still need actual
  host validation. Documentation completion does not close those engineering items.

## Completed 2026-10-05: linked QA inspection and selectors

- [x] Link Android inspector/search and browser selections in both directions without requiring
  Send to PC or triggering a host tap. Refresh changed-selection bounds/actions, restore browser
  selection after refresh and reject stale reads/device switches/superseded queued highlights.
- [x] Search tags/text/roles/actions in the overlay and browser; add action/tag filters plus browser
  role filtering. Keep advanced controls under More tools/Tools and collapsible inspector details.
- [x] Share a bounded core selector engine: unique/scoped tags, role/content/position XPath, counts,
  redacted QaLens XML, live match queries and explicit exports. Reject unsafe/unsupported/ambiguous
  XPath; keep hidden/password/custom coverage limits and worker processing.
- [x] Verify actual overlay search/copy, live phone↔browser highlighting and attributes, clipboard,
  search/action filters, page refresh and query builder; add standard XPath/core, bridge/privacy
  and actual browser wiring regressions. Update contributor/onboarding/protocol guidance.
- Physical phones/TalkBack/other Compose versions and browser-download completion in Codex remain
  unverified. Robot integration, flow recording and broad Appium replacement are outside this scope.

## Landing desktop and easier connection — 2026-10-04

- [x] Consolidate screen preview, semantics tree and automatic selected-element attributes on Landing;
  add clear Attributes/Semantics/Tree position/Diagnostics tabs and compact secondary navigation.
- [x] Discover authorized phones and SDK apps; replace primary token-copy flow with memory-only
  generated credentials and explicit SDK phone approval. Preserve advanced/manual compatibility.
- [x] Gate requests to authorized adb shell, keep requests stable, expire/cancel/deny them and reject
  ordinary other app UIDs. Preserve debug/no-op isolation and keep credentials out of profiles/reports.
- [x] Add opt-in remembered-app auto connect (new phone approval each session), owned-forward
  auto reconnect and explicit whole-phone sampled preview with memory/process/rate/pixel budgets.
- [x] Exercise actual phone approval, screen PNG, attributes/save/dedup, forward repair, paused-host
  Send/recording copies and revocation through Android/adb/Python; run 26 Python regressions and
  SDK/sample/consumer build/lint/isolation checks.
- [x] Verify redesigned Landing in the live browser: Connect/phone approval, mirror rectangle
  selection, four detail tabs, SDK Send, remembered-app restart/fresh approval, auto reconnect,
  Receive/collection, recording replay/Back and wide/narrow layouts. Fix inbox polling starving
  connection health and recording discovery; add real-app timer wiring regression to CI.
- Physical phones/OEM receiver behavior, TalkBack and real scrcpy remain open.

## Completed 2026-10-04: host lifecycle and PC transfer reliability

- [x] Reproduce Control Room's silent recording failure without Startup; establish scoped lifecycle
  and application context from `QaLensRoot`, bootstrap explicit Control Room recording actions,
  preflight privacy/enabled/launcher failures and expire unconsumed requests.
- [x] Guard delayed clip acknowledgements against null global context and stale sessions; exercise
  the old null-context path while capture continues, and decode HD master/UI clips from Control Room.
- [x] Keep HD consent visible until resumed service launch/foreground promotion, defer worker setup
  until promotion and report actual startup failures. The consuming app's exact foreground-service
  exception remains unconfirmed without its trace/device build.
- [x] Put PC pairing in shared SDK Control Room and both overlays, remove sample-only controls,
  and verify Send to PC, token rotation/privacy/stop with manual root installation. Receive already
  captured components and list completed recordings while Control Room is in front.
- [x] Queue auto-transfer enable during busy operations, retain it through transient failures with
  bounded retries, and reject stale connection/enable/disable responses. Add executable regressions
  to CI and keep component Receive enabled after temporary errors.
- [x] Verify real Android → adb → Python → shared transfer-controller copies, temporary forward
  outage recovery, private hashed files, deduplication and revoked authentication with an opt-in
  disposable-emulator harness.
- [x] After unlocking, verify the actual Firefox transfer checkbox/recovery, embedded replay/Back,
  SDK Send received with Control Room foreground, private JSON save/dedup and rotation shutdown.

## Completed 2026-10-02: desktop launcher and retrospective clips

- [x] Onboarding choices and Back/browser-history navigation; embed both existing `web/` viewers
  directly without copied sources. Keep replay controls reachable and pause video when leaving.
- [x] Add connected-device Back/Home/Wake/Settings, optional installed scrcpy lifecycle and bounded
  Downloads push/pull. Require the current connection nonce; keep launch separate and no-reset.
- [x] Detect completed archives and provide explicit Copy/Open replay plus connection-scoped,
  off-by-default automatic transfer of new sessions/clips. Stream privately with content hashes.
- [x] Remove the five-minute stop, add a configured duration and media/disk retention bounds;
  mark 10/20/60s or custom 1–300s without stopping. Export separate `.sal` clips after stop using
  an independent recent evidence buffer and playable-keyframe video trimming.
- [x] Move MediaRecorder setup/stop off main, check actual encoder sizes/alignment/rates, fall back
  resolutions and validate encoded samples. Pass real API 36 consent, HD master/clip playback,
  a >5-minute continuity check, automatic PC collection and GUI file push/pull.
- Still validate full-hour endurance, physical-phone encoders/rotation/consent recovery and TalkBack;
  actual scrcpy mirroring is unverified here because scrcpy is not installed. See
  [recording clips](docs/RECORDING_CLIPS.md) for budgets and evidence/timing limits.

## Completed 2026-10-02: PC component workbench

- [x] Send a selected component beside Copy test tag; preview redacted public attributes, values,
  bounds and visible tree context without automatically writing application data to disk.
- [x] Save/import/export content-addressed JSON; identical content reuses a file, changed values
  and tree position produce new hashes. Exclude capture times/live IDs from content identity.
- [x] Remember phone/package/activity profiles without credentials or resets; GUI adb pairing,
  installed-package suggestions, explicit launch and stale-connection guards.
- [x] Add trusted local argv processing pipelines, a standalone CLI, asynchronous status and bounded
  UTF-8 output previews. Require saved input; report failures/timeouts and cancel on shutdown.
- [x] Verify privacy (including custom/spoofed keys/password QA names), bounded inbox/ack/restart,
  hashes/files/profiles/pipelines, real phone-to-PC preview/save and browser import/results.
  Physical phones, TalkBack and Windows cleanup remain outside the executed matrix.


## Completed 2026-10-02: PC control and inspector movement

- [x] Add an explicitly enabled local automation bridge and a PC browser tool using adb forwarding.
  Read redacted, root-scoped Compose trees, select exact tags/IDs, and run tap/type/scroll actions.
  Include bounded observed logs/network/data snapshots, clear command errors, authentication,
  loopback binding, shutdown on disable, and release/no-op parity. No hidden content or implicit capture.
- [x] Route two-finger inspector drags to the host scroll surface while single taps inspect.
  Verify actual host scrolling, no accidental clicks, cancellation, and normal overlay controls.
- [x] Use physical, bounded bubble coordinates in LTR/RTL; reclamp for size/inset changes.
- [x] Add a movable inspector dock with a drag handle, safe system-bar/IME placement and persistence.
  Verify the four filters and selected-node detail stay usable on small screens after viewport
  changes and with three-button navigation. Physical rotation remains in the device matrix.
- [x] Exercise the PC-to-device path, gestures and RTL on a disposable emulator, run unit/build/lint
  and release isolation checks, and update integration instructions and the handover with limits.

## Priority 1: make the client useful to real QA teams

- **Confirm the reported HD/Last 10s crash in the consuming app.** Incorrect visual-context use
  in recording overlays/popups is reproduced and corrected without disabling host StrictMode.
  Recording bitmap allocation is bounded before capture. Null-context clip acknowledgement and
  consent/foreground-service handoff paths are hardened. HD now exercises the actual clip controls,
  not just API marks. Still obtain the app/device crash trace and verify the consuming build includes
  these changes; sample/emulator success cannot establish that this was its cause.

- **Validate the responsiveness fix in real host apps.** Dashboard queues are now bounded and
  batched, Repro/evidence/filter/search processing runs off main, and large tracks render lazily.
  The emulator load regression exercises a continuous stream; retest the reported overlay/Repro
  ANRs in the consuming app. Capture a trace if a stall remains. Stress very large individual log
  payloads and custom regex/provider code: input redaction runs on the caller and snapshot providers
  retain their main-thread contract. Keep recording coverage honest if further ingress limits are added.

1. **Validate the task-based overlay on physical phones.** Quick actions has recording,
   screenshots, bug marks and element inspection, with separate Review evidence, PC connection
   and Control Room destinations. Five built-in evidence views replace the old 12-tab panel;
   scores, bookmark editor and duplicate toolbars are retired from the overlay. Current behavior
   and the executed API 36 matrix are in [mobile overlay](docs/MOBILE_OVERLAY.md) and
   [Android verification](docs/ANDROID_VERIFICATION.md). Run TalkBack, actual company builds,
   different window sizes/Compose versions and physical phones; fix focus, dismissal and stale-state
   issues found. Emulator success does not establish the reported host failures are resolved.
2. **Physical-device capture and recovery matrix.** Exercise video opt-in, consent denial/late
   consent, OS projection stop, rotation, backgrounding, interrupted save and disk-full handling.
   Test password/redaction-matched/custom content and multiple windows, plus the API 23 screenshot
   fallback. Acceptance: no host crash, no stuck capture/saving controls, no cross-session callbacks,
   and explicit failure/coverage when output cannot be preserved. Keep frame recording as default.
   The reported HD failure in a newer host remains undiagnosed: establish whether OS consent
   appeared, check the explicit unmasked-video opt-in, and compare SDK builds in the same app/phone.
3. **Self-hosted company service boundary.** Decide how teams will bring their own identity, TLS,
   tenant isolation, retention/deletion and storage. The current Python service intentionally remains
   a loopback development mock with no authentication; put production deployment/auth in a separate,
   reviewed service design rather than implying the mock is multi-tenant.

## Priority 2: preserve client reliability and integration

- **Observer and inspection edges.** Real Room and Preferences DataStore writes/unsubscribe,
  observer cancellation/resume, preference snapshot freshness and route clearing are now covered
  on an API 36 emulator. Stress concurrent enable/disable and other Room versions. The active
  Inspector polls semantics-only updates, and a device runner checks separate Compose dialog roots, duplicate-tag
  reconciliation and hidden subtrees. Continue with background logging, physical-device windows,
  and Compose version compatibility beyond the tested 1.7.8 runtime.
- **Close Android lint follow-ups.** Frame metrics now use a weak Activity reference and detach on
  destroy; the in-app stop chip detaches on destroy, exposes an accessibility click and uses string
  resources. The projection notification body opens controls while its action stops recording.
  The stop chip now uses weak View references; its prior static-field lint warning is resolved.
  Validate TalkBack and rotation on physical devices. Review remaining sample metadata, Compose
  and dependency-freshness warnings without masking them with a baseline.
- **Facade decomposition.** `AnalysisEngine`, recording lifecycle/window, journals and crash
  registration have been extracted. Observation, recording coordination, panel state, evidence
  services and activity bridging still overlap in the facade. Extract one boundary at a time with
  behavior coverage; preserve public APIs and release/no-op gates.
- **Binary API compatibility.** Source consumer/parity checks pass, but do not establish old compiled
  client compatibility. Add broader API signature coverage and a versioned compatibility policy.
- **Host-owned OSS examples.** Add small, versioned native Ktor/Cronet/Apollo or crash-vendor
  examples where useful. Chucker's supported interceptor and launcher path is already covered;
  avoid duplicate network observations and crash-vendor echo.
- **SQL cancellation and webhook query contracts.** Stress long writes and report cancellation
  accurately. Replace raw query-string extras with an explicit encoding contract without breaking
  existing callers.

## Priority 3: tools, distribution and polish

- Add hostile-recording DOM regressions and load/compare race coverage to both web viewers.
- Add jank trend visualization, error-eviction/concurrency tests and rotation/leak diagnostics when
  the client backlog above is clear.
- Upgrade Kotlin/AGP/optional libraries as one tested group. Wrapper and CI both use verified
  Gradle 9.1.0; the internal repository package script now accepts an explicit version.
- iOS capture remains unimplemented and outside the Android-first priority.

## Completing an item

Inspect the relevant code, define an acceptance case, implement a focused change and run the checks
appropriate to the change. Public APIs need active/no-op parity and both release dependency gates.
Format changes need writer/readers/CLI/backend coverage. Update this file and CHANGELOG with shipped
behavior and remaining limits. Never claim an audit proves absence of bugs or that observed evidence
is complete when coverage says otherwise.
