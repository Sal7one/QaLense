#!/usr/bin/env python3
"""
demo_chunked.py — drives the R7 chunked/resumable webhook protocol against the mock backend.

Usage: python3 scripts/demo_chunked.py <file.sal> <base-url> [chunk-size]

Protocol (matches QaLensWebhook + backend/server.py):
  POST /webhook/chunk/start                    → {uploadId}  (idempotent: same file → same id)
  GET  /webhook/chunk/<id>/status              → {received:[...]}  (resume point)
  POST /webhook/chunk/<id>/<index>             → raw bytes + X-QaLens-Chunk-Crc32
  POST /webhook/chunk/<id>/finalize            → assembles + mock AI verdict
"""
import hashlib
import json
import sys
import urllib.request
import zlib
from pathlib import Path


def req(method, url, body=None, headers=None):
    r = urllib.request.Request(url, data=body, headers=headers or {}, method=method)
    with urllib.request.urlopen(r) as resp:
        return json.loads(resp.read().decode("utf-8"))


def main():
    path = Path(sys.argv[1])
    base = sys.argv[2].rstrip("/")
    chunk_size = int(sys.argv[3]) if len(sys.argv) > 3 else 1_000_000
    data = path.read_bytes()
    chunks = [data[i:i + chunk_size] for i in range(0, len(data), chunk_size)]
    digest = hashlib.sha256(data).hexdigest()

    print("  file:", path.name, "|", len(data), "bytes |", len(chunks), "chunks of", chunk_size)
    base_headers = {
        "X-QaLens-Sal-Name": path.name,
        "X-QaLens-Sal-Size": str(len(data)),
        "X-QaLens-Chunk-Count": str(len(chunks)),
        "X-QaLens-Digest": json.dumps({"sha256": digest[:12]}),
        "X-QaLens-App": "QaLens Sample (chunked demo)",
    }

    started = req("POST", base + "/webhook/chunk/start", body=b"", headers=base_headers)
    uid = started["uploadId"]
    print("  started:", "resumed" if started.get("resumed") else "new", "upload", uid)

    status = req("GET", base + "/webhook/chunk/" + uid + "/status")
    received = set(status.get("received", []))
    print("  resume state: chunks already on the backend:", sorted(received) or "none")

    for i, chunk in enumerate(chunks):
        if i in received:
            print("  chunk", i, "already there — skipped (that is the resume path)")
            continue
        req("POST", base + "/webhook/chunk/%s/%d" % (uid, i), body=chunk, headers={
            "X-QaLens-Chunk-Crc32": "%08x" % (zlib.crc32(chunk) & 0xFFFFFFFF),
            "X-QaLens-Chunk-Size": str(len(chunk)),
        })
        print("  chunk", i, "uploaded (", len(chunk), "bytes )")

    verdict = req("POST", base + "/webhook/chunk/" + uid + "/finalize", body=b"", headers=base_headers)
    print("  verdict:", verdict.get("severity"), "|", verdict.get("likelyOwner"), "|", verdict.get("summary", "")[:80])
    print("  stored as:", verdict.get("storedAs"), "→ dashboard", base)


if __name__ == "__main__":
    main()
