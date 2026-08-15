# QaLens Mock Webhook Backend

A **zero-dependency (Python stdlib-only) local server** that stands in for your real AI-analysis
backend while you develop and test QaLens's two hooks end-to-end. It accepts both hook flavors,
parses the uploaded `.sal`, produces a **deterministic mock AI verdict** from the file's own
`analysis.json` / `summary.json`, stores uploads under `backend/data/`, and serves a live
auto-refreshing dashboard. Python 3.9+ (no `cgi`, works on 3.13/3.14).

> This is a **local dev mock only** — no authentication, CORS open, `DELETE` unauthenticated.
> Never point it at a real endpoint or expose it beyond localhost.

## Quick start

```bash
python3 backend/server.py                # http://0.0.0.0:8000
python3 backend/server.py --port 9000    # custom port
python3 backend/server.py --host 127.0.0.1 --data-dir /tmp/qalens-data
```

Then open the dashboard at **http://127.0.0.1:8000/** — it auto-refreshes every 4s and lists every
upload as a card (click a card for the mock AI verdict).

Point the hooks at it:

- **Android emulator:** `adb reverse tcp:8000 tcp:8000`, then **Control Room → Webhook →
  `http://127.0.0.1:8000/webhook` → Test endpoint**.
- **Web player:** open `web/index.html` → ⚙ Settings → set **Backend URL** to
  `http://127.0.0.1:8000` → open a `.sal` → **⇪ Send to backend**.

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/webhook` | Mobile hook. `multipart/form-data` `.sal` (`file` part) with `X-QaLens-*` headers, **or** `application/json` `{"qalens":"webhook-test"}` (the Control Room "Test endpoint" ping). |
| `POST` | `/webhook/chunk/start` | Chunked upload (R7). Begin a resumable upload: `X-QaLens-Sal-Name/-Size`, `X-QaLens-Chunk-Count`, `X-QaLens-Digest` → `{"ok":true,"uploadId":"<id>"}` (or `{"resumed":true}` if that name+size+digest already started). |
| `POST` | `/webhook/chunk/<id>/<i>` | Raw chunk `i` (body bytes) with `X-QaLens-Chunk-Crc32` (lowercase hex) + `X-QaLens-Chunk-Size`. Returns `{"ok":true,"received":i}`, or `409` on crc/size mismatch. |
| `POST` | `/webhook/chunk/<id>/finalize` | Concatenate all chunks → same parse/verdict/store path + response shape as `/webhook`. `409` if a chunk is missing. |
| `GET`  | `/webhook/chunk/<id>/status` | `{"received":[0,1,…]}` — chunk indices already stored (the client skips them on resume). |
| `POST` | `/api/ingest` | Frontend hook. JSON session summary from the web player, **or** multipart `.sal` from the browser. |
| `GET`  | `/` | HTML dashboard (auto-refreshing). |
| `GET`  | `/ping` | Health check (`{"ok":true,"service":"qalens-mock-backend",…}`). |
| `GET`  | `/api/uploads` | JSON list of everything received (newest first). |
| `GET`  | `/api/uploads/<id>` | JSON detail: headers, query, parsed manifest/summary/analysis, `forAi`, mock verdict. |
| `GET`  | `/uploads/<id>/download` | The stored `.sal`, byte-for-byte. |
| `DELETE` | `/api/uploads/<id>` | Remove an upload. |
| `OPTIONS` | `*` | CORS preflight. |

Both `POST /webhook` and `POST /api/ingest` accept query params (`?team=payments`); they're
captured in the upload's `query` field. `X-QaLens-*` headers are captured in `headers`.

Recordings **larger than 2 MB** are uploaded by the app via the chunked flow above (1 MB chunks,
lowercase-hex `crc32` per chunk). `start` is idempotent by `name+size+digest`, so a dropped
connection resumes: the client asks `status`, re-sends only the missing chunks, then `finalize`.

The mobile client also keeps an **offline retry queue**: an upload that exhausts its retries with a
transport error (connection refused/timeout — not a 4xx/5xx response) is parked on-device (cap 20,
deduplicated by path) and re-attempted automatically on the next upload or when the device comes
back online. 4xx rejections are permanent and are not retried.

## curl examples

```bash
# 1) Test endpoint ping (no file)
curl -X POST http://127.0.0.1:8000/webhook \
  -H "Content-Type: application/json" \
  -d '{"qalens":"webhook-test"}'

# 2) Multipart .sal upload with X-QaLens-* headers (what the Control Room sends)
curl -F file=@web/sample.sal \
  -H "X-QaLens-App: QaLens Sample" \
  -H "X-QaLens-Version: 1.0.0" \
  -H "X-QaLens-Env: staging" \
  -H "X-QaLens-Device: Pixel 9 / Android 15" \
  -H "X-QaLens-User: qa+me@example.com" \
  "http://127.0.0.1:8000/webhook?team=payments"

# 3) JSON summary ingest (what the web player's "Send to backend" sends)
curl -X POST http://127.0.0.1:8000/api/ingest \
  -H "Content-Type: application/json" \
  -H "X-QaLens-App: QaLens Sample" \
  -d '{"app":"QaLens Sample","version":"1.0.0","score":58,"failedRequests":1,"likelyOwner":"Backend/API","sessionId":"demo"}'

# 4) Chunked / resumable upload (what the app sends for recordings > 2 MB)
#    split web/sample.sal into 1 MB chunks and upload them one at a time:
SAL=web/sample.sal
SIZE=$(stat -f%z "$SAL")                      # or: stat -c%s "$SAL" on Linux
DIGEST='{"failedRequests":1}'
CHUNK_COUNT=2                                 # 1 MB + remainder (sample.sal is small)

# start (idempotent: repeat it and you get {"resumed":true})
curl -s -X POST http://127.0.0.1:8000/webhook/chunk/start \
  -H "X-QaLens-Sal-Name: sample.sal" \
  -H "X-QaLens-Sal-Size: $SIZE" \
  -H "X-QaLens-Chunk-Count: $CHUNK_COUNT" \
  -H "X-QaLens-Digest: $DIGEST"
# → {"ok":true,"uploadId":"<id>"}

# upload chunk 0 (first 1 MB) — crc32 is lowercase hex
head -c 1000000 "$SAL" > /tmp/chunk0.bin
CRC0=$(python3 -c "import zlib,sys;print(format(zlib.crc32(open('/tmp/chunk0.bin','rb').read()) & 0xffffffff,'x'))")
curl -s -X POST http://127.0.0.1:8000/webhook/chunk/<id>/0 \
  --data-binary @/tmp/chunk0.bin \
  -H "Content-Type: application/octet-stream" \
  -H "X-QaLens-Chunk-Crc32: $CRC0" \
  -H "X-QaLens-Chunk-Size: $(stat -f%z /tmp/chunk0.bin)"

# check which chunks the backend already has (resume skips these)
curl -s http://127.0.0.1:8000/webhook/chunk/<id>/status

# upload chunk 1 (the remainder), then finalize → same response as /webhook
curl -s -X POST http://127.0.0.1:8000/webhook/chunk/<id>/finalize \
  -H "X-QaLens-Sal-Name: sample.sal" \
  -H "X-QaLens-Sal-Size: $SIZE" \
  -H "X-QaLens-Digest: $DIGEST"
```

## Data layout

Each upload is a directory under `backend/data/` (or `--data-dir`):

```text
backend/data/<unix-ts>-<sha256[:12]>/
├── recording.sal      # the raw upload (mobile/webhook path; empty for JSON ingest)
├── meta.json          # source, name, size, sha256, receivedAt, headers, query, parsed fields
├── verdict.json       # the mock AI verdict (+ short responseBody)
├── manifest.json      # parsed .sal manifest (when present)
├── summary.json       # parsed .sal summary (when present)
├── analysis.json      # parsed .sal analysis digest (when present)
└── forAi.txt          # for_ai.md text (when present)
```

The verdict `severity` is one of `ok` / `warning` / `critical`, derived deterministically
from `stats.failedRequests`, `stats.crashes`, and the session score. The short `responseBody`
is what the Control Room shows under the recording.

## Swapping the mock verdict for a real AI call

Everything funnels through one function: `mock_verdict(meta, parsed)` in `backend/server.py`.
Replace its body with a call to your real model (send `parsed["analysis"]` +
`parsed["summary"]` + `parsed["forAi"]`, or `meta`), and keep the contract the Control Room
relies on:

- return a dict with a short, punchy `responseBody` (JSON with at least `ok`, `severity`,
  `likelyOwner`, `summary`) — that string is what the tester sees on-device;
- `Store.save(…)` persists whatever you return, so the dashboard + `/api/uploads/<id>` keep
  working unchanged.

## Tests

```bash
python3 backend/tests/test_backend.py
```

15 end-to-end tests boot the real server on an ephemeral port with a temp data dir and cover ping,
test-ping, multipart upload, metadata parse, detail, byte-for-byte download round-trip, JSON
ingest, delete, the dashboard, a clear error on a bad upload, and the chunked/resumable flow
(start / chunk / status / finalize / download round-trip, missing-chunk conflict, and idempotent
re-start).
