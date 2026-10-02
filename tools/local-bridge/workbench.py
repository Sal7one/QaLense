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
import threading

KILL = getattr(signal, "SIGKILL", signal.SIGTERM)
MAX_DOCUMENT = 256 * 1024
HASH = re.compile(r"[0-9a-f]{64}")
PACKAGE = re.compile(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+")


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
        self.root = Path(data_dir).expanduser()
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        for name in ("components", "runs"):
            (self.root / name).mkdir(exist_ok=True, mode=0o700)
        self.adb = adb
        self.lock = threading.RLock()
        self.preview_dropped = 0
        self.pending = {}  # bounded previews; never implicitly persisted
        self.profiles_path = self.root / "profiles.json"
        self.session = secrets.token_urlsafe(32)
        self.connection = None
        self.connection_id = secrets.token_hex(16)
        self.owned_forward = None
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

    def adb_call(self, arguments, serial=None):
        if not self.adb: raise ValueError("adb unavailable; pass --adb /path/to/adb")
        command = [self.adb] + (["-s", serial] if serial else []) + arguments
        try:
            result = subprocess.run(command, capture_output=True, text=True, timeout=10)
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
        with self.lock:
            self.disconnect(server)
            self.owned_forward = (clean["serial"], port)
            self.connection = dict(clean, actualPlatformVersion=version)
            server.device_port = port; server.token = token
        return self.connection

    def disconnect(self, server):
        self.desktop.stop_mirror()
        with self.lock:
            server.device_port = None; server.token = None
            self.connection = None
            self.connection_id = secrets.token_hex(16)
            if self.owned_forward:
                serial, port = self.owned_forward
                self.owned_forward = None
                try: self.adb_call(["forward", "--remove", f"tcp:{port}"], serial)
                except ValueError: pass

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
