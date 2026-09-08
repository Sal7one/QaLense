# Reliability audit — 2026-09-08

This pass examined release dependencies, the capture/recording lifecycle, crash registration,
network body observation, screenshot handling, archive readers, backend verdicts, and verification
scripts. It replaces the earlier assumption that a green compile matrix proved release safety.
It is a targeted reliability overhaul, not a claim that every SDK path is now defect-free.

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

## Automated verification

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home /Users/salehalanazi/.gradle/wrapper/dists/gradle-9.1.0-bin/9agqghryom9wkf8r80qlhnts3/gradle-9.1.0/bin/gradle :qalens-core:test :qalens-compose:testDebugUnitTest :qalens-noop:testDebugUnitTest :qalens-replay:compileDebugKotlin :sample-app:assembleDebug :sample-app:assembleRelease :sample-app:verifyReleaseIsolation
node web/test/read.test.js
python3 backend/tests/test_backend.py
./demo.sh test
```

Results: 133 core tests, 5 body/stream tests, 1 no-op parity test, 44 web assertions,
18 backend tests. Both APK variants and release isolation pass. The merged release manifest
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

## Remaining work

- Physical-device video consent, rotation, service termination, and storage exhaustion need a
  repeatable instrumentation matrix. Emulator checks do not establish compatibility across OEMs.
- Capture buffers remain bounded UI buffers; long/busy sessions can lose earlier events before
  export. A recording-owned event store and explicit truncation coverage are next.
- Frame timings are now converted correctly and batched without deliberately favoring slow
  frames. Dropped callbacks and limited retained history still need explicit coverage accounting.
- Raw screenshots/video are not text-redacted. Annotation redaction does not mask pixels;
  visual privacy controls and accurate documentation need a dedicated pass.
- The facade still mixes registration, UI state and Android lifecycle work. The new pure
  lifecycle/window/crash engines help, but A3 decomposition is not complete.
- Custom regex execution, archive decompression resource limits, backend access boundaries,
  and arbitrary webhook query input remain review items.
- There is no physical device connected. Full release readiness is not asserted by this audit.
