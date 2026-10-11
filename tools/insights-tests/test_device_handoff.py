#!/usr/bin/env python3
"""Opt-in disposable-emulator check: native QA/Send -> real PC HTTP -> saved/reanalyzed case.

Requires installed sample and instrumentation APKs. Starts a synthetic HTTP provider, not an LLM.
No real recordings or credentials are used; only this check's forward and temporary files are removed.
"""
import argparse
import http.client
import json
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import tempfile
import threading
import time

from model_fixture import MODEL, ModelFixture

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/local-bridge"))
from server import BridgeServer
from workbench import Workbench


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial", help="Disposable emulator with the current sample/test APKs")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("Use a disposable emulator; this check changes the synthetic sample app.")
    adb = shutil.which("adb")
    if not adb:
        parser.error("Put Android platform-tools on PATH.")
    command = [adb, "-s", args.serial]
    readiness = "cache/qalens-insights-handoff-ready.json"
    forward = None
    runner = None
    with tempfile.TemporaryDirectory(prefix="qalens-native-handoff-") as directory:
        model = ModelFixture(("127.0.0.1", 0), expose_slow=True)
        threading.Thread(target=model.serve_forever, daemon=True).start()
        bench = Workbench(Path(directory) / "desktop")
        token = secrets.token_hex(24)
        pc = BridgeServer(("127.0.0.1", 0), None, token, bench)
        threading.Thread(target=pc.serve_forever, daemon=True).start()

        def shell(*items, check=True):
            return subprocess.run(command + ["shell", *items], text=True, capture_output=True,
                                  timeout=12, check=check).stdout.strip()

        def api(route, body=None):
            connection = http.client.HTTPConnection("127.0.0.1", pc.server_port, timeout=8)
            try:
                connection.request("POST" if body is not None else "GET", "/api/" + route,
                                   json.dumps(body).encode() if body is not None else None,
                                   {"Content-Type": "application/json", "X-Qalens-Session": bench.session,
                                    "X-Qalens-Connection": bench.connection_id})
                response = connection.getresponse()
                result = json.loads(response.read())
                assert response.status == 200, (response.status, result)
                return result
            finally:
                connection.close()

        try:
            shell("run-as", "com.qalens.sample", "rm", "-f", readiness)
            with (Path(directory) / "native.log").open("w+") as output:
                runner = subprocess.Popen(command + ["shell", "am", "instrument", "-w", "-e", "insightsOnly", "true",
                    "-e", "insightsBaseUrl", f"http://10.0.2.2:{model.server_port}",
                    "-e", "insightsTransferHoldSeconds", "60", "-e", "insightsTransferToken", token,
                    "com.qalens.sample.test/com.qalens.sample.RecordingRetentionInstrumentation"], stdout=output, stderr=subprocess.STDOUT)
                deadline = time.monotonic() + 240
                ready = None
                while time.monotonic() < deadline and runner.poll() is None:
                    raw = shell("run-as", "com.qalens.sample", "cat", readiness, check=False)
                    if raw:
                        try: ready = json.loads(raw)
                        except ValueError: pass
                        if ready and ready.get("ready") is True: break
                    time.sleep(.3)
                if not ready:
                    output.seek(0)
                    raise AssertionError("Native handoff did not become ready: " + output.read()[-12_000:])
                assert ready["port"] == 8766
                forward = subprocess.check_output(command + ["forward", "tcp:0", "tcp:8766"], text=True, timeout=12).strip()
                assert forward.isdecimal()
                pc.device_port = int(forward)
                with model.metrics_lock: before = model.metrics["chatRequests"]
                inbox = api("investigations/inbox")
                assert inbox["received"] == 1 and inbox["acknowledgedCount"] == 1, inbox
                digest = inbox["previews"][0]["hash"]
                document = api("investigations/document?hash=" + digest)["document"]
                assert document["schema"] == "qalens-investigation-transfer/1"
                bundle, report = document["bundle"], document["report"]
                ids = {item["id"] for item in bundle["items"]}
                assert {"timeline:0", "network:0", "logs:1", "state:0", "connectivity:0"} <= ids
                assert report["qaReport"]["steps"][0]["evidenceIds"] == ["timeline:0"]
                assert report["qaReport"]["expectedResult"] == bundle["qaContext"]["expectedResult"]
                assert report["qaReport"]["actualResult"] == bundle["qaContext"]["actualResult"]
                assert len(bundle["images"]) == 1 and bundle["images"][0]["source"] == "recording-video"
                assert not list(bench.investigations.directory.glob("*.json"))
                with model.metrics_lock: assert model.metrics["chatRequests"] == before, "Receive called a model"
                assert api("investigations/save", {"hash": digest})["duplicate"] is False
                assert api("investigations/save", {"hash": digest})["duplicate"] is True
                assert api("investigations/document?hash=" + digest)["document"] == document
                config = {"baseUrl": f"http://127.0.0.1:{model.server_port}", "protocol": "openai", "model": MODEL}
                job_id = api("insights/analyze", {"config": config, "bundle": bundle, "includeImage": True})["job"]["id"]
                deadline = time.monotonic() + 12
                while time.monotonic() < deadline:
                    job = api("insights/jobs?id=" + job_id)["job"]
                    if job["state"] in ("done", "error", "cancelled"): break
                    time.sleep(.05)
                assert job["state"] == "done", job
                assert job["report"]["qaReport"]["actualResult"] == bundle["qaContext"]["actualResult"]
                assert {"network:0", "logs:1"} <= {ident for item in job["report"]["observations"] for ident in item["evidenceIds"]}
                runner.wait(timeout=30)
                output.seek(0)
                result = output.read()
                assert runner.returncode == 0 and "OK:" in result and "INSTRUMENTATION_FAILED" not in result, result[-12_000:]
            print("OK: native QA/evidence/report/still handoff, authenticated PC receive/ack, explicit save/dedup and model reanalysis.")
        finally:
            if runner and runner.poll() is None:
                # Only the explicitly selected disposable emulator's synthetic sample runner.
                shell("am", "force-stop", "com.qalens.sample", check=False)
                runner.terminate()
                runner.wait(timeout=12)
            if forward:
                subprocess.run(command + ["forward", "--remove", "tcp:" + forward], capture_output=True, timeout=12)
            pc.shutdown(); pc.server_close(); bench.close(pc)
            model.shutdown(); model.server_close()


if __name__ == "__main__":
    main()
