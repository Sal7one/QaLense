# QaLens desktop and component workbench

A local browser GUI for a **QaLens-enabled Android QA build**. Inspect the live Compose tree, search
tags, text, roles and actions, and link selections between the phone and browser. Generate/check
QaLens XPath, highlight components and invoke public tap/type/scroll actions. Read a selected
component's attributes and tree position, save deduplicated JSON,
and run your own processors. Python standard library only; no Appium server or extra Android
library is required. Release builds still use `qalens-noop`.

Start with [ONBOARDING.md](../../ONBOARDING.md) for the SDK/web/Python overview. This guide owns
the desktop pairing, component, transfer and processor contracts.
For integrating the SDK into another app, follow [integration.md](../../integration.md) and the
[AI agent runbook](../../docs/AI_INTEGRATION.md). Pairing and inspection are SDK features; copying
the sample app or adding the mock backend is unnecessary.

Coverage is visible Compose semantics from attached/registered roots in the foreground Activity.
Native views, WebViews, arbitrary private state and unregistered windows are outside this tool.
An accepted semantics action is not proof of the resulting app state: refresh/assert its effect.

## Start and pair

Prerequisites: Python 3.9+, adb, an authorized USB/emulator device, an active QaLens QA build.
From the repository root:

```sh
python3 tools/local-bridge/server.py --gui \
  --pipeline-config tools/local-bridge/examples/pipelines.json
```

Open **http://127.0.0.1:8765**. **Landing** discovers authorized phones and installed QaLens apps.
Choose the phone/app, click **Connect**, then **Approve desktop** on the phone. No token copying,
custom host Settings or sample-app code is needed. The SDK Control Room asks for access to Compose
inspection/actions, observations and completed recordings; it does not start recording. Approval
returns to the host app. Connecting remembers a profile and preserves app data.

The PC creates a strong random credential internally, offers it through an explicit SDK receiver
restricted to senders with Android's `DUMP` permission (authorized adb shell), and opens Control Room
without credentials in Activity extras. The pending request expires after two minutes, stays stable
under repeated offers, and is rejected by Deny, cancellation or SDK disable. A listener starts only
after phone approval. **Cancel pairing** cancels the pending request. Ordinary other app UIDs cannot
offer pairing. The host's own code remains trusted and can use the existing API.

**Auto connect saved app** is opt-in after connecting once. It remembers that profile ID and
requests approval when the desktop starts with that authorized phone available; new access still
requires approval on the phone. It makes at most one request per page session and does not override
Deny/expiry/revocation. Disconnect turns the remembered choice off.

**Auto reconnect** repairs a missing adb forward after a temporary USB outage within this approved
session. It keeps the connection nonce and collection choices; it never retries host actions or
silently approves new access. Turn it off to stop transport repair. Token revocation, a device change,
Disconnect or a PC restart requires a new connection/approval. SDK **Stop PC inspector** stops
access; re-enable does not restart it. If the device listener is simply offline, the PC cannot
distinguish Stop from a crashed/offline app and shows reconnection status until you Disconnect.

For an older SDK or a custom/manual bridge, open **Device tools → Advanced manual pairing & saved
profiles**. On the phone, **QaLens Control → Desktop connection → PC inspector** (or overlay **More
tools**, full overlay **Tools**) offers Start, hidden-token copy, rotation and Stop. Copy its token
into the advanced form. Tokens are memory-only and excluded from reports; explicit Android 13+
clipboard copies use sensitive metadata and can outlive pairing. Existing `QaLens.startLocalBridge`
custom controls and terminal pairing remain supported.

Profiles remember name, serial, package, activity, expected Android version and port, without tokens.
A PC restart retains profiles/saved files and requires approval again. An old-tree command cannot
operate on a newly paired phone. There is no automatic recording or app reset. **Launch connected
app** is available separately under Device tools.

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

### Startup recovery

The launcher reports startup problems without a Python traceback and exits with a nonzero status.
It checks actual writes in the data root and `components`, `runs`, `recordings` and `transfers`,
including folders that already exist. A storage error identifies the blocked folder and prints a
quoted restart command using a separate folder under your home directory. Use that command for a
new workspace, or restore ownership/read/write access to the original folder to retain its profiles
and saved files. A previous run with sudo can leave folders owned by another account; run the
desktop as your normal user. The checks preserve existing permissions and saved data.

If the browser port is occupied, the error identifies the port and shows the existing URL if that
process is already QaLens. Use that instance, stop it with Ctrl-C and restart, or choose a free port:

```sh
python3 tools/local-bridge/server.py --gui --port 0
```

Open the **HTTP URL printed by the launcher**; the actual port replaces `0`. An explicit fixed port
still fails clearly when occupied. Port selection does not change the phone's `--device-port` or
workspace. Saved desktop profiles/files remain in `--data-dir`; browser viewer preferences/recents
are tied to the URL's origin and can differ on another port. Port binding happens before terminal
token prompting or adb forwarding. Startup does not terminate an existing process or modify its
listener. The focused CLI regressions are `python3 tools/local-bridge/test_startup.py`; actual
permission-denial cases require a non-root POSIX account.

## Phone → preview → file

1. Pair with **Link phone & web selection** checked (the default). Select in the SDK inspector or
   its **Search selectors & tags** screen: the browser loads attributes/selectors automatically.
   Selecting a tree node or preview rectangle highlights it on the phone. Selection never invokes
   a host tap/type/scroll action; those keep their separate buttons.
2. Linking reads only the cached selected ID every 1.5 seconds while Landing is visible. A changed
   selection reads a fresh visible tree and component; it does not continuously walk Compose.
   Turn linking off for independent inspection. Requests are bounded and stale replies/device
   changes cannot replace a newer choice. Rapid browser highlights are serialized; superseded
   queued choices are skipped. Stale targets explain the failure; actions are never retried.
3. **Send to PC** remains beside **Copy test tag**, including for untagged elements. Optional
   **Receive sent components** reads its bounded inbox every 2.5 seconds; it can receive already
   captured components while Control Room is in front. It is independent of live selection linking.
4. Use the selected element’s **Attributes**, **Selectors**, **Semantics**, **Tree position** and **Diagnostics** tabs. The complete
   JSON remains available. Component JSON contains bounds and semantics; screen pixels are a separate preview and are not saved with JSON.
5. **Save JSON** writes a content-addressed file. Repeated saves reuse it. **Download JSON** exports
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
durable: process/PC restarts and budget eviction can discard unsaved data. Receive stays enabled after temporary errors; authentication/connection changes stop it. Saved library lists the newest 500 files and reports omissions.

## Search and selectors

Landing searches tags, labels, text, descriptions, roles, state and supported actions; filter by
tag presence, role or tap/type/scroll/no actions. **Selectors** suggests exact tags, ancestor-tag
scopes for duplicates, role/content matches and visible sibling paths, with a match count for each.
**Check matches** opens the results; choose a result to inspect/highlight it. The builder combines
an exact attribute, optional parent tag and required action. Zero/duplicate matches are explicit.
**Export tree XML** and **Export selectors JSON** are explicit browser downloads; neither linking
nor generating suggestions writes application data to disk.

Android **Inspect elements** opens the movable inspector, whose **Search selectors & tags**
searches the visible tree. **Review evidence → Elements → Search** has the same searchable list. Choose a result to highlight its live host
element; **Actions & XPath selectors** shows actions and copyable suggestions. Tree capture stays
on main; redaction, matching, XML and selector generation run off main. Refresh after navigation
or changing content. Hidden/password values remain excluded, and custom/private state is not read.

XPath addresses **QaLens XML**, whose root is `<qalens>` with nested `<node>` elements. It is not
an Appium/UIAutomator XPath. Each node has a live `id`, plus allowlisted attributes `tag`, `label`,
`text`, `description`, `role`, `enabled`, `selected`, `heading`, `clickable`, `focusable`, `tap`,
`type` and `scroll`. Boolean/action values are strings `true`/`false`. Missing attributes stay absent.
The live evaluator accepts a bounded XPath 1.0 subset: child/descendant `node` paths, one positive
sibling-position predicate **or** exact attribute equalities joined with `and` per step; quoted
literals and `concat()` handle mixed quotes. Wildcards, arbitrary functions, unions and imported
XML evaluation are rejected. Examples:

```xpath
//node[@tag='checkout.submit']
//node[@tag='cart.row.42']//node[@tag='remove' and @tap='true']
/qalens/node[1]/node[2]
```

Counts describe the current visible tree (up to 1,000 nodes), not uniqueness across screens or an
entire list. Omitted nodes are disclosed; XPath commands reject truncated trees, duplicates and
trees that change during resolution. Prefer stable unique tags; content depends on values/language
and positional paths change with layout. XPath length <=4,096, <=64 steps; query returns at most
100 matches with omission counts. XML and selector strings follow host redaction; illegal XML
characters are removed consistently. This feature does not change `.sal` replay or add a runner.

## Persistence and automation

Default storage is **`~/.qalens/bridge`**; override with `--data-dir /your/local/directory`.
`profiles.json` holds allowlisted settings, `desktop.json` holds the opt-in auto-connect profile ID, `components/<hash>.json` holds explicitly saved data,
and `runs/<random-id>/` holds processor outputs plus `result.json`. New directories/files use
private permissions where supported. Existing directory permissions, disk encryption, backups,
retention and deleting files remain the PC owner's responsibility. The component workspace uses
no cookies or localStorage for pairing/credentials. Embedded
web viewers use origin-scoped browser storage for their own preferences/recents; recording
automatic-copy is separately opt-in and memory-only. No telemetry is added.

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
| `POST /api/apps` | `{serial}` → launcher activities with QaLens availability |
| `POST /api/pair` | `{profile}` → memory-only request for explicit phone approval |
| `POST /api/connection/check` | `{reconnect}` → status, bounded health read / owned forward repair |
| `POST /api/preview` | `{enabled,connectionId}` → explicit preview choice |
| `GET /api/screen` | PNG; requires session/connection headers and active preview |
| `GET /api/snapshot` | `/v1/snapshot`: forest, viewport, parent IDs, tags, actions |
| `GET /api/selection` | `/v1/selection`: cached selected ID, no Compose walk |
| `POST /api/selectors` | `/v1/selectors`: `{id}` → suggestions, match counts and redacted QaLens XML |
| `POST /api/query` | `/v1/query`: `{xpath}` → current visible matches and omission counts |
| `GET /api/events` | `/v1/events`: last 100 observations/network entries and cached data |
| `POST /api/command` | `/v1/command`: one of `{action,id}`, `{action,tag}`, `{action,xpath}`; tap/type/scroll/select |
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
python3 tools/local-bridge/test_connection.py
python3 tools/local-bridge/test_desktop.py
node --check tools/local-bridge/app.js
node tools/local-bridge/test_recording_transfer.js
node tools/local-bridge/test_polling.js
node tools/local-bridge/test_selectors.js
# Build/install sample debug + androidTest APKs as in CONTRIBUTING.md, then:
adb -s YOUR_DISPOSABLE_EMULATOR shell am instrument -w -e bridgeOnly true \
  com.qalens.sample.test/com.qalens.sample.RecordingRetentionInstrumentation
```

Require `OK:`. The device runner covers semantics commands, hidden/password/custom-key privacy,
phone button transfers, queue overflow/ack/restart, main timeout cancellation, disable/restart and
LTR/RTL gestures. Python checks cover hashing/dedup/restart, profiles/no-reset, local API controls,
connection freshness and real pipeline success/failure/timeout/shutdown/output paths. Physical
phones, TalkBack, other Compose versions and Windows processor cleanup remain unverified.
The selector browser regression uses the actual `app.js` and exercises inspection-only linking,
search/filtering, visible query results, clearing selection, stale reads/device switches and serialized
latest highlights. Core tests compare generated XPath with a standard XML XPath engine.

Phone approval also has a focused check:

```sh
adb -s YOUR_DISPOSABLE_EMULATOR shell am instrument -w -e pcPairingOnly true \
  com.qalens.sample.test/com.qalens.sample.RecordingRetentionInstrumentation
```

It checks a distinct app UID cannot offer access, no listener before approval, Deny/cancel/expiry,
real approval returning to a manual-root host, authenticated reads and disable/re-enable.

The SDK pairing UI also has a focused regression (with Startup installation removed):

```sh
adb -s YOUR_DISPOSABLE_EMULATOR shell am instrument -w -e pcUiOnly true -e manualRootOnly true \
  com.qalens.sample.test/com.qalens.sample.RecordingRetentionInstrumentation
```

It exercises SDK overlay/Control Room pairing, Send to PC, token rotation, paused-host inbox and
recording discovery, shutdown and disable/re-enable. Live tree reads/actions require the host app
screen in front; receiving already captured attributes and copying completed recordings do not.

The optional end-to-end transfer check requires installed sample/test APKs, Node 18+ and Python 3:

```sh
node tools/local-bridge/test_device_transfer.js emulator-SERIAL
python3 tools/local-bridge/test_device_pairing.py emulator-SERIAL
```

Use a disposable emulator. The check owns its temporary server/storage/forward and generates a
one-run token; it never uses a saved customer profile. It verifies busy enable, an adb outage and
recovery, automatic master/clip copies with Control Room foreground, file hashes/private permissions,
deduplication and authentication revocation. It tests the same controller the GUI uses, without a
browser; browser checkbox/layout/navigation need a separate live check.

## Desktop launcher, replay and phone tasks

`--gui` starts on **Landing**: connection, screen mirror, semantics tree and selected element
details together. Recordings, Saved elements, Automation, Replay and Device tools remain in the
compact navigation. Back and browser history navigate between pages; leaving Replay pauses its
video. Modern and classic replay load directly from the repository's `web/` source files through
an explicit asset allowlist, so fixes to those viewers apply here too. File picking/drop, timeline,
comparisons and the `.appsal` editor remain the existing web client's features. The shell and
viewers share one localhost origin; viewer preferences/recents use that origin's browser storage.

Device tools includes phone Back/Home/Wake, Android settings and the existing explicit no-reset
launch. Start mirror launches **your installed `scrcpy`** in a separate window, using this profile's
serial and adb executable. Stop mirror/disconnect/server exit closes the owned process. If scrcpy
is absent the GUI reports how to enable it; this tool does not download/install it. No external
mirror is embedded or remote-exposed. scrcpy launch was covered by the argv contract; real mirroring
requires installed scrcpy and remains unverified on this host.

Landing’s **Start preview** uses adb screen PNGs directly, without requiring scrcpy. This shows
**the whole phone**, including other apps and sensitive pixels; host text redaction/pixel masks do
not sanitize it. It requires explicit start after approved connection, keeps pixels only in memory,
and stops on leaving Landing, hiding the browser tab or disconnect. No background capture or files.
Capture is limited to one frame/second, one in-flight request, a four-second adb deadline, 16 MiB
PNG and 24 million decoded pixels. It is a sampled live preview, not a high-FPS video stream.
Selecting a screen position refreshes the live tree and chooses the smallest containing visible
node; dimensions and window origin must align. Unsupported/native areas are not Compose targets.
It selects for inspection; host actions require separate buttons. scrcpy remains the optional
high-FPS external mirror under Device tools.

Push explicitly chooses a browser file, up to 32 MiB, and writes `/sdcard/Download/<filename>`;
an existing same-name phone file is replaced. Names allow only letters/numbers/dot/dash/underscore.
Pull accepts one similarly named file under Downloads and writes a random-prefixed private PC file
under `transfers/`. Arbitrary device paths, shell commands, resets and uninstall operations are
not exposed. These actions require the current connection nonce; they never operate on a new
phone using a stale page's request. adb operations time out after 10 seconds.

Landing’s **Collect finished recordings** checkbox controls automatic collection. Recordings lists completed device `.sal` files and offers explicit Copy to PC / Open replay.
Automatic copy is opt-in, watches new completed files only after enabling, is not persisted,
and resets on device changes, disconnect or revoked authentication. Temporary failures retain the choice with bounded retries. Archives stream over the authenticated device bridge
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
