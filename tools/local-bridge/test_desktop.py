import importlib.util
import io
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from http.server import BaseHTTPRequestHandler, HTTPServer
import zipfile

spec = importlib.util.spec_from_file_location("bridge", Path(__file__).with_name("server.py"))
bridge = importlib.util.module_from_spec(spec); spec.loader.exec_module(bridge)
from desktop import valid_archive
from workbench import Workbench

class DesktopTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.bench = Workbench(self.temp.name, "fake-adb")
        self.server = bridge.BridgeServer(("127.0.0.1", 0), None, None, self.bench)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.bench.connection = {"serial": "synthetic", "package": "com.example.test"}
    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.bench.close(self.server); self.temp.cleanup()
    def request(self, path, data=None, headers=None):
        request = Request(f"http://127.0.0.1:{self.server.server_port}{path}", data=data, headers=headers or {})
        try: response = urlopen(request, timeout=3)
        except HTTPError as error: response = error
        with response: return response.code, response.read(), response.headers
    def test_viewer_sources_are_shared_and_paths_closed(self):
        code, css, headers = self.request("/style.css")
        self.assertEqual(code, 200)
        self.assertIn("text/css", headers["Content-Type"])
        self.assertIn(b":root{", css)
        self.assertNotIn(b"<html", css)
        code, script, _ = self.request("/recording-transfer.js")
        self.assertEqual(code, 200)
        self.assertEqual(script, Path(__file__).with_name("recording-transfer.js").read_bytes())
        for name in ["index-v2.html", "sal.js", "app-v2.js", "app.js"]:
            code, data, headers = self.request("/web/" + name + "?desktop")
            self.assertEqual(code, 200)
            self.assertEqual(data, (Path(__file__).parents[2] / "web" / name).read_bytes())
            self.assertIn("frame-ancestors 'self'", headers["Content-Security-Policy"])
        for path in ["/web/../server.py", "/web/tools/sal_report.js", "/web/%2e%2e/server.py"]:
            self.assertEqual(self.request(path)[0], 404)
    def test_adb_tasks_reject_stale_connections_and_arbitrary_shell(self):
        with patch.object(self.bench, "adb_call", return_value="") as adb:
            self.assertRaises(ValueError, self.bench.desktop.task, {"action": "back", "connectionId": "old"})
            self.bench.desktop.task({"action": "back", "connectionId": self.bench.connection_id})
            adb.assert_called_once_with(["shell", "input", "keyevent", "4"], "synthetic")
            self.assertRaises(ValueError, self.bench.desktop.task, {"action": "shell", "connectionId": self.bench.connection_id})
            self.assertRaises(ValueError, self.bench.desktop.task, {"action": "pull", "remote": "/data/secret", "connectionId": self.bench.connection_id})
    def test_mirror_uses_connected_serial_and_owned_process_cleanup(self):
        from unittest.mock import Mock
        process = Mock(); process.poll.return_value = None
        with patch("desktop.shutil.which", return_value="/synthetic/scrcpy"), patch("desktop.subprocess.Popen", return_value=process) as spawn:
            result = self.bench.desktop.task({"action": "mirror", "connectionId": self.bench.connection_id})
            self.assertTrue(result["ok"])
            self.assertEqual(spawn.call_args.args[0], ["/synthetic/scrcpy", "-s", "synthetic", "--window-title", "QaLens mirror"])
            self.assertEqual(spawn.call_args.kwargs["env"]["ADB"], "fake-adb")
            self.bench.desktop.stop_mirror(); process.terminate.assert_called_once(); process.wait.assert_called_once()
        with patch("desktop.native_scrcpy", return_value=None):
            self.assertRaises(ValueError, self.bench.desktop.task, {"action": "mirror", "connectionId": self.bench.connection_id})

    def test_push_requires_auth_connection_and_bounded_safe_filename(self):
        self.assertEqual(self.request("/api/file/push", b"fixture")[0], 401)
        headers = {"X-Qalens-Session": self.bench.session, "X-Qalens-Connection": self.bench.connection_id, "X-Qalens-File": "fixture.json"}
        with patch.object(self.bench, "adb_call", return_value="") as adb:
            code, body, _ = self.request("/api/file/push", b'{"synthetic":true}', headers)
            self.assertEqual(code, 200); self.assertIn(b"Pushed", body)
            self.assertEqual(adb.call_args.args[0][0], "push")
            self.assertEqual(adb.call_args.args[0][-1], "/sdcard/Download/fixture.json")
            self.assertEqual(list(self.bench.desktop.transfers.iterdir()), [])
            headers["X-Qalens-File"] = "../secret"
            self.assertEqual(self.request("/api/file/push", b"x", headers)[0], 400)
    def test_recording_receive_is_explicit_private_deduplicated_and_authenticated(self):
        archive = Path(self.temp.name) / "synthetic.sal"
        with zipfile.ZipFile(archive, "w") as zip:
            zip.writestr("manifest.json", '{"formatVersion":1}')
        payload = archive.read_bytes()
        class Device(BaseHTTPRequestHandler):
            def log_message(self, *_): pass
            def do_GET(inner):
                if inner.headers.get("Authorization") != "Bearer synthetic-token":
                    inner.send_error(401); return
                inner.send_response(200); inner.send_header("Content-Length", str(len(payload))); inner.end_headers(); inner.wfile.write(payload)
        device = HTTPServer(("127.0.0.1", 0), Device)
        threading.Thread(target=device.serve_forever, daemon=True).start()
        self.server.device_port = device.server_port; self.server.token = "synthetic-token"
        body = {"name": "session_1.sal", "connectionId": self.bench.connection_id}
        try:
            self.assertEqual(self.bench.desktop.library()["items"], [])
            result = self.bench.desktop.receive(self.server, body)
            self.assertFalse(result["duplicate"])
            self.assertTrue(self.bench.desktop.receive(self.server, body)["duplicate"])
            target = self.bench.desktop.recording(result["id"])
            self.assertEqual(target.read_bytes(), payload)
            self.assertEqual(target.stat().st_mode & 0o777, 0o600)
            self.assertEqual(self.request("/api/recordings/file?id=" + result["id"])[0], 401)
            code, actual, _ = self.request("/api/recordings/file?id=" + result["id"], headers={"X-Qalens-Session": self.bench.session})
            self.assertEqual(code, 200); self.assertEqual(actual, payload)
            self.assertRaises(ValueError, self.bench.desktop.receive, self.server, dict(body, name="../../secret"))
        finally: device.shutdown(); device.server_close()
    def test_archive_budget_rejects_traversal_and_duplicates(self):
        file = Path(self.temp.name) / "hostile.sal"
        with zipfile.ZipFile(file, "w") as zip:
            zip.writestr("manifest.json", "{}"); zip.writestr("../secret", "unsafe")
        self.assertRaises(ValueError, valid_archive, file)

    def test_failed_recording_receive_removes_partial_and_keeps_library_empty(self):
        from http.client import IncompleteRead
        class BrokenResponse(io.BytesIO):
            def read(self, *_): raise IncompleteRead(b"partial", 100)
        body = {"name": "session_1.sal", "connectionId": self.bench.connection_id}
        for response in [io.BytesIO(b"not a ZIP"), BrokenResponse()]:
            with patch("desktop.urlopen", return_value=response):
                self.assertRaises(ValueError, self.bench.desktop.receive, self.server, body)
            self.assertEqual(list(self.bench.desktop.recordings.iterdir()), [])

if __name__ == "__main__": unittest.main()
