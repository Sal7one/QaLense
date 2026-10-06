"""Pinned scrcpy 5.0 video/control adapter. Pixels and input stay on loopback/in memory.

Protocol reference: Genymobile/scrcpy v5.0 Streamer and ControlMessageReader.
This is an internal, versioned protocol, not a general scrcpy client API.
"""
import hashlib
import math
import os
from pathlib import Path
import queue
import secrets
import socket
import struct
import subprocess
import threading
import time
from urllib.request import urlopen

VERSION = "5.0"
SERVER_SHA256 = "26cbc9ad0aced6c2282455bef4fb43462605c1f8758c74b4ab1dbf818c229daa"
SERVER_URL = f"https://github.com/Genymobile/scrcpy/releases/download/v{VERSION}/scrcpy-server-v{VERSION}"
CACHE = Path.home() / ".qalens" / "dependencies" / "scrcpy" / VERSION
MAX_PACKET = 2 * 1024 * 1024
MAX_QUEUED = 8 * 1024 * 1024
LEASE_SECONDS = 7
INSTALL_LOCK = threading.Lock()


def server_path():
    return CACHE / "scrcpy-server"


def available():
    try:
        path = server_path()
        return not path.is_symlink() and path.is_file() and path.stat().st_size <= 2 * 1024 * 1024 and hashlib.sha256(path.read_bytes()).hexdigest() == SERVER_SHA256
    except OSError:
        return False


def install():
    # Fixed official asset + digest, atomic install; never execute a downloaded shell script.
    with INSTALL_LOCK:
        try:
            CACHE.mkdir(parents=True, exist_ok=True, mode=0o700)
            notice = Path(__file__).with_name("SCRCPY_LICENSE").read_bytes()
            (CACHE / "LICENSE").write_bytes(notice)
        except OSError as error:
            raise ValueError("scrcpy setup needs write access to ~/.qalens/dependencies/scrcpy and the included SCRCPY_LICENSE file.") from error
        if available(): return {"ok": True, "version": VERSION}
        try:
            CACHE.mkdir(parents=True, exist_ok=True, mode=0o700)
            with urlopen(SERVER_URL, timeout=20) as response:
                data = response.read(2 * 1024 * 1024 + 1)
            if len(data) > 2 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != SERVER_SHA256:
                raise ValueError("scrcpy download failed its SHA-256 check. Nothing was installed.")
            temporary = CACHE / (".server-" + secrets.token_hex(8))
            try:
                with temporary.open("xb") as output: output.write(data)
                temporary.chmod(0o600)
                temporary.replace(server_path())
            finally:
                temporary.unlink(missing_ok=True)
            return {"ok": True, "version": VERSION}
        except OSError as error:
            raise ValueError("Could not set up scrcpy. Check internet access and write access to ~/.qalens/dependencies; then try Start mirror again.") from error


def exact(stream, count):
    data = bytearray()
    while len(data) < count:
        try: piece = stream.recv(count - len(data))
        except socket.timeout:
            if data: raise ConnectionError("Phone video packet was interrupted")
            raise
        if not piece: raise ConnectionError("Phone video connection closed")
        data.extend(piece)
    return bytes(data)


def position(body, width, height):
    def coordinate(name, size):
        value = body.get(name)
        if type(value) not in (int, float) or not math.isfinite(value) or not 0 <= value <= 1:
            raise ValueError("Touch coordinates must be inside the displayed phone")
        return round(value * (size - 1))
    return struct.pack(">iiHH", coordinate("x", width), coordinate("y", height), width, height)


class Session:
    def __init__(self, adb, serial, connection_id):
        self.adb, self.serial, self.connection_id = adb, serial, connection_id
        self.id = secrets.token_hex(16)
        self.scid = secrets.randbelow(0x7fffffff)
        self.remote = "/data/local/tmp/qalens-scrcpy-" + self.id + ".jar"
        self.target = f"localabstract:scrcpy_{self.scid:08x}"
        self.lock = threading.RLock()
        self.send_lock = threading.Lock()
        self.closed = threading.Event()
        self.process = self.video = self.control = None
        self.port = None
        self.width = self.height = 0
        self.revision = 0
        self.mode_epoch = 1
        self.mode = "control"
        self.pointer = None
        self.attached = False
        self.deadline = time.monotonic() + 15
        self.error = ""
        self.logs = ""
        self.packets = queue.Queue(maxsize=128)
        self.queued_bytes = 0

    def command(self, args, timeout=8):
        result = subprocess.run([self.adb, "-s", self.serial, *args], capture_output=True, timeout=timeout)
        if result.returncode: raise ValueError("scrcpy could not reach the phone through adb. Check the USB connection.")
        return result.stdout.decode(errors="replace").strip()

    def start(self, mode):
        if not available(): raise ValueError("Set up scrcpy before starting the mirror")
        self.mode = mode
        try:
            self.command(["push", str(server_path()), self.remote])
            if self.closed.is_set(): raise ValueError("Mirror start was cancelled")
            self.port = int(self.command(["forward", "tcp:0", self.target]))
            if not 1 <= self.port <= 65535: raise ValueError("Invalid scrcpy forwarding port")
            args = [self.adb, "-s", self.serial, "shell", "CLASSPATH=" + self.remote, "app_process", "/",
                    "com.genymobile.scrcpy.Server", VERSION, f"scid={self.scid:08x}", "log_level=warn",
                    "tunnel_forward=true", "audio=false", "video_codec=h264", "max_size=1600",
                    "max_fps=60", "video_bit_rate=6000000", "send_device_meta=false",
                    "send_dummy_byte=true", "send_stream_meta=true", "send_frame_meta=true",
                    "clipboard_autosync=false", "power_on=false", "cleanup=false"]
            with self.lock:
                if self.closed.is_set(): raise ValueError("Mirror start was cancelled")
                self.process = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            threading.Thread(target=self.drain_logs, daemon=True).start()
            deadline = time.monotonic() + 6
            while True:
                try:
                    video = socket.create_connection(("127.0.0.1", self.port), timeout=1)
                    video.settimeout(1)
                    if exact(video, 1) != b"\0": raise ValueError("Invalid scrcpy forward handshake")
                    break
                except (OSError, ConnectionError):
                    if 'video' in locals(): video.close()
                    if time.monotonic() >= deadline or self.closed.is_set() or self.process.poll() is not None:
                        raise ValueError("scrcpy video could not start. Return to the phone and try again.")
                    self.closed.wait(.1)
            with self.lock:
                if self.closed.is_set(): video.close(); raise ValueError("Mirror start was cancelled")
                self.video = video
                self.control = socket.create_connection(("127.0.0.1", self.port), timeout=2)
                self.control.settimeout(1)
            if exact(video, 4) != b"h264": raise ValueError("The pinned scrcpy server did not supply H.264 video")
            self.enqueue(b"h264")
            video.settimeout(2)
            self.deadline = time.monotonic() + LEASE_SECONDS
            threading.Thread(target=self.read_video, daemon=True).start()
            threading.Thread(target=self.watch, daemon=True).start()
            return self.state()
        except (OSError, ValueError, subprocess.TimeoutExpired) as error:
            self.close(str(error))
            # Stop may have raced adb push/forward before those resources were registered.
            if self.closed.is_set(): threading.Thread(target=self.cleanup, daemon=True).start()
            raise ValueError(str(error)) from error

    def drain_logs(self):
        try:
            while data := self.process.stdout.read(256):
                self.logs = (self.logs + data.decode(errors="replace"))[-2048:]
        except (OSError, ValueError): pass

    def enqueue(self, data):
        with self.lock:
            if self.closed.is_set(): return
            if self.queued_bytes + len(data) > MAX_QUEUED: raise ValueError("Mirror consumer is too slow. Restart the mirror.")
            self.queued_bytes += len(data)
        try: self.packets.put_nowait(data)
        except queue.Full: raise ValueError("Mirror consumer is too slow. Restart the mirror.")

    def read_video(self):
        try:
            while not self.closed.is_set():
                try: header = exact(self.video, 12)
                except socket.timeout: continue
                if header[0] & 0x80:
                    _, width, height = struct.unpack(">III", header)
                    if not 0 < width <= 4096 or not 0 < height <= 4096 or width * height > 8_000_000:
                        raise ValueError("Phone video dimensions exceed the mirror budget")
                    with self.send_lock, self.lock:
                        self.release_pointer()
                        self.width, self.height = width, height
                        self.revision += 1
                else:
                    size = struct.unpack(">I", header[8:])[0]
                    if not 0 < size <= MAX_PACKET: raise ValueError("Phone video packet exceeds the mirror budget")
                    header += exact(self.video, size)
                self.enqueue(header)
        except (OSError, ValueError) as error: self.close(str(error))

    def stream(self):
        with self.lock:
            if self.closed.is_set(): raise ValueError(self.error or "Mirror stopped")
            if self.attached: raise ValueError("This video stream already has a viewer")
            self.attached = True
        try:
            while not self.closed.is_set():
                try: data = self.packets.get(timeout=.5)
                except queue.Empty: continue
                with self.lock: self.queued_bytes -= len(data)
                yield data
        finally: self.close()

    def state(self):
        return {"ok": True, "streamId": self.id, "version": VERSION, "width": self.width, "height": self.height,
                "revision": self.revision, "modeEpoch": self.mode_epoch, "mode": self.mode,
                "running": not self.closed.is_set(), "error": self.error}

    def heartbeat(self):
        if self.closed.is_set(): raise ValueError(self.error or "Mirror stopped")
        self.deadline = time.monotonic() + LEASE_SECONDS
        return self.state()

    def watch(self):
        while not self.closed.wait(.5):
            if time.monotonic() > self.deadline: self.close("Mirror page closed or stopped responding")
            elif self.process.poll() is not None: self.close("Phone mirror process stopped; reconnect and start again")

    def release_pointer(self):
        if self.pointer and self.control:
            try: self.control.sendall(self.touch_packet(3, *self.pointer))
            except OSError: pass
        self.pointer = None

    def touch_packet(self, action, x, y, width, height):
        # Virtual finger, not mouse buttons: Android drag/long press matches phone touch behavior.
        return struct.pack(">BBQiiHHHII", 2, action, 0, x, y, width, height, 0 if action in {1, 3} else 0xffff, 0, 0)

    def set_mode(self, mode):
        if mode not in {"control", "preview", "inspect"}: raise ValueError("Choose Control, Preview or Inspect")
        with self.send_lock, self.lock:
            if self.closed.is_set(): raise ValueError("Mirror stopped")
            self.release_pointer()
            self.mode = mode
            self.mode_epoch += 1
            return self.state()

    def input(self, body):
        with self.send_lock, self.lock:
            if self.closed.is_set() or self.mode != "control": raise ValueError("Phone input is paused")
            if type(body.get("revision")) is not int or type(body.get("modeEpoch")) is not int or body["revision"] != self.revision or body["modeEpoch"] != self.mode_epoch or not self.width:
                raise ValueError("Mirror changed. Wait for a fresh frame before sending input")
            action = body.get("action")
            if action == "touch":
                state = body.get("state")
                if state not in {"down", "move", "up", "cancel"}: raise ValueError("Invalid touch state")
                if state == "cancel": self.release_pointer(); return {"ok": True}
                coords = position(body, self.width, self.height)
                x, y, width, height = struct.unpack(">iiHH", coords)
                if state == "down" and self.pointer: raise ValueError("A phone touch is already active")
                if state != "down" and not self.pointer: raise ValueError("Phone touch is no longer active")
                data = self.touch_packet({"down": 0, "up": 1, "move": 2}[state], x, y, width, height)
                self.pointer = None if state == "up" else (x, y, width, height)
            elif action == "scroll":
                coords = position(body, self.width, self.height)
                def scroll(name):
                    value = body.get(name, 0)
                    if type(value) not in (int, float) or not math.isfinite(value) or abs(value) > 16: raise ValueError("Invalid scroll amount")
                    return max(-32768, min(32767, round(value * 32768 / 16)))
                data = bytes([3]) + coords + struct.pack(">hhI", scroll("horizontal"), scroll("vertical"), 0)
            elif action == "key":
                code = body.get("keycode")
                if type(code) is not int or code not in {3, 4, 19, 20, 21, 22, 23, 61, 62, 66, 67, 112, 122, 123, 124, 224}:
                    raise ValueError("Unsupported phone key")
                data = struct.pack(">BBIII", 0, 0, code, 0, 0) + struct.pack(">BBIII", 0, 1, code, 0, 0)
            elif action == "text":
                text = body.get("text")
                if not isinstance(text, str) or not text or len(text.encode()) > 300: raise ValueError("Phone text must be 1–300 UTF-8 bytes")
                raw = text.encode(); data = bytes([1]) + struct.pack(">I", len(raw)) + raw
            else: raise ValueError("Unsupported mirror action")
            try: self.control.sendall(data)
            except OSError as error:
                self.close("Phone input failed; it was not retried")
                raise ValueError("Phone input failed; it was not retried") from error
            return {"ok": True}

    def close(self, error=""):
        with self.lock:
            if self.closed.is_set(): return
            self.closed.set(); self.error = error[:256]
            self.release_pointer()
            for stream in (self.video, self.control):
                if stream:
                    try: stream.shutdown(socket.SHUT_RDWR)
                    except OSError: pass
                    stream.close()
            if self.process and self.process.poll() is None:
                try: self.process.terminate()
                except OSError: pass
        threading.Thread(target=self.cleanup, daemon=True).start()

    def cleanup(self):
        if self.process:
            try: self.process.wait(timeout=2)
            except subprocess.TimeoutExpired: self.process.kill(); self.process.wait(timeout=2)
            if self.process.stdout: self.process.stdout.close()
        # Delete only this random session's jar and exact owned adb forward, never another mirror.
        try:
            rows = self.command(["forward", "--list"], 2).splitlines()
            if self.port and any(row.split() == [self.serial, f"tcp:{self.port}", self.target] for row in rows):
                self.command(["forward", "--remove", f"tcp:{self.port}"], 2)
            self.command(["shell", "rm", "-f", self.remote], 2)
        except (ValueError, OSError, subprocess.TimeoutExpired): pass


if __name__ == "__main__":
    import sys
    try:
        install()
        print(f"Verified scrcpy {VERSION} server ready at {server_path()}")
    except ValueError as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
