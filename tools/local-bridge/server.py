#!/usr/bin/env python3
"""Loopback PC UI/API -> adb forward -> explicitly enabled QaLens device bridge.
Python standard library only. No recordings, tokens or application data are saved.
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
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

MAX_BODY = 16_384
MAX_RESPONSE = 4 * 1024 * 1024


class BridgeServer(HTTPServer):
    def __init__(self, address, device_port, token):
        super().__init__(address, Handler)
        self.device_port = device_port
        self.token = token


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
        self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'")
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
        assets = {"/": ("index.html", "text/html; charset=utf-8"), "/app.js": ("app.js", "text/javascript; charset=utf-8"), "/style.css": ("style.css", "text/css; charset=utf-8")}
        if self.command == "GET" and self.path in assets:
            name, mime = assets[self.path]
            return self.reply(200, Path(__file__).with_name(name).read_bytes(), mime)
        endpoints = {("GET", "/api/snapshot"): "/v1/snapshot", ("GET", "/api/events"): "/v1/events", ("POST", "/api/command"): "/v1/command"}
        route = endpoints.get((self.command, self.path))
        if not route:
            return self.reply(404, {"ok": False, "error": "Unknown endpoint"})
        if not hmac.compare_digest(self.headers.get("Authorization", "").encode(), f"Bearer {self.server.token}".encode()):
            return self.reply(401, {"ok": False, "error": "Enter the device pairing token"})
        if len(self.headers.get_all("Content-Length", [])) > 1 or self.headers.get("Transfer-Encoding"):
            return self.reply(400, {"ok": False, "error": "Duplicate lengths/chunking unsupported"})
        try:
            size = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            return self.reply(400, {"ok": False, "error": "Invalid content length"})
        if not 0 <= size <= MAX_BODY:
            return self.reply(413, {"ok": False, "error": "Command too large"})
        body = None
        if self.command == "POST":
            if not self.headers.get("Content-Type", "").startswith("application/json"):
                return self.reply(415, {"ok": False, "error": "Use application/json"})
            try:
                body = self.rfile.read(size)
                if len(body) != size or not isinstance(json.loads(body), dict):
                    raise ValueError()
            except (ValueError, TimeoutError):
                return self.reply(400, {"ok": False, "error": "Invalid command JSON"})
        headers = {"Authorization": f"Bearer {self.server.token}", "Content-Type": "application/json"}
        request = Request(f"http://127.0.0.1:{self.server.device_port}{route}", data=body, headers=headers, method=self.command)
        try:
            try:
                response = urlopen(request, timeout=5)
            except HTTPError as error:
                response = error
            with response:
                data = response.read(MAX_RESPONSE + 1)
                if len(data) > MAX_RESPONSE:
                    return self.reply(502, {"ok": False, "error": "Device response exceeds bridge limit"})
                json.loads(data)
                return self.reply(response.code, data)
        except (URLError, TimeoutError, ConnectionError, ValueError):
            return self.reply(502, {"ok": False, "error": "Device unavailable. Start its bridge, check token/adb, then refresh. Commands are never retried automatically."})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="adb device serial (required if more than one device is connected)")
    parser.add_argument("--port", type=int, default=8765, help="PC UI port")
    parser.add_argument("--device-port", type=int, default=8766, help="QaLens device port")
    parser.add_argument("--adb", default=shutil.which("adb"), help="adb executable")
    parser.add_argument("--no-adb", action="store_true", help="Use an existing forward at --device-port")
    args = parser.parse_args()
    token = os.environ.get("QALENS_BRIDGE_TOKEN") or getpass.getpass("Device pairing token: ")
    if not re.fullmatch(r"[A-Za-z0-9_-]{24,128}", token):
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
        if not args.no_adb:
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
        with BridgeServer(("127.0.0.1", args.port), device_port, token) as server:
            print(f"QaLens PC inspector: http://127.0.0.1:{server.server_port} (Ctrl-C stops and removes this forward)", flush=True)
            server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        if forward:
            subprocess.run(adb + ["forward", "--remove", f"tcp:{forward}"], check=False, stdout=subprocess.DEVNULL)


if __name__ == "__main__":
    main()
