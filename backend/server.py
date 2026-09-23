#!/usr/bin/env python3
"""
QaLens mock webhook backend — a zero-dependency (stdlib-only) local server that stands in for
your real AI-analysis backend while you develop and test the QaLens hooks end to end.

It accepts BOTH of QaLens's hook flavors on one endpoint:

  POST /webhook          (mobile hook)  multipart .sal upload with X-QaLens-* headers, exactly
                                        what the app's Control Room sends (QaLensWebhook), plus
                                        the metadata-only JSON ping the "Test endpoint" button sends.
  POST /api/ingest       (frontend hook) the web player's JSON session summary or a multipart
                                        .sal from the browser (Mission Control -> "Send to backend").

And a small live dashboard so QA/developers can watch uploads arrive:

  GET  /                 dashboard (auto-refreshing; click a card for the AI verdict)
  GET  /ping             health check
  GET  /api/uploads      JSON list of everything received
  GET  /api/uploads/<id> JSON detail (headers, parsed digest, mock AI verdict)
  GET  /uploads/<id>/download   the stored .sal file
  DELETE /api/uploads/<id>      remove an upload

The "AI analysis" is a deterministic mock: it reads the .sal's own precomputed
analysis.json / summary.json and produces a short verdict (severity + likely owner + top
evidence). The verdict is returned in the webhook response body, which the app's Control Room
shows right under the recording.

Run:
    python3 backend/server.py                # local only: http://127.0.0.1:8000
    python3 backend/server.py --port 9000
    python3 backend/tests/test_backend.py    # end-to-end self-test

Point the app at it (emulator):
    adb reverse tcp:8000 tcp:8000
    Control Room -> Webhook -> http://127.0.0.1:8000/webhook -> Test endpoint

Works on Python 3.9+ (including 3.13/3.14 — no cgi module used).
"""

import argparse
import hashlib
import ipaddress
import io
import json
import re
import shutil
import sys
import threading
import time
import zipfile
import gzip
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

VERSION = "0.1.0"
DEFAULT_PORT = 8000
DEFAULT_HOST = "127.0.0.1"
MAX_REQUEST_BYTES = 64 * 1024 * 1024
MAX_ARCHIVE_ENTRIES = 4096
MAX_ARCHIVE_ENTRY_BYTES = 256 * 1024 * 1024
MAX_ARCHIVE_EXPANDED_BYTES = 512 * 1024 * 1024
MAX_ARCHIVE_TEXT_BYTES = 16 * 1024 * 1024
MAX_ARCHIVE_MANIFEST_BYTES = 1 * 1024 * 1024
UPLOAD_ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,200}$")
CHUNK_SIZE_BYTES = 1_000_000
LOCK = threading.Lock()

# Avoid escape sequences entirely (CRLF is framing-sensitive in multipart parsing).
CRLF = chr(13) + chr(10)
CRLF_B = CRLF.encode("latin-1")


# ---------------------------------------------------------------------------
# Storage
# ---------------------------------------------------------------------------

class Store:
    """Tiny file-backed store: data/<id>/recording.sal + meta.json + verdict.json."""

    def __init__(self, root: Path):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)

    def _ids(self):
        return [p.name for p in sorted(self.root.iterdir()) if (p / "meta.json").is_file()]

    def list(self):
        out = []
        for uid in self._ids():
            meta = self._read_json(uid, "meta.json")
            if meta:
                meta["id"] = uid
                verdict = self._read_json(uid, "verdict.json") or {}
                meta["verdict"] = {"severity": verdict.get("severity"),
                                   "label": verdict.get("label"),
                                   "summary": verdict.get("summary")}
                out.append(meta)
        out.sort(key=lambda m: m.get("receivedAt", 0), reverse=True)
        return out

    def get(self, uid: str):
        if not UPLOAD_ID_RE.fullmatch(uid):
            return None
        meta = self._read_json(uid, "meta.json")
        if meta is None:
            return None
        detail = dict(meta)
        detail["id"] = uid
        detail["verdict"] = self._read_json(uid, "verdict.json")
        detail["manifest"] = self._read_json(uid, "manifest.json")
        detail["summary"] = self._read_json(uid, "summary.json")
        detail["analysis"] = self._read_json(uid, "analysis.json")
        for_ai = self.root / uid / "forAi.txt"
        detail["forAi"] = for_ai.read_text("utf-8") if for_ai.is_file() else None
        return detail

    def path(self, uid: str, name: str) -> Path:
        if not UPLOAD_ID_RE.fullmatch(uid) or name != "recording.sal":
            return self.root / "__invalid_upload_id__" / "recording.sal"
        return self.root / uid / name

    def save(self, uid: str, meta: dict, payload: bytes, verdict: dict, parsed: dict):
        with LOCK:
            d = self.root / uid
            d.mkdir(parents=True, exist_ok=True)
            (d / "recording.sal").write_bytes(payload)
            (d / "meta.json").write_text(json.dumps(meta, indent=2), "utf-8")
            (d / "verdict.json").write_text(json.dumps(verdict, indent=2), "utf-8")
            for key, value in parsed.items():
                if value is None:
                    continue
                if isinstance(value, str):
                    (d / (key + ".txt")).write_text(value, "utf-8")
                else:
                    (d / (key + ".json")).write_text(
                        json.dumps(value, indent=2, ensure_ascii=False), "utf-8")

    def delete(self, uid: str) -> bool:
        if not UPLOAD_ID_RE.fullmatch(uid):
            return False
        with LOCK:
            d = self.root / uid
            if not d.is_dir():
                return False
            shutil.rmtree(d)
            return True

    def delete_chunk_upload(self, uid: str):
        if not UPLOAD_ID_RE.fullmatch(uid):
            return
        with LOCK:
            shutil.rmtree(self.uploads_dir() / uid, ignore_errors=True)

    def _read_json(self, uid: str, name: str):
        if not UPLOAD_ID_RE.fullmatch(uid) or name not in {
            "meta.json", "verdict.json", "manifest.json", "summary.json", "analysis.json"
        }:
            return None
        p = self.root / uid / name
        if not p.is_file():
            return None
        try:
            return json.loads(p.read_text("utf-8"))
        except (OSError, json.JSONDecodeError):
            return None

    # -- chunked / resumable upload staging (data/uploads/<id>/chunks/) --------
    def uploads_dir(self) -> Path:
        return self.root / "uploads"

    def chunk_meta(self, uid: str):
        if not UPLOAD_ID_RE.fullmatch(uid):
            return None
        p = self.uploads_dir() / uid / "chunks" / "meta.json"
        if not p.is_file():
            return None
        try:
            return json.loads(p.read_text("utf-8"))
        except (OSError, json.JSONDecodeError):
            return None

    def save_chunk_meta(self, uid: str, meta: dict):
        if not UPLOAD_ID_RE.fullmatch(uid):
            return
        p = self.uploads_dir() / uid / "chunks" / "meta.json"
        p.parent.mkdir(parents=True, exist_ok=True)
        with LOCK:
            p.write_text(json.dumps(meta, indent=2), "utf-8")

    def find_chunk_upload(self, name: str, size: int, digest: str):
        """Return the id of an existing chunked upload keyed by name+size+digest, or None."""
        udir = self.uploads_dir()
        if not udir.is_dir():
            return None
        for p in sorted(udir.iterdir()):
            meta = self.chunk_meta(p.name)
            if meta and meta.get("name") == name and meta.get("size") == size \
                    and meta.get("digest") == digest:
                return p.name
        return None

    def write_chunk(self, uid: str, index: int, data: bytes):
        if not UPLOAD_ID_RE.fullmatch(uid) or index < 0:
            return False
        p = self.uploads_dir() / uid / "chunks" / (str(index) + ".bin")
        p.parent.mkdir(parents=True, exist_ok=True)
        with LOCK:
            p.write_bytes(data)
        return True

    def read_chunk(self, uid: str, index: int):
        if not UPLOAD_ID_RE.fullmatch(uid) or index < 0:
            return None
        p = self.uploads_dir() / uid / "chunks" / (str(index) + ".bin")
        if not p.is_file():
            return None
        return p.read_bytes()

    def received_chunks(self, uid: str) -> list:
        if not UPLOAD_ID_RE.fullmatch(uid):
            return []
        d = self.uploads_dir() / uid / "chunks"
        if not d.is_dir():
            return []
        out = []
        for f in sorted(d.iterdir()):
            if f.name.endswith(".bin"):
                try:
                    out.append(int(f.name[:-4]))
                except ValueError:
                    pass
        return sorted(out)


# ---------------------------------------------------------------------------
# .sal parsing (zipfile + json only — same contract as QaLensSalReader)
# ---------------------------------------------------------------------------

def _valid_archive_name(name: str) -> bool:
    if not name or "\\" in name or "\x00" in name or name.startswith("/"):
        return False
    parts = name.rstrip("/").split("/")
    return all(part not in ("", ".", "..") for part in parts) and ":" not in parts[0]


def _bounded_gzip(data: bytes, limit: int, name: str) -> bytes:
    result = bytearray()
    with gzip.GzipFile(fileobj=io.BytesIO(data)) as stream:
        while True:
            block = stream.read(min(65536, limit - len(result) + 1))
            if not block:
                return bytes(result)
            if len(block) > limit - len(result):
                raise ValueError("Expanded text limit exceeded: " + name)
            result.extend(block)


def parse_sal(payload: bytes) -> dict:
    """Check every ZIP member, then decode bounded v1/v2 evidence for the mock verdict."""
    out = {"manifest": None, "summary": None, "analysis": None, "forAi": None}
    selected = {"manifest.json", "summary.json", "analysis.json", "for_ai.md"}
    extracted = {}
    checksums = {}
    try:
        with zipfile.ZipFile(io.BytesIO(payload)) as zf:
            infos = zf.infolist()
            if len(infos) > MAX_ARCHIVE_ENTRIES:
                raise ValueError("Too many archive entries")
            seen = set()
            outer_total = 0
            for info in infos:
                name = info.filename
                if not _valid_archive_name(name) or name in seen:
                    raise ValueError("Invalid or duplicate archive entry: " + name)
                seen.add(name)
                if info.flag_bits & 1 or info.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
                    raise ValueError("Unsupported archive entry: " + name)
                if info.file_size > MAX_ARCHIVE_ENTRY_BYTES:
                    raise ValueError("Archive entry size limit exceeded: " + name)
                outer_total += info.file_size
                if outer_total > MAX_ARCHIVE_EXPANDED_BYTES:
                    raise ValueError("Archive expanded size limit exceeded")

            decoded_total = 0
            for info in infos:
                name = info.filename
                if info.is_dir():
                    if info.file_size:
                        raise ValueError("Invalid archive directory: " + name)
                    continue
                is_text = name.endswith((".json", ".txt", ".md"))
                text_limit = MAX_ARCHIVE_MANIFEST_BYTES if name == "manifest.json" else MAX_ARCHIVE_TEXT_BYTES
                # Gzip framing can add a little to an otherwise valid text entry.
                outer_limit = min(MAX_ARCHIVE_ENTRY_BYTES, text_limit + 65536) if is_text else MAX_ARCHIVE_ENTRY_BYTES
                raw = bytearray() if is_text else None
                raw_size = 0
                checksum = 0
                with zf.open(info) as stream:
                    while True:
                        block = stream.read(65536)
                        if not block:
                            break  # ZipExtFile verifies its ZIP CRC when fully read.
                        raw_size += len(block)
                        if raw_size > outer_limit:
                            raise ValueError("Archive entry size limit exceeded: " + name)
                        if raw is not None:
                            raw.extend(block)
                        else:
                            checksum = zlib.crc32(block, checksum)
                if raw_size != info.file_size:
                    raise ValueError("Archive entry size mismatch: " + name)
                if raw is not None:
                    data = bytes(raw)
                    if name.endswith(".json") and data.startswith(b"\x1f\x8b"):
                        data = _bounded_gzip(data, text_limit, name)
                    if len(data) > text_limit:
                        raise ValueError("Expanded text limit exceeded: " + name)
                    checksum = zlib.crc32(data)
                    if name in selected:
                        extracted[name] = data.decode("utf-8")
                    decoded_total += len(data)
                else:
                    decoded_total += raw_size
                if decoded_total > MAX_ARCHIVE_EXPANDED_BYTES:
                    raise ValueError("Archive decoded size limit exceeded")
                checksums[name] = "%08x" % (checksum & 0xffffffff)

            def read_json(name):
                text = extracted.get(name)
                if text is None:
                    return None
                value = json.loads(text)
                if not isinstance(value, dict):
                    raise ValueError("Invalid object in " + name)
                return value

            out["manifest"] = read_json("manifest.json")
            if out["manifest"] is None:
                raise ValueError("Missing manifest.json")
            version = out["manifest"].get("formatVersion", 1)
            if type(version) is not int or version not in (1, 2):
                raise ValueError("Unsupported .sal formatVersion: " + str(version))
            files = out["manifest"].get("files")
            if files is not None:
                if not isinstance(files, list) or len(files) > MAX_ARCHIVE_ENTRIES:
                    raise ValueError("Invalid manifest files list")
                for item in files:
                    name = item.get("name") if isinstance(item, dict) else item
                    if not isinstance(name, str) or name not in checksums:
                        raise ValueError("Missing archive entry in manifest: " + str(name))
                    if isinstance(item, dict) and "crc32" in item:
                        expected = item["crc32"]
                        if not isinstance(expected, str) or not re.fullmatch(r"[0-9a-fA-F]{8}", expected):
                            raise ValueError("Invalid archive checksum: " + name)
                        if checksums[name] != expected.lower():
                            raise ValueError("Archive checksum mismatch: " + name)
            out["summary"] = read_json("summary.json")
            out["analysis"] = read_json("analysis.json")
            out["forAi"] = extracted.get("for_ai.md")
    except (zipfile.BadZipFile, OSError, ValueError, UnicodeDecodeError, EOFError,
            RuntimeError, zlib.error) as exc:
        raise ValueError("Invalid .sal recording: " + str(exc)) from exc
    return out


def chunk_upload_key(name: str, size: int, digest: str) -> str:
    """Deterministic idempotency key for a chunked upload (name+size+digest)."""
    return hashlib.sha256(("%s:%s:%s" % (name, size, digest)).encode("utf-8")).hexdigest()


# ---------------------------------------------------------------------------
# Mock AI verdict — deterministic, so the same .sal always gets the same verdict
# ---------------------------------------------------------------------------

def mock_verdict(meta: dict, parsed: dict) -> dict:
    manifest = parsed.get("manifest") or {}
    summary = parsed.get("summary") or {}
    analysis = parsed.get("analysis") or {}
    stats = analysis.get("stats") or {}
    recording = (analysis.get("coverage") or {}).get("recording") or {}
    partial = bool(recording.get("truncated") or recording.get("droppedFrameCallbacks") or
                   any(track.get("dropped", 0) for track in (recording.get("tracks") or {}).values()))
    anomalies = analysis.get("anomalies") or []

    score = summary.get("score") if isinstance(summary.get("score"), int) else None
    failed = int(stats.get("failedRequests", 0) or 0)
    crashes = int(stats.get("crashes", 0) or 0)
    owner = summary.get("category") or analysis.get("likelyOwner", {}).get("category") or "Unknown"
    confidence = summary.get("confidence") or analysis.get("likelyOwner", {}).get("confidence", "LOW")

    top_endpoint = None
    for ep in analysis.get("endpoints") or []:
        if int(ep.get("failures", 0) or 0) > 0:
            top_endpoint = ep.get("endpoint")
            break

    if not summary and not stats:
        severity, label = "unknown", "Insufficient evidence"
    elif crashes:
        severity, label = "critical", "CRASH — likely release blocker"
    elif failed >= 3 or (score is not None and score < 50):
        severity, label = "critical", "HIGH RISK — multiple failures"
    elif failed >= 1 or (score is not None and score < 70):
        severity, label = "warning", "Needs attention"
    elif partial:
        severity, label = "unknown", "Partial recording — evidence was omitted"
    else:
        severity, label = "ok", "No failures in captured evidence"

    evidence = []
    if partial:
        evidence.append("recording reports omitted observations; conclusions cover retained evidence only")
    if failed:
        evidence.append(str(failed) + " failed request(s)")
    if top_endpoint:
        evidence.append("worst endpoint: " + str(top_endpoint))
    if crashes:
        evidence.append(str(crashes) + " crash(es)")
    if not evidence:
        evidence.append("no analysis or summary available" if severity == "unknown" else "no failure signal in captured evidence")

    verdict = {
        "generatedAt": int(time.time() * 1000),
        "engine": "qalens-mock-ai/1",
        "severity": severity,
        "label": label,
        "score": score,
        "likelyOwner": owner,
        "confidence": confidence,
        "summary": ("Mock AI: " + label + ". Likely owner " + str(owner) + " (" + str(confidence) + "). "
                    + "; ".join(evidence) + "."),
        "evidence": evidence,
    }
    # The app shows only the HTTP response body — keep THAT short and punchy.
    verdict["responseBody"] = json.dumps({
        "ok": True,
        "engine": "qalens-mock-ai/1",
        "severity": severity,
        "likelyOwner": owner,
        "summary": verdict["summary"][:400],
        "id": meta.get("id"),
    })
    return verdict


# ---------------------------------------------------------------------------
# multipart/form-data (binary-safe, stdlib only)
# ---------------------------------------------------------------------------

def parse_multipart(body: bytes, boundary: str) -> list:
    delim = ("--" + boundary).encode("latin-1")
    parts = []
    for chunk in body.split(delim)[1:]:
        if chunk[:2] == b"--":            # closing delimiter
            break
        chunk = chunk[2:]                 # strip CRLF after the boundary line
        head, sep, content = chunk.partition(CRLF_B + CRLF_B)
        if not sep:
            continue
        if content.endswith(CRLF_B):
            content = content[:-2]        # framing CRLF before the next boundary
        headers = {}
        for line in head.decode("latin-1").split(CRLF):
            if ":" in line:
                k, v = line.split(":", 1)
                headers[k.strip().lower()] = v.strip()
        cd = headers.get("content-disposition", "")
        m = re.search('name="([^"]*)"', cd)
        name = m.group(1) if m else None
        m = re.search('filename="([^"]*)"', cd)
        filename = m.group(1) if m else None
        parts.append({"name": name, "filename": filename,
                      "content": content, "headers": headers})
    return parts


# ---------------------------------------------------------------------------
# HTTP handler
# ---------------------------------------------------------------------------

# Enumerate exact request headers instead of a wildcard: Safari rejects partial wildcards.
# CORS is granted only to loopback pages or a page served from this backend's hostname.
# Native clients do not use CORS. This remains a development server, not an auth boundary.
CORS_HEADERS = {
    "Access-Control-Allow-Methods": "GET, POST, DELETE, OPTIONS",
    "Access-Control-Allow-Headers": ("Content-Type, X-QaLens-App, X-QaLens-Version, "
                                     "X-QaLens-Env, X-QaLens-Device, X-QaLens-Platform, "
                                     "X-QaLens-User, X-QaLens-Sal-Name, X-QaLens-Sal-Size, "
                                     "X-QaLens-Digest, X-QaLens-Chunk-Crc32, "
                                     "X-QaLens-Chunk-Size, X-QaLens-Chunk-Count"),
}


class Handler(BaseHTTPRequestHandler):
    server_version = "QaLensMockBackend/" + VERSION
    protocol_version = "HTTP/1.1"

    def _store(self):
        return self.server.store

    def _send(self, code: int, body: bytes, ctype: str = "application/json"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        origin = self._allowed_origin()
        if origin:
            self.send_header("Access-Control-Allow-Origin", origin)
            self.send_header("Vary", "Origin")
            for k, v in CORS_HEADERS.items():
                self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def _json(self, code: int, obj: dict):
        self._send(code, json.dumps(obj).encode("utf-8"))

    def _html(self, code: int, text: str):
        self._send(code, text.encode("utf-8"), "text/html; charset=utf-8")

    def _allowed_origin(self):
        origin = self.headers.get("Origin")
        if not origin:
            return None
        try:
            parsed_origin = urlparse(origin)
            if parsed_origin.scheme not in ("http", "https") or not parsed_origin.hostname:
                return None
            origin_host = parsed_origin.hostname.lower().rstrip(".")
            if origin_host == "localhost":
                return origin
            try:
                if ipaddress.ip_address(origin_host).is_loopback:
                    return origin
            except ValueError:
                pass
            request_host = urlparse("//" + (self.headers.get("Host") or "")).hostname
            if request_host and origin_host == request_host.lower().rstrip("."):
                return origin
        except ValueError:
            return None
        return None

    def _read_body(self):
        if self.headers.get("Transfer-Encoding"):
            self.close_connection = True
            self._json(501, {"ok": False, "error": "chunked HTTP request bodies are not supported"})
            return None
        raw_length = self.headers.get("Content-Length")
        if raw_length is None:
            return b""
        try:
            length = int(raw_length)
        except (TypeError, ValueError):
            self.close_connection = True
            self._json(400, {"ok": False, "error": "invalid Content-Length"})
            return None
        if length < 0:
            self.close_connection = True
            self._json(400, {"ok": False, "error": "invalid Content-Length"})
            return None
        if length > MAX_REQUEST_BYTES:
            self.close_connection = True
            self._json(413, {"ok": False, "error": "request body exceeds 64 MiB development-server limit"})
            return None
        body = self.rfile.read(length) if length else b""
        if len(body) != length:
            self.close_connection = True
            self._json(400, {"ok": False, "error": "request body ended before Content-Length"})
            return None
        return body

    def _qalens_headers(self) -> dict:
        out = {}
        for k, v in self.headers.items():
            if k.lower().startswith("x-qalens-"):
                out[k] = v
        return out

    def log_message(self, fmt, *args):
        print("[%s] %s" % (self.log_date_time_string(), fmt % args), file=sys.stderr)

    # -- routing ------------------------------------------------------------
    def do_OPTIONS(self):
        self._send(204, b"")

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/") or "/"
        if path == "/":
            return self._html(200, dashboard_html(self._store()))
        if path == "/ping":
            return self._json(200, {"ok": True, "service": "qalens-mock-backend",
                                    "version": VERSION, "time": int(time.time() * 1000)})
        if path == "/api/uploads":
            return self._json(200, {"uploads": self._store().list()})
        if path.startswith("/api/uploads/"):
            uid = path[len("/api/uploads/"):]
            detail = self._store().get(uid)
            if detail is None:
                return self._json(404, {"error": "unknown upload id"})
            return self._json(200, detail)
        if path.startswith("/uploads/") and path.endswith("/download"):
            uid = path[len("/uploads/"):-len("/download")]
            p = self._store().path(uid, "recording.sal")
            if not p.is_file():
                return self._json(404, {"error": "unknown upload id"})
            self._send(200, p.read_bytes(), "application/zip")
            return
        if path.startswith("/webhook/chunk/") and path.endswith("/status"):
            uid = path[len("/webhook/chunk/"):-len("/status")]
            meta = self._store().chunk_meta(uid)
            if meta is None:
                return self._json(404, {"ok": False, "error": "unknown upload id"})
            return self._json(200, {"ok": True, "uploadId": uid,
                                    "received": self._store().received_chunks(uid)})
        self._json(404, {"error": "not found"})

    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/") or "/"
        ctype = (self.headers.get("Content-Type") or "").lower()
        body = self._read_body()
        if body is None:
            return

        if path == "/ping":
            return self._json(200, {"ok": True, "service": "qalens-mock-backend"})

        if path.startswith("/webhook/chunk/"):
            return self._handle_chunk_post(path, parsed.query, body)

        if path == "/webhook":
            if ctype.startswith("application/json"):
                try:
                    payload = json.loads(body.decode("utf-8"))
                except (json.JSONDecodeError, UnicodeDecodeError):
                    return self._json(400, {"ok": False, "error": "invalid JSON"})
                if not isinstance(payload, dict):
                    return self._json(400, {"ok": False, "error": "JSON body must be an object"})
                if payload.get("qalens") == "webhook-test":
                    return self._json(200, {
                        "ok": True,
                        "message": "Endpoint OK — QaLens webhook reachable (mock backend "
                                   + VERSION + "). Ready for .sal uploads.",
                    })
                return self._ingest_json(payload, parsed.query)
            if ctype.startswith("multipart/form-data"):
                m = re.search(r"boundary=(\S+)", self.headers.get("Content-Type") or "")
                if not m:
                    return self._json(400, {"ok": False, "error": "multipart boundary missing"})
                parts = parse_multipart(body, m.group(1))
                file_part = next((p for p in parts if p["name"] == "file" and p["filename"]), None)
                if file_part is None:
                    file_part = next((p for p in parts if p["filename"]), None)
                if file_part is None:
                    return self._json(400, {"ok": False, "error": "no file part in multipart"})
                return self._ingest_sal(file_part, parsed.query, source="mobile")
            return self._json(415, {"ok": False, "error": "expected multipart/form-data or application/json"})

        if path == "/api/ingest":
            if ctype.startswith("application/json"):
                try:
                    payload = json.loads(body.decode("utf-8"))
                except (json.JSONDecodeError, UnicodeDecodeError):
                    return self._json(400, {"ok": False, "error": "invalid JSON"})
                if not isinstance(payload, dict):
                    return self._json(400, {"ok": False, "error": "JSON body must be an object"})
                return self._ingest_json(payload, parsed.query, source="web")
            if ctype.startswith("multipart/form-data"):
                m = re.search(r"boundary=(\S+)", self.headers.get("Content-Type") or "")
                if not m:
                    return self._json(400, {"ok": False, "error": "multipart boundary missing"})
                parts = parse_multipart(body, m.group(1))
                file_part = next((p for p in parts if p["filename"]), None)
                json_part = next((p for p in parts if p["name"] == "summary" and p["filename"] is None), None)
                if file_part is not None:
                    return self._ingest_sal(file_part, parsed.query, source="web")
                if json_part is not None:
                    try:
                        payload = json.loads(json_part["content"].decode("utf-8"))
                    except (json.JSONDecodeError, UnicodeDecodeError):
                        return self._json(400, {"ok": False, "error": "invalid summary JSON"})
                    if not isinstance(payload, dict):
                        return self._json(400, {"ok": False, "error": "summary JSON must be an object"})
                    return self._ingest_json(payload, parsed.query, source="web")
            return self._json(415, {"ok": False, "error": "expected JSON or multipart"})

        self._json(404, {"error": "not found"})

    def do_DELETE(self):
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/")
        if path.startswith("/api/uploads/"):
            uid = path[len("/api/uploads/"):]
            ok = self._store().delete(uid)
            return self._json(200 if ok else 404, {"ok": ok})
        self._json(404, {"error": "not found"})

    # -- ingestion ----------------------------------------------------------
    def _ingest_sal(self, file_part: dict, query: str, source: str):
        return self._ingest_sal_bytes(file_part["content"],
                                      file_part["filename"] or "session.sal",
                                      query, source)

    def _ingest_sal_bytes(self, payload: bytes, name: str, query: str, source: str, parsed=None,
                          cleanup_chunk_id: str = None):
        if parsed is None:
            try:
                parsed = parse_sal(payload)
            except ValueError as exc:
                return self._json(400, {"ok": False, "error": str(exc)})
        manifest = parsed.get("manifest") or {}
        summary = parsed.get("summary") or {}
        analysis = parsed.get("analysis") or {}

        received_at = int(time.time() * 1000)
        digest = hashlib.sha256(payload).hexdigest()
        uid = "%d-%s" % (time.time_ns(), digest[:12])

        meta = {
            "id": uid,
            "source": source,
            "name": name,
            "sizeBytes": len(payload),
            "sha256": digest,
            "receivedAt": received_at,
            "headers": self._qalens_headers(),
            "query": {k: v[0] for k, v in parse_qs(query).items()},
            "sessionId": manifest.get("sessionId") if isinstance(manifest, dict) else None,
            "app": (manifest.get("app") or {}) if isinstance(manifest, dict) else {},
            "environment": manifest.get("environment") if isinstance(manifest, dict) else None,
            "device": manifest.get("device") if isinstance(manifest, dict) else None,
            "platform": manifest.get("platform") if isinstance(manifest, dict) else None,
            "score": summary.get("score") if isinstance(summary, dict) else None,
            "likelyOwner": summary.get("category") if isinstance(summary, dict) else None,
            "failedRequests": (analysis.get("stats") or {}).get("failedRequests")
                              if isinstance(analysis, dict) else None,
            "crashes": (analysis.get("stats") or {}).get("crashes")
                       if isinstance(analysis, dict) else None,
        }
        verdict = mock_verdict(meta, parsed)
        self._store().save(uid, meta, payload, verdict,
                           {"manifest": manifest, "summary": summary,
                            "analysis": analysis, "forAi": parsed.get("forAi")})
        # A chunk finalize response is sent below. Remove temporary staging before exposing
        # success, so clients observing a completed response can rely on cleanup having finished.
        if cleanup_chunk_id is not None:
            self._store().delete_chunk_upload(cleanup_chunk_id)
        print("webhook: %s upload '%s' (%d bytes) score=%s owner=%s severity=%s id=%s" % (
            source, name, len(payload), meta["score"], meta["likelyOwner"],
            verdict["severity"], uid), file=sys.stderr)
        resp = json.loads(verdict["responseBody"])
        resp["id"] = uid
        resp["storedAs"] = name
        self._json(200, resp)

    def _ingest_json(self, payload: dict, query: str, source: str = "web"):
        """Frontend hook: the web player posts a JSON session summary (no .sal)."""
        received_at = int(time.time() * 1000)
        digest = hashlib.sha256(json.dumps(payload, sort_keys=True).encode("utf-8")).hexdigest()
        uid = "%d-%s" % (time.time_ns(), digest[:12])
        meta = {
            "id": uid,
            "source": source,
            "name": payload.get("name") or payload.get("app") or "session-summary",
            "sizeBytes": len(json.dumps(payload)),
            "sha256": digest,
            "receivedAt": received_at,
            "headers": self._qalens_headers(),
            "query": {k: v[0] for k, v in parse_qs(query).items()},
            "sessionId": payload.get("sessionId"),
            "app": {"name": payload.get("app"), "version": payload.get("version")},
            "environment": payload.get("environment"),
            "device": payload.get("device"),
            "platform": payload.get("platform", "web"),
            "score": payload.get("score"),
            "likelyOwner": payload.get("likelyOwner"),
            "failedRequests": payload.get("failedRequests"),
            "crashes": payload.get("crashes"),
            "summaryText": payload.get("summaryText"),
        }
        verdict = {
            "generatedAt": received_at,
            "engine": "qalens-mock-ai/1",
            "severity": "warning" if payload.get("failedRequests") else "ok",
            "label": "Web summary ingested",
            "score": payload.get("score"),
            "likelyOwner": payload.get("likelyOwner"),
            "summary": "Mock AI: web player summary for %s (score %s)." % (
                payload.get("app") or "session", payload.get("score")),
            "responseBody": json.dumps({
                "ok": True, "id": uid,
                "message": "Summary ingested from web player (score %s)." % payload.get("score"),
            }),
        }
        self._store().save(uid, meta, b"", verdict, {"summary": payload})
        print("webhook: web summary ingest id=%s score=%s" % (uid, payload.get("score")), file=sys.stderr)
        self._json(200, {"ok": True, "id": uid, "verdict": verdict["label"]})

    # -- chunked / resumable upload (R7) -----------------------------------
    def _handle_chunk_post(self, path: str, query: str, body: bytes):
        rest = path[len("/webhook/chunk/"):]
        if rest == "start":
            return self._chunk_start()
        if rest.endswith("/finalize"):
            uid = rest[:-len("/finalize")]
            if not UPLOAD_ID_RE.fullmatch(uid):
                return self._json(404, {"ok": False, "error": "not found"})
            return self._chunk_finalize(uid, query)
        if "/" in rest:
            uid, idx = rest.rsplit("/", 1)
            if UPLOAD_ID_RE.fullmatch(uid) and idx.isdigit():
                return self._chunk_put(uid, int(idx), body)
        return self._json(404, {"error": "not found"})

    def _chunk_start(self):
        name = self.headers.get("X-QaLens-Sal-Name") or "session.sal"
        size_hdr = self.headers.get("X-QaLens-Sal-Size") or ""
        count_hdr = self.headers.get("X-QaLens-Chunk-Count") or ""
        digest = self.headers.get("X-QaLens-Digest") or ""
        try:
            size = int(size_hdr)
            count = int(count_hdr)
        except ValueError:
            return self._json(400, {"ok": False, "error": "invalid Sal-Size or Chunk-Count"})
        expected_count = (size + CHUNK_SIZE_BYTES - 1) // CHUNK_SIZE_BYTES if size > 0 else 0
        if size <= 0 or size > MAX_REQUEST_BYTES or count != expected_count:
            return self._json(400, {"ok": False, "error": "Sal-Size or Chunk-Count is outside supported limits"})
        if len(name) > 255 or len(digest) > 4096:
            return self._json(400, {"ok": False, "error": "upload name or digest is too long"})
        # Idempotent by name+size+digest: re-starting the same upload resumes it.
        existing = self._store().find_chunk_upload(name, size, digest)
        if existing:
            return self._json(200, {"ok": True, "resumed": True, "uploadId": existing})
        uid = "%d-%s" % (time.time_ns(), chunk_upload_key(name, size, digest))
        self._store().save_chunk_meta(uid, {
            "uploadId": uid,
            "name": name,
            "size": size,
            "chunkCount": count,
            "digest": digest,
            "createdAt": int(time.time() * 1000),
            "headers": self._qalens_headers(),
        })
        return self._json(200, {"ok": True, "uploadId": uid})

    def _chunk_put(self, uid: str, index: int, body: bytes):
        meta = self._store().chunk_meta(uid)
        if meta is None:
            return self._json(404, {"ok": False, "error": "unknown upload id"})
        crc_hex = (self.headers.get("X-QaLens-Chunk-Crc32") or "").strip().lower()
        size_hdr = self.headers.get("X-QaLens-Chunk-Size") or ""
        try:
            expected_crc = int(crc_hex, 16)
            expected_size = int(size_hdr)
        except ValueError:
            return self._json(409, {"ok": False, "error": "bad chunk crc/size headers"})
        count = int(meta.get("chunkCount") or 0)
        total_size = int(meta.get("size") or 0)
        if index < 0 or index >= count:
            return self._json(404, {"ok": False, "error": "chunk index outside upload"})
        required_size = min(CHUNK_SIZE_BYTES, total_size - index * CHUNK_SIZE_BYTES)
        if expected_size != required_size or len(body) != required_size:
            return self._json(409, {"ok": False, "error": "chunk size mismatch"})
        if zlib.crc32(body) != expected_crc:
            return self._json(409, {"ok": False, "error": "chunk crc mismatch"})
        if not self._store().write_chunk(uid, index, body):
            return self._json(400, {"ok": False, "error": "invalid upload id or chunk index"})
        return self._json(200, {"ok": True, "received": index})

    def _chunk_finalize(self, uid: str, query: str):
        meta = self._store().chunk_meta(uid)
        if meta is None:
            return self._json(404, {"ok": False, "error": "unknown upload id"})
        try:
            count = int(meta.get("chunkCount") or 0)
            expected_size = int(meta.get("size") or 0)
        except (TypeError, ValueError):
            return self._json(400, {"ok": False, "error": "invalid stored upload metadata"})
        if not 1 <= count <= (MAX_REQUEST_BYTES + CHUNK_SIZE_BYTES - 1) // CHUNK_SIZE_BYTES:
            return self._json(400, {"ok": False, "error": "invalid stored chunk count"})
        received = self._store().received_chunks(uid)
        missing = [i for i in range(count) if i not in received]
        if missing:
            return self._json(409, {"ok": False, "error": "missing chunk %d" % missing[0]})
        parts = []
        for i in range(count):
            data = self._store().read_chunk(uid, i)
            if data is None:
                return self._json(409, {"ok": False, "error": "missing chunk %d" % i})
            parts.append(data)
        payload = b"".join(parts)
        if len(payload) != expected_size or expected_size > MAX_REQUEST_BYTES:
            return self._json(409, {"ok": False, "error": "assembled upload size mismatch"})
        name = meta.get("name") or self.headers.get("X-QaLens-Sal-Name") or "session.sal"
        # Same parse + verdict + store path as the multipart /webhook upload. Completed or
        # permanently malformed uploads no longer leave staging chunks behind.
        try:
            parsed = parse_sal(payload)
        except ValueError as exc:
            self._store().delete_chunk_upload(uid)
            return self._json(400, {"ok": False, "error": str(exc)})
        return self._ingest_sal_bytes(payload, name, query, source="mobile", parsed=parsed,
                                      cleanup_chunk_id=uid)


# ---------------------------------------------------------------------------
# Dashboard (single HTML page; polls /api/uploads)
# ---------------------------------------------------------------------------

def dashboard_html(store: Store) -> str:
    uploads = store.list()
    cards = []
    for u in uploads:
        score = u.get("score")
        v = u.get("verdict") or {}
        sev = v.get("severity") or "ok"
        app = u.get("app") or {}
        cards.append('''
        <article class="card" data-id="%s">
          <div class="card-top">
            <span class="sev sev-%s">%s</span>
            <span class="score">%s</span>
          </div>
          <h3>%s</h3>
          <div class="meta">%s · %s</div>
          <div class="meta muted">%s · %s · %s</div>
          <div class="meta">%s</div>
          <div class="card-actions">
            <button onclick="detail('%s')">Verdict</button>
            <a class="btn" href="/uploads/%s/download" download>⬇ .sal</a>
            <button class="danger" onclick="del('%s')">✕</button>
          </div>
        </article>''' % (
            u["id"], sev, sev, ("%s" % score if score is not None else "—"),
            esc(app.get("name") or u.get("name") or "session"),
            esc(app.get("version") or ""), esc(u.get("environment") or "—"),
            esc(u.get("device") or "web"), esc(u.get("source") or "?"),
            fmt_time(u.get("receivedAt")),
            esc(u.get("name") or ""),
            u["id"], u["id"], u["id"]))
    grid = "".join(cards) if cards else '<div class="empty">No uploads yet — send one! (see the guide below)</div>'
    return DASHBOARD.replace("__CARDS__", grid)


DASHBOARD = """<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<title>QaLens Mock Backend</title>
<style>
:root { --bg:#070a12; --surface:#0e1421; --surface2:#161e30; --line:rgba(148,173,215,.14);
  --text:#eef2f9; --muted:#8694ab; --accent:#6d9eff; --ok:#3ddc84; --warn:#fbbf24; --err:#ff6b6b; }
* { box-sizing:border-box; }
body { margin:0; background:radial-gradient(1100px 520px at 85% -12%, rgba(59,102,241,.16), transparent 60%),
  radial-gradient(900px 480px at -10% 110%, rgba(34,70,160,.14), transparent 55%), var(--bg);
  color:var(--text); font:14px/1.5 "Inter",-apple-system,"Segoe UI",Roboto,sans-serif; min-height:100vh; }
header { display:flex; align-items:center; gap:14px; padding:14px 24px; border-bottom:1px solid var(--line);
  position:sticky; top:0; background:rgba(7,10,18,.8); backdrop-filter:blur(14px); }
.brand { display:flex; align-items:center; gap:9px; font-weight:700; font-size:16px; }
.brand-mark { width:26px; height:26px; border-radius:8px; display:inline-flex; align-items:center; justify-content:center;
  background:linear-gradient(135deg,var(--accent),#8b7bff); color:#fff; font-size:13px; }
.brand-sub { color:var(--muted); font-weight:500; font-size:12.5px; }
.spacer { flex:1; }
.live { color:var(--ok); font-size:12px; display:flex; gap:6px; align-items:center; }
.live::before { content:""; width:8px; height:8px; border-radius:50%; background:var(--ok); box-shadow:0 0 8px var(--ok); }
main { max-width:1180px; margin:0 auto; padding:22px 24px 60px; }
h1 { font-size:20px; letter-spacing:-.3px; margin:0 0 4px; }
.sub { color:var(--muted); font-size:13px; margin-bottom:18px; }
.grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(240px,1fr)); gap:13px; }
.card { background:var(--surface); border:1px solid var(--line); border-radius:14px; padding:14px 16px;
  box-shadow:0 12px 30px rgba(0,0,0,.35); }
.card-top { display:flex; justify-content:space-between; align-items:center; margin-bottom:8px; }
.sev { font-size:10px; font-weight:800; text-transform:uppercase; letter-spacing:.6px; border-radius:6px; padding:2px 8px; }
.sev-ok { background:rgba(61,220,132,.14); color:var(--ok); }
.sev-warning { background:rgba(251,191,36,.14); color:var(--warn); }
.sev-critical { background:rgba(255,107,107,.14); color:var(--err); }
.score { font-weight:800; font-variant-numeric:tabular-nums; }
.card h3 { margin:0 0 4px; font-size:14.5px; letter-spacing:-.1px; }
.meta { font-size:12px; color:var(--text); }
.meta.muted { color:var(--muted); }
.card-actions { display:flex; gap:7px; margin-top:12px; }
button, .btn { border:1px solid var(--line); background:var(--surface2); color:var(--text); border-radius:9px;
  padding:5px 12px; font-size:12px; font-weight:600; cursor:pointer; text-decoration:none; display:inline-block; }
button:hover, .btn:hover { border-color:var(--accent); }
button.danger { color:var(--err); }
.empty { color:var(--muted); text-align:center; padding:60px 10px; border:1px dashed var(--line); border-radius:16px; }
section.guide { margin-top:26px; background:var(--surface); border:1px solid var(--line); border-radius:14px; padding:16px 20px; }
section.guide h2 { margin:0 0 8px; font-size:13px; text-transform:uppercase; letter-spacing:1px; color:var(--muted); }
section.guide code { background:var(--surface2); border-radius:6px; padding:2px 7px; font-size:12px; font-family:ui-monospace,Menlo,monospace; }
section.guide li { margin:4px 0; font-size:13px; }
#detail { position:fixed; inset:0; background:rgba(0,0,0,.6); backdrop-filter:blur(4px);
  display:flex; align-items:center; justify-content:center; z-index:10; }
#detail.hidden { display:none; }
#detail .panel { background:var(--surface); border:1px solid var(--line); border-radius:16px;
  max-width:720px; width:calc(100% - 40px); max-height:84vh; overflow:auto; padding:20px 24px; }
#detail h2 { margin:0 0 12px; font-size:16px; }
#detail pre { background:var(--surface2); border-radius:10px; padding:12px; font-size:12px; overflow:auto; white-space:pre-wrap; }
#detail .row { display:flex; justify-content:space-between; gap:12px; font-size:13px; padding:3px 0; }
#detail .row span { color:var(--muted); }
</style></head>
<body>
<header>
  <span class="brand"><span class="brand-mark">◉</span>QaLens<span style="color:var(--muted)"> Mock Backend</span></span>
  <span class="brand-sub">webhook receiver · dashboard</span>
  <span class="spacer"></span>
  <span class="live">listening</span>
</header>
<main>
  <h1>Received sessions</h1>
  <div class="sub">Everything the mobile app (Control Room webhook) or the web player ("Send to backend") POSTs lands here. Click a card for the mock AI verdict. Auto-refreshes every 4s.</div>
  <div class="grid" id="grid">__CARDS__</div>
  <section class="guide">
    <h2>Point your hooks here</h2>
    <ul>
      <li><b>Android (emulator):</b> <code>adb reverse tcp:8000 tcp:8000</code> then Control Room → Webhook → <code>http://127.0.0.1:8000/webhook</code> → <b>Test endpoint</b>.</li>
      <li><b>Android (device):</b> use your Mac's LAN IP, e.g. <code>http://192.168.1.20:8000/webhook</code>.</li>
      <li><b>Web player:</b> open Mission Control → ⚙ Settings → set <b>Backend URL</b> to <code>http://127.0.0.1:8000</code> → open a .sal → <b>⇪ Send to backend</b>.</li>
      <li><b>curl:</b> <code>curl -F file=@web/sample.sal -H "X-QaLens-App: Demo" http://127.0.0.1:8000/webhook</code></li>
    </ul>
  </section>
</main>
<div id="detail" class="hidden"><div class="panel" id="detailBody"></div></div>
<script>
setInterval(() => location.reload(), 4000);
async function detail(id) {
  const r = await fetch("/api/uploads/" + id); const d = await r.json();
  const v = d.verdict || {};
  document.getElementById("detailBody").innerHTML =
    "<h2>Mock AI verdict — " + (d.name || id) + "</h2>" +
    "<div class='row'><span>Severity</span><b>" + (v.severity || "?") + "</b></div>" +
    "<div class='row'><span>Label</span><b>" + (v.label || "?") + "</b></div>" +
    "<div class='row'><span>Score</span><b>" + (v.score == null ? "—" : v.score + "/100") + "</b></div>" +
    "<div class='row'><span>Likely owner</span><b>" + (v.likelyOwner || "—") + "</b></div>" +
    "<div class='row'><span>Confidence</span><b>" + (v.confidence || "—") + "</b></div>" +
    "<p>" + (v.summary || "") + "</p>" +
    "<pre>" + JSON.stringify({headers: d.headers, query: d.query, sessionId: d.sessionId, stats: d.analysis ? d.analysis.stats : null}, null, 2) + "</pre>" +
    "<p style='text-align:right'><button onclick='closeDetail()'>Close</button></p>";
  document.getElementById("detail").classList.remove("hidden");
}
function closeDetail() { document.getElementById("detail").classList.add("hidden"); }
async function del(id) {
  if (!confirm("Delete upload " + id + "?")) return;
  await fetch("/api/uploads/" + id, {method: "DELETE"}); location.reload();
}
</script>
</body></html>"""


def esc(s):
    return (str(s or "")).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


def fmt_time(ms):
    if not ms:
        return "—"
    return time.strftime("%H:%M:%S", time.localtime(ms / 1000))


def make_server(host=DEFAULT_HOST, port=DEFAULT_PORT, data_dir="backend/data"):
    server = ThreadingHTTPServer((host, port), Handler)
    server.store = Store(Path(data_dir))
    return server


def main():
    parser = argparse.ArgumentParser(description="QaLens mock webhook backend")
    parser.add_argument(
        "--host",
        default=DEFAULT_HOST,
        help="interface to bind (default: 127.0.0.1; use 0.0.0.0 only on a trusted dev network)",
    )
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--data-dir", default=str(Path(__file__).resolve().parent / "data"))
    args = parser.parse_args()
    server = make_server(args.host, args.port, args.data_dir)
    port = server.server_address[1]
    print("QaLens mock backend listening on http://%s:%d" % (args.host, port))
    print("  dashboard:   http://127.0.0.1:%d/" % port)
    print("  webhook:     http://127.0.0.1:%d/webhook" % port)
    print("  data dir:    %s" % args.data_dir)
    print("  Ctrl-C to stop")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print()
        print("bye")
        server.shutdown()


if __name__ == "__main__":
    main()
