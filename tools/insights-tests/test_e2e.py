#!/usr/bin/env python3
"""Cross-surface contracts: actual .sal -> web evidence -> real Python/model HTTP -> citations.

Uses synthetic data and a controlled HTTP protocol fixture, not a downloaded language model.
No adb, phone pairing, live browser, video pixels, real host recordings or model-quality claims.
"""
import base64
import copy
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import zlib

from fixture_archive import FIXTURE, write_archive
from model_fixture import MODEL, ModelFixture

ROOT = Path(__file__).resolve().parents[2]
BRIDGE = ROOT / "tools/local-bridge"
sys.path.insert(0, str(BRIDGE))
from workbench import Workbench

spec = importlib.util.spec_from_file_location("qalens_insights_e2e_bridge", BRIDGE / "server.py")
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)


def node(**input):
    result = subprocess.run(["node", str(Path(__file__).with_name("read_bundle.js"))],
                            input=json.dumps(input), text=True, capture_output=True, timeout=15, cwd=ROOT)
    if result.returncode:
        raise AssertionError(result.stderr)
    return json.loads(result.stdout)


def tiny_png():
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 6, 0, 0, 0)) +
            chunk(b"IDAT", zlib.compress(b"\x00\x30\x50\x70\xff")) + chunk(b"IEND", b""))


class CrossSurfaceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not shutil.which("node"):
            raise unittest.SkipTest("Node 18+ is required for real web reader/client contracts")
        cls.temp = tempfile.TemporaryDirectory(prefix="qalens-insights-e2e-")
        cls.archive = write_archive(Path(cls.temp.name) / "synthetic.sal")
        cls.model = ModelFixture(("127.0.0.1", 0))
        threading.Thread(target=cls.model.serve_forever, daemon=True).start()
        cls.bench = Workbench(Path(cls.temp.name) / "desktop")
        cls.server = bridge.BridgeServer(("127.0.0.1", 0), None, None, cls.bench)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.evidence = node(archive=str(cls.archive))["bundle"]

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown(); cls.server.server_close(); cls.bench.close(cls.server)
        cls.model.shutdown(); cls.model.server_close(); cls.temp.cleanup()

    def config(self, protocol, model=MODEL):
        return {"baseUrl": f"http://127.0.0.1:{self.model.server_port}", "protocol": protocol, "model": model}

    def api(self, route, value=None):
        body = json.dumps(value).encode() if value is not None else None
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=5)
        try:
            connection.request("POST" if value is not None else "GET", route, body,
                               {"Content-Type": "application/json", "X-Qalens-Session": self.bench.session,
                                "X-Qalens-Connection": self.bench.connection_id})
            response = connection.getresponse()
            return response.status, json.loads(response.read())
        finally:
            connection.close()

    def job(self, ident):
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            code, result = self.api("/api/insights/jobs?id=" + ident)
            self.assertEqual(code, 200, result)
            if result["job"]["state"] in ("done", "error", "cancelled"):
                return result["job"]
            time.sleep(.02)
        self.fail("Actual desktop HTTP job did not finish")

    def check_report(self, report, evidence):
        self.assertEqual(report["schema"], "qalens-insights-report/1")
        ids = {item["id"] for item in evidence["items"]} | {image["id"] for image in evidence.get("images", [])}
        cites = {ident for section in ("observations", "hypotheses") for item in report[section] for ident in item["evidenceIds"]}
        self.assertTrue(cites <= ids)
        self.assertIn("network:0", cites)
        self.assertIn("logs:1", cites)
        if evidence.get("images"):
            self.assertIsNone(report["pixelsAnalyzed"])
        else:
            self.assertIn(report["pixelsAnalyzed"], (False, None))

    def test_01_archive_tracks_and_original_timestamps_survive_selection(self):
        result = node(archive=str(self.archive))
        self.assertEqual(result["tracks"], {"timeline": 3, "network": 2, "logs": 4, "state": 3, "crashes": 0,
                                          "performance": 1, "connectivity": 1, "memory": 1, "marks": 1})
        evidence = result["bundle"]
        self.assertEqual(evidence["recording"]["t0"], 1800000000000)
        self.assertEqual(evidence["recording"]["focusMs"], 52000)
        items = {item["id"]: item for item in evidence["items"]}
        self.assertEqual(items["network:0"]["tMs"], 50200)
        self.assertEqual(items["state:0"]["tMs"], 40000)
        self.assertEqual(items["connectivity:0"]["tMs"], 0)
        self.assertTrue(items["connectivity:0"]["details"]["windowContext"])
        self.assertIn("memory:0", items)
        self.assertIn("performance:0", items)
        self.assertNotIn("network:1", items)
        self.assertFalse(evidence["coverage"]["media"]["pixelsSent"])
        self.assertLessEqual(len(json.dumps(evidence, separators=(",", ":"))), 48000)

    def test_02_both_real_http_protocols_accept_web_bundle_on_desktop_without_a_phone(self):
        self.assertIsNone(self.bench.connection)
        for protocol in ("openai", "ollama"):
            with self.subTest(protocol=protocol):
                config = self.config(protocol)
                code, discovered = self.api("/api/insights/models", {"config": config})
                self.assertEqual(code, 200, discovered)
                self.assertEqual([model["id"] for model in discovered["models"]], [MODEL])
                code, accepted = self.api("/api/insights/analyze", {"config": config, "bundle": self.evidence})
                self.assertEqual(code, 200, accepted)
                job = self.job(accepted["job"]["id"])
                self.assertEqual(job["state"], "done", job)
                self.check_report(job["report"], self.evidence)

    def test_03_standalone_web_client_uses_same_provider_and_grounding(self):
        for protocol in ("openai", "ollama"):
            with self.subTest(protocol=protocol):
                found = node(operation="discover", config=self.config(protocol))
                self.assertEqual([model["id"] for model in found["models"]], [MODEL])
                result = node(operation="analyze", config=self.config(protocol), bundle=self.evidence)
                self.check_report(result, self.evidence)

    def test_04_unknown_ids_do_not_become_captured_facts_across_clients(self):
        config = self.config("openai", "fixture-unknown-reference")
        direct = node(operation="analyze", config=config, bundle=self.evidence)
        code, accepted = self.api("/api/insights/analyze", {"config": config, "bundle": self.evidence})
        self.assertEqual(code, 200, accepted)
        desktop = self.job(accepted["job"]["id"])["report"]
        for report in (direct, desktop):
            self.check_report(report, self.evidence)
            self.assertTrue(report["groundingWarnings"])
            self.assertFalse(any("Invented evidence" in item["text"] for item in report["observations"]))
            self.assertEqual(report["hypotheses"][-1]["confidence"], "low")

    def test_05_reviewed_still_has_explicit_consent_and_correct_wire_format(self):
        image = {"id": "images:0", "tMs": 52000, "mediaType": "image/png", "source": "recording-frame",
                 "approximate": False, "data": base64.b64encode(tiny_png()).decode()}
        evidence = copy.deepcopy(self.evidence)
        evidence["images"] = [image]
        code, refused = self.api("/api/insights/analyze", {"config": self.config("openai"), "bundle": evidence})
        self.assertEqual(code, 400, refused)
        for protocol in ("openai", "ollama"):
            with self.subTest(protocol=protocol):
                config = self.config(protocol)
                direct = node(operation="analyze", config=config, bundle=self.evidence, image=image, includeImage=True)
                self.assertTrue(direct["pixelsProvided"])
                code, accepted = self.api("/api/insights/analyze", {"config": config, "bundle": evidence, "includeImage": True})
                self.assertEqual(code, 200, accepted)
                job = self.job(accepted["job"]["id"])
                self.assertEqual(job["state"], "done", job)
                self.assertTrue(job["pixelsSent"])
                self.assertTrue(job["report"]["pixelsProvided"])
                self.assertIsNone(job["report"]["pixelsAnalyzed"])
                with self.model.metrics_lock:
                    self.assertEqual(self.model.metrics["lastImageCount"], 1)
                    self.assertEqual(self.model.metrics["lastProtocol"], protocol)

    def test_06_log_flood_is_bounded_and_retains_original_error_id(self):
        fixture = json.loads(FIXTURE.read_text())
        fixture["logs"] = [{"ts": 1800000052000, "type": "LOG", "tag": "noise", "message": "background " + "x" * 500} for _ in range(5000)]
        fixture["logs"][-1]["message"] = "ERROR player buffering after segment failure"
        flooded = write_archive(Path(self.temp.name) / "flood.sal", fixture)
        evidence = node(archive=str(flooded))["bundle"]
        self.assertLessEqual(len(evidence["items"]), 300)
        self.assertLessEqual(len(json.dumps(evidence, ensure_ascii=False, separators=(",", ":"))), 48000)
        self.assertIn("logs:4999", {item["id"] for item in evidence["items"]})
        self.assertGreater(evidence["omissions"]["contextLimit"] + evidence["omissions"]["itemLimit"], 0)
        code, accepted = self.api("/api/insights/analyze", {"config": self.config("openai"), "bundle": evidence})
        self.assertEqual(code, 200, accepted)
        self.assertEqual(self.job(accepted["job"]["id"])["state"], "done")

    def test_07_current_player_evidence_keeps_its_distinct_provenance_and_citation(self):
        investigation = {"target": "qalens-player", "runtime": {
            "id": "player:runtime", "source": "current-player", "client": "web",
            "observedAtMillis": 1801000000000, "recordingPositionMs": 52000,
            "details": {"fixture": "synthetic current replay state, not captured host telemetry",
                        "paused": True, "lastError": "Synthetic media decoder error", "clockPositionMs": 52000}
        }}
        evidence = {**self.evidence, "investigation": investigation}
        for protocol in ("openai", "ollama"):
            with self.subTest(protocol=protocol):
                config = self.config(protocol)
                direct = node(operation="analyze", config=config, bundle=self.evidence, investigation=investigation)
                code, accepted = self.api("/api/insights/analyze", {"config": config, "bundle": evidence})
                self.assertEqual(code, 200, accepted)
                job = self.job(accepted["job"]["id"])
                self.assertEqual(job["state"], "done", job)
                for report in (direct, job["report"]):
                    self.assertEqual(report["observations"][0]["evidenceIds"], ["player:runtime"])
                    self.assertIn("not a recorded host event", report["observations"][0]["text"])
                    self.assertEqual(report["hypotheses"][0]["confidence"], "low")
                    self.assertTrue(any("current" in check or "player" in check.lower() for check in report["recommendedChecks"]))
                with self.model.metrics_lock:
                    self.assertEqual(self.model.metrics["lastTarget"], "qalens-player")
                    self.assertTrue(self.model.metrics["runtimeProvided"])

    def test_08_qa_expected_actual_and_captured_steps_survive_both_clients(self):
        context = {"expectedResult": "After tapping Play, playback starts or a retryable error appears.",
                   "actualResult": "Tester tapped Play; loading continued and playback did not begin."}
        evidence = node(archive=str(self.archive), options={"qaContext": context})["bundle"]
        self.assertEqual(evidence["qaContext"], context)
        for protocol in ("openai", "ollama"):
            with self.subTest(protocol=protocol):
                config = self.config(protocol)
                direct = node(operation="analyze", config=config, bundle=evidence)
                code, accepted = self.api("/api/insights/analyze", {"config": config, "bundle": evidence})
                self.assertEqual(code, 200, accepted)
                job = self.job(accepted["job"]["id"])
                self.assertEqual(job["state"], "done", job)
                for report in (direct, job["report"]):
                    qa = report["qaReport"]
                    self.assertEqual(qa["expectedResult"], context["expectedResult"])
                    self.assertEqual(qa["actualResult"], context["actualResult"])
                    self.assertEqual(qa["expectedSource"], "tester")
                    self.assertEqual(qa["actualSource"], "tester")
                    self.assertEqual(len(qa["steps"]), 1)
                    self.assertEqual(qa["steps"][0]["evidenceIds"], ["timeline:1"])
                    self.assertIn("Play", qa["steps"][0]["action"])
                    self.assertLessEqual(len(qa["title"]), 400)

    def test_09_missing_expectation_is_not_invented_and_host_steps_are_not_player_steps(self):
        evidence = {**self.evidence, "investigation": {"target": "qalens-player"}}
        config = self.config("openai")
        direct = node(operation="analyze", config=config, bundle=evidence)
        code, accepted = self.api("/api/insights/analyze", {"config": config, "bundle": evidence})
        self.assertEqual(code, 200, accepted)
        job = self.job(accepted["job"]["id"])
        self.assertEqual(job["state"], "done", job)
        for report in (direct, job["report"]):
            qa = report["qaReport"]
            self.assertEqual(qa["expectedSource"], "not-provided")
            self.assertEqual(qa["steps"], [], "Captured host clicks must not become QaLens replay controls")
            self.assertTrue(qa["expectedResult"].strip())

    def test_10_phone_wire_case_receive_save_import_and_explicit_reanalysis(self):
        """A controlled SDK wire fixture exercises the real desktop HTTP ownership path."""
        context = {"expectedResult": "Play starts the video.", "actualResult": "Loading continues after Play."}
        evidence = node(archive=str(self.archive), options={"qaContext": context})["bundle"]
        image = {"id": "images:0", "tMs": 52000, "mediaType": "image/png", "source": "recording-frame",
                 "approximate": False, "data": base64.b64encode(tiny_png()).decode()}
        evidence["images"] = [image]
        report = node(operation="analyze", config=self.config("openai"), bundle=evidence, includeImage=True)
        document = {"schema": "qalens-investigation-transfer/1", "bundle": evidence, "report": report}
        pending = {"fixture-session:1": document}
        token = "synthetic-fixture-pairing-only"

        class PhoneWire(BaseHTTPRequestHandler):
            def log_message(self, *_): pass

            def do_GET(self):
                self.respond()

            def do_POST(self):
                self.respond()

            def respond(self):
                if self.headers.get("Authorization") != "Bearer " + token:
                    status, value = 401, {"ok": False}
                elif self.command == "GET" and self.path == "/v1/investigations/inbox":
                    status, value = 200, {"ok": True, "transfers": [{"id": ident, "document": case}
                                                                 for ident, case in pending.items()], "dropped": 0}
                elif self.command == "POST" and self.path == "/v1/investigations/ack":
                    ack = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
                    for ident in ack["ids"]: pending.pop(ident, None)
                    status, value = 200, {"ok": True}
                else:
                    status, value = 404, {"ok": False}
                data = json.dumps(value).encode()
                self.send_response(status); self.send_header("Content-Length", str(len(data)))
                self.send_header("Content-Type", "application/json"); self.end_headers(); self.wfile.write(data)

        phone = ThreadingHTTPServer(("127.0.0.1", 0), PhoneWire)
        threading.Thread(target=phone.serve_forever, daemon=True).start()
        previous = self.server.device_port, self.server.token
        self.server.device_port, self.server.token = phone.server_port, token
        try:
            with self.model.metrics_lock:
                calls = self.model.metrics["chatRequests"]
            code, inbox = self.api("/api/investigations/inbox")
            self.assertEqual(code, 200, inbox)
            self.assertEqual(inbox["received"], 1)
            self.assertEqual(inbox["acknowledgedCount"], 1)
            self.assertFalse(pending, "Phone cases are acknowledged only after desktop ownership")
            digest = inbox["previews"][0]["hash"]
            code, received = self.api("/api/investigations/document?hash=" + digest)
            self.assertEqual(code, 200, received)
            self.assertFalse(received["saved"])
            self.assertFalse(list(self.bench.investigations.directory.glob("*.json")), "Receiving never saves implicitly")
            with self.model.metrics_lock:
                self.assertEqual(self.model.metrics["chatRequests"], calls, "Receiving never contacts a model")
            self.assertEqual(received["document"]["bundle"]["qaContext"], context)
            self.assertEqual(received["document"]["report"]["qaReport"]["steps"][0]["evidenceIds"], ["timeline:1"])
            imported = node(operation="import", document=received["document"])
            self.assertEqual(imported["bundle"]["items"], received["document"]["bundle"]["items"])
            self.assertEqual(imported["report"]["qaReport"]["expectedResult"], context["expectedResult"])
            for duplicate in (False, True):
                code, saved = self.api("/api/investigations/save", {"hash": digest})
                self.assertEqual(code, 200, saved)
                self.assertEqual(saved["duplicate"], duplicate)
            self.assertEqual(len(list(self.bench.investigations.directory.glob("*.json"))), 1)
            self.server.device_port, self.server.token = None, None
            self.bench.investigations.clear()
            code, saved_document = self.api("/api/investigations/document?hash=" + digest)
            self.assertEqual(code, 200, saved_document)
            self.assertTrue(saved_document["saved"])
            self.assertEqual(saved_document["document"], received["document"], "Canonical saved content is stable on read")
            bundle = saved_document["document"]["bundle"]
            code, refused = self.api("/api/insights/analyze", {"config": self.config("openai"), "bundle": bundle})
            self.assertEqual(code, 400, f"Handoff image permission is not model permission: {refused}")
            code, accepted = self.api("/api/insights/analyze", {"config": self.config("openai"), "bundle": bundle, "includeImage": True})
            self.assertEqual(code, 200, accepted)
            job = self.job(accepted["job"]["id"])
            self.assertEqual(job["state"], "done", job)
            self.assertEqual(job["report"]["qaReport"]["actualResult"], context["actualResult"])
            self.check_report(job["report"], bundle)
        finally:
            self.server.device_port, self.server.token = previous
            phone.shutdown(); phone.server_close()


if __name__ == "__main__":
    unittest.main()
