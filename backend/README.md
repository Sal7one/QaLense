# QaLens mock webhook backend

`server.py` is a Python stdlib development server for testing QaLens uploads. It stores artifacts,
serves a dashboard and computes a deterministic mock verdict from observed evidence. It does not
call an AI model. Missing/partial evidence must not become a healthy verdict.

The server has no authentication, open CORS and unauthenticated deletion. The code defaults to
`0.0.0.0`; for local development bind explicitly to loopback:

```sh
python3 backend/server.py --host 127.0.0.1 --port 8000
# Optional isolated storage:
python3 backend/server.py --host 127.0.0.1 --port 9000 --data-dir /tmp/qalens-data
```

Open `http://127.0.0.1:8000/` for the dashboard. Python 3.9+ is required; no `cgi` module dependency.
Do not use this mock as a production endpoint. [next.md](../next.md) tracks security and reader gaps.

## Connect a client

- Android emulator: select a disposable device, run `adb -s emulator-5554 reverse tcp:8000 tcp:8000`,
  then set Control Room → Webhook to `http://127.0.0.1:8000/webhook` and use Test endpoint.
  The sample debug configuration allows local cleartext; the SDK does not grant that to every host.
- Web: open the v2 or classic viewer, configure Backend URL `http://127.0.0.1:8000`, load a session
  and choose Send to backend. No endpoint is configured by default.

## HTTP contract

| Method | Path | Purpose |
|---|---|---|
| POST | `/webhook` | Multipart `file` containing a `.sal`, or JSON `{"qalens":"webhook-test"}` ping |
| POST | `/api/ingest` | Web summary JSON or multipart archive |
| POST | `/webhook/chunk/start` | Start/resume by name, size and digest; returns `uploadId` |
| POST | `/webhook/chunk/<id>/<i>` | Raw bytes for chunk index i; verifies supplied size and CRC32 |
| GET | `/webhook/chunk/<id>/status` | Returns received chunk indices |
| POST | `/webhook/chunk/<id>/finalize` | Assemble, parse, store and return the normal verdict; 409 for missing chunks |
| GET | `/ping` | Health response |
| GET | `/` | Dashboard |
| GET | `/api/uploads` | Stored upload list |
| GET | `/api/uploads/<id>` | Metadata, parsed evidence and verdict |
| GET | `/uploads/<id>/download` | Original `.sal` bytes |
| DELETE | `/api/uploads/<id>` | Remove an upload |
| OPTIONS | Any | CORS preflight |

Android sends `X-QaLens-App/-Version/-Env/-Device/-Platform/-User`, recording name/size and
`X-QaLens-Digest` (the archive's `analysis.json.stats`). Query parameters are retained as metadata.
Recordings above 2,000,000 bytes use 1,000,000-byte chunks. Start sends `X-QaLens-Sal-Name`,
`X-QaLens-Sal-Size`, `X-QaLens-Chunk-Count` and digest. Each chunk sends `X-QaLens-Chunk-Crc32`
(lowercase hexadecimal) and `X-QaLens-Chunk-Size`; mismatch returns 409. Start is idempotent for the
same name/size/digest; clients query status to skip received chunks before finalization.

The Android client uses bounded workers and retries transient failures. Queue rejection, transport
failures and exhausted 408/429/5xx outcomes can persist in a capped 20-item queue. Other permanent
HTTP outcomes are removed; chunk mismatch 409 has its own retry path. Queued uploads are bound to
their original endpoint and may retry on later upload/connectivity events. Legacy queue records
without a destination require an explicit new upload. Disabling the SDK cancels active upload work.
See [client fixes](../docs/CLIENT_SAFETY_FIXES.md); do not treat every 4xx as nonretryable.

## Examples

Run from the repository root with the loopback server running:

```sh
curl -X POST http://127.0.0.1:8000/webhook \
  -H 'Content-Type: application/json' -d '{"qalens":"webhook-test"}'
curl -F file=@web/sample.sal -H 'X-QaLens-App: QaLens Sample' \
  'http://127.0.0.1:8000/webhook?team=example'
```

For a complete executable chunk protocol example, use `backend/tests/test_backend.py` or the
`curl` mode in `demo.sh`. Do not hard-code a chunk count for an arbitrary archive size.

## Storage and extension point

Uploads live under `backend/data/` or `--data-dir`, in per-upload directories containing raw
recording bytes, metadata, verdict and parsed manifest/summary/analysis/AI brief when available.
Keep this generated data out of Git. Archives follow [SAL_FORMAT.md](../docs/SAL_FORMAT.md);
Android-equivalent expansion budgets/checksum policy are not established for the backend.

`mock_verdict(meta, parsed)` is the extension point for real analysis. Preserve the client response
contract and coverage semantics if replacing it. A model-backed service additionally needs its own
authentication, storage, privacy and operational design; none is supplied by this local mock.

## Tests

```sh
python3 backend/tests/test_backend.py
```

The last client-fix baseline passed 20 tests against ephemeral local servers and temporary storage.
Coverage includes upload/download, metadata/JSON ingest, dashboard/deletion, malformed input,
Android-style v2 compression, partial-evidence verdicts and chunk start/status/finalize/resume.
[HANDOVER.md](../HANDOVER.md) records the dated whole-project baseline.
