"""Local, explicit component storage/profiles and trusted argv processing pipelines."""
import hashlib
import json
import math
import os
from pathlib import Path
import re
import secrets
import signal
import subprocess
import sys
import tempfile
import threading
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request
from http.client import IncompleteRead
from device_http import urlopen

KILL = getattr(signal, "SIGKILL", signal.SIGTERM)
MAX_DOCUMENT = 256 * 1024
HASH = re.compile(r"[0-9a-f]{64}")
PACKAGE = re.compile(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+")


class StorageUnavailable(OSError):
    """A specific workspace directory could not be created or written."""
    def __init__(self, directory, error):
        super().__init__(error.errno, error.strerror or str(error), str(directory))


def prepare_directory(directory):
    """Check actual writes, including existing directories; access()/mkdir alone are insufficient."""
    try:
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        with tempfile.NamedTemporaryFile(dir=directory, prefix=".qalens-write-check-") as probe:
            probe.write(b"QaLens")
            probe.flush()
    except OSError as error:
        raise StorageUnavailable(directory, error) from error


def canonical(document):
    if not isinstance(document, dict) or document.get("schema") != "qalens.component" or document.get("version") != 1:
        raise ValueError("Use a QaLens component v1 JSON document")
    if type(document.get("version")) is not int: raise ValueError("Component version must be an integer")
    content = document.get("content")
    if not isinstance(content, dict) or not isinstance(content.get("component"), dict) or not isinstance(content.get("tree"), dict):
        raise ValueError("Component attributes and tree context required")
    tree = content["tree"]
    if not isinstance(tree.get("path", []), list) or len(tree.get("path", [])) > 64 or not all(isinstance(p, dict) for p in tree.get("path", [])):
        raise ValueError("Tree path must contain <=64 entries")
    attributes = content["component"].get("attributes", [])
    if not isinstance(attributes, list) or len(attributes) > 100 or not all(isinstance(a, dict) and isinstance(a.get("name"), str) for a in attributes):
        raise ValueError("Attributes require named entries, at most 100")
    def normalize(value, depth=0):
        if depth > 24: raise ValueError("Component nesting exceeds limit")
        if isinstance(value, dict): return {k: normalize(v, depth + 1) for k, v in value.items()}
        if isinstance(value, list): return [normalize(v, depth + 1) for v in value]
        if type(value) in (int, float):
            if not math.isfinite(value) or abs(value) > 2**53 - 1: raise ValueError("Numbers must be finite and within JSON safe precision")
            return int(value) if value == int(value) else value
        return value
    content = normalize(content)
    encoded = json.dumps(content, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False).encode()
    if len(encoded) > MAX_DOCUMENT:
        raise ValueError("Component exceeds 256 KiB")
    # Content alone: live IDs, timestamps, transfer IDs and device serials are provenance.
    digest = hashlib.sha256(encoded).hexdigest()
    if document.get("hash", digest) != digest: raise ValueError("Component hash mismatch")
    return {"schema": "qalens.component", "version": 1, "hash": digest, "content": content,
            "capturedAtMillis": document.get("capturedAtMillis"), "liveNodeId": document.get("liveNodeId")}


def write_json(path, data):
    """Atomic replacement, private permissions, no user-controlled destination paths."""
    temporary = path.with_name(path.name + "." + secrets.token_hex(8) + ".tmp")
    try:
        with open(temporary, "x", encoding="utf-8") as stream:
            os.chmod(temporary, 0o600)
            json.dump(data, stream, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
            stream.write("\n")
            stream.flush(); os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def profile(input):
    """Small QaLens profile; Appium capabilities are an import format, not a driver."""
    if not isinstance(input, dict):
        raise ValueError("Profile must be an object")
    def field(name, fallback=""):
        value = input.get(name, input.get("appium:" + name, fallback))
        if not isinstance(value, str) or len(value) > 256:
            raise ValueError(f"{name} must be text <=256 characters")
        return value.strip()
    package = field("package", field("appPackage"))
    activity = field("activity", field("appActivity"))
    serial = field("serial", field("udid"))
    if not PACKAGE.fullmatch(package):
        raise ValueError("Package must be an Android application ID (com.example.app), not a URL")
    if activity and not re.fullmatch(r"\.?[A-Za-z][A-Za-z0-9_.$]*", activity):
        raise ValueError("Use an Android activity class, e.g. .MainActivity")
    if not re.fullmatch(r"[A-Za-z0-9_.:\[\]-]{1,128}", serial):
        raise ValueError("Choose a valid adb device serial")
    platform = field("platformName", "Android")
    if platform.lower() != "android": raise ValueError("QaLens supports Android Compose")
    port = input.get("devicePort", 8766)
    if isinstance(port, bool) or not isinstance(port, int) or not 1024 <= port <= 65535:
        raise ValueError("Device port must be 1024–65535")
    return {"id": hashlib.sha256(f"{serial}\n{package}\n{activity}\n{port}".encode()).hexdigest()[:24],
            "name": field("name", package), "serial": serial, "package": package, "activity": activity,
            "platformVersion": field("platformVersion"), "devicePort": port}


class Workbench:
    def __init__(self, data_dir, adb=None, pipeline_config=None):
        self.root = Path(data_dir).expanduser().absolute()
        prepare_directory(self.root)
        for name in ("components", "runs", "investigations"):
            prepare_directory(self.root / name)
        self.adb = adb
        self.lock = threading.RLock()
        from investigations import Store
        self.investigations = Store(self.root / "investigations")
        self.preview_dropped = 0
        self.pending = {}  # bounded previews; never implicitly persisted
        self.profiles_path = self.root / "profiles.json"
        self.session = secrets.token_urlsafe(32)
        self.connection = None
        self.connection_id = secrets.token_hex(16)
        self.owned_forward = None
        self.phase = "disconnected"
        self.pair_deadline = 0
        self.approved = False
        self.phone_approved = False
        self.approval_query_supported = None
        self.health_supported = None
        self.last_check = 0
        self.connection_notice = "Connect your phone to begin."
        self.jobs = {}
        self.pipelines = self.load_pipelines(pipeline_config)
        self.worker = None
        self.stopping = threading.Event()
        self.process = None
        from desktop import Desktop
        self.desktop = Desktop(self)

    def load_pipelines(self, path):
        if not path: return {}
        config_path = Path(path).resolve()
        if config_path.stat().st_size > 64 * 1024: raise ValueError("Pipeline config exceeds 64 KiB")
        config = json.loads(config_path.read_text())
        if not isinstance(config, dict): raise ValueError("Pipeline config must be an object")
        pipelines = config.get("pipelines", [])
        if not isinstance(pipelines, list) or len(pipelines) > 20: raise ValueError("At most 20 pipelines")
        result = {}
        for item in pipelines:
            if not isinstance(item, dict): raise ValueError("Pipeline must be an object")
            ident = item.get("id", "")
            if not re.fullmatch(r"[a-zA-Z0-9_-]{1,64}", ident) or ident in result: raise ValueError("Unique pipeline id required")
            steps = item.get("steps", [])
            if not isinstance(steps, list) or not 1 <= len(steps) <= 10: raise ValueError("Pipeline requires 1–10 steps")
            for step in steps:
                if not isinstance(step, dict): raise ValueError("Pipeline step must be an object")
                argv = step.get("argv")
                if not isinstance(argv, list) or not 1 <= len(argv) <= 64 or not all(isinstance(arg, str) and len(arg) <= 4096 for arg in argv):
                    raise ValueError("Steps require argv arrays; shell command strings are unsupported")
                timeout = step.get("timeoutSeconds", 30)
                if type(timeout) is not int or not 1 <= timeout <= 300: raise ValueError("Timeout must be 1–300 seconds")
            result[ident] = dict(item, cwd=str(config_path.parent))
        return result

    def profiles(self):
        if not self.profiles_path.exists(): return []
        if self.profiles_path.stat().st_size > 128 * 1024: raise ValueError("Profiles file exceeds limit")
        result = json.loads(self.profiles_path.read_text())
        if not isinstance(result, list) or len(result) > 100: raise ValueError("Invalid profiles file")
        return [profile(item) for item in result]

    def save_profile(self, value):
        clean = profile(value)
        with self.lock:
            items = [item for item in self.profiles() if item["id"] != clean["id"]]
            if len(items) >= 100: raise ValueError("At most 100 profiles")
            write_json(self.profiles_path, items + [clean])
        return clean

    def preferences(self):
        path = self.root / "desktop.json"
        if not path.exists(): return {"autoConnect": False, "profileId": ""}
        if path.stat().st_size > 4096: raise ValueError("Desktop preferences exceed limit")
        value = json.loads(path.read_text())
        if not isinstance(value, dict): raise ValueError("Invalid desktop preferences")
        return {"autoConnect": value.get("autoConnect") is True, "profileId": str(value.get("profileId", ""))[:24]}

    def save_preferences(self, body):
        if type(body.get("autoConnect")) is not bool: raise ValueError("Choose whether to auto connect")
        with self.lock:
            ident = body.get("profileId", "")
            if body["autoConnect"] and not any(p["id"] == ident for p in self.profiles()):
                raise ValueError("Connect once and choose a saved app before enabling auto connect")
            clean = {"autoConnect": body["autoConnect"], "profileId": ident if body["autoConnect"] else ""}
            write_json(self.root / "desktop.json", clean)
            return clean

    def adb_call(self, arguments, serial=None, timeout=10):
        if not self.adb: raise ValueError("adb unavailable; pass --adb /path/to/adb")
        command = [self.adb] + (["-s", serial] if serial else []) + arguments
        try:
            result = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
        except (OSError, subprocess.TimeoutExpired) as error:
            raise ValueError("adb unavailable or timed out; check the device") from error
        if result.returncode: raise ValueError("adb rejected the request; check connection/authorization/package")
        return result.stdout[:256 * 1024]

    def devices(self):
        rows = self.adb_call(["devices", "-l"]).splitlines()[1:]
        return [{"serial": row.split()[0], "state": row.split()[1], "description": " ".join(row.split()[2:])}
                for row in rows if len(row.split()) >= 2][:100]

    def connect(self, server, value, token):
        clean = profile(value)
        if not isinstance(token, str) or not re.fullmatch(r"[A-Za-z0-9_-]{24,128}", token):
            raise ValueError("Enter the app's random 24–128 character pairing token")
        if not any(d["serial"] == clean["serial"] and d["state"] == "device" for d in self.devices()):
            raise ValueError("Device is disconnected or unauthorized")
        version = self.adb_call(["shell", "getprop", "ro.build.version.release"], clean["serial"]).strip()
        installed = self.adb_call(["shell", "pm", "path", clean["package"]], clean["serial"]).strip()
        if not installed.startswith("package:"): raise ValueError("Package is not installed on this device")
        forward = self.adb_call(["forward", "tcp:0", f"tcp:{clean['devicePort']}"], clean["serial"]).strip()
        try: port = int(forward)
        except ValueError: raise ValueError("adb returned an invalid forwarding port")
        if not 1 <= port <= 65535: raise ValueError("adb returned an invalid forwarding port")
        with self.lock:
            self.disconnect(server)
            self.owned_forward = (clean["serial"], port)
            self.connection = dict(clean, actualPlatformVersion=version)
            server.device_port = port; server.token = token
            self.phase = "connected"; self.approved = True
            self.phone_approved = False; self.approval_query_supported = None; self.health_supported = None
            self.last_check = 0
            self.connection_notice = "Connected. App data preserved."
        return self.connection

    def disconnect(self, server):
        self.desktop.stop_mirror()
        with self.lock:
            if self.connection and self.phase in ("awaiting-approval", "connecting") and server.token:
                try:
                    self.adb_call(["shell", "am", "broadcast", "-n", self.connection["package"] + "/com.qalens.QaLensPcPairingReceiver",
                        "-a", "com.qalens.action.CANCEL_PC_PAIRING", "--es", "token", server.token], self.connection["serial"])
                except ValueError: pass
            server.device_port = None; server.token = None
            self.remove_owned_forward()
            self.connection = None
            self.phase = "disconnected"; self.approved = False; self.pair_deadline = 0
            self.phone_approved = False; self.approval_query_supported = None; self.health_supported = None
            self.connection_notice = "Disconnected. Choose your app and Connect."
            self.desktop.stop_preview()
            self.investigations.clear()
            self.connection_id = secrets.token_hex(16)

    def apps(self, serial):
        if not isinstance(serial, str) or not re.fullmatch(r"[A-Za-z0-9_.:\[\]-]{1,128}", serial):
            raise ValueError("Choose an authorized phone")
        rows = self.adb_call(["shell", "cmd", "package", "query-activities", "--components", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER"], serial).splitlines()
        apps = {}
        for row in rows:
            parts = row.strip().split("/", 1)
            if len(parts) != 2 or not PACKAGE.fullmatch(parts[0]): continue
            package, activity = parts
            if not re.fullmatch(r"\.?[A-Za-z][A-Za-z0-9_.$]*", activity): continue
            app = apps.setdefault(package, {"package": package, "activity": "", "qalens": False})
            if activity == "com.qalens.QaLensControlActivity": app["qalens"] = True
            elif not activity.startswith("com.qalens.") and not app["activity"]: app["activity"] = activity
        return sorted(apps.values(), key=lambda a: (not a["qalens"], a["package"]))[:2000]

    def pair(self, server, value):
        # Token generated here, never returned to the GUI, a profile, or Activity extras.
        with self.lock:
            self.connect(server, value, secrets.token_urlsafe(32))
            self.phase = "awaiting-approval"; self.approved = False
            self.pair_deadline = time.monotonic() + 120
            self.connection_notice = "Approve desktop access on your phone."
            current = self.connection
            try:
                result = self.adb_call(["shell", "am", "broadcast", "-n", current["package"] + "/com.qalens.QaLensPcPairingReceiver",
                    "-a", "com.qalens.action.REQUEST_PC_PAIRING", "--es", "token", server.token,
                    "--ei", "port", str(current["devicePort"])], current["serial"])
                if "Permission Denial" in result or "Exception" in result: raise ValueError("Pairing request was rejected")
                code = re.search(r"Broadcast completed: result=(\d+)", result)
                if not code or code.group(1) != "1":
                    notices = {"2": "A pairing request is already open on the phone. Deny or finish it, then Connect again.",
                               "3": "QaLens is disabled in the app. Enable it before connecting."}
                    raise ValueError(notices.get(code.group(1) if code else "", "Phone approval is unavailable. Update the app's QaLens SDK, or use Advanced manual connection."))
                self.adb_call(["shell", "am", "start", "-n", current["package"] + "/com.qalens.QaLensControlActivity"], current["serial"])
                self.save_profile(current)
            except ValueError as error:
                self.disconnect(server)
                raise ValueError(str(error)) from error
            return self.connection

    def check_phone_approval(self, server):
        """Read only the offered credential's state; never approve or scan other ports/apps."""
        if self.approval_query_supported is False: return None
        current = self.connection
        try:
            result = self.adb_call(["shell", "am", "broadcast", "--receiver-foreground", "-n",
                current["package"] + "/com.qalens.QaLensPcPairingReceiver", "-a", "com.qalens.action.QUERY_PC_PAIRING",
                "--es", "token", server.token], current["serial"], timeout=2)
        except ValueError: return None
        match = re.search(r"Broadcast completed: result=(\d+)", result)
        if not match: return None
        code = int(match.group(1))
        if code == 0:
            self.approval_query_supported = False  # Older SDK: authenticated HTTP remains the proof.
            return None
        if code not in (10, 11, 12, 13, 14): return None
        self.approval_query_supported = True
        if code in (11, 12):
            self.phone_approved = True
            port_match = re.search(r'data="(\d+)"', result)
            if port_match:
                port = int(port_match.group(1))
                if 1024 <= port <= 65535 and port != current["devicePort"]:
                    # The phone proves this port belongs to our exact approved request. Allocate a
                    # fresh forward, then release only our old one; never rebind someone else's port.
                    try:
                        forwarded = int(self.adb_call(["forward", "tcp:0", f"tcp:{port}"], current["serial"]).strip())
                        if not 1 <= forwarded <= 65535: raise ValueError("Invalid adb port")
                    except ValueError: return code
                    self.remove_owned_forward()
                    self.connection = dict(current, devicePort=port)
                    self.owned_forward = (current["serial"], forwarded); server.device_port = forwarded
        return code

    def probe_connection(self, server):
        route = "/v1/recordings" if self.health_supported is False else "/v1/health"
        while True:
            request = Request(f"http://127.0.0.1:{server.device_port}{route}", headers={"Authorization": f"Bearer {server.token}"})
            try:
                with urlopen(request, timeout=2) as response:
                    limit = 512 * 1024 if route == "/v1/recordings" else 4096
                    payload = response.read(limit + 1)
                    if len(payload) > limit: raise ValueError("Bridge response exceeds limit")
                    value = json.loads(payload)
                    if response.code != 200 or not isinstance(value, dict) or value.get("ok") is not True:
                        raise ValueError("Bridge unavailable")
                    if route == "/v1/health":
                        if (value.get("schema") != "qalens.bridge.health" or type(value.get("version")) is not int or value["version"] != 1 or
                            value.get("package") != self.connection["package"] or value.get("port") != self.connection["devicePort"]):
                            raise ValueError("Unexpected bridge identity")
                        self.health_supported = True
                    return
            except HTTPError as error:
                if route != "/v1/health" or error.code != 404: raise
                error.close()
                self.health_supported = False; route = "/v1/recordings"

    def check_connection(self, server, reconnect=True):
        with self.lock:
            if not self.connection or not server.token: return self.connection_state(server)
            now = time.monotonic()
            if now - self.last_check < 1: return self.connection_state(server)
            self.last_check = now
            if not self.approved and now >= self.pair_deadline:
                approved_on_phone = self.phone_approved
                self.disconnect(server); self.phase = "expired"
                self.connection_notice = ("Phone approved, but the PC could not reach its inspector. Check USB/adb and click Connect again." if approved_on_phone else
                    "Phone approval expired or was denied. Click Connect to try again.")
                return self.connection_state(server)
            failure = None
            try:
                self.probe_connection(server)
                self.phase = "connected"; self.approved = True
                self.connection_notice = "Connected. Select a component to inspect it."
            except HTTPError as error:
                if error.code in (401, 403) and self.approved:
                    self.disconnect(server); self.phase = "revoked"
                    self.connection_notice = "Phone revoked access. Click Connect and approve again."
                else: failure = error.code
                error.close()
            except (URLError, OSError, ValueError, IncompleteRead):
                failure = "transport"
            if failure is not None:
                if not self.approved:
                    code = self.check_phone_approval(server)
                    if code in (13, 14):
                        self.disconnect(server); self.phase = "ended"
                        self.connection_notice = ("QaLens is disabled on the phone. Enable it, then Connect again." if code == 14 else
                            "The phone request ended or its inspector could not start. Click Connect again; avoid starting a separate manual pairing.")
                        return self.connection_state(server)
                    self.phase = "connecting" if self.phone_approved else "awaiting-approval"
                    self.connection_notice = (f"Phone approved. Connecting to its inspector on device port {self.connection['devicePort']}… Check USB/adb if this persists." if self.phone_approved else
                        f"Approve desktop access on your phone (device port {self.connection['devicePort']}).")
                else:
                    self.phase = "reconnecting"
                    self.connection_notice = "Phone temporarily unavailable. Reconnecting…" if reconnect else "Phone unavailable. Auto reconnect is off."
                if reconnect:
                    try:
                        current = self.connection
                        if any(d["serial"] == current["serial"] and d["state"] == "device" for d in self.devices()):
                            # Recreate an absent forward; never steal a local port rebound to another target.
                            rows = [r.split() for r in self.adb_call(["forward", "--list"]).splitlines()]
                            wanted = [current["serial"], f"tcp:{server.device_port}", f"tcp:{current['devicePort']}"]
                            if wanted not in rows:
                                port = int(self.adb_call(["forward", "tcp:0", wanted[2]], current["serial"]).strip())
                                if not 1 <= port <= 65535: raise ValueError("Invalid adb port")
                                self.remove_owned_forward()
                                self.owned_forward = (current["serial"], port); server.device_port = port
                    except (ValueError, TypeError): pass
            return self.connection_state(server)

    def remove_owned_forward(self):
        if not self.owned_forward: return
        serial, port = self.owned_forward
        self.owned_forward = None
        try:
            expected = f"tcp:{self.connection['devicePort']}" if self.connection else None
            rows = [r.split() for r in self.adb_call(["forward", "--list"]).splitlines()]
            if any(r[:2] == [serial, f"tcp:{port}"] and (expected is None or r[2:] == [expected]) for r in rows):
                self.adb_call(["forward", "--remove", f"tcp:{port}"], serial)
        except ValueError: pass

    def connection_state(self, server):
        return {"ok": True, "phase": self.phase, "notice": self.connection_notice,
                "connected": bool(server.token) and (self.approved or self.connection is None),
                "connection": self.connection, "connectionId": self.connection_id,
                "preview": self.desktop.preview_active}

    def preview(self, document):
        clean = canonical(document)
        with self.lock:
            # Refresh provenance and move to the end; changed content has a different key.
            self.pending.pop(clean["hash"], None)
            self.pending[clean["hash"]] = clean
            while len(self.pending) > 10 or sum(len(json.dumps(d).encode()) for d in self.pending.values()) > 2 * 1024 * 1024:
                self.pending.pop(next(iter(self.pending))); self.preview_dropped += 1
        return clean

    def document(self, digest):
        if not isinstance(digest, str) or not HASH.fullmatch(digest): raise ValueError("Invalid component hash")
        path = self.root / "components" / (digest + ".json")
        if not path.exists(): raise ValueError("Save the component first")
        if path.stat().st_size > 2 * MAX_DOCUMENT: raise ValueError("Stored component exceeds limit")
        document = canonical(json.loads(path.read_text()))
        if document["hash"] != digest: raise ValueError("Component hash mismatch")
        return document

    def save(self, digest):
        with self.lock:
            document = self.pending.get(digest)
            if not document: raise ValueError("Preview expired; capture or import again")
            path = self.root / "components" / (document["hash"] + ".json")
            duplicate = path.exists()
            if duplicate: self.document(digest)  # detect corruption instead of silently accepting it
            else: write_json(path, document)
        return {"ok": True, "hash": digest, "duplicate": duplicate, "file": str(path.resolve())}

    def saved(self):
        paths = sorted((self.root / "components").glob("*.json"), key=lambda p: p.stat().st_mtime, reverse=True)
        items = []
        for path in paths[:500]:
            if not HASH.fullmatch(path.stem): continue
            try:
                doc = self.document(path.stem)
                items.append({"hash": path.stem, "component": str(doc["content"]["component"].get("label", "Component"))[:256],
                              "package": doc["content"].get("package"), "capturedAtMillis": doc.get("capturedAtMillis")})
            except (ValueError, OSError): continue
        return {"ok": True, "items": items, "omitted": max(0, len(paths) - 500)}

    def artifacts(self, job_id):
        if not isinstance(job_id, str) or not re.fullmatch(r"[0-9a-f]{24}", job_id): raise ValueError("Invalid run id")
        directory = (self.root / "runs" / job_id).resolve()
        if directory.parent != (self.root / "runs").resolve() or not directory.is_dir(): raise ValueError("Run unavailable")
        return [{"name": p.name, "size": p.stat().st_size} for p in sorted(directory.iterdir())
                if p.is_file() and not p.is_symlink() and p.stat().st_size <= 1024 * 1024][:100]

    def artifact(self, job_id, name):
        files = self.artifacts(job_id)
        if not isinstance(name, str) or not any(f["name"] == name for f in files): raise ValueError("Output unavailable or exceeds 1 MiB")
        path = self.root / "runs" / job_id / name
        if path.is_symlink() or path.resolve().parent != (self.root / "runs" / job_id).resolve(): raise ValueError("Output outside run directory")
        try:
            with open(path, "rb") as stream: text = stream.read(1024 * 1024 + 1).decode("utf-8")
        except UnicodeDecodeError: raise ValueError("GUI previews UTF-8 output files only")
        if len(text.encode()) > 1024 * 1024: raise ValueError("Output exceeds 1 MiB")
        return {"ok": True, "name": name, "text": text}

    def run_pipeline(self, ident, digest):
        if ident not in self.pipelines: raise ValueError("Choose a pipeline configured by the PC owner")
        self.document(digest)
        with self.lock:
            if self.worker and self.worker.is_alive(): raise ValueError("A pipeline is running; wait for its result")
            job_id = secrets.token_hex(12)
            output = self.root / "runs" / job_id
            output.mkdir(mode=0o700)
            job = {"id": job_id, "pipeline": ident, "hash": digest, "status": "running", "steps": [], "output": str(output.resolve())}
            self.jobs[job_id] = job
            while len(self.jobs) > 100: self.jobs.pop(next(iter(self.jobs)))
            self.worker = threading.Thread(target=self._run, args=(job, output), daemon=False)
            self.worker.start()
        return dict(job)

    def _run(self, job, output):
        pipeline = self.pipelines[job["pipeline"]]
        values = {"component": str((self.root / "components" / (job["hash"] + ".json")).resolve()),
                  "output": str(output.resolve()), "python": sys.executable, "hash": job["hash"]}
        try:
            for step in pipeline["steps"]:
                if self.stopping.is_set(): raise ValueError("Server stopped; pipeline cancelled")
                argv = [arg.format_map(values) for arg in step["argv"]]
                # Bounded disk spool; no pairing tokens in subprocess environment, no shell or retries.
                environment = {k: v for k, v in os.environ.items() if k not in ("QALENS_BRIDGE_TOKEN", "QALENS_PC_SESSION_TOKEN")}
                with open(os.devnull, "wb") as sink:
                    process = subprocess.Popen(argv, cwd=pipeline["cwd"], env=environment, stdin=subprocess.DEVNULL,
                                               stdout=sink, stderr=sink, start_new_session=True)
                    with self.lock:
                        self.process = process
                        if self.stopping.is_set(): self.kill_process(process, KILL)
                    try: code = process.wait(timeout=step.get("timeoutSeconds", 30))
                    except subprocess.TimeoutExpired:
                        self.kill_process(process, KILL)
                        process.wait(timeout=3)
                        raise
                    finally:
                        with self.lock: self.process = None
                with self.lock: job["steps"].append({"exitCode": code})
                if self.stopping.is_set(): raise ValueError("Server stopped; pipeline cancelled")
                if code: raise ValueError("Processor failed; inspect its output directory")
            with self.lock: job["status"] = "completed"
        except subprocess.TimeoutExpired:
            with self.lock: job.update(status="failed", error="Processor timed out; no automatic retry")
        except (OSError, ValueError, KeyError) as error:
            with self.lock: job.update(status="failed", error=str(error))
        finally:
            with self.lock: write_json(output / "result.json", job)

    @staticmethod
    def kill_process(process, signum):
        try:
            if process.poll() is not None: return
            if os.name == "posix": os.killpg(process.pid, signum)
            else: process.kill()
        except ProcessLookupError: pass

    def close(self, server):
        self.desktop.stop_mirror()
        self.stopping.set()
        with self.lock:
            process = self.process
        if process: self.kill_process(process, KILL)
        if self.worker: self.worker.join(timeout=5)
        self.disconnect(server)
