# QaLens PC inspector

A local browser tool for a **QaLens-enabled QA build**. It reads live, visible Compose semantics,
shows root-scoped node IDs, parent relationships, test tags, bounds, labels, field values and available
actions. Select an element in the tree or bounds map, highlight it on the phone, or invoke its public
Compose tap/type/scroll action. Recent observed logs, network metadata and cached host-owned
Room/DataStore snapshots are available separately. No Appium installation, accessibility service,
reflection into private Compose internals or extra Android library is required.

This is a small Compose control tool, not a general Appium replacement: it cannot inspect arbitrary
native/WebView/private state, automatically discover unregistered dialog windows, infer unobserved
interactions, or claim complete recording coverage. A response acknowledges an action handler's
acceptance; refresh the tree or assert host state to verify its effect.

## Run it

Prerequisites: Python 3.9+, adb on PATH, a USB/emulator device with USB debugging authorized, and an
active QaLens QA build. Release builds must still depend on `qalens-noop`.

In the sample, open **Settings → PC inspector → Start bridge**. Copy the displayed pairing token.
The token stays in process memory and the text is hidden from QaLens reports. Stop the bridge when
finished. It never starts automatically and disabling QaLens closes it; re-enable requires another
explicit start.

For a host integration, expose this behind your own QA-only control:

```kotlin
// Generate once per pairing/session; display to the authorized tester without logging/persisting it.
val token = ByteArray(24).also { java.security.SecureRandom().nextBytes(it) }
    .joinToString("") { "%02x".format(it) }
QaLens.startLocalBridge(token) // device loopback port 8766 by default
// Observe QaLens.localBridgeStatus for Listening / Failed / Stopped.
// When finished:
QaLens.stopLocalBridge()
```

Start the PC server from the repository root:

```sh
adb devices
python3 tools/local-bridge/server.py --serial YOUR_DEVICE_SERIAL
```

Paste the device token at the terminal's hidden prompt. Open <http://127.0.0.1:8765>, enter the same
token and connect. The server creates an adb forward on an unused PC port, proxies only its three
allowed endpoints, and removes **its own** forward on Ctrl-C. It never removes other adb forwards.
Use `--adb /path/to/adb` if adb is not on PATH, `--port` for the browser port, or `--device-port` to
match a custom SDK port. More than one connected device requires `--serial`.

Existing forward / scripts:

```sh
adb -s YOUR_DEVICE_SERIAL forward tcp:8766 tcp:8766
# Supply QALENS_BRIDGE_TOKEN from your local shell/secret manager; do not commit it.
python3 tools/local-bridge/server.py --no-adb --device-port 8766
```

`QALENS_BRIDGE_TOKEN` is an optional alternative to the prompt. The browser keeps its input in memory;
it uses no localStorage, cookies, telemetry or data files. The page makes explicit requests; it does
not continuously scan the phone or retry actions. Disconnects and busy-host timeouts are surfaced.

## HTTP API

The PC endpoints require `Authorization: Bearer <pairing token>`. Commands require
`Content-Type: application/json`. Same-origin browser access is supported; external Origins and
non-loopback Host headers are rejected. The device endpoint is reachable through adb forwarding and
rejects direct browser Origin headers. Both listeners bind `127.0.0.1` only.

| PC endpoint | Device endpoint | Behavior |
|---|---|---|
| `GET /api/snapshot` | `/v1/snapshot` | Protocol v1, viewport, screen/route, visible semantics forest |
| `GET /api/events` | `/v1/events` | Last 100 dashboard events/network entries and cached data sources |
| `POST /api/command` | `/v1/command` | Exact target and `tap`, `type`, `scroll`, or `select` |

```json
{"action":"tap","tag":"transfer.confirm"}
{"action":"type","tag":"transfer.amount.field","text":"25"}
{"action":"scroll","id":"semantics:1:42","dx":0,"dy":400}
{"action":"select","tag":"transfer.confirm"}
```

Supply exactly one `tag` or `id`. Duplicate tags return **409**, absent/hidden/off-screen targets **404**,
and unsupported/disabled/declined actions **409**. IDs belong to live Compose roots and can become
stale after navigation/recreation; refresh before acting. Use a scrollable ancestor's ID for scroll.
Deltas are pixels, positive forward and negative backward, subject to the host semantics handler.
No arbitrary Kotlin, SQL, deep links, shell commands, screenshots or recording commands are exposed.

Requests have 8 KiB header / 16 KiB body limits. Type text is limited to 4096 characters and scroll
magnitudes to 10,000 pixels. A snapshot exports at most 1000 visible nodes and reports `omittedNodes`.
User-facing strings are redacted using host configuration and limited to 2048 characters. Responses
are capped at 4 MiB. The device processes one connection at a time, with read/write deadlines and a
1500 ms main-dispatch deadline; queued actions cancelled on timeout never run later. An already
running synchronous host handler cannot be interrupted safely. Do not retry an uncertain action
without checking the app. Observe status if the selected port is already occupied.

`qaHiddenFromReports` subtrees and password values are excluded. The host can set
`enableSemanticsReflection = false` to reject tree reads/actions with 403 even while paired. Host regex redaction still requires
review; ordinary labels/field values and allowlisted data may contain business data. Diagnostics
export no network bodies. Data snapshots come from the existing cached analysis state; they do not
query Room or call providers on demand. They may lag recent writes and remain dashboard evidence,
not a complete recording. Registered `qaInspectionRoot()` dialog/popup roots participate; otherwise
coverage is limited to attached roots in the foreground host Activity.

## Mobile inspection gestures

Single taps inspect an element. **Two-finger drags scroll the host**: the overlay cancels its pending
tap and forwards the two-finger centroid as a one-finger host drag until either finger lifts. Remaining
fingers cannot trigger a click. The inspector/tag modes support this while the panel/HUD/recording is
closed. This does not forward arbitrary multi-touch gestures to the host or control another window.

Drag the **Move inspector** handle to reposition the filters and selected-node card. The floating
bubble and dock use physical coordinates independent of RTL, remain inside system/keyboard-safe
bounds, and save normalized positions so size changes/rotation stay bounded. The old unbounded
bubble offset is replaced with a new physical position preference. On exceptionally small screens,
the detail content scrolls inside the bounded dock.

## Verification

```sh
python3 tools/local-bridge/test_server.py
node --check tools/local-bridge/app.js
# Build/install sample debug and androidTest APKs as in CONTRIBUTING.md, then:
adb -s YOUR_DISPOSABLE_EMULATOR shell am instrument -w -e bridgeOnly true \
  com.qalens.sample.test/com.qalens.sample.RecordingRetentionInstrumentation
```

The device case exercises real semantics tap/type/scroll, hidden/password exclusions, duplicate-tag
errors, observed logs, pending-main timeout cancellation, stop/disable/restart, and injected LTR/RTL
two-finger host scrolling plus bubble/dock drags. Require its `OK:` result, not only adb exit zero.
Physical devices, TalkBack and other Compose versions remain part of the wider client matrix.
