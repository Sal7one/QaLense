# Reliability audit — 2026-09-08

> Latest client follow-up: [2026-09-13 fixes and verification](CLIENT_SAFETY_FIXES.md).
> The findings and counts below describe earlier passes.

This pass examined release dependencies, the capture/recording lifecycle, crash registration,
network body observation, screenshot handling, archive readers, backend verdicts, and verification
scripts. It replaces the earlier assumption that a green compile matrix proved release safety.
It is a targeted reliability overhaul, not a claim that every SDK path is now defect-free.

## OSS and Kotlin follow-up — 2026-09-08

This pass reviewed the integration boundaries across Kotlin core, Android lifecycle/notifications,
Compose facade/UI, navigation, replay, no-op and the sample, and reran web/backend regressions.
It is a targeted code audit plus module-wide automated checks, not proof that every code path is
bug-free. The implementation and migration contract are in [OSS integrations](OSS_INTEGRATIONS.md).

Concrete fixes: invalid Chucker listener/launcher reflection, silent interception disable under
its legacy flag, duplicate OkHttp observations, inconsistent custom-network/crash privacy,
crash-vendor echo, missing network-state permission, incorrect default-network transitions,
notification permission race handling and unobserved Compose configuration reads. New capabilities
include generic network sinks, declared network source coverage, integration diagnostics and an
Overview copy action/public Chucker launcher. Composite consumers now resolve the same coordinates
used by publications, and an independent app compiles public APIs against active/no-op variants.

Validation on the final implementation:

- **156 core tests, 13 body/OkHttp tests, 1 no-op parity test:** zero failures/errors/skips.
- **58 web assertions and 20 backend tests:** pass.
- Debug/release sample and independent consumer APKs build; the instrumented APK builds.
- Both release dependency gates pass. The sample requires QaLens and Chucker no-op artifacts and
  rejects active capture/replay/Chucker modules. The consumer checks QaLens isolation separately.
- Lint passes for compose, android, navigation-compose and replay with **zero errors**. There are
  25 warnings and 3 informational findings, including dependency upgrades, lifecycle/static
  references, an API-level metric warning, notification navigation and text/allocation suggestions.
  No new lint baseline/suppression was introduced to hide these findings.
- Emulator-5554 returns `INSTRUMENTATION_CODE: -1`: real Chucker 4.1.0 launcher intent, loopback
  response preservation, exactly one observation under duplicate interceptor installation,
  generic adapter redaction/disable behavior, crash bridge non-echo and both retention cases pass.
  The final limited archive observed 100 requests, retained 63 and disclosed 37 omissions; the
  first retained all 600. The limited archive also decoded through the actual CLI reader.
  The merged release manifest contains no SDK/Chucker components or debug cleartext override.
- CI, issue/PR templates and contribution instructions are added. Workflow commands pass locally;
  the GitHub workflow itself has not been executed remotely. No artifacts were published.

Chucker 4.2.0 failed compilation with this repo's Kotlin 2.0.21 because its published metadata is
2.2.0. The executed sample baseline is 4.1.0; do not bypass metadata checks or claim 4.2/4.3 runtime
coverage. Generic callbacks support host-owned integrations; native Ktor/Cronet/Apollo plugins and
an actual Sentry dependency/example are not shipped. Runtime full observer teardown, binary API
compatibility and the physical-device recovery/privacy matrix remain open.

## Confirmed defects fixed

| Area | Reproduced defect / code finding | Result |
|---|---|---|
| Release | Sample used active SDK dependencies for every build; no-op dependency was commented out | Debug-only capture/replay dependencies, release no-op, executable dependency isolation gate |
| Android → web | Writer DEFLATEs gzip JSON; reader only removed one layer; tests used STORE | Decode ZIP then gzip; shared fix covers classic, v2, and CLI; Android-layout regression fixtures |
| Android → backend | Gzip JSON decoded as text; malformed/missing analysis could produce “Looks healthy” | Decode v2 correctly, reject invalid manifests/unsupported versions, label absent analysis insufficient |
| Web archive input | Truncated offsets/sizes, duplicate entries and invalid manifests could fail obscurely or appear empty | Explicit structure checks and readable errors; v1/v2 remain supported |
| Crash capture | Startup plus explicit install could chain the crash handler recursively to itself | Install-once registration with tests proving one capture and one host delegation |
| Recording ownership | Stale consent, frame and stop callbacks could mutate a later recording | Tested session lifecycle, callback identities, no overlapping saves |
| Recording loss | Watchdog and last-activity destruction discarded captured media | Save captured evidence; explicit discard still discards |
| Navigation | Recorder retained the activity that started capture | Capture current foreground activity and update the retained host reference |
| Saving responsiveness | ZIP creation and whole-video CRC reads ran on the UI thread | Background packaging, streaming CRC, atomic publication, visible saving state |
| Archive accuracy | Old crashes/memory/bookmarks leaked into a new session; video manifest referenced omitted frames | Slice all timestamped tracks to the recording window; video-only manifests omit absent frames |
| Crash archive | Crash was queued to the main thread and could miss immediate finalization | Pass the captured crash directly into synchronous crash finalization |
| Screenshot | Failed copies leaked bitmaps; capture restored a hidden overlay as visible | Recycle failures/results, restore prior visibility, surface capture errors, redact annotation labels |
| Body observation | One-shot/duplex bodies could be consumed early; declared sizes were trusted | Skip special bodies; bound actual writes; preserve bytes; use Response.peekBody |
| Upload metadata | Triage header attempted to read gzip bytes as JSON | Decode v2 gzip before reading stats |
| Demo verification | `set -e` aborted at the expected CLI exit 1 | Check that exit explicitly and include release isolation and body tests |
| Frame timing | Nanosecond timings were interpreted as milliseconds, flagging normal frames frozen; updates fed back into rendering | Convert units with boundary tests; batch all observed frames once per second |
| QA controls | No saving feedback; overview omitted captured crashes | Saving feedback across panels, crash details/copy action, jank sample count |

## Recording retention follow-up

Eight recording-owned tracks now survive dashboard eviction and log clearing. A synchronized
journal freezes at stop before archive/video finalization and rejects late observations. Independent
entry and estimated-size budgets preserve earliest admitted evidence and prevent one noisy track
from evicting another. Explicit annotation removal is respected and counted separately from loss.
State maps are copied at capture. Frame timings enter directly from the Android listener, before
UI batching; Android-reported callback drops are counted.

`analysis.json.coverage.recording` exposes retained/observed/dropped/removed counts, budgets and
callback loss. Web and Android replay show a partial-recording warning; text/AI/JSON reports carry
coverage. CLI exits 2 on reported loss without an observed failure (1 still indicates a failure).
Partial comparisons cannot certify a fix. The mock backend returns unknown when evidence is
partial and no stronger failure signal exists. Older archives remain readable.

The oversized-body device check exposed quadratic scanning in the default email redaction regex.
A local-part boundary prevents repeated rescans; two tests cover bounded matcher work and continued
redaction of long addresses. This fixes the observed default-rule stall, not arbitrary custom regexes.

See [the retention contract](RECORDING_RETENTION.md) for bounds and the device test command.

## Automated verification

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home /Users/salehalanazi/.gradle/wrapper/dists/gradle-9.1.0-bin/9agqghryom9wkf8r80qlhnts3/gradle-9.1.0/bin/gradle :qalens-core:test :qalens-compose:testDebugUnitTest :qalens-noop:testDebugUnitTest :qalens-replay:compileDebugKotlin :sample-app:assembleDebug :sample-app:assembleRelease :sample-app:verifyReleaseIsolation
node web/test/read.test.js
python3 backend/tests/test_backend.py
./demo.sh test
```

Results: 145 core tests, 5 body/stream tests, 1 no-op parity test, 58 web assertions,
20 backend tests. Both APK variants and release isolation pass. The merged release manifest
contains no QaLens control, recording, replay or file-provider components. Existing build-tool
deprecation warnings remain.

## Emulator checks

On the Pixel 8 Pro emulator, installed the built debug APK, opened the QA panel and Control Room,
started a frame recording, navigated in the sample, and verified that background capture loss saved
rather than discarded the archive. The resulting Android v2 file contained 52 valid frame entries
and decoded through both the shared web/CLI reader and the Python backend parser. Actual recorded
files and screenshots remain outside version control. A second recording on the final APK
was stopped with the in-app REC chip; its state track spans multiple screens and its frame
metrics are in milliseconds. The first archive also passed a real multipart upload to the local
mock backend. No unexpected AndroidRuntime fatal exceptions were logged during these checks. The second archive contains
125 frames across Home, Account Detail and Profile. Its observed slow startup frames reach 4,500ms,
consistent with Android's graphics report on this emulator; this is not a performance benchmark
or a claim that the app is now smooth under every condition.

A deliberate `adb shell am crash com.qalens.sample` during a third recording produced a complete
archive with 17 frames, exactly one crash, the shell-induced exception text, and a `crash` anomaly
in analysis.json. The process terminated normally through Android's handler, and the sample
relaunched. Crash-during-save, background-thread crashes and video crashes remain separate cases.

## Device retention follow-up

The platform instrumentation runner passed on emulator-5554 with the final APK. One real Android
archive retained all 600 synthetic requests and all 600 logs after the dashboard was cleared,
including the first HTTP 500. A second archive observed 100 oversized-body requests, retained 41
and reported 59 omissions against its 8 MiB estimated network budget. Both archives decoded
successfully. The same oversized case initially timed out during email redaction and passed after
the default-pattern fix. The runner returns `INSTRUMENTATION_CODE: -1` on success.

Loaded the actual limited Android archive in both web viewers: each visibly displayed the 59/41
coverage warning and a partial-comparison notice. The Android replay warning is compiled but was
not separately exercised on screen. Device artifacts remain outside version control. These short,
synthetic sessions validate retention and serialization, not sustained rendering performance.

## Remaining work

- Physical-device video consent, rotation, service termination, and storage exhaustion need a
  repeatable instrumentation matrix. Emulator checks do not establish compatibility across OEMs.
- Raw screenshots/video are not text-redacted. Annotation redaction does not mask pixels;
  visual privacy controls and accurate documentation need a dedicated pass.
- The facade still mixes registration, UI state and Android lifecycle work. The new pure
  lifecycle/window/crash engines help, but A3 decomposition is not complete.
- Custom regex execution, archive decompression resource limits, backend access boundaries,
  and arbitrary webhook query input remain review items.
- There is no physical device connected. Full release readiness is not asserted by this audit.
