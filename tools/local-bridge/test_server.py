import importlib.util
import json
import os
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from unittest.mock import patch
import device_http

spec = importlib.util.spec_from_file_location("bridge", Path(__file__).with_name("server.py"))
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)
TOKEN = "synthetic-token-0123456789"

class Device(BaseHTTPRequestHandler):
    def log_message(self, *_): pass
    def do_GET(self):
        data = json.dumps({"ok": True, "path": self.path, "authorized": self.headers.get("Authorization") == f"Bearer {TOKEN}", "label": "<script>host text</script>"}).encode()
        self.send_response(200); self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)
    def do_POST(self):
        data = self.rfile.read(int(self.headers["Content-Length"]))
        self.send_response(409); self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)

class BridgeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.device = HTTPServer(("127.0.0.1", 0), Device)
        cls.server = bridge.BridgeServer(("127.0.0.1", 0), cls.device.server_port, TOKEN)
        for server in (cls.device, cls.server): threading.Thread(target=server.serve_forever, daemon=True).start()
    @classmethod
    def tearDownClass(cls):
        for server in (cls.server, cls.device): server.shutdown(); server.server_close()
    def call(self, path, body=None, headers=None):
        request = Request(f"http://127.0.0.1:{self.server.server_port}{path}", data=body, headers=headers or {})
        try: result = urlopen(request, timeout=3)
        except HTTPError as error: result = error
        with result: return result.code, result.read(), result.headers
    def test_tree_proxy_and_asset_security(self):
        status, data, headers = self.call("/api/snapshot", headers={"Authorization": f"Bearer {TOKEN}"})
        self.assertEqual(status, 200); self.assertTrue(json.loads(data)["authorized"])
        self.assertEqual(headers["Cache-Control"], "no-store")
        self.assertEqual(self.call("/../server.py")[0], 404)
        self.assertIn(b"textContent", self.call("/app.js")[1])
        self.assertNotIn(b"innerHTML", self.call("/app.js")[1])
    def test_auth_origin_and_dns_rebinding(self):
        self.assertEqual(self.call("/api/events")[0], 401)
        self.assertEqual(self.call("/api/events", headers={"Authorization": f"Bearer {TOKEN}", "Origin": "https://evil.example"})[0], 403)
        self.assertEqual(self.call("/", headers={"Host": "evil.example"})[0], 403)
    def test_commands_keep_device_errors_and_require_json(self):
        headers = {"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"}
        self.assertEqual(self.call("/api/command", b'{"ok":false,"error":"ambiguous"}', headers)[0], 409)
        self.assertEqual(self.call("/api/command", b"[]", headers)[0], 400)
        self.assertEqual(self.call("/api/command", b"x" * 16385, headers)[0], 413)
        self.assertEqual(self.call("/api/command", b"{}", {"Authorization": f"Bearer {TOKEN}"})[0], 415)
    def test_selector_routes_preserve_auth_and_device_errors(self):
        headers = {"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"}
        code, data, _ = self.call("/api/selection", headers=headers)
        self.assertEqual(code, 200); self.assertEqual(json.loads(data)["path"], "/v1/selection")
        for route in ("selectors", "query"):
            self.assertEqual(self.call("/api/" + route, b'{"xpath":"//node"}', headers)[0], 409)
            self.assertEqual(self.call("/api/" + route, b"{}")[0], 401)
    def test_disconnect_is_actionable_and_not_retried(self):
        port = self.server.device_port; self.server.device_port = 1
        try: self.assertEqual(self.call("/api/snapshot", headers={"Authorization": f"Bearer {TOKEN}"})[0], 502)
        finally: self.server.device_port = port
    def test_company_proxy_settings_never_receive_loopback_device_credentials(self):
        received = []
        class Proxy(BaseHTTPRequestHandler):
            def log_message(self, *_): pass
            def do_GET(self):
                received.append(self.path)
                self.send_response(502); self.send_header("Content-Length", "0"); self.end_headers()
        proxy = HTTPServer(("127.0.0.1", 0), Proxy)
        threading.Thread(target=proxy.serve_forever, daemon=True).start()
        try:
            with patch.dict(os.environ, {"http_proxy": f"http://127.0.0.1:{proxy.server_port}", "no_proxy":""}, clear=True):
                request = Request(f"http://127.0.0.1:{self.server.server_port}/api/snapshot", headers={"Authorization":f"Bearer {TOKEN}"})
                with device_http.urlopen(request, timeout=3) as response:
                    self.assertEqual(response.code, 200); self.assertTrue(json.loads(response.read())["authorized"])
            self.assertEqual(received, [], "A proxy saw the PC or forwarded phone request")
        finally: proxy.shutdown(); proxy.server_close()

if __name__ == "__main__": unittest.main()
