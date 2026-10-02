# QaLens component workbench

A local browser GUI for a **QaLens-enabled Android QA build**. Inspect the live Compose tree, search
exact tags, highlight components on the phone and invoke public tap/type/scroll actions. Send a
selected component from the phone, read its attributes and tree position, save deduplicated JSON,
and run your own processors. Python standard library only; no Appium server or extra Android
library is required. Release builds still use `qalens-noop`.

Coverage is visible Compose semantics from attached/registered roots in the foreground Activity.
Native views, WebViews, arbitrary private state and unregistered windows are outside this tool.
An accepted semantics action is not proof of the resulting app state: refresh/assert its effect.

## Start and pair

Prerequisites: Python 3.9+, adb, an authorized USB/emulator device, an active QaLens QA build.
In the sample open **More → Settings → PC inspector → Start bridge**. Its random token is held in
process memory and hidden from QaLens reports. For a host app, expose a QA-only pairing control:

```kotlin
// Generate once per pairing; display privately to the authorized tester, never log/save it.
val token = ByteArray(24).also { java.security.SecureRandom().nextBytes(it) }
    .joinToString("") { "%02x".format(it) }
QaLens.startLocalBridge(token) // default device loopback port 8766
// Observe QaLens.localBridgeStatus. When finished:
QaLens.stopLocalBridge()
```

From the repository root:

```sh
python3 tools/local-bridge/server.py --gui \
  --pipeline-config tools/local-bridge/examples/pipelines.json
```

Open **http://127.0.0.1:8765**. In **Devices & apps**, scan phones, choose a serial, enter the real
Android package (`com.example.app`), optional activity (`.MainActivity`) and current pairing token.
**Find installed apps** supplies package suggestions. **Save profile** remembers the setup;
**Connect this profile** creates a dedicated adb forward. Launching is a separate button. It never
clears/uninstalls app data or changes device animations. Expected Android version mismatches are
reported; the device's actual version is displayed after pairing. Connecting establishes transport;
**Refresh tree** verifies that the app bridge is running and the token matches.

Profiles remember name, serial, package, activity, expected version and port. Tokens stay in server/
page memory and are cleared from the profile form after successful connection. A PC restart needs
pairing again; saved profiles and component files remain. A device switch invalidates browser
commands against an old tree, requiring refresh. There is no automatic launch, capture or recording.

Appium-style capability import maps `appium:udid`, `appium:appPackage`, `appium:appActivity` and
`appium:platformVersion`. `platformName` must be Android. A URL is not a package and whitespace is
not valid in an activity class. Driver, reset, animation, locator and autolaunch capabilities are
ignored with a notice: this is QaLens Compose control, not a UIAutomator2/Appium session.

Terminal pairing remains available:

```sh
python3 tools/local-bridge/server.py --serial YOUR_DEVICE_SERIAL
# Enter its token at the hidden prompt, then open the same GUI.
```

Multiple connected devices require `--serial` in terminal mode. Use `--adb /path/to/adb`, `--port`
for the browser port or `--device-port` for a custom SDK port. `--no-adb` uses an existing forward
at `--device-port`. `QALENS_BRIDGE_TOKEN` can replace the terminal prompt; never commit it. Ctrl-C
or Disconnect removes only a forward created by this workbench, preserving other adb forwards.

## Phone → preview → file

1. Pair and enable **Receive phone selections** in the inspector. It checks a memory inbox every
   2.5 seconds while the page is visible. This does not continuously scan the Compose tree.
2. Select a component on the phone. **Send to PC** is beside **Copy test tag**, including for
   components without a tag. Alternatively choose a live node on the PC and **Capture attributes**.
3. Review the attributes table, viewport bounds diagram and ancestry/sibling indices. The complete
   JSON remains available. These are bounds and semantics, not a pixel screenshot or recreated widget.
4. **Save JSON** writes a content-addressed file. Repeated saves reuse it. **Download JSON** exports
   to the browser's download location. **Import component JSON** opens a preview without saving;
   imported documents depend on their producer's redaction rules.

Documents use `schema: "qalens.component"`, `version: 1`, `content`, `capturedAtMillis`, `liveNodeId`
and a PC-computed `hash`. `content` contains package/activity, screen/route, viewport/density,
component fields, public semantics attributes and visible tree path/immediate children. Each path
entry includes tag, role and sibling index among visible siblings; it describes position, not a
stable automation selector. Hidden siblings and off-screen nodes are excluded.

SHA-256 covers sorted compact UTF-8 JSON of `content`. Integral numbers are normalized (1.0 = 1).
Changes in captured values, bounds, ancestry/sibling position, package, viewport or semantics produce another
file. Timestamps, live node IDs, transfer IDs and adb serials are provenance outside the hash.
Identical content across phones deduplicates; the first saved timestamp/live ID is retained.
Truncated, unsupported or hidden values cannot be compared; equal exports do not prove equal host state.
Live IDs are not replayable selectors. Content hashes are identity/integrity checks, not encryption.

Device capture uses real public semantics keys. Text, tags, labels, field values, focus/selection,
roles, state, text selection, progress and collection metadata are supported. Actions, scroll-range
callbacks and unsupported/custom properties expose names/coverage, never executable closures or
arbitrary `toString()` values. Hidden subtrees and password text/values are excluded; host redaction
runs off main. Very deep ancestry is conservatively value-masked. Ordinary values may still contain business data: review before saving/sharing.

Limits: device components <=256 KiB after redaction, strings <=2048 characters, up to 100 properties,
8 values per text/list property, 64 ancestors and 100 immediate visible children. Truncated property/
child/path counts are disclosed. Device inbox: 10 previews / 1 MiB, oldest dropped with a counter.
Reads are non-destructive; acknowledgements are idempotent after PC memory accepts a preview.
Stop/disable clears the inbox. PC previews: 10 / 2 MiB, omission counter exposed. Neither inbox is
durable: process/PC restarts and budget eviction can discard unsaved data. Receive pauses on an
error until explicitly enabled again. Saved library lists the newest 500 files and reports omissions.

## Persistence and automation

Default storage is **`~/.qalens/bridge`**; override with `--data-dir /your/local/directory`.
`profiles.json` holds allowlisted settings, `components/<hash>.json` holds explicitly saved data,
and `runs/<random-id>/` holds processor outputs plus `result.json`. New directories/files use
private permissions where supported. Existing directory permissions, disk encryption, backups,
retention and deleting files remain the PC owner's responsibility. No localStorage, cookies,
telemetry or automatic background disk captures are used.

Configure trusted processors with `--pipeline-config`. The GUI cannot author executable commands.
For example:

```json
{"pipelines":[{"id":"analyze","name":"Analyze component","steps":[
  {"argv":["{python}","my_processor.py","{component}","{output}"],"timeoutSeconds":30}
]}]}
```

Config-relative working directory; placeholders: `{python}`, `{component}` (saved JSON path),
`{output}` (unique run directory), `{hash}`. Each step is an argv array, without a shell. Other braces
must be doubled if literal. Steps run sequentially in the background; failure stops the pipeline.
One pipeline at a time, 1–10 steps, 1–300 second timeout per step, no automatic retries. Pairing-token
variables are removed from the child environment; processors run with the PC user's privileges,
**not in a sandbox**. Use your own trusted programs. Standard output/error are discarded; write
results/logs to `{output}`. Shutdown cancels the current processor (POSIX process group; parent
process on Windows). Output disk usage is controlled by the chosen processor/PC owner.

Select and **save** a component first, then choose **Automation → Run on saved component**.
Refresh results to see status/exit codes; **View output files** reads/downloads immediate UTF-8 files
<=1 MiB (100 files maximum), excluding symlinks/outside paths. Older run results remain on disk;
the GUI lists up to 100 runs from the current server process. The included `component-summary`
pipeline writes a tag-presence check and readable attributes; it does not certify app quality.

CI/local scripts can run the same contract without a phone or browser:

```sh
python3 tools/local-bridge/process.py --component /path/to/component.json \
  --pipeline-config tools/local-bridge/examples/pipelines.json --pipeline component-summary \
  --data-dir /path/to/local/output
```

Exit 0 means processor completion, 1 means failed/timed out, 2 means invalid setup. No archive or
recording format changes are involved; these are standalone component documents.

## HTTP integration

Both listeners bind `127.0.0.1`. PC APIs accept `Authorization: Bearer <device pairing token>` for
scripts. The GUI bootstraps a separate ephemeral `X-Qalens-Session` for the local PC workspace;
external Origins/non-loopback Host headers are rejected, with no CORS. Browser component/command
requests also require `X-Qalens-Connection` from the latest snapshot, guarding device switches.
The device rejects direct browser Origin headers and always requires its pairing bearer token.
This trusts the local OS/adb environment; it is not a remotely exposed or multi-tenant service.

| PC endpoint | Device endpoint / behavior |
|---|---|
| `GET /api/snapshot` | `/v1/snapshot`: forest, viewport, parent IDs, tags, actions |
| `GET /api/events` | `/v1/events`: last 100 observations/network entries and cached data |
| `POST /api/command` | `/v1/command`: `{action,id}` or `{action,tag}`, tap/type/scroll/select |
| `POST /api/component` | `/v1/component`: `{id}` or `{tag}`, returns a component preview |
| `GET /api/inbox` | `/v1/components/inbox` + `/ack`: bounded phone selections → PC previews |
| `GET /api/previews`, `/api/saved`, `/api/workbench` | Memory previews, saved metadata, profiles/jobs |
| `POST /api/import` | `{document}` → validated preview/hash, no disk write |
| `POST /api/save`, `/api/document` | `{hash}` → explicit save or read saved component |
| `POST /api/run` | `{hash,pipeline}` → asynchronous job |
| `POST /api/artifacts`, `/api/artifact` | `{job}` → files; `{job,name}` → UTF-8 output |

Duplicate tags return 409; absent/hidden/off-screen targets 404; unsupported/disabled/declined actions
409. Scroll deltas are pixels <=10,000 magnitude; type text <=4096 characters. Refresh live IDs
before use. Recent diagnostics contain no network bodies and use cached, host-registered data;
providers/Room are not queried on demand. Empty tracks do not establish complete coverage.

Device headers/body: 8 KiB / 16 KiB; snapshot <=1000 nodes (`omittedNodes`); responses <=4 MiB.
PC component import <=256 KiB + envelope, other bodies <=16 KiB. Live semantics stay on main;
networking, JSON/redaction and files stay off main. A 1500 ms dispatch timeout cancels still-queued
commands. An already running synchronous host action cannot be interrupted safely. Verify state
before retrying an uncertain action. Turning off `enableSemanticsReflection` returns 403 for live
reads/actions. Disable stops the listener; re-enable requires explicit start.

## Mobile gestures and verification

Single taps inspect. Two-finger inspect/tag drags forward a one-finger centroid drag to the Activity
content while panel/HUD/recording is closed. The pending inspector tap is cancelled and residual
fingers suppressed. Registered dialog roots can be read; touch forwarding controls the host Activity
window only. Drag **Move inspector** to reposition the card/filters. Bubble/dock physical positions
are clamped within system/IME bounds in LTR/RTL and retained across size changes.

```sh
python3 tools/local-bridge/test_server.py
python3 tools/local-bridge/test_workbench.py
node --check tools/local-bridge/app.js
# Build/install sample debug + androidTest APKs as in CONTRIBUTING.md, then:
adb -s YOUR_DISPOSABLE_EMULATOR shell am instrument -w -e bridgeOnly true \
  com.qalens.sample.test/com.qalens.sample.RecordingRetentionInstrumentation
```

Require `OK:`. The device runner covers semantics commands, hidden/password/custom-key privacy,
phone button transfers, queue overflow/ack/restart, main timeout cancellation, disable/restart and
LTR/RTL gestures. Python checks cover hashing/dedup/restart, profiles/no-reset, local API controls,
connection freshness and real pipeline success/failure/timeout/shutdown/output paths. Physical
phones, TalkBack, other Compose versions and Windows processor cleanup remain unverified.

## Desktop launcher, replay and phone tasks

`--gui` starts on a choice screen: Replay evidence, Connect a phone, Inspect components or Collect
recordings. Sticky Back/Start and browser history navigate between pages; leaving Replay pauses its
video. Modern and classic replay load directly from the repository's `web/` source files through
an explicit asset allowlist, so fixes to those viewers apply here too. File picking/drop, timeline,
comparisons and the `.appsal` editor remain the existing web client's features. The shell and
viewers share one localhost origin; viewer preferences/recents use that origin's browser storage.

Devices & apps includes phone Back/Home/Wake, Android settings and the existing explicit no-reset
launch. Start mirror launches **your installed `scrcpy`** in a separate window, using this profile's
serial and adb executable. Stop mirror/disconnect/server exit closes the owned process. If scrcpy
is absent the GUI reports how to enable it; this tool does not download/install it. No external
mirror is embedded or remote-exposed. scrcpy launch was covered by the argv contract; real mirroring
requires installed scrcpy and remains unverified on this host.

Push explicitly chooses a browser file, up to 32 MiB, and writes `/sdcard/Download/<filename>`;
an existing same-name phone file is replaced. Names allow only letters/numbers/dot/dash/underscore.
Pull accepts one similarly named file under Downloads and writes a random-prefixed private PC file
under `transfers/`. Arbitrary device paths, shell commands, resets and uninstall operations are
not exposed. These actions require the current connection nonce; they never operate on a new
phone using a stale page's request. adb operations time out after 10 seconds.

Recordings lists completed device `.sal` files and offers explicit Copy to PC / Open replay.
Automatic copy is opt-in, watches new completed files only after enabling, is not persisted,
and resets on device changes or failures. Archives stream over the authenticated device bridge
into private `recordings/<sha256>.sal` files; metadata remembers the original filename/app.
Transfer is bounded to 400 MiB / 60 seconds, basic ZIP/manifest budgets are checked before
publication, and the replay reader performs full track/checksum validation. A slow/failed transfer
leaves no `.partial` file. Discovery contains filenames/sizes only, and runs without rescanning
semantics. This can copy unmasked video when its producer opted in; review capture permissions.
See [recording clips](../../docs/RECORDING_CLIPS.md) for Android usage, timings and coverage.

Added authenticated PC APIs: GET `/api/recordings/device`, `/api/recordings/local`,
`/api/recordings/file?id=<sha256>`; POST `/api/recordings/receive` (`name`, `connectionId`),
`/api/adb` (`action`, `connectionId`, optional `remote`); POST `/api/file/push` (raw bytes,
`X-Qalens-File`, `X-Qalens-Connection`). Device GET `/v1/recordings` and
`/v1/recordings/<generated-name>.sal` require the same bearer pairing. Binary copying uses an IO
worker with a 60-second deadline; it can occupy the bridge's single client slot until done.
