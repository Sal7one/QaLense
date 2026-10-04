"""Phone approval, reconnect ownership and explicit bounded screen preview regressions."""
import io
import json
from pathlib import Path
import struct
import tempfile
import time
import unittest
from unittest.mock import Mock, patch
from urllib.error import HTTPError, URLError
from workbench import Workbench

PROFILE = {"serial": "emulator-5560", "package": "com.example.app", "activity": ".MainActivity"}

class ConnectionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.bench = Workbench(self.temp.name, "fake-adb")
        self.server = Mock(token=None, device_port=None)
        self.forward = "emulator-5560 tcp:19333 tcp:8766\n"
        def adb(args, serial=None):
            if args == ["devices", "-l"]: return "List of devices attached\nemulator-5560 device model:Pixel\n"
            if args[:3] == ["shell", "getprop", "ro.build.version.release"]: return "13\n"
            if args[:3] == ["shell", "pm", "path"]: return "package:/data/app/app.apk\n"
            if args[:2] == ["forward", "tcp:0"]: return "19333\n"
            if args == ["forward", "--list"]: return self.forward
            return "Broadcast completed: result=1\n"
        self.bench.adb_call = Mock(side_effect=adb)
    def tearDown(self): self.bench.close(self.server); self.temp.cleanup()
    def approved_response(self):
        response = Mock(code=200); response.read.return_value = b'{"ok":true,"items":[]}'
        response.__enter__ = Mock(return_value=response); response.__exit__ = Mock(return_value=False)
        return response
    def test_pair_requires_phone_approval_and_never_returns_or_persists_credential(self):
        connected = self.bench.pair(self.server, PROFILE)
        token = self.server.token
        self.assertGreaterEqual(len(token), 24)
        self.assertNotIn(token, json.dumps(connected))
        self.assertNotIn(token, self.bench.profiles_path.read_text())
        self.assertFalse(self.bench.connection_state(self.server)["connected"])
        self.assertEqual(self.bench.phase, "awaiting-approval")
        calls = [c.args[0] for c in self.bench.adb_call.call_args_list]
        start = next(c for c in calls if c[:3] == ["shell", "am", "start"])
        self.assertNotIn(token, start)
        with self.assertRaises(ValueError): self.bench.desktop.task({"action":"back", "connectionId":self.bench.connection_id})
        with patch("workbench.urlopen", return_value=self.approved_response()):
            state = self.bench.check_connection(self.server)
        self.assertTrue(state["connected"]); self.assertEqual(state["phase"], "connected")
    def test_expiry_cancels_pending_phone_request_and_forgets_credentials(self):
        self.bench.pair(self.server, PROFILE); token = self.server.token
        self.bench.pair_deadline = 0
        state = self.bench.check_connection(self.server)
        self.assertEqual(state["phase"], "expired"); self.assertIsNone(self.server.token)
        self.assertTrue(any("com.qalens.action.CANCEL_PC_PAIRING" in c.args[0] and token in c.args[0] for c in self.bench.adb_call.call_args_list))
    def test_transient_outage_repairs_only_missing_forward_without_rotating_session(self):
        self.bench.connect(self.server, PROFILE, "synthetic-pairing-token-0123456789")
        ident = self.bench.connection_id
        self.forward = "other-device tcp:19333 tcp:9999\n"
        with patch("workbench.urlopen", side_effect=URLError("offline")):
            state = self.bench.check_connection(self.server)
        self.assertEqual(state["phase"], "reconnecting"); self.assertTrue(state["connected"])
        self.assertEqual(state["connectionId"], ident)
        self.assertFalse(any(c.args[0][:2] == ["forward", "--remove"] for c in self.bench.adb_call.call_args_list))
        self.bench.last_check = 0
        with patch("workbench.urlopen", return_value=self.approved_response()):
            self.assertEqual(self.bench.check_connection(self.server)["phase"], "connected")
    def test_auth_revocation_stops_preview_and_auto_reconnect_off_does_not_repair(self):
        self.bench.connect(self.server, PROFILE, "synthetic-pairing-token-0123456789")
        self.bench.desktop.preview({"enabled":True, "connectionId":self.bench.connection_id})
        before = len(self.bench.adb_call.call_args_list)
        with patch("workbench.urlopen", side_effect=URLError("offline")):
            self.bench.check_connection(self.server, False)
        self.assertEqual(len(self.bench.adb_call.call_args_list), before)
        self.bench.last_check = 0
        with patch("workbench.urlopen", side_effect=HTTPError("http://localhost", 401, "revoked", {}, io.BytesIO())):
            state = self.bench.check_connection(self.server)
        self.assertEqual(state["phase"], "revoked"); self.assertFalse(state["connected"])
        self.assertFalse(self.bench.desktop.preview_active); self.assertIsNone(self.server.token)
    def test_discovery_filters_sdk_launcher_without_coupling_to_sample(self):
        self.bench.adb_call = Mock(return_value="com.company.qa/.MainActivity\ncom.company.qa/com.qalens.QaLensControlActivity\ncom.other.app/.Home\nmalformed\n")
        apps = self.bench.apps("emulator-5560")
        self.assertEqual(apps[0], {"package":"com.company.qa", "activity":".MainActivity", "qalens":True})
        self.assertFalse(apps[1]["qalens"])
    def test_auto_connect_remembers_only_chosen_profile_and_explicit_choice(self):
        self.assertFalse(self.bench.preferences()["autoConnect"])
        with self.assertRaises(ValueError): self.bench.save_preferences({"autoConnect":True,"profileId":"unknown"})
        saved = self.bench.save_profile(PROFILE)
        self.bench.save_preferences({"autoConnect":True,"profileId":saved["id"],"token":"must-not-persist"})
        restarted = Workbench(self.temp.name)
        self.assertEqual(restarted.preferences(), {"autoConnect":True,"profileId":saved["id"]})
        self.assertNotIn("must-not-persist", (self.bench.root / "desktop.json").read_text())
        self.bench.save_preferences({"autoConnect":False})
        self.assertEqual(self.bench.preferences(), {"autoConnect":False,"profileId":""})
    def test_old_sdk_or_disabled_app_returns_actionable_error_and_removes_forward(self):
        original = self.bench.adb_call.side_effect
        for code, notice in ((0,"Update"),(2,"already open"),(3,"disabled")):
            self.bench.adb_call.side_effect = lambda args, serial=None: f"Broadcast completed: result={code}\n" if "com.qalens.action.REQUEST_PC_PAIRING" in args else original(args, serial)
            with self.assertRaisesRegex(ValueError, notice): self.bench.pair(self.server, PROFILE)
            self.assertIsNone(self.server.token); self.assertIsNone(self.bench.owned_forward)
    def test_preview_is_explicit_bounded_memory_only_and_rejects_stale_stop(self):
        self.bench.connect(self.server, PROFILE, "synthetic-pairing-token-0123456789")
        ident = self.bench.connection_id; desktop = self.bench.desktop
        with self.assertRaises(ValueError): desktop.screen(ident)
        desktop.preview({"enabled":True, "connectionId":ident})
        png = b'\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR' + struct.pack('>II', 1080, 1920) + b'synthetic'
        process = Mock(stdout=io.BytesIO(png)); process.wait.return_value = 0; process.poll.return_value = 0
        with patch("desktop.subprocess.Popen", return_value=process):
            self.assertEqual(desktop.screen(ident), png)
        self.assertEqual(list(desktop.transfers.iterdir()), [])
        with self.assertRaises(ValueError): desktop.screen(ident)  # throttled
        desktop.preview_time = 0
        huge = png[:16] + struct.pack('>II', 8192, 8192)
        process.stdout = io.BytesIO(huge)
        with patch("desktop.subprocess.Popen", return_value=process), self.assertRaises(ValueError): desktop.screen(ident)
        desktop.preview_time = 0
        class StopWhileReading(io.BytesIO):
            def read(self, size): desktop.stop_preview(); return super().read(size)
        process.stdout = StopWhileReading(png)
        with patch("desktop.subprocess.Popen", return_value=process), self.assertRaises(ValueError): desktop.screen(ident)
        with self.assertRaises(ValueError): desktop.preview({"enabled":True,"connectionId":"stale"})

if __name__ == '__main__': unittest.main()
