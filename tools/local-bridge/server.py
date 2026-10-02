#!/usr/bin/env python3
"""Loopback PC UI/API -> adb forward -> explicitly enabled QaLens device bridge.
Python standard library only. Component files require explicit Save; tokens stay in memory.
"""
import argparse
import getpass
import hmac
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import sys
sys.path.insert(0, str(Path(__file__).parent))
from workbench import Workbench, MAX_DOCUMENT
from desktop import WEB_ROOT, WEB_ASSETS, MAX_TRANSFER
from urllib.parse import urlsplit, parse_qs
import mimetypes
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

MAX_BODY = 16_384
MAX_RESPONSE = 4 * 1024 * 1024


class BridgeServer(ThreadingHTTPServer):
    daemon_threads = True
    def __init__(self, address, device_port, token, workbench=None):
        super().__init__(address, Handler)
        self.device_port = device_port
        self.token = token
        self.workbench = workbench


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def setup(self):
        super().setup()
        self.connection.settimeout(5)

    def log_message(self, *_):
        pass  # Never log payloads or credentials.

    def reply(self, status, payload, content_type="application/json; charset=utf-8"):
        data = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.send_header("X-Content-Type-Options", "nosniff")
        # Existing replay permits explicit user-configured backend sends; shell APIs remain same-origin.
        connect = "'self' http: https:" if self.path.startswith("/web/") else "'self'"
        self.send_header("Content-Security-Policy", f"default-src 'self'; script-src 'self'; style-src 'self'; connect-src {connect}; frame-src 'self'; media-src 'self' blob:; img-src 'self' blob: data:; font-src 'self' data:; style-src-attr 'unsafe-inline'; frame-ancestors 'self'")
        self.end_headers()
        self.wfile.write(data)
        self.close_connection = True

    def do_GET(self):
        self.handle_request()

    def do_POST(self):
        self.handle_request()

    def handle_request(self):
        self.connection.settimeout(5)
        port = self.server.server_port
        hosts = {f"127.0.0.1:{port}", f"localhost:{port}"}
        if self.headers.get("Host") not in hosts:
            return self.reply(403, {"ok": False, "error": "Loopback Host required"})
        if self.headers.get("Origin") not in {None, f"http://127.0.0.1:{port}", f"http://localhost:{port}"}:
            return self.reply(403, {"ok": False, "error": "External browser origin denied"})
        parsed = urlsplit(self.path)
        if self.command == "GET" and parsed.path.startswith("/web/"):
            name = parsed.path.removeprefix("/web/")
            if name not in WEB_ASSETS: return self.reply(404, {"ok": False, "error": "Unknown viewer asset"})
            return self.reply(200, (WEB_ROOT / name).read_bytes(), mimetypes.guess_type(name)[0] or "application/octet-stream")
        assets = {"/": ("index.html", "text/html; charset=utf-8"), "/app.js": ("app.js", "text/javascript; charset=utf-8"), "/style.css": ("style.css", "text/css; charset=utf-8")}
        if self.command == "GET" and self.path in assets:
            name, mime = assets[self.path]
            return self.reply(200, Path(__file__).with_name(name).read_bytes(), mime)
        bench = self.server.workbench
        if bench and self.command == "GET" and self.path == "/api/bootstrap":
            return self.reply(200, {"ok": True, "session": bench.session})
        endpoints = {("GET", "/api/recordings/device"): "/v1/recordings", ("GET", "/api/snapshot"): "/v1/snapshot", ("GET", "/api/events"): "/v1/events", ("POST", "/api/command"): "/v1/command", ("POST", "/api/component"): "/v1/component", ("GET", "/api/inbox"): "/v1/components/inbox"}
        route = endpoints.get((self.command, self.path))
        binary = parsed.path == "/api/recordings/file"
        if not route and not (bench and (self.path in WORKBENCH_ROUTES or binary)):
            return self.reply(404, {"ok": False, "error": "Unknown endpoint"})
        desktop = bench and hmac.compare_digest(self.headers.get("X-Qalens-Session", "").encode(), bench.session.encode())
        device = self.server.token and hmac.compare_digest(self.headers.get("Authorization", "").encode(), f"Bearer {self.server.token}".encode())
        if not (desktop or device):
            return self.reply(401, {"ok": False, "error": "Enter the device pairing token"})
        if binary and self.command == "GET" and bench:
            try:
                path = bench.desktop.recording(parse_qs(parsed.query).get("id", [""])[0])
                self.send_response(200)
                self.send_header("Content-Type", "application/octet-stream")
                self.send_header("Content-Length", str(path.stat().st_size))
                self.send_header("Cache-Control", "no-store")
                self.send_header("Connection", "close")
                self.end_headers()
                with path.open("rb") as stream: shutil.copyfileobj(stream, self.wfile, 128 * 1024)
                self.close_connection = True
            except ValueError as error: return self.reply(400, {"ok": False, "error": str(error)})
            return
        if len(self.headers.get_all("Content-Length", [])) > 1 or self.headers.get("Transfer-Encoding"):
            return self.reply(400, {"ok": False, "error": "Duplicate lengths/chunking unsupported"})
        try:
            size = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            return self.reply(400, {"ok": False, "error": "Invalid content length"})
        limit = MAX_TRANSFER if self.path == "/api/file/push" else MAX_DOCUMENT + 4096 if self.path == "/api/import" else MAX_BODY
        if not 0 <= size <= limit:
            return self.reply(413, {"ok": False, "error": "Command too large"})
        if self.path == "/api/file/push" and self.command == "POST" and bench:
            try:
                data = self.rfile.read(size)
                if len(data) != size: raise ValueError("Incomplete file upload")
                return self.reply(200, bench.desktop.push({"name": self.headers.get("X-Qalens-File", ""),
                    "connectionId": self.headers.get("X-Qalens-Connection", "")}, data))
            except (ValueError, OSError) as error: return self.reply(400, {"ok": False, "error": str(error)[:256]})
        body = None
        if self.command == "POST":
            if not self.headers.get("Content-Type", "").startswith("application/json"):
                return self.reply(415, {"ok": False, "error": "Use application/json"})
            try:
                body = self.rfile.read(size)
                if len(body) != size or not isinstance(json.loads(body), dict):
                    raise ValueError()
            except (ValueError, TimeoutError, RecursionError):
                return self.reply(400, {"ok": False, "error": "Invalid command JSON"})
        try:
            if bench and self.path in WORKBENCH_ROUTES:
                return self.reply(200, self.workbench_request(json.loads(body) if body else {}))
            if not self.server.token or not self.server.device_port:
                return self.reply(503, {"ok": False, "error": "Connect a device first"})
            # Serialize session switching with a device read/action; never replay a command.
            from contextlib import nullcontext
            with bench.lock if bench else nullcontext():
                if desktop and self.command == "POST" and self.headers.get("X-Qalens-Connection") != bench.connection_id:
                    return self.reply(409, {"ok": False, "error": "Device connection changed; refresh the tree before selecting a target"})
                if not desktop and not hmac.compare_digest(self.headers.get("Authorization", "").encode(), f"Bearer {self.server.token}".encode()):
                    return self.reply(401, {"ok": False, "error": "Device pairing changed; connect again"})
                code, payload = self.proxy(route, body, self.command)
                if code == 200 and bench and self.path in {"/api/snapshot", "/api/recordings/device"}: payload["connectionId"] = bench.connection_id
                if code == 200 and bench and self.path == "/api/component":
                    payload = {"ok": True, "document": bench.preview(payload)}
                elif code == 200 and bench and self.path == "/api/inbox":
                    ids = []
                    for item in payload.get("transfers", [])[:10]:
                        bench.preview(item["document"])
                        ids.append(item["id"])
                    # PC memory owns the preview before acknowledgement; Save is still explicit.
                    if ids: self.proxy("/v1/components/ack", json.dumps({"ids": ids}).encode(), "POST")
                    payload = {"ok": True, "dropped": payload.get("dropped", 0), "received": len(ids),
                               "documents": list(bench.pending.values()), "omittedPreviews": bench.preview_dropped}
                return self.reply(code, payload)
        except ValueError as error:
            return self.reply(400, {"ok": False, "error": str(error)[:256]})
        except (KeyError, TypeError, RecursionError):
            return self.reply(400, {"ok": False, "error": "Invalid component, profile or request; check required fields and limits"})
        except (OSError, TimeoutError):
            return self.reply(500, {"ok": False, "error": "Local storage unavailable; nothing was confirmed saved"})

    def proxy(self, route, body, method):
        headers = {"Authorization": f"Bearer {self.server.token}", "Content-Type": "application/json"}
        request = Request(f"http://127.0.0.1:{self.server.device_port}{route}", data=body, headers=headers, method=method)
        try:
            try: response = urlopen(request, timeout=5)
            except HTTPError as error: response = error
            with response:
                data = response.read(MAX_RESPONSE + 1)
                if len(data) > MAX_RESPONSE: raise ValueError("Device response exceeds limit")
                return response.code, json.loads(data)
        except (URLError, TimeoutError, ConnectionError, ValueError):
            return 502, {"ok": False, "error": "Device unavailable. Start its bridge, check token/adb, then refresh. Commands are never retried automatically."}

    def workbench_request(self, body):
        bench = self.server.workbench
        if self.command == "GET":
            if self.path == "/api/workbench":
                with bench.lock:
                    return {"ok": True, "connected": bool(self.server.token), "connection": bench.connection,
                            "connectionId": bench.connection_id, "profiles": bench.profiles(), "dataDir": str(bench.root.resolve()),
                            "pipelines": [{"id": p["id"], "name": p.get("name", p["id"])} for p in bench.pipelines.values()],
                            "jobs": list(bench.jobs.values()), "scrcpyAvailable": bool(shutil.which("scrcpy"))}
            if self.path == "/api/recordings/local": return bench.desktop.library()
            if self.path == "/api/saved": return bench.saved()
            if self.path == "/api/previews":
                with bench.lock: return {"ok": True, "documents": list(bench.pending.values()), "omittedPreviews": bench.preview_dropped}
            raise ValueError("POST required")
        if self.path == "/api/adb": return bench.desktop.task(body)
        if self.path == "/api/recordings/receive": return bench.desktop.receive(self.server, body)
        if self.path == "/api/devices": return {"ok": True, "devices": bench.devices()}
        if self.path == "/api/packages":
            serial = body.get("serial", "")
            if not isinstance(serial, str) or not re.fullmatch(r"[A-Za-z0-9_.:\[\]-]{1,128}", serial): raise ValueError("Invalid device serial")
            rows = bench.adb_call(["shell", "pm", "list", "packages", "-3"], serial).splitlines()
            return {"ok": True, "packages": [r.removeprefix("package:") for r in rows if r.startswith("package:")][:2000]}
        if self.path == "/api/profile":
            clean = bench.save_profile(body["profile"])
            return {"ok": True, "profile": clean, "notice": "Only device/package/activity/version/port remembered. Appium reset, animation and driver options are ignored; no reset occurs."}
        if self.path == "/api/profile/delete":
            with bench.lock:
                from workbench import write_json
                write_json(bench.profiles_path, [p for p in bench.profiles() if p["id"] != body.get("id")])
            return {"ok": True}
        if self.path == "/api/connect": return {"ok": True, "connection": bench.connect(self.server, body["profile"], body.get("token"))}
        if self.path == "/api/disconnect": bench.disconnect(self.server); return {"ok": True}
        if self.path == "/api/launch":
            with bench.lock:
                current = bench.connection
                if not current or not current["activity"]: raise ValueError("Connect a profile with an activity first")
                bench.adb_call(["shell", "am", "start", "-n", current["package"] + "/" + current["activity"]], current["serial"])
            return {"ok": True}
        if self.path == "/api/import": return {"ok": True, "document": bench.preview(body["document"])}
        if self.path == "/api/save": return bench.save(body.get("hash"))
        if self.path == "/api/document": return {"ok": True, "document": bench.document(body.get("hash"))}
        if self.path == "/api/artifacts": return {"ok": True, "files": bench.artifacts(body.get("job"))}
        if self.path == "/api/artifact": return bench.artifact(body.get("job"), body.get("name"))
        if self.path == "/api/run": return {"ok": True, "job": bench.run_pipeline(body.get("pipeline"), body.get("hash"))}
        raise ValueError("Unknown operation")


WORKBENCH_ROUTES = {"/api/workbench", "/api/saved", "/api/previews", "/api/devices", "/api/packages",
                    "/api/profile", "/api/profile/delete", "/api/connect", "/api/disconnect", "/api/launch",
                    "/api/adb", "/api/file/push", "/api/recordings/local", "/api/recordings/receive", "/api/import", "/api/save", "/api/document", "/api/run", "/api/artifacts", "/api/artifact"}



def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="adb device serial (required if more than one device is connected)")
    parser.add_argument("--port", type=int, default=8765, help="PC UI port")
    parser.add_argument("--device-port", type=int, default=8766, help="QaLens device port")
    parser.add_argument("--adb", default=shutil.which("adb"), help="adb executable")
    parser.add_argument("--no-adb", action="store_true", help="Use an existing forward at --device-port")
    parser.add_argument("--gui", action="store_true", help="Start unpaired; choose a device and profile in the browser")
    parser.add_argument("--data-dir", default="~/.qalens/bridge", help="Explicitly saved components, profiles and pipeline results")
    parser.add_argument("--pipeline-config", help="Trusted local JSON pipeline configuration (never editable through HTTP)")
    args = parser.parse_args()
    token = None if args.gui else os.environ.get("QALENS_BRIDGE_TOKEN") or getpass.getpass("Device pairing token: ")
    if not args.gui and not re.fullmatch(r"[A-Za-z0-9_-]{24,128}", token):
        parser.error("Use the device's random 24–128 character token")
    if not (1024 <= args.port <= 65535 and 1024 <= args.device_port <= 65535):
        parser.error("Ports must be 1024–65535")
    def stop_on_signal(_signal, _frame):
        raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM, stop_on_signal)
    if hasattr(signal, "SIGHUP"):
        signal.signal(signal.SIGHUP, stop_on_signal)
    forward = None
    adb = []
    try:
        if args.gui:
            device_port = None
        elif not args.no_adb:
            if not args.adb:
                parser.error("Install adb or pass --adb /path/to/adb")
            rows = subprocess.check_output([args.adb, "devices"], text=True).splitlines()[1:]
            devices = [row.split()[0] for row in rows if len(row.split()) > 1 and row.split()[1] == "device"]
            serial = args.serial or (devices[0] if len(devices) == 1 else None)
            if serial not in devices:
                parser.error("Choose a connected, authorized device with --serial")
            adb = [args.adb, "-s", serial]
            forward = subprocess.check_output(adb + ["forward", "tcp:0", f"tcp:{args.device_port}"], text=True).strip()
            device_port = int(forward)
        else:
            device_port = args.device_port
        bench = Workbench(args.data_dir, args.adb, args.pipeline_config)
        with BridgeServer(("127.0.0.1", args.port), device_port, token, bench) as server:
            if forward:
                bench.owned_forward = (serial, device_port)
                forward = None
            print(f"QaLens workbench: http://127.0.0.1:{server.server_port} (Ctrl-C stops and removes this forward)", flush=True)
            try: server.serve_forever()
            finally: bench.close(server)
    except KeyboardInterrupt:
        pass
    finally:
        if forward:
            subprocess.run(adb + ["forward", "--remove", f"tcp:{forward}"], check=False, stdout=subprocess.DEVNULL)


if __name__ == "__main__":
    main()
