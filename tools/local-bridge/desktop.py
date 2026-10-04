"""Shared desktop facilities. All file transfer destinations stay under owned private storage."""
import hashlib
import gzip
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import time
import threading
import struct
import zipfile
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError
from http.client import IncompleteRead

MAX_ARCHIVE = 400 * 1024 * 1024
MAX_TRANSFER = 32 * 1024 * 1024
RECORDING = re.compile(r"(?:session|clip)_[A-Za-z0-9_]+\.sal")
HASH_FILE = re.compile(r"[a-f0-9]{64}\.sal")
REMOTE_FILE = re.compile(r"/sdcard/Download/[A-Za-z0-9_.-]{1,128}")
WEB_ROOT = Path(__file__).resolve().parents[2] / "web"
WEB_ASSETS = {"index-v2.html", "app-v2.js", "styles-v2.css", "index.html", "app.js", "styles.css", "sal.js", "sample.sal"}


def valid_archive(path):
    # Validate ZIP budgets without loading media into Python RAM. Replay validates decoded tracks/CRCs.
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        names = [entry.filename for entry in entries]
        if len(entries) > 4096 or len(set(names)) != len(names) or "manifest.json" not in names:
            raise ValueError("Invalid recording structure")
        if sum(entry.file_size for entry in entries) > 512 * 1024 * 1024:
            raise ValueError("Recording exceeds expanded archive budget")
        for entry in entries:
            if entry.filename.startswith("/") or ".." in entry.filename.split("/") or "\\" in entry.filename or entry.file_size > 256 * 1024 * 1024:
                raise ValueError("Unsafe recording entry")
        if archive.getinfo("manifest.json").file_size > 1024 * 1024: raise ValueError("Manifest exceeds limit")
        with archive.open("manifest.json") as input:
            data = input.read(1024 * 1024 + 1)
        if data[:2] == b"\x1f\x8b":
            import io
            with gzip.GzipFile(fileobj=io.BytesIO(data)) as decoded: data = decoded.read(1024 * 1024 + 1)
        if len(data) > 1024 * 1024: raise ValueError("Decoded manifest exceeds limit")
        manifest = json.loads(data)
        if not isinstance(manifest, dict) or type(manifest.get("formatVersion", 1)) is not int or manifest.get("formatVersion", 1) not in (1, 2):
            raise ValueError("Unsupported recording manifest")
        return manifest


class Desktop:
    def __init__(self, bench):
        self.bench = bench
        self.recordings = bench.root / "recordings"
        self.transfers = bench.root / "transfers"
        for path in (self.recordings, self.transfers): path.mkdir(mode=0o700, exist_ok=True)
        self.mirror = None
        self.preview_active = False
        self.preview_generation = 0
        self.preview_lock = threading.Lock()
        self.preview_time = 0

    def stop_preview(self):
        self.preview_active = False
        self.preview_generation += 1

    def preview(self, body):
        with self.bench.lock:
            self.current(body)
            if self.bench.phase != "connected": raise ValueError("Approve and connect the phone before starting preview")
            if type(body.get("enabled")) is not bool: raise ValueError("Choose Start or Stop preview")
            self.stop_preview()
            self.preview_active = body["enabled"]
        return {"ok": True, "enabled": self.preview_active}

    def screen(self, connection_id):
        # One bounded capture at a time, no disk, no continued sampling without browser requests.
        if not self.preview_lock.acquire(blocking=False): raise ValueError("Screen capture already running")
        process = None
        timer = None
        try:
            with self.bench.lock:
                current = self.current({"connectionId": connection_id})
                if not self.preview_active or self.bench.phase != "connected": raise ValueError("Start screen preview first")
                if time.monotonic() - self.preview_time < 0.8: raise ValueError("Preview is limited to one frame per second")
                self.preview_time = time.monotonic()
                generation = self.preview_generation
                command = [self.bench.adb, "-s", current["serial"], "exec-out", "screencap", "-p"]
            process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            timer = threading.Timer(4, process.kill); timer.start()
            data = process.stdout.read(16 * 1024 * 1024 + 1)
            if len(data) > 16 * 1024 * 1024: raise ValueError("Screen image exceeds preview budget")
            if process.wait(timeout=1) != 0: raise ValueError("Screen capture unavailable")
            if len(data) < 24 or data[:16] != b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR": raise ValueError("Invalid screen image")
            width, height = struct.unpack(">II", data[16:24])
            if not 0 < width <= 8192 or not 0 < height <= 8192 or width * height > 24_000_000: raise ValueError("Screen dimensions exceed preview budget")
            with self.bench.lock:
                if generation != self.preview_generation or connection_id != self.bench.connection_id or not self.preview_active:
                    raise ValueError("Screen preview stopped or device changed")
            return data
        finally:
            if timer: timer.cancel()
            if process:
                if process.poll() is None: process.kill()
                process.wait(timeout=2)
                if process.stdout: process.stdout.close()
            self.preview_lock.release()

    def current(self, body):
        current = self.bench.connection
        if not current or body.get("connectionId") != self.bench.connection_id:
            raise ValueError("Device connection changed; reconnect before running a device task")
        if self.bench.phase == "awaiting-approval": raise ValueError("Approve desktop access on the phone first")
        return current

    def task(self, body):
        with self.bench.lock:
            current = self.current(body)
            action = body.get("action")
            commands = {"back": ["shell", "input", "keyevent", "4"], "home": ["shell", "input", "keyevent", "3"],
                        "wake": ["shell", "input", "keyevent", "224"], "settings": ["shell", "am", "start", "-a", "android.settings.SETTINGS"]}
            if action in commands:
                self.bench.adb_call(commands[action], current["serial"])
                return {"ok": True, "notice": f"{action} requested"}
            if action == "mirror-stop":
                self.stop_mirror(); return {"ok": True, "notice": "Mirror closed"}
            if action == "mirror":
                executable = shutil.which("scrcpy")
                if not executable: raise ValueError("Install scrcpy locally to enable mirroring")
                if self.mirror and self.mirror.poll() is None: return {"ok": True, "notice": "Mirror already running"}
                environment = os.environ.copy()
                if self.bench.adb: environment["ADB"] = self.bench.adb
                self.mirror = subprocess.Popen([executable, "-s", current["serial"], "--window-title", "QaLens mirror"],
                    env=environment, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                return {"ok": True, "notice": "scrcpy launched in its own window; close it or use Stop mirror"}
            if action == "pull":
                remote = body.get("remote", "")
                if not isinstance(remote, str) or not REMOTE_FILE.fullmatch(remote) or Path(remote).name in {".", ".."}:
                    raise ValueError("Choose one file under /sdcard/Download (letters, numbers, dots, dashes, underscores)")
                size = self.bench.adb_call(["shell", "stat", "-c", "%s", remote], current["serial"]).strip()
                if not size.isdigit() or int(size) > MAX_TRANSFER: raise ValueError("File missing or exceeds 32 MiB")
                path = self.transfers / (secrets.token_hex(8) + "_" + Path(remote).name)
                try:
                    self.bench.adb_call(["pull", remote, str(path)], current["serial"])
                    if path.stat().st_size > MAX_TRANSFER: raise ValueError("Transferred file exceeds limit")
                    os.chmod(path, 0o600)
                    return {"ok": True, "notice": f"Pulled to {path}"}
                except Exception:
                    path.unlink(missing_ok=True); raise
            raise ValueError("Unknown adb task")

    def push(self, body, data):
        with self.bench.lock:
            current = self.current(body)
            name = body.get("name", "")
            if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9_.-]{1,128}", name) or name in {".", ".."}:
                raise ValueError("Rename the file using letters, numbers, dots, dashes or underscores")
            if len(data) > MAX_TRANSFER: raise ValueError("File exceeds 32 MiB")
            path = self.transfers / (secrets.token_hex(8) + "_" + name)
            try:
                with path.open("xb") as output:
                    os.chmod(path, 0o600); output.write(data)
                remote = "/sdcard/Download/" + name
                self.bench.adb_call(["push", str(path), remote], current["serial"])
                return {"ok": True, "notice": f"Pushed to {remote} (existing same-name file is replaced)"}
            finally: path.unlink(missing_ok=True)

    def receive(self, server, body):
        with self.bench.lock:
            self.current(body)
            name = body.get("name", "")
            if not isinstance(name, str) or not RECORDING.fullmatch(name): raise ValueError("Invalid recording name")
            request = Request(f"http://127.0.0.1:{server.device_port}/v1/recordings/{name}",
                              headers={"Authorization": f"Bearer {server.token}"})
            temporary = self.recordings / (secrets.token_hex(12) + ".partial")
            digest = hashlib.sha256(); count = 0
            deadline = time.monotonic() + 60
            try:
                try: response = urlopen(request, timeout=60)
                except (HTTPError, URLError, TimeoutError) as error: raise ValueError("Device rejected or timed out during recording transfer; reconnect and request Copy again") from error
                with response, temporary.open("xb") as output:
                    os.chmod(temporary, 0o600)
                    while True:
                        if time.monotonic() >= deadline: raise ValueError("Recording transfer exceeded 60 seconds; request Copy again")
                        try: chunk = response.read(128 * 1024)
                        except (OSError, TimeoutError, IncompleteRead) as error: raise ValueError("Recording transfer interrupted; request Copy again") from error
                        if not chunk: break
                        count += len(chunk)
                        if count > MAX_ARCHIVE: raise ValueError("Recording exceeds transfer budget")
                        digest.update(chunk); output.write(chunk)
                    output.flush(); os.fsync(output.fileno())
                try: manifest = valid_archive(temporary)
                except (zipfile.BadZipFile, RuntimeError, EOFError) as error: raise ValueError("Incomplete or invalid recording; no archive saved") from error
                ident = digest.hexdigest()
                target = self.recordings / (ident + ".sal")
                duplicate = target.exists()
                if not duplicate: os.replace(temporary, target)
                from workbench import write_json
                metadata = {"name": name, "package": self.bench.connection["package"],
                    "app": str((manifest.get("app") or {}).get("name", ""))[:256] if isinstance(manifest.get("app"), dict) else "", "startMillis": manifest.get("startMillis"), "endMillis": manifest.get("endMillis")}
                write_json(target.with_suffix(".json"), metadata)
                return {"ok": True, "id": ident, "name": name, "size": count, "duplicate": duplicate}
            finally: temporary.unlink(missing_ok=True)

    def library(self):
        files = sorted(self.recordings.glob("*.sal"), key=lambda path: path.stat().st_mtime, reverse=True)
        items = []
        for path in files[:200]:
            if not HASH_FILE.fullmatch(path.name) or path.is_symlink(): continue
            metadata = path.with_suffix(".json")
            info = {}
            if metadata.is_file() and not metadata.is_symlink() and metadata.stat().st_size <= 16 * 1024:
                try: info = json.loads(metadata.read_text())
                except (ValueError, OSError): pass
            if not isinstance(info, dict): info = {}
            items.append({"id": path.stem, "size": path.stat().st_size, "name": info.get("name", path.name), "app": info.get("app", "")})
        return {"ok": True, "items": items, "omitted": max(0, len(files) - 200)}

    def recording(self, ident):
        if not isinstance(ident, str) or not HASH_FILE.fullmatch(ident + ".sal"): raise ValueError("Invalid recording ID")
        path = self.recordings / (ident + ".sal")
        if path.is_symlink() or not path.is_file() or path.stat().st_size > MAX_ARCHIVE: raise ValueError("Recording unavailable")
        return path

    def stop_mirror(self):
        if self.mirror and self.mirror.poll() is None:
            self.mirror.terminate()
            try: self.mirror.wait(timeout=3)
            except subprocess.TimeoutExpired: self.mirror.kill(); self.mirror.wait(timeout=3)
        self.mirror = None
