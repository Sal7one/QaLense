#!/usr/bin/env python3
"""Opt-in real API36 sample fixture: phone approval, screen PNG, attributes and owned forward repair.
Requires installed sample/test APKs; only run on a disposable emulator. Not part of test discovery.
"""
import argparse
import json
from pathlib import Path
import secrets
import shutil
import subprocess
import struct
import sys
import tempfile
import threading
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from workbench import Workbench
from server import BridgeServer


def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument("serial")
    parser.add_argument("--device-port", type=int, default=8767)
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"): parser.error("Use a disposable emulator")
    if not 1024 <= args.device_port <= 65535: parser.error("Device port must be 1024–65535")
    adb = shutil.which("adb")
    if not adb: parser.error("adb required")
    command = [adb, "-s", args.serial]
    def shell(*items): return subprocess.check_output(command + ["shell", *items], text=True, stderr=subprocess.DEVNULL, timeout=12).strip()
    def wait(condition, message):
        until = time.monotonic() + 45
        while time.monotonic() < until:
            try:
                if condition(): return
            except HTTPError as error: error.close()
            except (subprocess.CalledProcessError, URLError): pass
            time.sleep(.2)
        raise AssertionError(message)
    with tempfile.TemporaryDirectory(prefix="qalens-pairing-") as directory:
        bench = Workbench(directory, adb)
        server = BridgeServer(("127.0.0.1", 0), None, None, bench)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        def api(path, body=None, binary=False):
            headers = {"X-Qalens-Session":bench.session, "X-Qalens-Connection":bench.connection_id}
            if body is not None: headers["Content-Type"] = "application/json"
            request = Request(f"http://127.0.0.1:{server.server_port}/api/{path}", data=json.dumps(body).encode() if body is not None else None, headers=headers)
            with urlopen(request, timeout=15) as response: return response.read() if binary else json.loads(response.read())
        fixture = None
        try:
            # Reject leftover checkpoints from an interrupted previous fixture run.
            shell("run-as", "com.qalens.sample", "rm", "-rf", "files/qalens-transfer-test")
            fixture = subprocess.Popen(command + ["shell", "am", "instrument", "-w", "-e", "desktopTransferToken", secrets.token_hex(24), "-e", "desktopPhoneApproval", "true", "com.qalens.sample.test/com.qalens.sample.RecordingRetentionInstrumentation"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            stage = lambda: shell("run-as", "com.qalens.sample", "cat", "files/qalens-transfer-test/stage")
            def signal(value): shell("run-as", "com.qalens.sample", "sh", "-c", f"'printf %s {value} > files/qalens-transfer-test/signal'")
            wait(lambda: stage() == "ready", "Fixture did not start")
            apps = api("apps", {"serial":args.serial})["apps"]
            app = next(a for a in apps if a["package"] == "com.qalens.sample")
            assert app["qalens"]
            api("pair", {"profile":{"serial":args.serial, **app, "devicePort":args.device_port}})
            assert not api("workbench")["connected"]
            assert bench.phase == "awaiting-approval"
            assert server.token not in bench.profiles_path.read_text()
            # Verify pending approval without relying on a fake approved flag.
            assert bench.check_phone_approval(server) == 10
            signal("approve"); wait(lambda: stage() == "paired", "Phone did not approve")
            assert bench.check_phone_approval(server) == 12
            assert bench.connection["devicePort"] == args.device_port
            # Phone is listening, but PC lost its forward during approval: report approval
            # truthfully, recreate only our transport, then authenticate without a second prompt.
            approval_id = bench.connection_id
            subprocess.check_call(command + ["forward", "--remove", f"tcp:{server.device_port}"], stdout=subprocess.DEVNULL)
            bench.last_check = 0
            connecting = api("connection/check", {"reconnect":True})
            assert connecting["phase"] == "connecting" and not connecting["connected"]
            assert "Phone approved" in connecting["notice"]
            wait(lambda: api("connection/check", {"reconnect":True})["phase"] == "connected", "PC did not authenticate")
            assert bench.connection_id == approval_id
            assert bench.health_supported is True
            snapshot = api("snapshot")
            node = next(n for n in snapshot["nodes"] if n["tag"] == "home.total.balance")
            document = api("component", {"id":node["id"]})["document"]
            assert document["content"]["component"]["attributes"]
            assert api("save", {"hash":document["hash"]})["ok"]
            assert api("save", {"hash":document["hash"]})["duplicate"]
            # Current default: actual scrcpy H.264, no hidden legacy-flag override.
            mirror = api("preview", {"enabled":True,"connectionId":bench.connection_id, "mode":"preview"})
            assert mirror["version"] == "5.0"
            headers = {"X-Qalens-Session":bench.session, "X-Qalens-Connection":bench.connection_id,
                "X-Qalens-Mirror":mirror["streamId"]}
            with urlopen(Request(f"http://127.0.0.1:{server.server_port}/api/mirror/video", headers=headers), timeout=10) as video:
                assert video.read(4) == b"h264"
                flag, width, height = struct.unpack(">III", video.read(12))
                assert flag & 0x80000000 and width > 0 and height > 0
            api("preview", {"enabled":False,"connectionId":bench.connection_id, "streamId":mirror["streamId"]})
            ident = bench.connection_id; old = server.device_port
            subprocess.check_call(command + ["forward", "--remove", f"tcp:{old}"], stdout=subprocess.DEVNULL)
            bench.last_check = 0
            assert api("connection/check", {"reconnect":True})["phase"] == "reconnecting"
            wait(lambda: api("connection/check", {"reconnect":True})["phase"] == "connected", "Owned forward not repaired")
            assert bench.connection_id == ident and server.device_port != old
            # Default SDK select leaves its real Send button visible; fixture uses that button.
            api("command", {"action":"select", "id":node["id"]})
            signal("send"); wait(lambda: stage() == "sent", "Phone Send did not complete")
            signal("record"); wait(lambda: stage() == "saved", "Fixture did not save")
            inbox = api("inbox")
            assert inbox["received"] == 1 and inbox["documents"][-1]["content"]["component"]["tag"] == node["tag"]
            names = api("recordings/device")["items"][:2]
            assert any(n["name"].startswith("clip_") for n in names)
            for entry in names: api("recordings/receive", {"name":entry["name"], "connectionId":ident})
            assert len(api("recordings/local")["items"]) == 2
            signal("copied"); wait(lambda: stage() == "rotated", "Fixture did not rotate")
            bench.last_check = 0
            assert api("connection/check", {"reconnect":True})["phase"] == "revoked"
            assert server.token is None and bench.owned_forward is None
            signal("done")
            output, _ = fixture.communicate(timeout=15)
            assert "OK:" in output, output
            print(f"PASS: real phone approval on port {args.device_port}, missing-forward approval handoff/recovery, authenticated lightweight health, tree/attributes/save, scrcpy stream, forward repair, paused-host Send/recording copies and auth revocation")
        finally:
            server.shutdown(); server.server_close(); bench.close(server)
            if fixture and fixture.poll() is None:
                subprocess.run(command + ["shell", "am", "force-stop", "com.qalens.sample"], stdout=subprocess.DEVNULL)
                fixture.terminate()
                try:
                    output, _ = fixture.communicate(timeout=5)
                    if "FAIL" in output or "Exception" in output: print(output, file=sys.stderr)
                except subprocess.TimeoutExpired: fixture.kill(); fixture.communicate()

if __name__ == "__main__": main()
