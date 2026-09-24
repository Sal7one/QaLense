# Current backlog

Updated 2026-09-24. This is the only current backlog. [HANDOVER.md](HANDOVER.md) owns verified
baselines and project context. Older changelog entries are historical, not uncompleted work.

## Priority 1: make the client useful to real QA teams

1. **Validate the simplified tester flow on devices.** The default sheet now exposes record,
   screenshot and mark-a-bug actions; team upload appears after setup and a recording exists.
   A Pixel emulator check covered 360 × 640 dp at 150% font, scrolling More tools, the in-app stop
   chip, save/share sheet and a real local upload result. RTL and both overlay color schemes were
   checked on the emulator; the quick-actions accessibility tree now has button roles and a named
   Close control. Still run TalkBack and the complete flow on physical phones. Fix any focus, size,
   dismissal or stale-state problem found.
2. **Physical-device capture and recovery matrix.** Exercise video opt-in, consent denial/late
   consent, OS projection stop, rotation, backgrounding, interrupted save and disk-full handling.
   Test password/redaction-matched/custom content and multiple windows, plus the API 23 screenshot
   fallback. Acceptance: no host crash, no stuck capture/saving controls, no cross-session callbacks,
   and explicit failure/coverage when output cannot be preserved. Keep frame recording as default.
3. **Self-hosted company service boundary.** Decide how teams will bring their own identity, TLS,
   tenant isolation, retention/deletion and storage. The current Python service intentionally remains
   a loopback development mock with no authentication; put production deployment/auth in a separate,
   reviewed service design rather than implying the mock is multi-tenant.

## Priority 2: preserve client reliability and integration

- **Observer and inspection edges.** Stress Room and repeated concurrent enable/disable; DataStore
  cancellation/resume and route clearing are already covered. The active Inspector now polls
  semantics-only updates, and a device runner checks separate Compose dialog roots, duplicate-tag
  reconciliation and hidden subtrees. Continue with background logging, physical-device windows,
  and Compose version compatibility beyond the tested 1.7.8 runtime.
- **Close Android lint follow-ups.** Frame metrics now use a weak Activity reference and detach on
  destroy; the in-app stop chip detaches on destroy, exposes an accessibility click and uses string
  resources. The projection notification body opens controls while its action stops recording.
  Validate TalkBack and rotation on physical devices; the active chip still intentionally holds its
  View until stop/destroy and lint reports that static-field warning. Review remaining sample
  metadata and dependency-freshness warnings without masking them with a baseline.
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
