# QaLens — current backlog

Updated 2026-09-13. This replaces duplicated sections with contradictory shipped/open statuses.
See [HANDOVER.md](HANDOVER.md) for product context and [the audit](docs/RELIABILITY_AUDIT.md)
for the latest verified behavior, commands, and limits.

## Verified baseline

162 core tests, 15 Compose/OkHttp tests, 2 no-op tests, 5 Android replay tests, 58 web assertions, 20 backend tests.
Debug and release APKs build; release dependency isolation is enforced by
`:sample-app:verifyReleaseIsolation`. `./demo.sh test` includes the expanded matrix.
Use JDK 17 and cached Gradle 9.1.0 as documented in HANDOVER.md.

## Shipped in the client audit pass

Fourteen findings addressed: exception delegation, credential redaction/import isolation, runtime
shutdown, screenshot/frame privacy and background encoding, stream-safe previews, bounded Android
replay, refreshed inspection, background limited SQL, honest macros/uploads, periodic memory and
consistent exported frame scores. See [migration and checks](docs/CLIENT_SAFETY_FIXES.md).

## Shipped in the reliability pass

- Actual debug/no-op dependency separation, with a release isolation check.
- Android v2 gzip-inside-DEFLATE decoding in the shared web reader and mock backend.
- Archive structure validation and no healthy verdict for missing analysis.
- Idempotent crash registration and direct crash inclusion during finalization.
- Recording lifecycle guards, save instead of discard on stalls, current-activity capture,
  background/atomic saves, streaming video checksums, and visible saving feedback.
- Timestamp window isolation for all recorded evidence tracks.
- Correct nanosecond-to-millisecond frame timing; batch all observed frames once per second.
- Safe request/response previews with stream-preservation tests.
- Screenshot recycling/visibility fixes and redacted annotations.
- Overview crashes row, copy-crash-with-evidence action, and jank sample counts.
- Nearest-rank percentiles (earlier session), all tests retained.

- Recording-owned histories preserve early evidence after UI eviction/clearing. Per-track entry
  and size budgets, omissions and Android callback drops appear in coverage, replay and reports.
- A device regression runner verifies actual archive retention and budget warnings.

## Shipped in the OSS integration pass

- Replace invalid Chucker transaction reflection with supported interceptor coexistence and its
  public launcher. Real device baseline: Chucker 4.1.0, Kotlin 2.0.21; newer metadata requires
  a host-compatible toolchain. Legacy mode no longer silently disables capture.
- Add a generic network sink, shared privacy/capture policy, declared source diagnostics and
  an Overview integration check. Deduplicate repeated OkHttp interceptors per call.
- Bound/redact external crash evidence and prevent vendor callback echo loops.
- Fix network-state permission, default-network handling and stale Compose settings reads.
- Add independent debug/release consumer builds, a public contribution guide, issue/PR templates
  and GitHub CI. CI commands pass locally; the remote workflow has not yet run.

## Next — evidence reliability

1. **P1: expand visual privacy validation.** Compose masks and private-cache defaults now ship.
   Validate password/custom content, multiple windows, rotation and opt-in full-display video
   on physical devices; arbitrary pixels remain outside text redaction.
2. **P1: instrumented recovery tests.** Rotation, consent denial and late consent, stopped
   projection, backgrounding, disk full, and crash while saving. Emulator plus physical devices.
3. **A3: facade decomposition.** AnalysisEngine is extracted; recording lifecycle, recording
   window and crash registration now have pure core engines. ObservationCollector,
   RecordingController, PanelStateController, EvidenceService, ActivityBridge and the injected
   backend boundary are still incomplete.

## Next — hardening and experience

- Bounded custom regex policy and web/backend archive expanded-byte limits (Android now bounded).
- Webhook query encoding contract and backend mock access defaults.
- Web escaping regression tests and load/compare race tests in both players.
- Error-buffer eviction and background logging concurrency tests.
- Jank sparkline and a versioned, native Sentry integration example.
- Room and concurrent enable/disable stress tests; complete public API/binary compatibility checks.
- Native Ktor/Cronet/Apollo adapters beyond the generic transport callback.
- Resolve lifecycle/static-reference lint warnings with rotation/leak instrumentation; update
  Kotlin/AGP/dependencies as a coordinated compatibility pass, not isolated version bumps.
- **R9-WebP:** video/frame format remains unchanged; JPEG frames, gzip tracks, CRC32, v1/v2 readers.
- iOS capture remains unimplemented; format/replay/backend are platform-neutral.

## Prior shipped feature groups

A1/A2 analysis debounce; A4 error surface; A5 basic watchdog/lifecycle hooks; A6 data observers;
A7 compile/runtime no-op checks; B1/B13 crashes/bridge; B2 jank; B3 tabs; B4 exports; B5
connectivity/Chucker; B7/B8 coroutine/memory; B11 compare; B14 search; B15 macro assertions;
B16 redaction policy; B17 capture flags; C1–C16 web fixes/features; R7 resumable uploads;
R8 optional bodies; R9 v2 (WebP deferred); R10 crash finalization; R11 compare. “Shipped” means
implemented, not proof against all lifecycle edge cases. Open issues above take precedence.

## Change rules

- Public QaLens symbols require no-op twins and release isolation/parity verification.
- Pure logic belongs in core, with behavior tests.
- Breaking archive changes require writer + Android/web/CLI + spec updates together.
- Keep both web players working over file:// drag-drop and HTTP.
- No secrets, generated builds, local config or recorded user data in commits.
