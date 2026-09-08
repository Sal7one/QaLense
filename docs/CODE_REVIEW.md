# QaLens — current code review

Updated 2026-09-08 after reviewing the current implementation rather than trusting the prior
handover. Detailed fixes, reasoning, verification commands and limits are in
[the reliability audit](RELIABILITY_AUDIT.md).

## Resolved in this pass

- **P0: release isolation was not real.** The sample used `implementation` for the active SDK
  and disabled `releaseImplementation(qalens-noop)`. It now selects dependencies by build type;
  `:sample-app:verifyReleaseIsolation` verifies the resolved runtime graph, and both APKs build.
- **P1: real Android v2 archives did not replay correctly on web/backend.** The writer wraps gzip
  JSON in ZIP DEFLATE. Readers now decode both layers; regression fixtures match Android's layout.
  The webhook's triage stats header also decodes gzip.
- **P1: invalid archives could appear healthy.** Web validation is explicit; backend returns 400
  for malformed/unsupported archives and reports insufficient evidence when analysis is absent.
- **P1: repeated crash-handler installation could recurse.** A pure tested registration guard
  installs once and always delegates the host crash handler, even if evidence collection fails.
- **P1: recording lifecycle could discard or mix sessions.** Session IDs guard callbacks;
  active/saving phases prevent overlap; watchdog/host shutdown save evidence; capture follows the
  foreground activity; normal archive work runs off the UI thread and publishes atomically.
- **P1: recorded tracks included earlier sessions.** A tested window filter limits events,
  network, crashes, frame metrics, connectivity, memory and bookmarks. The crash being finalized
  is passed directly to the archive instead of depending on queued UI delivery.
- **P1: body capture could affect the host stream.** One-shot/duplex requests are skipped;
  actual writes are bounded; response capture uses OkHttp 4.12's `Response.peekBody` (the old
  claim that it had been removed was incorrect). Five JVM tests cover preservation and limits.
- **P2: screenshot failure paths leaked memory or changed overlay visibility.** Failed bitmaps
  and delivered copies are recycled, prior visibility is restored, and annotation text is redacted.
- **P2: inconsistent percentiles.** The earlier session introduced shared nearest-rank calculations
  with seven core regression tests; these remain green.
- **P2: verification script aborted on expected demo failures.** It now checks CLI exit 1 explicitly
  and includes no-op, body and release-isolation verification.
- **P1: Android nanoseconds were interpreted as milliseconds.** Normal frames could appear
  frozen. Three boundary tests protect the conversion; all observed frames are batched once
  per second to avoid rendering feedback from per-frame StateFlow updates.
- **UI:** saving status, recent-crash evidence, and jank sample counts now appear in QA controls.

## Open findings, in priority order

| Priority | Finding | Next action |
|---|---|---|
| P1 | Recording exports still depend on capped live UI buffers | Recording-owned append store; state truncation explicitly in coverage |
| P1 | Pixel content is not redacted by text rules | Visual masking/privacy controls; correct any claims that all pixels are redacted |
| P1 | Dropped frame callbacks and bounded frame history are not described in coverage | Report missing samples and recording-owned performance history |
| P1 | Android consent/rotation/storage/service recovery lacks full instrumentation coverage | Physical-device and emulator matrix, including interrupted saves |
| P1 | Facade still owns too many responsibilities | Complete A3 services with injected dependencies and behavioral tests |
| P2 | Custom regexes run without a time budget | Define bounded redaction policy and test adversarial patterns |
| P2 | ZIP/gzip decoding lacks total expanded-byte budgets | Streaming limits across web, backend and Android readers |
| P2 | Webhook extra query input is appended raw | Define encoding contract and handle existing queries/fragments |
| P2 | Backend mock is unauthenticated | Keep development scope explicit and audit bind/access defaults |
| P2 | Web HTML escaping relies on convention | Add hostile-recording UI regression coverage |
| P2 | Connectivity bars are a bandwidth estimate | Label the estimate accurately |

The previous review's “nothing above P2” conclusion was incorrect. Compilation cannot prove
release isolation, and synthetic archives must match the actual producer. These checks now have
explicit regression coverage. Real-device video behavior and the open findings above remain limits.
