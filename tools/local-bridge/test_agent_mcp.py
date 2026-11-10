"""Exercise the desktop handoff and MCP client against real loopback HTTP and stdio."""
import base64
from contextlib import contextmanager
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import threading
import unittest

from agent_bridge import Handoff
from server import BridgeServer
from workbench import Workbench

PACKAGE = "com.example.synthetic"
DEVICE_TOKEN = "synthetic-device-token-012345"
PNG = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jT5sAAAAASUVORK5CYII=")


class Device(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def respond(self, code, result):
        data = result if isinstance(result, bytes) else json.dumps(result).encode()
        self.send_response(code)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        fixture = self.server.fixture
        fixture.routes.append(self.path)
        if self.headers.get("Authorization") != f"Bearer {DEVICE_TOKEN}" or fixture.revoked:
            return self.respond(401, {"ok": False})
        if self.path == "/v1/health":
            if fixture.redirect_health:
                self.send_response(302)
                self.send_header("Location", f"http://127.0.0.1:{self.server.server_port}/v1/events")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            health = dict(fixture.health)
            if fixture.delay_health and not fixture.entered_health.is_set():
                fixture.entered_health.set()
                fixture.resume_health.wait(3)
            return self.respond(200, health)
        if self.path == "/v1/snapshot":
            if fixture.block_snapshot:
                fixture.entered_snapshot.set()
                fixture.resume_snapshot.wait(3)
            return self.respond(200, fixture.snapshot)
        if self.path == "/v1/events":
            return self.respond(200, fixture.events)
        return self.respond(404, {"ok": False})

    def do_POST(self):
        self.server.fixture.routes.append(self.path)
        data = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if (self.path == "/v1/screenshot" and data == {"includeOverlay": False} and
                self.headers.get("Authorization") == f"Bearer {DEVICE_TOKEN}"):
            return self.respond(200, PNG)
        return self.respond(404, {"ok": False})


class MCP:
    def __init__(self, command, args):
        self.process = subprocess.Popen([command, *args], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.PIPE, text=True)
        self.ident = 0

    def message(self, method, params=None, notification=False):
        request = {"jsonrpc": "2.0", "method": method, "params": params or {}}
        if not notification:
            self.ident += 1
            request["id"] = self.ident
        self.process.stdin.write(json.dumps(request) + "\n")
        self.process.stdin.flush()
        if not notification:
            response = json.loads(self.process.stdout.readline())
            assert response["id"] == self.ident, response
            return response

    def tool(self, name, **arguments):
        return self.message("tools/call", {"name": name, "arguments": arguments})["result"]

    def result(self, name, **arguments):
        result = self.tool(name, **arguments)
        assert not result.get("isError"), result
        return json.loads(result["content"][0]["text"])

    def __enter__(self):
        result = self.message("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                                             "clientInfo": {"name": "test", "version": "1"}})
        assert result["result"]["protocolVersion"] == "2025-06-18"
        self.message("notifications/initialized", notification=True)
        return self

    def __exit__(self, *_):
        self.process.communicate(timeout=10)


class AgentMCPTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.routes, self.revoked = [], False
        self.block_snapshot = False
        self.delay_health = False
        self.redirect_health = False
        self.entered_snapshot, self.resume_snapshot = threading.Event(), threading.Event()
        self.entered_health, self.resume_health = threading.Event(), threading.Event()
        self.health = {"ok": True, "schema": "qalens.bridge.health", "version": 1, "package": PACKAGE,
                       "port": 8767, "policyId": 8, "semanticsEnabled": True}
        self.node = {"id": "one", "tag": "home.submit", "parentId": "root", "windowId": "main",
                     "role": "Button", "label": "Submit", "bounds": {"left": 10, "top": 20, "right": 180, "bottom": 70},
                     "actions": ["tap"], "enabled": True, "description": ["excluded full description"]}
        self.snapshot = {"ok": True, "screen": "Home", "route": "home", "selectedId": "one",
                         "policyId": 8, "viewport": {"width": 400, "height": 800}, "nodes": [
                             {"id": "root", "label": "Home", "bounds": {}}, self.node,
                             {"id": "input", "label": "excluded input", "value": "excluded input", "actions": ["type"]},
                             {"id": "duplicate"}, {"id": "duplicate"}]}
        self.events = {"ok": True, "policyId": 8, "network": [
            {"url": "https://user:password@example.test/path?excluded=query#fragment", "method": "GET",
             "error": "excluded error", "requestPreview": "excluded body", "durationMs": 23}],
            "logs": [{"tag": "Synthetic", "message": "Example log [redacted]"}], "dataSources": {"excluded": "value"}}
        phone = self.serve(ThreadingHTTPServer(("127.0.0.1", 0), Device))
        phone.fixture = self
        self.bench = Workbench(self.root, None)
        self.server = self.serve(BridgeServer(("127.0.0.1", 0), phone.server_port, DEVICE_TOKEN, self.bench))
        self.bench.connection = {"package": PACKAGE, "devicePort": 8767}

    def serve(self, server):
        thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": .01}, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        return server

    def request(self, path, body=None, headers=None):
        connection = HTTPConnection("127.0.0.1", self.server.server_port, timeout=10)
        try:
            all_headers = {"Content-Type": "application/json", **(headers or {})}
            connection.request("GET" if body is None else "POST", "/api/" + path,
                               json.dumps(body) if body is not None else None, all_headers)
            with connection.getresponse() as response:
                return response.status, json.loads(response.read())
        finally:
            connection.close()

    def desktop(self, path, body=None, code=200):
        status, result = self.request(path, body, {"X-Qalens-Session": self.bench.session,
                                                 "X-Qalens-Connection": self.bench.connection_id})
        self.assertEqual(status, code, result)
        return result

    @contextmanager
    def client(self, state=None):
        state = state or self.desktop("agent/status")
        config = state["config"]["mcpServers"]["qalens"]
        with MCP(config["command"], config["args"]) as client:
            yield client

    def draft(self, code=200):
        return self.desktop("agent/draft", {"task": "edit", "nodeId": self.node["id"],
                            "intent": "Keep Submit visible in the small layout.",
                            "target": {"screen": "Home", "route": "home", "element": self.node}}, code)

    def test_review_share_explore_and_reference(self):
        with self.client() as client:
            tools = client.message("tools/list")["result"]["tools"]
            self.assertEqual(len(tools), 8)
            self.assertTrue(client.tool("qalens_tree")["isError"])
            self.assertIn("home.submit", self.draft()["request"]["prompt"])
            state = self.desktop("agent/access", {"enabled": True})
            access_file = Path(state["config"]["mcpServers"]["qalens"]["args"][-1])
            if os.name == "posix":
                self.assertEqual(stat.S_IMODE(access_file.stat().st_mode), 0o600)
            credentials = json.loads(access_file.read_text())
            self.assertNotIn(credentials["token"], json.dumps(state))
            agent = {"Authorization": "Bearer " + credentials["token"]}
            self.assertEqual(self.request("command", {"action": "tap"}, agent)[0], 401)
            self.assertEqual(self.request("agent/access", {"enabled": False}, agent)[0], 401)
            self.assertEqual(self.request("agent/tool", {"name": "qalens_screen"}, {
                **agent, "Origin": f"http://127.0.0.1:{self.server.server_port}"})[0], 403)
            self.assertEqual(self.request("agent/tool", {"name": "qalens_screen"}, {
                "Authorization": f"Bearer {DEVICE_TOKEN}"})[0], 401)

            screen = client.result("qalens_screen")
            self.assertEqual(screen["package"], PACKAGE)
            self.assertEqual(screen["omittedNodes"], 2)
            snapshot = screen["snapshotId"]
            page = client.result("qalens_tree", snapshotId=snapshot, limit=1)
            self.assertEqual(page["nextOffset"], 1)
            matches = client.result("qalens_tree", snapshotId=snapshot, query="home.submit")
            self.assertEqual([node["id"] for node in matches["nodes"]], ["one"])
            tree = client.result("qalens_tree", snapshotId=snapshot, offset=1)
            self.assertEqual(tree["nodes"][1]["label"], "[editable]")
            self.assertNotIn("excluded", json.dumps(tree))
            element = client.result("qalens_element", snapshotId=snapshot, nodeId="one")
            self.assertEqual(element["ancestors"][0]["id"], "root")
            self.assertTrue(client.tool("qalens_element", snapshotId="", nodeId="one")["isError"])

            request = self.draft()["request"]
            self.assertNotIn("excluded", request["prompt"])
            self.assertFalse(client.result("qalens_list_requests")["requests"])
            self.assertEqual(self.desktop("agent/send", {"id": request["id"]})["delivery"], "mcp")
            shared = client.result("qalens_get_request", requestId=request["id"])
            self.assertEqual(shared["prompt"], request["prompt"])
            self.assertEqual(client.result("qalens_list_requests")["requests"][0]["id"], request["id"])
            client.result("qalens_highlight", snapshotId=snapshot, nodeId="one", note="Verify the action area.")
            focus = self.desktop("agent/status")["focus"]
            self.assertEqual((focus["node"]["tag"], focus["note"]), ("home.submit", "Verify the action area."))
            self.assertTrue(all(path in {"/v1/health", "/v1/snapshot"} for path in self.routes))
            self.assertTrue(client.tool("qalens_screenshot")["isError"])
            self.assertTrue(client.tool("qalens_diagnostics", kind="logs")["isError"])

            self.desktop("agent/access", {"enabled": False})
            self.assertFalse(access_file.exists())
            self.desktop("agent/access", {"enabled": True})
            self.assertTrue(client.tool("qalens_tree")["isError"], "Old client must stay revoked")
            with self.client() as fresh:
                self.assertEqual(fresh.result("qalens_list_requests"), {"requests": []})

    def test_policy_and_stale_targets(self):
        self.health.pop("policyId")
        self.assertIn("Update", self.desktop("agent/access", {"enabled": True}, 409)["error"])
        self.health["policyId"] = 8
        self.desktop("agent/access", {"enabled": True})
        with self.client() as client:
            screen = client.result("qalens_screen")
            request = self.draft()["request"]
            self.snapshot["route"] = "settings"
            self.desktop("agent/send", {"id": request["id"]}, 409)
            self.draft(code=409)
            self.assertTrue(client.tool("qalens_highlight", snapshotId=screen["snapshotId"], nodeId="one")["isError"])
            self.snapshot["route"] = "home"
            self.desktop("agent/send", {"id": request["id"]})
            self.health["policyId"] = 9
            self.assertTrue(client.tool("qalens_get_request", requestId=request["id"])["isError"])
            self.assertFalse(self.desktop("agent/status")["enabled"])
        self.health["semanticsEnabled"] = False
        self.desktop("agent/access", {"enabled": True}, 403)

    def test_optional_evidence_and_device_revocation(self):
        self.desktop("agent/access", {"enabled": True, "screenshots": True, "diagnostics": True})
        with self.client() as client:
            image = client.tool("qalens_screenshot")["content"][0]
            self.assertEqual((image["mimeType"], base64.b64decode(image["data"])), ("image/png", PNG))
            network = client.result("qalens_diagnostics", kind="network")
            self.assertEqual(network["items"][0]["url"], "https://example.test/path")
            self.assertNotIn("excluded", json.dumps(network["items"]))
            self.assertIn("[redacted]", json.dumps(client.result("qalens_diagnostics", kind="logs")))
            self.revoked = True
            self.assertTrue(client.tool("qalens_tree")["isError"])
            self.assertFalse(self.desktop("agent/status")["enabled"])

    def test_phone_health_does_not_follow_redirect_with_pairing_credential(self):
        self.redirect_health = True
        self.desktop("agent/access", {"enabled": True}, 409)
        self.assertEqual(self.routes, ["/v1/health"])
        self.assertFalse(self.desktop("agent/status")["enabled"])

    def test_revoke_during_capture_discards_result(self):
        self.desktop("agent/access", {"enabled": True})
        self.block_snapshot = True
        with self.client() as client:
            result = []
            thread = threading.Thread(target=lambda: result.append(client.tool("qalens_tree")), daemon=True)
            thread.start()
            try:
                self.assertTrue(self.entered_snapshot.wait(3))
                self.desktop("agent/access", {"enabled": False})
            finally:
                self.resume_snapshot.set()
                thread.join(5)
            self.assertTrue(result[0]["isError"])

    def test_delayed_health_cannot_clear_newly_enabled_session(self):
        self.desktop("agent/access", {"enabled": True})
        self.delay_health = True
        with self.client() as client:
            result = []
            thread = threading.Thread(target=lambda: result.append(client.tool("qalens_screen")), daemon=True)
            thread.start()
            try:
                self.assertTrue(self.entered_health.wait(3))
                self.desktop("agent/access", {"enabled": False})
                self.health["policyId"] = self.snapshot["policyId"] = 9
                self.desktop("agent/access", {"enabled": True})
            finally:
                self.resume_health.set()
                thread.join(5)
            self.assertTrue(result[0]["isError"])
            self.assertTrue(self.desktop("agent/status")["enabled"])
            with self.client() as fresh:
                self.assertEqual(fresh.result("qalens_screen")["screen"], "Home")

    def test_fixed_argv_direct_delivery_is_explicit_and_idempotent(self):
        adapter = self.root / "adapter.py"
        output = self.root / "requests.jsonl"
        adapter.write_text("import json, sys\nwith open(sys.argv[1], 'a') as out:\n"
                           "    print(json.dumps(json.load(sys.stdin)), file=out)\n")
        config = self.root / "handoff.json"
        config.write_text(json.dumps({"name": "Synthetic harness", "argv": [sys.executable, str(adapter), str(output)]}))
        self.server.agents.handoff = Handoff.load(config)
        self.desktop("agent/access", {"enabled": True})
        request = self.draft()["request"]
        self.assertFalse(output.exists())
        for _ in range(2):
            response = self.desktop("agent/send", {"id": request["id"], "deliver": True, "argv": ["ignored"]})
            self.assertEqual(response["delivery"], "accepted")
        sent = [json.loads(line) for line in output.read_text().splitlines()]
        self.assertEqual(sent, [request])


if __name__ == "__main__":
    unittest.main()
