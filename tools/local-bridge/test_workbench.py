import copy
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import Mock, patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

sys.path.insert(0, str(Path(__file__).parent))
from workbench import Workbench, canonical, profile
from server import BridgeServer


def fixture():
    return {"schema": "qalens.component", "version": 1, "capturedAtMillis": 100, "liveNodeId": "semantics:1:42",
            "content": {"package": "com.example.app", "component": {"label": "<script>fixture</script>", "tag": "account.name", "value": "Ada", "attributes": []},
                        "tree": {"path": [{"tag": "account", "siblingIndex": 2}]}, "viewport": {"width": 1080, "height": 1920}}}


class WorkbenchTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.bench = Workbench(self.temp.name)
    def tearDown(self):
        if self.bench.worker: self.bench.worker.join(timeout=5)
        self.temp.cleanup()
    def test_hash_excludes_provenance_but_preserves_values_position_and_package(self):
        document = fixture(); original = canonical(document)["hash"]
        document["capturedAtMillis"] = 900; document["liveNodeId"] = "semantics:92:7"
        self.assertEqual(canonical(document)["hash"], original)
        integer = copy.deepcopy(document); integer["content"]["viewport"]["width"] = 1080.0
        self.assertEqual(canonical(integer)["hash"], original)
        for change in (lambda d: d["content"]["component"].update(value="Grace"),
                       lambda d: d["content"]["tree"]["path"][0].update(siblingIndex=3),
                       lambda d: d["content"].update(package="com.other.app")):
            changed = copy.deepcopy(document); change(changed)
            self.assertNotEqual(canonical(changed)["hash"], original)
    def test_preview_explicit_save_dedup_restart_import_and_corruption(self):
        document = self.bench.preview(fixture()); digest = document["hash"]
        self.assertEqual(self.bench.saved()["items"], [])
        self.assertFalse(self.bench.save(digest)["duplicate"])
        self.assertTrue(self.bench.save(digest)["duplicate"])
        self.assertEqual(len(list((Path(self.temp.name) / "components").glob("*.json"))), 1)
        restored = Workbench(self.temp.name).document(digest)
        self.assertEqual(restored["content"], fixture()["content"])
        if os.name == "posix": self.assertEqual(os.stat(self.bench.save(digest)["file"]).st_mode & 0o777, 0o600)
        with self.assertRaises(ValueError): self.bench.document("../../etc/passwd")
        altered = copy.deepcopy(restored); altered["content"]["component"]["value"] = "changed"
        with self.assertRaises(ValueError): self.bench.preview(altered)
        Path(self.bench.save(digest)["file"]).write_text(json.dumps(altered))
        with self.assertRaises(ValueError): self.bench.save(digest)
    def test_limits_invalid_schema_and_bounded_preview_queue(self):
        for invalid in ({}, {"schema":"qalens.component","version":True,"content":fixture()["content"]}):
            with self.assertRaises(ValueError): self.bench.preview(invalid)
        huge = fixture(); huge["content"]["component"]["value"] = "x" * (256 * 1024)
        with self.assertRaises(ValueError): self.bench.preview(huge)
        for index in range(15):
            document = fixture(); document["content"]["component"]["value"] = str(index); self.bench.preview(document)
        self.assertEqual(len(self.bench.pending), 10)
        self.assertEqual(self.bench.saved()["items"], [])
    def test_profile_persistence_appium_mapping_no_credentials_or_reset(self):
        value = {"name": "QA", "platformName": "Android", "appium:udid": "emulator-5560", "appium:appPackage": "com.example.app",
                 "appium:appActivity": ".MainActivity", "appium:platformVersion": "13", "token": "must-not-save",
                 "appium:noReset": False, "appium:fullReset": True, "appium:autolunch": "true"}
        saved = self.bench.save_profile(value)
        self.assertEqual(saved["package"], "com.example.app")
        text = self.bench.profiles_path.read_text()
        self.assertNotIn("must-not-save", text); self.assertNotIn("Reset", text); self.assertNotIn("autolunch", text)
        self.assertEqual(Workbench(self.temp.name).profiles()[0], saved)
        for bad in (dict(value, **{"appium:appPackage": "https://myapp.dev"}), dict(value, **{"appium:udid": "x; echo secret"}), dict(value, **{"appium:appActivity": "Com. Myapp.ui.MainActivity"})):
            with self.assertRaises(ValueError): profile(bad)
    def test_connect_owns_forward_and_launch_never_resets(self):
        server = Mock(token=None, device_port=None)
        def adb(args, serial=None):
            if args == ["devices", "-l"]: return "List of devices attached\nemulator-5560 device product:test\n"
            if args[:3] == ["shell", "getprop", "ro.build.version.release"]: return "13\n"
            if args[:3] == ["shell", "pm", "path"]: return "package:/data/app/app.apk\n"
            if args[:2] == ["forward", "tcp:0"]: return "19333\n"
            return ""
        self.bench.adb_call = Mock(side_effect=adb)
        self.bench.connect(server, {"serial":"emulator-5560","package":"com.example.app"}, "synthetic-pairing-token-0123456789")
        self.assertEqual(server.device_port, 19333)
        self.bench.disconnect(server)
        self.bench.adb_call.assert_called_with(["forward", "--remove", "tcp:19333"], "emulator-5560")
        self.assertIsNone(server.token)
        self.assertFalse(any("clear" in call.args[0] or "uninstall" in call.args[0] for call in self.bench.adb_call.call_args_list))
    def test_real_pipeline_outputs_failure_timeout_and_shutdown(self):
        config = Path(self.temp.name) / "pipelines.json"
        config.write_text(json.dumps({"pipelines": [
            {"id":"ok", "steps":[{"argv":["{python}",str(Path(__file__).with_name("examples") / "summarize.py"),"{component}","{output}"]}]},
            {"id":"fail", "steps":[{"argv":["{python}","-c","raise SystemExit(7)"]}]},
            {"id":"timeout", "steps":[{"argv":["{python}","-c","import time; time.sleep(20)"],"timeoutSeconds":1}]}
        ]}))
        self.bench = Workbench(self.temp.name, pipeline_config=config)
        digest = self.bench.preview(fixture())["hash"]; self.bench.save(digest)
        job = self.bench.run_pipeline("ok", digest); self.bench.worker.join(timeout=5)
        self.assertEqual(self.bench.jobs[job["id"]]["status"], "completed")
        self.assertTrue(json.loads((Path(job["output"]) / "summary.json").read_text())["hasTestTag"])
        self.assertIn("summary.json", [f["name"] for f in self.bench.artifacts(job["id"])])
        self.assertIn(digest, self.bench.artifact(job["id"], "summary.json")["text"])
        with self.assertRaises(ValueError): self.bench.artifact(job["id"], "../profiles.json")
        outside = Path(self.temp.name) / "secret.txt"; outside.write_text("synthetic-secret")
        (Path(job["output"]) / "link.txt").symlink_to(outside)
        with self.assertRaises(ValueError): self.bench.artifact(job["id"], "link.txt")
        for ident in ("fail", "timeout"):
            job = self.bench.run_pipeline(ident, digest); self.bench.worker.join(timeout=5)
            self.assertEqual(self.bench.jobs[job["id"]]["status"], "failed")
        self.bench.run_pipeline("timeout", digest)
        start = time.monotonic(); self.bench.close(Mock())
        self.assertLess(time.monotonic() - start, 5)
        self.assertFalse(self.bench.worker.is_alive())
        config.write_text('{"pipelines":[{"id":"shell","steps":[{"argv":"echo unsafe"}]}]}')
        with self.assertRaises(ValueError): Workbench(self.temp.name, pipeline_config=config)
    def test_http_auth_explicit_import_save_and_storage_is_not_public(self):
        server = BridgeServer(("127.0.0.1", 0), None, None, self.bench)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        def call(path, body=None, auth=True, extra=None):
            headers = {"X-Qalens-Session": self.bench.session} if auth else {}
            if extra: headers.update(extra)
            if body is not None: headers["Content-Type"] = "application/json"
            request = Request(f"http://127.0.0.1:{server.server_port}/api/{path}", data=json.dumps(body).encode() if body is not None else None, headers=headers)
            try: response = urlopen(request, timeout=3)
            except HTTPError as error: response = error
            with response: return response.code, json.loads(response.read())
        try:
            self.assertEqual(call("workbench", auth=False)[0], 401)
            self.assertEqual(call("bootstrap", auth=False, extra={"Origin":"https://evil.example"})[0], 403)
            self.assertEqual(call("bootstrap", auth=False)[1]["session"], self.bench.session)
            self.assertEqual(call("snapshot")[0], 503)
            nested = Request(f"http://127.0.0.1:{server.server_port}/api/import", data=b'{"document":' + b'[' * 2000 + b'0' + b']' * 2000 + b'}', headers={"X-Qalens-Session":self.bench.session,"Content-Type":"application/json"})
            with self.assertRaises(HTTPError) as rejected: urlopen(nested, timeout=3)
            self.assertEqual(rejected.exception.code, 400)
            digest = call("import", {"document":fixture()})[1]["document"]["hash"]
            self.assertEqual(call("saved")[1]["items"], [])
            self.assertFalse(call("save", {"hash":digest})[1]["duplicate"])
            self.assertTrue(call("save", {"hash":digest})[1]["duplicate"])
            self.assertEqual(call("document", {"hash":"../../server.py"})[0], 400)
            self.assertEqual(call("profile", {"profile":{"serial":"emulator-5560","package":"https://myapp.dev"}})[0], 400)
            server.token = "synthetic-pairing-token-0123456789"; server.device_port = 1
            with patch("server.Handler.proxy", return_value=(200, {"ok":True,"nodes":[]})):
                connection = call("snapshot")[1]["connectionId"]
                self.assertEqual(call("command", {"action":"tap","tag":"target"})[0], 409)
                self.assertEqual(call("command", {"action":"tap","tag":"target"}, extra={"X-Qalens-Connection":connection})[0], 200)
                self.bench.connection_id = "new-connection"
                self.assertEqual(call("command", {"action":"tap","tag":"target"}, extra={"X-Qalens-Connection":connection})[0], 409)
        finally: server.shutdown(); server.server_close()

if __name__ == "__main__": unittest.main()
