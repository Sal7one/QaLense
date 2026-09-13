# Current backlog

Updated 2026-09-13, following client fixes `7a05bee`. This is the only current backlog.
[HANDOVER.md](HANDOVER.md) owns the verified baseline; [client fixes](docs/CLIENT_SAFETY_FIXES.md)
records the fourteen addressed findings. “Implemented” does not mean every lifecycle edge is proven.

## Priority 1: client reliability and evidence

1. **Physical-device privacy and recovery matrix.** Exercise video opt-in, consent denial/late
   consent, OS projection stop, rotation, backgrounding, interrupted save and disk-full handling.
   Test password/redaction-matched/custom content and multiple windows, plus the API 23 screenshot
   fallback. Acceptance: no host crash, no stuck capture/saving controls, no cross-session callbacks,
   and explicit failure/coverage when output cannot be preserved. Keep frame recording as default.
2. **Observer and inspection edge cases.** Stress Room and repeated concurrent enable/disable;
   DataStore basic cancellation/resume and route clearing are already covered. Add semantics updates
   that do not cause layout, lifecycle churn and background logging. Acceptance: no duplicate/leaked
   observers, no collection after disable, current inspection and preserved host responsiveness.
3. **Replay/storage failure tests.** Android byte/path/CRC limits already exist. Test cancelled
   imports, cache cleanup, malformed time/index entries and memory pressure with real Android
   readers. Verify failures terminate cleanly and do not leave unbounded temporary data.
4. **Bounded redaction execution.** Built-in long-email quadratic behavior was fixed; arbitrary
   custom regexes still lack a time policy. Define limits and adversarial behavior tests without
   silently dropping privacy rules or blocking the host UI.

## Priority 2: SDK integration and maintainability

- **Incremental facade decomposition (former A3).** `AnalysisEngine`, recording lifecycle/window,
  journals and crash registration have been extracted. Observation, recording coordination, panel
  state, evidence services and activity bridging still overlap in the facade. Extract one boundary
  at a time with behavior tests; preserve public APIs and current no-op/dependency gates.
- **Full API/binary compatibility.** Source consumer/parity checks pass, including public coroutine
  dependency visibility. Add broader API signature coverage and a versioned binary compatibility
  policy. A source build alone does not establish compatibility with previously compiled clients.
- **Host-owned OSS examples.** Add versioned native Ktor/Cronet/Apollo or crash-vendor examples where
  useful. Generic sinks and crash bridges already exist. Retain Chucker's supported public path and
  avoid reporting requests twice when a transport already uses instrumented OkHttp.
- **SQL cancellation and UX.** SELECT/WITH are limited in SQL and run off main. Stress aggregates,
  sorting and long writes; distinguish cancellation requests from confirmed interruption.
- **Webhook query contract.** Fragments, credential origin changes, queue pressure and transient
  retries are addressed. Replace raw extra-query conventions with an explicit encoding contract
  without breaking existing callers.
- **Client polish with evidence.** Jank sparkline, error eviction/concurrency tests and rotation/leak
  diagnostics. Crash overview, copy actions, jank counts, atomic screenshot publication and basic
  saving feedback already exist. Do not reimplement them from historical lists.

## Priority 3: other readers and distribution

- Extend expanded-byte budgets to web/backend ZIP/gzip readers; decide and document checksum error
  policy consistently. Android rejects checksum mismatches; web currently warns and continues.
- Add hostile-recording DOM regression coverage and load/compare race tests to both web viewers.
- Harden mock backend bind/access defaults before any exposure beyond local development. It has
  open CORS and unauthenticated operations and is not a production analysis service.
- Reconcile wrapper/CI Gradle versions and upgrade Kotlin/AGP/optional libraries as a tested group.
  No publication to Maven Central or remote CI success was established in the previous work.
- Optional frame WebP encoding (former R9-WebP), coordinated across writers/readers/spec. Current
  frames are JPEG; v2 gzip tracks and CRC are already implemented.
- iOS capture remains unimplemented and is outside the current client-first priority.

## Completing an item

Inspect the relevant code, reproduce the issue or define a concrete acceptance case, implement a
focused change and test behavior at the right layer. Public APIs need active/no-op parity and both
release dependency gates. Format changes need writer/readers/CLI/backend coverage. Update this file
and CHANGELOG with shipped behavior and remaining limits; do not keep another parallel backlog.
