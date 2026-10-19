"""Real phone/desktop HTTP handoff, ownership, storage and consent contracts."""
import copy
import errno
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

import insights
import investigations
from test_insights import bridge, bundle, image_bundle, model, player_bundle, qa_bundle, report
from workbench import Workbench

TOKEN = "synthetic-phone-token-for-transfer"


def document(evidence=None, output=None):
    value = {"schema": investigations.SCHEMA, "bundle": qa_bundle() if evidence is None else evidence}
    if output is not None:
        value["report"] = output
    return value


class ValidationTests(unittest.TestCase):
    def test_canonical_content_and_report_hash_are_idempotent(self):
        output = report()
        output["observations"].append({"text": "Unknown claim", "evidenceIds": ["logs:999"]})
        output["hypotheses"].append({"title": "Unverified guess", "confidence": "high", "reasoning": "No captured support", "evidenceIds": [], "nextChecks": []})
        output["groundingWarnings"] = ["Provider says its conclusion is verified."]
        evidence = qa_bundle()
        evidence["qaContext"] = {"expectedResult": "Bearer synthetic-secret", "actualResult": '"token":"synthetic-secret"'}
        evidence["items"][0]["tMs"] = 6000.0
        for original in (document(evidence, output), document(image_bundle(), output), document(player_bundle(), output)):
            digest, clean, size = investigations.validate_document(original)
            again, reread, again_size = investigations.validate_document(copy.deepcopy(clean))
            self.assertEqual(again, digest)
            self.assertEqual(again_size, size)
            self.assertEqual(reread, clean)
            self.assertNotIn("conclusion is verified", json.dumps(clean))
            self.assertNotIn("Unknown claim", json.dumps(clean))
            reversed_keys = dict(reversed(list(clean.items())))
            self.assertEqual(investigations.validate_document(reversed_keys)[0], digest)

    def test_portable_handoff_rejects_configs_wrong_shapes_and_utf8_overflow(self):
        bad = [None, [], {"schema": investigations.SCHEMA}]
        item = document(); item["config"] = {"apiKey": "must not transfer"}; bad.append(item)
        item = document(); item["report"] = None; bad.append(item)
        item = document(output=report()); item["report"]["observations"][0].pop("evidenceIds"); bad.append(item)
        item = document(); item["bundle"]["qaContext"] = {"actualResult": True}; bad.append(item)
        item = document(); item["bundle"]["items"][0]["tMs"] = -1; bad.append(item)
        item = document(); item["bundle"]["items"][0]["summary"] = "\ud800"; bad.append(item)
        item = document(image_bundle()); item["bundle"]["coverage"]["padding"] = "🙂" * 16_000
        item["bundle"]["items"][0]["details"]["padding"] = "🙂" * 16_000
        item["bundle"]["items"][1]["details"]["padding"] = "🙂" * 16_000
        item["bundle"]["images"][0]["data"] += "a" * 100_000; bad.append(item)
        for value in bad:
            with self.subTest(value_type=type(value).__name__), self.assertRaises(ValueError):
                investigations.validate_document(value)
        self.assertNotIn("report", investigations.validate_document(document())[1])
        a = {"id": "session:1", "document": document()}
        with self.assertRaisesRegex(ValueError, "duplicate transfer IDs"):
            investigations.validate_inbox({"ok": True, "transfers": [a, a], "dropped": 0})


class HandoffTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="qalens-investigation-tests-")
        self.addCleanup(self.temp.cleanup)
        self.bench = Workbench(Path(self.temp.name) / "data")
        self.transfers, self.acks, self.requests = [], [], []
        self.dropped, self.fail_ack, self.status, self.raw_response = 0, False, 200, None
        self.gate, self.arrived = None, threading.Event()
        self.owned_at_ack = []
        fixture = self

        class PhoneHandler(BaseHTTPRequestHandler):
            def log_message(self, *_): pass
            def reply(self, status, value):
                raw = value if isinstance(value, bytes) else json.dumps(value).encode()
                try:
                    self.send_response(status)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(raw)))
                    self.end_headers(); self.wfile.write(raw)
                except (ConnectionError, OSError): pass
            def do_GET(self):
                fixture.requests.append((self.path, self.headers.get("Authorization")))
                if self.headers.get("Authorization") != "Bearer " + TOKEN:
                    return self.reply(401, {"ok": False, "error": TOKEN})
                fixture.arrived.set()
                payload = fixture.raw_response if fixture.raw_response is not None else {"ok": True, "transfers": copy.deepcopy(fixture.transfers), "dropped": fixture.dropped}
                if fixture.gate:
                    fixture.gate.wait(4)
                self.reply(fixture.status, payload)
            def do_POST(self):
                fixture.requests.append((self.path, self.headers.get("Authorization")))
                value = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                fixture.acks.append(value["ids"])
                fixture.owned_at_ack.append(set(fixture.bench.investigations.pending))
                if fixture.fail_ack:
                    return self.reply(503, {"ok": False, "error": TOKEN})
                fixture.transfers = [item for item in fixture.transfers if item["id"] not in value["ids"]]
                self.reply(200, {"ok": True})

        self.phone = ThreadingHTTPServer(("127.0.0.1", 0), PhoneHandler)
        self.phone.daemon_threads = True
        self.pc = bridge.BridgeServer(("127.0.0.1", 0), self.phone.server_port, TOKEN, self.bench)
        self.addCleanup(self.close)
        threading.Thread(target=self.phone.serve_forever, daemon=True).start()
        threading.Thread(target=self.pc.serve_forever, daemon=True).start()

    def close(self):
        if self.gate:
            self.gate.set()
        self.pc.shutdown(); self.pc.server_close()
        self.phone.shutdown(); self.phone.server_close()

    def call(self, path, method="GET", body=None, session=True, connection=True, extra_headers=None):
        headers = {"Content-Type": "application/json"}
        if session: headers["X-Qalens-Session"] = self.bench.session
        if connection: headers["X-Qalens-Connection"] = self.bench.connection_id
        headers.update(extra_headers or {})
        client = http.client.HTTPConnection("127.0.0.1", self.pc.server_port, timeout=6)
        try:
            client.request(method, path, json.dumps(body).encode() if body is not None else None, headers)
            response = client.getresponse()
            raw = response.read()
            return response.status, json.loads(raw)
        finally:
            client.close()

    def receive(self, value=None, ident="session:1"):
        self.transfers = [{"id": ident, "document": document(output=report()) if value is None else value}]
        code, result = self.call("/api/investigations/inbox")
        self.assertEqual(code, 200, result)
        return result

    def test_authentication_methods_and_local_saved_routes_work_unpaired(self):
        self.assertEqual(self.call("/api/investigations/previews", session=False)[0], 401)
        self.assertEqual(self.call("/api/investigations/inbox", session=False,
                                   extra_headers={"Authorization": "Bearer " + TOKEN})[0], 401)
        self.assertEqual(self.call("/api/investigations/inbox", connection=False)[0], 409)
        self.assertEqual(self.requests, [])
        self.assertEqual(self.call("/api/investigations/previews", "POST", {})[0], 405)
        self.assertEqual(self.call("/api/investigations/save")[0], 405)
        digest = self.receive()["previews"][0]["hash"]
        self.assertEqual(self.call("/api/investigations/save", "POST", {"hash": digest})[0], 200)
        self.bench.disconnect(self.pc)
        self.assertEqual(self.call("/api/investigations/inbox")[0], 503)
        self.assertEqual(self.call("/api/investigations/previews")[1]["previews"], [])
        self.assertEqual(self.call("/api/investigations/saved")[1]["saved"][0]["hash"], digest)
        self.assertTrue(self.call("/api/investigations/document?hash=" + digest)[1]["saved"])
        self.assertEqual(self.call("/api/investigations/save", "POST", {"hash": digest})[0], 200)
        self.assertEqual(self.call("/api/investigations/document?hash=../outside")[0], 400)
        self.assertEqual(self.call("/api/investigations/document?hash=" + digest + "&hash=" + digest)[0], 400)

    def test_inbox_owns_before_ack_deduplicates_and_keeps_invalid_cases_on_phone(self):
        good = document(output=report())
        self.transfers = [{"id": "session:1", "document": good},
                          {"id": "session:2", "document": copy.deepcopy(good)},
                          {"id": "session:3", "document": {"schema": "invalid", "apiKey": TOKEN}}]
        self.dropped = 2
        code, result = self.call("/api/investigations/inbox")
        self.assertEqual(code, 200, result)
        self.assertEqual((result["received"], result["duplicates"], result["invalid"], result["dropped"]), (1, 1, 1, 2))
        self.assertEqual(result["connectionId"], self.bench.connection_id)
        self.assertEqual(self.acks, [["session:1", "session:2"]])
        digest = result["previews"][0]["hash"]
        self.assertIn(digest, self.owned_at_ack[0])
        self.assertEqual([item["id"] for item in self.transfers], ["session:3"])
        self.assertEqual(list((self.bench.root / "investigations").glob("*.json")), [])
        selected = self.call("/api/investigations/document?hash=" + digest)[1]["document"]
        self.assertEqual(selected["report"]["qaReport"]["steps"][0]["evidenceIds"], ["timeline:2"])
        self.assertNotIn(TOKEN, json.dumps(result))

    def test_stale_real_http_response_is_not_accepted_or_acknowledged(self):
        self.transfers = [{"id": "session:1", "document": document(output=report())}]
        self.gate = threading.Event()
        results = []
        worker = threading.Thread(target=lambda: results.append(self.call("/api/investigations/inbox")))
        worker.start()
        self.assertTrue(self.arrived.wait(2))
        self.assertEqual(self.call("/api/investigations/inbox")[0], 409, "Only one reception is allowed")
        started = time.monotonic()
        self.bench.disconnect(self.pc)
        self.assertLess(time.monotonic() - started, .5, "Inbox network wait must not hold the phone mutex")
        self.gate.set(); worker.join(3)
        self.assertFalse(worker.is_alive())
        self.assertEqual(results[0][0], 409)
        self.assertEqual(self.acks, [])
        self.assertEqual(self.bench.investigations.previews()["previews"], [])

    def test_ack_failure_keeps_memory_and_retry_is_a_safe_duplicate(self):
        self.fail_ack = True
        result = self.receive()
        self.assertFalse(result["acknowledged"])
        self.assertEqual(len(result["previews"]), 1)
        self.assertEqual(result["acknowledgedCount"], 0)
        self.fail_ack = False
        code, again = self.call("/api/investigations/inbox")
        self.assertEqual(code, 200)
        self.assertEqual((again["received"], again["duplicates"]), (0, 1))
        self.assertTrue(again["acknowledged"])
        self.assertEqual(self.transfers, [])

    def test_capacity_refuses_without_ack_and_explicit_save_releases_a_slot(self):
        values = []
        for index in range(11):
            value = document(); value["bundle"]["question"] = f"Reported case {index}"
            values.append({"id": f"session:{index}", "document": value})
        self.transfers = values[:10]
        self.assertEqual(self.call("/api/investigations/inbox")[1]["received"], 10)
        self.transfers = [values[10], values[0]]
        code, result = self.call("/api/investigations/inbox")
        self.assertEqual(code, 200)
        self.assertEqual((result["blocked"], result["duplicates"], result["received"]), (1, 1, 0))
        self.assertEqual(self.acks[-1], ["session:0"])
        self.assertEqual(len(result["previews"]), 10)
        digest = result["previews"][0]["hash"]
        self.assertEqual(self.call("/api/investigations/save", "POST", {"hash": digest})[0], 200)
        self.assertEqual(self.call("/api/investigations/inbox")[1]["received"], 1)
        self.assertEqual(len(self.bench.investigations.previews()["previews"]), 10)
        self.assertEqual(self.transfers, [])

    def test_byte_capacity_is_bounded_without_silent_eviction(self):
        a = document(); b = document(); b["bundle"]["question"] = "Different case"
        size = max(investigations.validate_document(a)[2], investigations.validate_document(b)[2])
        with patch.object(investigations, "MAX_PENDING_BYTES", size + 10):
            self.transfers = [{"id": "session:1", "document": a}, {"id": "session:2", "document": b}]
            result = self.call("/api/investigations/inbox")[1]
            self.assertEqual((result["received"], result["blocked"]), (1, 1))
            self.assertLessEqual(result["pendingBytes"], size + 10)
            self.assertEqual(self.acks, [["session:1"]])

    def test_explicit_atomic_private_save_integrity_and_corruption_fail_honestly(self):
        digest = self.receive(document(output=report()))["previews"][0]["hash"]
        with patch.object(investigations, "write_json", side_effect=OSError(errno.EACCES, "synthetic denial")):
            code, result = self.call("/api/investigations/save", "POST", {"hash": digest})
            self.assertEqual(code, 500)
            self.assertFalse(result["ok"])
        self.assertEqual(len(self.bench.investigations.previews()["previews"]), 1)
        code, saved = self.call("/api/investigations/save", "POST", {"hash": digest})
        self.assertEqual(code, 200, saved)
        self.assertEqual(self.bench.investigations.previews()["previews"], [])
        path = self.bench.root / "investigations" / (digest + ".json")
        if os.name == "posix": self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        self.assertEqual(list(path.parent.glob("*.tmp")), [])
        self.assertEqual(investigations.validate_document(json.loads(path.read_text()))[0], digest)
        changed = json.loads(path.read_text()); changed["bundle"]["recording"]["name"] = "corrupted"
        path.write_text(json.dumps(changed))
        self.assertEqual(self.call("/api/investigations/document?hash=" + digest)[0], 400)
        self.assertEqual(self.call("/api/investigations/saved")[1]["corrupt"], 1)
        self.assertEqual(self.call("/api/investigations/save", "POST", {"hash": digest})[0], 400)

    @unittest.skipUnless(hasattr(os, "symlink"), "Requires symlink support")
    def test_symlink_destination_is_refused_without_overwriting_the_target(self):
        result = self.receive(); digest = result["previews"][0]["hash"]
        outside = Path(self.temp.name) / "outside.json"; outside.write_text("keep this file")
        (self.bench.root / "investigations" / (digest + ".json")).symlink_to(outside)
        self.assertEqual(self.call("/api/investigations/save", "POST", {"hash": digest})[0], 400)
        self.assertEqual(outside.read_text(), "keep this file")

    def test_handoff_image_does_not_grant_model_consent_or_call_a_provider(self):
        result = self.receive(document(image_bundle(), report()))
        self.assertTrue(result["previews"][0]["hasImage"])
        digest = result["previews"][0]["hash"]
        transferred = self.call("/api/investigations/document?hash=" + digest)[1]["document"]
        with model() as fixture:
            code, error = self.call("/api/insights/analyze", "POST", {"config": fixture.config("openai"), "bundle": transferred["bundle"]})
            self.assertEqual(code, 400)
            self.assertIn("includeImage", error["error"])
            self.assertEqual(fixture.requests, [])

    def test_phone_errors_duplicate_json_and_duplicate_ids_never_ack_or_echo(self):
        self.status = 403
        self.raw_response = json.dumps({"ok": False, "error": TOKEN}).encode()
        code, result = self.call("/api/investigations/inbox")
        self.assertEqual(code, 502)
        self.assertNotIn(TOKEN, json.dumps(result))
        self.status = 200
        self.raw_response = b'{"ok":true,"ok":false,"transfers":[]}'
        self.assertEqual(self.call("/api/investigations/inbox")[0], 502)
        self.raw_response = None
        entry = {"id": "session:1", "document": document()}
        self.transfers = [entry, copy.deepcopy(entry)]
        self.assertEqual(self.call("/api/investigations/inbox")[0], 400)
        self.assertEqual(self.acks, [])


if __name__ == "__main__":
    unittest.main()
