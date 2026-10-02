# QaLens local test backend

This free, zero-dependency Python server lets you exercise the real upload paths without signing up
for a hosted service. It stores sample `.sal` files, shows them on a local dashboard, and returns a
deterministic mock verdict. It does not call an AI model.

For the whole SDK/web/Python setup, read [ONBOARDING.md](../ONBOARDING.md). This backend is
separate from the [desktop workbench](../tools/local-bridge/README.md).

## Try a complete send in one minute

From the repository root:

```sh
./demo.sh quick
```

This starts the backend at `http://127.0.0.1:8000` and the web player at
`http://127.0.0.1:8100`. In Mission Control, open **Settings → Backend URL**, enter
`http://127.0.0.1:8000`, load the bundled sample, and choose **Send to backend**. The received
recording appears on the dashboard at `http://127.0.0.1:8000/`.

To confirm the Android webhook without building the app, or to send the included sample archive:

```sh
curl -fsS -X POST http://127.0.0.1:8000/webhook \
  -H 'Content-Type: application/json' -d '{"qalens":"webhook-test"}'
curl -fsS -F file=@web/sample.sal \
  -H 'X-QaLens-App: QaLens Sample' \
  http://127.0.0.1:8000/webhook
```

`./demo.sh curl` exercises the mobile ping, mobile `.sal` upload, web summary, and resumable upload.
The dashboard updates from the server's stored records.

## Send from Android

For a USB-connected emulator or phone, run:

```sh
adb reverse tcp:8000 tcp:8000
```

In QaLens **Control Room → Webhook**, set `http://127.0.0.1:8000/webhook` and choose **Test
endpoint**. Record and stop a session; choose **Send latest session** from the tester panel, or
**Webhook** beside the recording in Control Room. The tester sheet only displays the send action
after an endpoint and a saved session exist. Upload is always a deliberate tap.

The sample debug app permits local HTTP for this demo. Host apps retain control of their own network
security settings. QaLens does not add cleartext access to consuming apps.

## Run the backend by itself

```sh
python3 backend/server.py
# or choose another local port and an isolated data directory
python3 backend/server.py --port 9000 --data-dir /tmp/qalens-demo-data
```

The default bind is `127.0.0.1`; Python 3.9 or newer is required. Upload bodies are capped at 64 MiB.
The sample `./demo.sh` web server also binds to loopback. Set a local URL in both the web player and
the Android Control Room; no endpoint is preconfigured in the SDK.

The server has no authentication, encryption, access controls, tenant isolation, or production
retention policy. It keeps raw recordings under `backend/data/` (or `--data-dir`) and its dashboard
allows unauthenticated downloads and deletion. Keep it on loopback for local testing. Binding it to
`0.0.0.0` is an explicit opt-in for a trusted development network, not a way to host company data.
For company use, each team can run QaLens for free and connect it to a backend they own; a real
shared service still needs an independently designed identity, access, transport, storage, and
retention model.

## HTTP contract

| Method | Path | Purpose |
|---|---|---|
| POST | `/webhook` | Android JSON test ping or multipart `.sal` upload |
| POST | `/api/ingest` | Web-player JSON summary or multipart archive |
| POST | `/webhook/chunk/start` | Start or resume a chunked upload |
| POST | `/webhook/chunk/<id>/<index>` | Upload one CRC32-checked chunk |
| GET | `/webhook/chunk/<id>/status` | List received chunk indices |
| POST | `/webhook/chunk/<id>/finalize` | Assemble, parse and store an upload |
| GET | `/ping` | Health check |
| GET | `/` | Local dashboard |
| GET | `/api/uploads` | Stored upload list |
| GET | `/api/uploads/<id>` | Parsed evidence and mock verdict |
| GET | `/uploads/<id>/download` | Original `.sal` bytes |
| DELETE | `/api/uploads/<id>` | Delete a stored upload |

The browser API allows CORS for loopback pages and pages served from the same host. This keeps the
local web demo working without granting arbitrary websites access to a localhost recording
dashboard. Native Android sends do not use CORS.

Android sends app/device metadata and a digest. Recordings over 2,000,000 bytes use 1 MB chunks;
each chunk carries a size and CRC32, and the client resumes missing chunks after a dropped
connection. The backend's mock verdict preserves partial-recording and evidence-loss signals.

## Storage, extension and limits

Each upload gets a directory containing `recording.sal`, metadata, parsed manifest/summary/analysis,
and a verdict. The data directory is ignored by Git. The dashboard's delete action removes one
recording. The `.sal` format is documented in [SAL_FORMAT.md](../docs/SAL_FORMAT.md).

`mock_verdict(meta, parsed)` is an example seam for analysis code. It preserves the response shape
used by the SDK. The mock reader rejects invalid paths, duplicates, CRC mismatches and archives
over the 4,096-entry, 256 MiB/entry, 512 MiB expanded-total, 16 MiB/text and 1 MiB/manifest
limits. It still has no authentication, tenant isolation or production retention policy; do not
expose it as a public archive-processing service.

Run backend checks with:

```sh
python3 backend/tests/test_backend.py
```
