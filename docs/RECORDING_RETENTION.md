# Recording evidence retention

A recording owns its history from actual capture start (after video consent) until stop. Dashboard
ring buffers and the Clear logs/network actions affect only the dashboard. Bookmark removal and
Clear bookmarks deliberately edit recording annotations too. Data is admitted when its observer
runs; requests that complete after stop and callbacks that Android never delivers are not recovered.

## Bounds

Each track keeps the earliest observations that fit. A rejected large entry does not prevent a later
small entry from fitting. Tracks have independent budgets, so log floods cannot evict crashes.

| Track | Entries | Estimated size budget |
|---|---:|---:|
| Logs | 10,000 | 2 MiB |
| Network | 2,000 | 8 MiB |
| Crashes | 100 | 2 MiB |
| Performance | 40,000 | 8 MiB |
| Connectivity | 1,000 | 2 MiB |
| Memory | 2,000 | 2 MiB |
| Bookmarks | 1,000 | 2 MiB |
| State | 1,000 | 2 MiB |

The 28 MiB total is **estimated object/text accounting**, not a measured or guaranteed JVM heap
ceiling. Entry budgets also bound collection overhead. Media retains its existing 600-frame /
five-minute limit and is stored separately. Serialization and UI buffers consume additional memory.
This journal is in memory: normal and handled-crash finalization export it, but abrupt process death
or power loss can still lose structured tracks. Disk journaling/recovery is not implemented.

## Additive coverage metadata

SAL remains v2 and analysis remains `qalens-analysis/1`. `analysis.json.coverage.recording` adds:

- `policy: "keep-earliest"` and `truncated` (true if a retention budget omitted observations).
- `droppedFrameCallbacks`: Android-reported missed frame-metrics callbacks delivered during the
  recording. Boundary callbacks may describe frames before recording began. This is not a count of
  dropped video frames, and zero does not prove all frames were observed.
- `tracks.<name>`: `observed`, `retained`, `dropped`, `removed`, `estimatedBytes`, `maxEntries`,
  `maxEstimatedBytes`. Explicit bookmark deletion contributes to `removed`, not `dropped`.

Untruncated retention does not mean complete instrumentation or a healthy app. Other coverage notes
still describe missing/disabled tracks and sampling limits. Legacy files without this object have
unknown retention coverage; readers preserve compatibility rather than inventing counts.

Web and Android replay display partial-recording warnings. CLI Markdown, AI and JSON reports
carry coverage, and text archives include warning notes. The CLI returns 1 for an observed failure,
2 for invalid input or reported evidence loss without a failure, and otherwise retains its existing
exit behavior. Comparisons involving reported loss use “absent, fix unverified” instead of “fixed.”
The mock backend marks partial evidence unknown unless a known failure warrants warning/critical.

Configured structured-text redaction still runs at export. This change does not mask raw pixels.

## Verification

Ten core tests cover UI eviction/clearing, 36,000 frame samples, size/entry limits, concurrent
observers, session closure, copied state maps, annotation edits, secondary tracks and digest coverage.
Fourteen web/CLI assertions and two backend tests cover reported loss and failure precedence.
The oversized-body device case also exposed quadratic email matching on long non-email tokens.
A local-part boundary removes repeated scans; two redaction regressions check a deterministic
character-read budget and preservation of long-address masking. Custom regex time budgets remain open.

The Android runner uses platform Instrumentation without third-party test dependencies. Run it on
a **disposable sample-app emulator**: it launches the sample, generates synthetic evidence, opens
the normal share chooser, and saves two recordings under the app's usual retention policy (which
keeps five files). It does not send HTTP requests to the fixture hostname or share files externally.

```sh
# Use JDK 17 / Gradle 9.1.0 as documented in HANDOVER.md.
gradle :sample-app:assembleDebug :sample-app:assembleDebugAndroidTest
adb -s emulator-5554 install -r sample-app/build/outputs/apk/debug/sample-app-debug.apk
adb -s emulator-5554 install -r sample-app/build/outputs/apk/androidTest/debug/sample-app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r com.qalens.sample.test/com.qalens.sample.RecordingRetentionInstrumentation
```

Success requires `INSTRUMENTATION_CODE: -1` and the `OK: 600 requests and logs survived UI clearing`
message. The runner checks that the early HTTP 500 and every synthetic log survive dashboard
clearing, then exceeds the network byte budget and checks omitted counts against the actual ZIP
track plus a warning in report.txt. This is a retention regression test, not a performance benchmark
or a video/rotation/storage-failure compatibility test.
