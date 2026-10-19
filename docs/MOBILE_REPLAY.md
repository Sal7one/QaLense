# Android recording replay

The optional `qalens-replay` module plays `.sal` evidence in the host QA app. Open a finished
recording through **Control Room → Play**, or open a shared `.sal`. Production variants must
exclude this active module; see [integration](../integration.md).

## Play, seek and inspect

- Replay opens on **Timeline**, in chronological order. Play advances media and the current event;
  Timeline, Network and Logs keep the current row visible. State shows the latest sample at that time.
- Tap an event to pause and seek to its time. Previous/Next move through the selected event track
  (all events from Summary/State), skipping duplicate timestamps. **First error** jumps to the
  earliest observed error across the tracks and opens its track.
- Drag the position slider to scrub. A playing session resumes on release; a paused session stays
  paused. Drag previews are coalesced; the final media seek is immediate.
- Drag an event list to browse without interrupting playback. **Follow playback** brings the list
  back to the current event. Starting Play, seeking or choosing a new track restores following.
- Enter fullscreen through its control or the media viewport. System Back returns to the controls
  at the same position. Leaving/backgrounding the Activity pauses playback; returning does not autoplay.
- Play at the session's end restarts from the beginning. Close returns to the caller for files
  opened from Control Room/shared intents, or to the picker for files opened inside the player.

Summary retains the archive's precomputed analysis and does not follow playback. A highlighted
event is the latest observation in that track at or before the playhead; it is not a continuous
assertion of app state. Multiple events at the same timestamp remain listed; one current row is
highlighted. Timelines never imply that empty/missing tracks establish healthy behavior.

## Synchronization and coverage

Frame playback advances using actual monotonic elapsed time rather than counting timer ticks.
Frames/state come from the most recent capture at or before the playhead; a future capture is
not displayed before its timestamp. Frame recordings are sampled evidence, not continuous video.
New HD recordings stamp video start on the encoder worker before posting UI activation; a busy
main queue no longer shifts that metadata to the later UI callback. The timestamp is an encoder
start reference, not a claim of sub-frame calibration of every device's display/encoder pipeline.

Inside the video interval, Media3's decoder position drives the playhead. Preparation/buffering
does not invent video progress. Seeks use the archive's `videoStartMillis` offset, including clips
whose media begins earlier than the session interval. Older files without that field align the
video's end with the session end. Before/after video coverage, session time can still advance
through retained observations; the viewport explicitly says that video is unavailable there.

A decoder failure is shown in the viewport and pauses playback. QA can choose Play again to
replay the retained events without video, or reopen the file to retry decoding. Unreadable frames
also report failure. These behaviors do not repair media or hide recording omission warnings.
No archive schema, privacy opt-in or capture permission changes are needed for replay.

Tracks are sorted on import. Current-row/frame/state lookup and Previous/Next use binary search;
merged events are prepared once rather than sorted during transport clicks. Lists stay lazy and
auto-follow moves only when the current row is outside the visible viewport. The media surface
is detached from a PlayerView when fullscreen changes or the view is disposed.
Row labels/details have bounded, ellipsized previews so a huge detail cannot occupy the entire
event pane or cause repeated text layout stalls. The retained archive is unchanged.

## Reproduce and verify

From the SDK checkout, with JDK 17 and the local Android SDK configured:

```sh
./gradlew :qalens-replay:testDebugUnitTest :qalens-replay:lintDebug \
  :sample-app:assembleDebug :sample-app:assembleDebugAndroidTest
adb -s YOUR_DISPOSABLE_SERIAL install -r sample-app/build/outputs/apk/debug/sample-app-debug.apk
adb -s YOUR_DISPOSABLE_SERIAL install -r sample-app/build/outputs/apk/androidTest/debug/sample-app-debug-androidTest.apk
adb -s YOUR_DISPOSABLE_SERIAL shell am instrument -w -e replayOnly true \
  com.qalens.sample.test/com.qalens.sample.RecordingRetentionInstrumentation
```

Require `OK: Android frame/video replay clock`. An instrumentation process exiting successfully
is insufficient if its output contains `FAIL:`. This runner uses synthetic private archives and
a [six-second color fixture](../sample-app/src/androidTest/assets/README.md). It checks rendered
pixels as well as decoder/time/selected-event state: forward/backward seeks, delayed/legacy video
alignment, trailing evidence, Timeline/Network/Logs/State, auto-follow/manual browsing, playing
scrubs, fullscreen surfaces/Back, background pause, replay from end and decoder-error evidence play.
It needs no recording consent and does not test MediaProjection capture. Never run sample fixtures
against a customer app. Failure screenshots stay in the sample's private cache and must not be committed.

Run the [tester workflow](../CONTRIBUTING.md#focused-device-checks) too for the existing recording →
Control Room → player → Close path. Repeat replay with small displays, larger fonts and actual
company archives that can be shared safely. Physical phones, rotation/process recreation,
OEM decoders and other Android versions require their own validation; the current executed
matrix is in [Android verification](ANDROID_VERIFICATION.md).
