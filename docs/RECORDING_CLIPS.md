# Long sessions and retrospective bug clips

Start a normal recording. While it runs, tap **★ Clip** beside the draggable REC/Stop control,
then **Last 10s**, **Last 20s**, **Last 60s**, or **Custom duration…** (1–300 seconds).
The mark ends at that moment and does not stop the session. Stop normally when finished; QaLens
then saves the master and a separate `clip_*.sal` for each accepted mark. A toast confirms the mark;
errors report rejected marks or export failures. Clips appear in the normal recordings history.

Hosts can configure the presets and duration, or mark a clip programmatically:

```kotlin
QaLens.configure {
    recordingMaxDurationMinutes = 60 // default; builder supports 1–180
    recordingClipPresetsSeconds = listOf(10, 20, 60, 120) // at most six valid distinct presets
}
QaLens.startRecording() // permission-free, masked sampled frames
QaLens.saveRecentClip(seconds = 20, label = "Checkout failed")
// Later: QaLens.stopRecording()
```

Use `startRecording(video = true)` only when the host has explicitly enabled `allowUnmaskedVideo`.
Android still requires screen-sharing consent. H.264 setup/stop, JPEG encoding, frame deletion,
clip copies/remuxing and ZIP writes run off main. Live Activity/Compose capture stays on main.
Encoder sizes/alignment/rates are checked; preparation falls back through smaller resolutions.
A finalized video must have a readable video sample or the archive uses masked sampled frames.
The recording control itself may appear in full-display video, which has no per-node pixel masks.

## Timing and media limits

The old five-minute automatic stop is removed. The configured duration stops and saves the session;
Android projection revocation, background/secure-window frame stalls or storage failures can end
capture earlier. A one-hour duration is supported by configuration, but a full hour on a physical
phone has not been verified. Video uses a duration-aware bitrate (up to 4 Mbps) and a 240 MiB
file limit to fit existing readers. Long sessions trade encoding quality for duration. This does
not promise constant visual quality, audio capture, or that every encoder honors its bitrate.

Frame mode scales JPEGs to at most 720 px wide, quality 60. Sampling is approximately
`max(500 ms, configured duration / 3600)`; a default hour samples about once per second.
At most 3,600 frames / 192 MiB remain. Older media is evicted if either budget fills and loss is
reported in existing partial-recording coverage. Video keeps 650 fallback frames, sampled about
once per second. A mark pins its available frames before later eviction.

Video clips are remuxed from the finalized master, beginning at the previous keyframe, so a
requested 10-second clip can include a little earlier footage. `analysis.json.clip` records the
label, requested duration/start, mark time and actual start. Video time and structured observations
use that actual window. Held frame/state samples may seed its beginning. Frame mode clips contain
JPEG sequences rather than an MP4; the existing players replay those sequences. If video trimming
fails, an available sampled-frame clip is preserved and the log reports the fallback.

Up to 20 marks are accepted per session, additionally bounded by 32 MiB estimated retained clip
evidence and 128 MiB pinned JPEGs. Reaching a clip budget leaves the master running. Saved history
keeps at most 30 archives / 1 GiB, newest first. Source media is removed after successful export;
failed source directories are retained locally for diagnosis, not automatically recovered. No
structured-data durability after abrupt process death or power loss is promised.

## Evidence limits

Full-session journals still preserve earliest evidence within their independent budgets. A separate
recent buffer preserves latest observations for marks, so full-session exhaustion does not starve a
late bug clip. Logs have 2,000 entries / 1 MiB, networking 500 / 2 MiB, performance 8,000 / 1 MiB,
state 650 / 1 MiB and memory 650 / 256 KiB. Other tracks retain their standard limits.
These are estimated accounting bounds, not JVM heap guarantees. Extreme traffic can omit evidence
inside the requested interval. No recording tool recovers unobserved requests, actions or logs.

Clip `coverage.recording.policy` is `keep-latest-buffer`; its retained/dropped counts describe that
buffer's **lifetime**, including observations outside the exported time slice. Players conservatively
show partial coverage when anything was evicted. They must not interpret those counts as the number
of observations missing from the requested clip itself. Clip media omissions similarly describe frame
evictions before the mark, including outside its window. The main archive retains `keep-earliest`.
Format v2, nested gzip JSON, file checksums and existing reader budgets are unchanged.

## Desktop collection

Launch `python3 tools/local-bridge/server.py --gui` and choose Replay evidence, Connect a phone,
Inspect components or Collect recordings. The shell embeds the existing `web/` viewers directly;
no replay implementation or static-source copy exists in the Python tools directory.

Pair the explicitly started device bridge. Recordings → Check phone recordings lists completed
files; Copy to PC writes a private, SHA-256-addressed `.sal`. Identical bytes reuse the archive.
Open replay passes the file to the existing reader in the embedded modern/classic viewer.

Automatic copy is off by default. Enabling it takes the current phone library as its baseline and
copies only subsequently finished archives, including clips. Existing archives require Copy.
The preference is connection-scoped, memory-only, disabled on a device switch/disconnect, and
paused on transfer errors. It does not delete phone archives or retry failed commands silently.
Do not leave a capture computer connected to a customer's app without their capture agreement.

## Verification

The `RecordingRetentionInstrumentation` focused modes use only synthetic sample evidence:

```sh
adb -s YOUR_DISPOSABLE_SERIAL shell am instrument -w -r -e clipsOnly true \
  com.qalens.sample.test/com.qalens.sample.RecordingRetentionInstrumentation
# Adds a >5-minute continuity regression:
adb -s YOUR_DISPOSABLE_SERIAL shell am instrument -w -r -e clipsOnly true -e longSession true \
  com.qalens.sample.test/com.qalens.sample.RecordingRetentionInstrumentation
# Approve the real OS consent dialog on that disposable sample emulator:
adb -s YOUR_DISPOSABLE_SERIAL shell am instrument -w -r -e videoOnly true \
  com.qalens.sample.test/com.qalens.sample.RecordingRetentionInstrumentation
# Deny the OS dialog promptly; verifies reset/no archive and a following frame capture:
adb -s YOUR_DISPOSABLE_SERIAL shell am instrument -w -r -e videoDenyOnly true \
  com.qalens.sample.test/com.qalens.sample.RecordingRetentionInstrumentation
```

Require their `OK:` result; adb exit zero is insufficient. The frame-focused check clicks preset/custom
native clip controls. The tests overflow log history, retain a recent failure, exclude post-mark logs, keep capture running, and inspect the actual archive media.
Video verifies both master and clip have a readable H.264 track beginning at a sync frame. Physical
phones, complete-hour endurance, rotation/encoder vendor matrices and TalkBack remain unverified.
