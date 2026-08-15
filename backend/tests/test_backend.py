#!/usr/bin/env python3
"""
End-to-end self-test for the QaLens mock webhook backend.

Boots the real server on an ephemeral port with a temp data dir and exercises the
contracts the mobile hook (QaLensWebhook) and the frontend hook (web player) rely on:

  * GET /ping health
  * POST /webhook with JSON {"qalens":"webhook-test"}  -> Control Room "Test endpoint" button
  * POST /webhook multipart .sal + X-QaLens-* headers  -> Control Room upload
  * GET /api/uploads, GET /api/uploads/<id>            -> dashboard data
  * GET /uploads/<id>/download                         -> stored file round-trips byte-for-byte
  * POST /api/ingest JSON summary                      -> web player frontend hook
  * DELETE /api/uploads/<id>                           -> cleanup

Run:  python3 backend/tests/test_backend.py
"""

import http.client
import json
import os
import sys
import tempfile
import threading
import unittest
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from server import make_server  # noqa: E402

SAMPLE_SAL = Path(__file__).resolve().parent.parent.parent / "web" / "sample.sal"

CRLF = chr(13) + chr(10)
PORT = None


def request(method, path, body=None, headers=None, expect=None):
    conn = http.client.HTTPConnection("127.0.0.1", PORT, timeout=10)
    conn.request(method, path, body=body, headers=headers or {})
    resp = conn.getresponse()
    data = resp.read()
    conn.close()
    if expect is not None and resp.status != expect:
        raise AssertionError(
            "%s %s -> %s (expected %s): %s" % (method, path, resp.status, expect, data[:400]))
    return resp, data


def multipart_body(boundary, file_name, file_bytes, fields=None):
    out = []
    for name, value in (fields or {}).items():
        out.append(("--" + boundary + CRLF
                    + "Content-Disposition: form-data; name=\"" + name + "\"" + CRLF + CRLF
                    + value + CRLF).encode("utf-8"))
    out.append(("--" + boundary + CRLF
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + file_name + "\"" + CRLF
                + "Content-Type: application/zip" + CRLF + CRLF).encode("utf-8"))
    out.append(file_bytes)
    out.append((CRLF + "--" + boundary + "--" + CRLF).encode("utf-8"))
    return b"".join(out)


def crc32_hex(data):
    """Lowercase hex crc32, matching the client's X-QaLens-Chunk-Crc32 header."""
    return format(zlib.crc32(data) & 0xFFFFFFFF, "x")


def start_chunk_upload(name, size, count, digest, app="QaLens Sample"):
    resp, data = request("POST", "/webhook/chunk/start", body=b"",
                         headers={
                             "X-QaLens-App": app,
                             "X-QaLens-Sal-Name": name,
                             "X-QaLens-Sal-Size": str(size),
                             "X-QaLens-Chunk-Count": str(count),
                             "X-QaLens-Digest": digest,
                         }, expect=200)
    return json.loads(data)


def put_chunk(uid, index, chunk):
    resp, data = request("POST", "/webhook/chunk/%s/%d" % (uid, index), body=chunk,
                         headers={
                             "X-QaLens-Chunk-Crc32": crc32_hex(chunk),
                             "X-QaLens-Chunk-Size": str(len(chunk)),
                         }, expect=200)
    return json.loads(data)


class BackendTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        global PORT
        cls.tmp = tempfile.TemporaryDirectory()
        cls.server = make_server("127.0.0.1", 0, data_dir=os.path.join(cls.tmp.name, "data"))
        PORT = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.tmp.cleanup()

    def test_01_ping(self):
        resp, data = request("GET", "/ping", expect=200)
        self.assertTrue(json.loads(data)["ok"])
        self.assertEqual(json.loads(data)["service"], "qalens-mock-backend")

    def test_02_mobile_test_endpoint_ping(self):
        body = json.dumps({"qalens": "webhook-test"}).encode("utf-8")
        resp, data = request("POST", "/webhook", body=body,
                             headers={"Content-Type": "application/json"}, expect=200)
        payload = json.loads(data)
        self.assertTrue(payload["ok"])
        self.assertIn("Endpoint OK", payload["message"])

    def test_03_mobile_multipart_sal_upload(self):
        self.assertTrue(SAMPLE_SAL.is_file(), "web/sample.sal missing")
        raw = SAMPLE_SAL.read_bytes()
        boundary = "----qalens-test-boundary"
        body = multipart_body(boundary, "sample.sal", raw)
        headers = {
            "Content-Type": "multipart/form-data; boundary=" + boundary,
            "X-QaLens-App": "QaLens Sample",
            "X-QaLens-Version": "1.0.0",
            "X-QaLens-Env": "staging",
            "X-QaLens-Device": "Pixel 9 / Android 15",
            "X-QaLens-Platform": "android 15",
            "X-QaLens-User": "qa+saleh@example.com",
            "X-QaLens-Sal-Name": "sample.sal",
            "X-QaLens-Sal-Size": str(len(raw)),
            "X-QaLens-Digest": "{\"failedRequests\":1}",
        }
        resp, data = request("POST", "/webhook?team=payments&pipeline=nightly",
                             body=body, headers=headers, expect=200)
        payload = json.loads(data)
        self.assertTrue(payload["ok"])
        self.assertIn("severity", payload)
        self.assertIn("summary", payload)
        type(self).upload_id = payload["id"]

    def test_04_upload_listed_with_parsed_metadata(self):
        resp, data = request("GET", "/api/uploads", expect=200)
        uploads = json.loads(data)["uploads"]
        self.assertGreaterEqual(len(uploads), 1)
        u = next(x for x in uploads if x["id"] == type(self).upload_id)
        self.assertEqual(u["source"], "mobile")
        self.assertEqual((u["app"] or {}).get("name"), "QaLens Sample")
        self.assertEqual(u["environment"], "staging")
        self.assertEqual(u["score"], 58)
        self.assertEqual(u["likelyOwner"], "Backend/API")
        self.assertEqual(u["query"]["team"], "payments")
        self.assertEqual(u["headers"]["X-QaLens-User"], "qa+saleh@example.com")

    def test_05_detail_has_verdict_and_analysis(self):
        resp, data = request("GET", "/api/uploads/" + type(self).upload_id, expect=200)
        d = json.loads(data)
        self.assertEqual(d["verdict"]["severity"], "warning")
        self.assertIn("Backend/API", d["verdict"]["likelyOwner"])
        self.assertTrue(d["analysis"]["coverage"]["network"])
        self.assertIn("forAi", d)
        self.assertTrue(d["forAi"].startswith("# How to analyze"))
        self.assertIn("failed_request", [a["kind"] for a in d["analysis"]["anomalies"]])

    def test_06_download_roundtrips(self):
        resp, data = request("GET", "/uploads/" + type(self).upload_id + "/download", expect=200)
        self.assertEqual(data, SAMPLE_SAL.read_bytes())

    def test_07_frontend_hook_json_summary(self):
        summary = {
            "app": "QaLens Sample", "version": "1.0.0", "environment": "staging",
            "device": "web player", "platform": "web",
            "score": 58, "likelyOwner": "Backend/API",
            "failedRequests": 1, "crashes": 0,
            "sessionId": "frontend-hook-demo",
            "name": "sample.sal (from web player)",
        }
        resp, data = request("POST", "/api/ingest", body=json.dumps(summary).encode("utf-8"),
                             headers={"Content-Type": "application/json",
                                      "X-QaLens-App": "QaLens Sample"}, expect=200)
        payload = json.loads(data)
        self.assertTrue(payload["ok"])
        type(self).web_id = payload["id"]

    def test_08_web_ingest_listed(self):
        resp, data = request("GET", "/api/uploads", expect=200)
        uploads = json.loads(data)["uploads"]
        u = next(x for x in uploads if x["id"] == type(self).web_id)
        self.assertEqual(u["source"], "web")
        self.assertEqual(u["sessionId"], "frontend-hook-demo")
        self.assertEqual((u["app"] or {}).get("name"), "QaLens Sample")

    def test_09_delete_cleanup(self):
        resp, data = request("DELETE", "/api/uploads/" + type(self).upload_id, expect=200)
        self.assertTrue(json.loads(data)["ok"])
        resp, data = request("GET", "/api/uploads/" + type(self).upload_id, expect=404)

    def test_10_dashboard_html(self):
        resp, data = request("GET", "/", expect=200)
        self.assertIn(b"QaLens Mock Backend", data)
        self.assertIn(b"Received sessions", data)

    def test_11_bad_upload_gets_clear_error(self):
        resp, data = request("POST", "/webhook", body=b"not a zip",
                             headers={"Content-Type": "multipart/form-data; boundary=x"},
                             expect=400)
        self.assertFalse(json.loads(data)["ok"])

    # -- chunked / resumable upload (R7) ------------------------------------
    def test_12_chunked_upload_roundtrip(self):
        raw = SAMPLE_SAL.read_bytes()
        chunk_size = 1_000_000
        # sample.sal is smaller than one chunk, so this is [whole, empty remainder] —
        # still exercises start → chunk ×2 → status → finalize → download.
        chunks = [raw[:chunk_size], raw[chunk_size:]]
        start = start_chunk_upload("sample.sal", len(raw), len(chunks), "{\"failedRequests\":1}")
        self.assertTrue(start["ok"])
        uid = start["uploadId"]
        for i, chunk in enumerate(chunks):
            got = put_chunk(uid, i, chunk)
            self.assertEqual(got["received"], i)
        resp, data = request("GET", "/webhook/chunk/%s/status" % uid, expect=200)
        self.assertEqual(sorted(json.loads(data)["received"]), [0, 1])
        resp, data = request("POST", "/webhook/chunk/%s/finalize" % uid, body=b"",
                             headers={"X-QaLens-Sal-Name": "sample.sal",
                                      "X-QaLens-Sal-Size": str(len(raw)),
                                      "X-QaLens-Digest": "{\"failedRequests\":1}"}, expect=200)
        final = json.loads(data)
        self.assertTrue(final["ok"])
        self.assertIn("severity", final)
        self.assertIn("likelyOwner", final)
        self.assertIn("summary", final)
        self.assertEqual(final["storedAs"], "sample.sal")
        resp, data = request("GET", "/uploads/%s/download" % final["id"], expect=200)
        self.assertEqual(data, raw)

    def test_13_chunk_status_reports_received(self):
        raw = SAMPLE_SAL.read_bytes()
        chunks = [raw[:1_000_000], raw[1_000_000:]]
        start = start_chunk_upload("status.sal", len(raw), len(chunks), "{\"failedRequests\":2}")
        uid = start["uploadId"]
        resp, data = request("GET", "/webhook/chunk/%s/status" % uid, expect=200)
        self.assertEqual(json.loads(data)["received"], [])
        put_chunk(uid, 0, chunks[0])
        resp, data = request("GET", "/webhook/chunk/%s/status" % uid, expect=200)
        self.assertEqual(json.loads(data)["received"], [0])
        put_chunk(uid, 1, chunks[1])
        resp, data = request("GET", "/webhook/chunk/%s/status" % uid, expect=200)
        self.assertEqual(json.loads(data)["received"], [0, 1])

    def test_14_finalize_missing_chunk_conflicts(self):
        raw = SAMPLE_SAL.read_bytes()
        chunks = [raw[:1_000_000], raw[1_000_000:]]
        start = start_chunk_upload("missing.sal", len(raw), len(chunks), "{\"failedRequests\":3}")
        uid = start["uploadId"]
        put_chunk(uid, 0, chunks[0])  # chunk 1 deliberately omitted
        resp, data = request("POST", "/webhook/chunk/%s/finalize" % uid, body=b"",
                             headers={"X-QaLens-Sal-Name": "missing.sal",
                                      "X-QaLens-Sal-Size": str(len(raw)),
                                      "X-QaLens-Digest": "{\"failedRequests\":3}"}, expect=409)
        payload = json.loads(data)
        self.assertFalse(payload["ok"])
        self.assertIn("missing chunk", payload["error"])

    def test_15_chunk_start_is_idempotent_resume(self):
        raw = SAMPLE_SAL.read_bytes()
        first = start_chunk_upload("resume.sal", len(raw), 2, "{\"failedRequests\":4}")
        self.assertFalse(first.get("resumed", False))
        second = start_chunk_upload("resume.sal", len(raw), 2, "{\"failedRequests\":4}")
        self.assertTrue(second["ok"])
        self.assertTrue(second.get("resumed", False))
        self.assertEqual(second["uploadId"], first["uploadId"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
