"""Real CLI startup failures and clean recovery, using disposable storage/listeners only."""
import json
import os
from pathlib import Path
import queue
import re
import shlex
import socket
import subprocess
import sys
import tempfile
import threading
import unittest
from urllib.request import Request, urlopen

sys.path.insert(0, str(Path(__file__).parent))
from workbench import Workbench

SCRIPT = Path(__file__).with_name("server.py")
PERMISSIONS = os.name == "posix" and os.geteuid() != 0


class StartupTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="qalens-startup-")
        self.root = Path(self.temp.name) / "QA's saved data"

    def tearDown(self):
        self.temp.cleanup()

    def run_cli(self, *args, env=None):
        return subprocess.run([sys.executable, str(SCRIPT), *args], text=True,
                              capture_output=True, timeout=8, env=env)

    def assert_failure(self, result):
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("Traceback", result.stderr + result.stdout)
        self.assertNotIn("QaLens workbench:", result.stdout)
        self.assertIn("QaLens could not start:", result.stderr)

    def start_cli(self, *args, env=None):
        process = subprocess.Popen([sys.executable, str(SCRIPT), *args], text=True,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
        self.addCleanup(self.stop_cli, process)
        lines = queue.Queue()
        threading.Thread(target=lambda: lines.put(process.stdout.readline()), daemon=True).start()
        line = lines.get(timeout=8)
        match = re.search(r"http://127\.0\.0\.1:(\d+)", line)
        self.assertIsNotNone(match, line)
        return process, match.group(0)

    def stop_cli(self, process):
        if process.poll() is None:
            process.terminate()
        try:
            return process.communicate(timeout=8)
        except subprocess.TimeoutExpired:
            process.kill(); process.communicate()
            self.fail("Desktop did not stop cleanly")

    @unittest.skipUnless(PERMISSIONS, "Needs real POSIX permissions and a non-root account")
    def test_unwritable_default_data_directory(self):
        home = Path(self.temp.name) / "home"
        root = home / ".qalens" / "bridge"
        root.mkdir(parents=True, mode=0o700)
        root.chmod(0o500)
        try:
            result = self.run_cli("--gui", "--port", "0", env={**os.environ, "HOME": str(home)})
            self.assert_failure(result)
            self.assertIn(str(root), result.stderr)
            self.assertIn("cannot write", result.stderr)
            self.assertIn("--data-dir", result.stderr)
            self.assertEqual(root.stat().st_mode & 0o777, 0o500)
            command = shlex.split(result.stderr.split("\n  ", 1)[1].splitlines()[0])
            process, url = self.start_cli(*command[2:], env={**os.environ, "HOME": str(home)})
            with urlopen(url, timeout=5) as response:
                self.assertEqual(response.status, 200)
            self.assertTrue((home / "QaLens-data").is_dir())
            self.assertEqual(root.stat().st_mode & 0o777, 0o500)
            self.stop_cli(process)
        finally:
            root.chmod(0o700)

    @unittest.skipUnless(PERMISSIONS, "Needs real POSIX permissions and a non-root account")
    def test_existing_child_folders_are_write_checked_without_changing_saved_data(self):
        Workbench(self.root)
        saved = self.root / "profiles.json"
        saved.write_text("[]\n")
        for name in ("components", "runs", "recordings", "transfers"):
            with self.subTest(folder=name):
                path = self.root / name
                path.chmod(0o500)
                try:
                    result = self.run_cli("--gui", "--port", "0", "--data-dir", str(self.root))
                    self.assert_failure(result)
                    self.assertIn(str(path), result.stderr)
                    self.assertEqual(path.stat().st_mode & 0o777, 0o500)
                    self.assertEqual(saved.read_text(), "[]\n")
                finally:
                    path.chmod(0o700)
        self.assertEqual(list(self.root.rglob(".qalens-write-check-*")), [])

    def test_data_directory_is_a_file(self):
        self.root.write_text("keep this file")
        result = self.run_cli("--gui", "--port", "0", "--data-dir", str(self.root))
        self.assert_failure(result)
        self.assertIn("directory is required", result.stderr)
        self.assertEqual(self.root.read_text(), "keep this file")

    @unittest.skipUnless(os.name == "posix", "Recovery shell command is exercised on POSIX")
    def test_recovery_command_chooses_another_folder_when_the_suggested_name_is_blocked(self):
        home = Path(self.temp.name) / "home"
        home.mkdir()
        blocked = home / "QaLens-data"
        blocked.write_text("keep this file")
        env = {**os.environ, "HOME": str(home)}
        result = self.run_cli("--gui", "--port", "0", "--data-dir", str(blocked), env=env)
        self.assert_failure(result)
        command = shlex.split(result.stderr.split("\n  ", 1)[1].splitlines()[0])
        self.assertNotEqual(command[-1], str(blocked))
        process, url = self.start_cli(*command[2:], env=env)
        with urlopen(url, timeout=5) as response:
            self.assertEqual(response.status, 200)
        self.stop_cli(process)
        self.assertEqual(blocked.read_text(), "keep this file")

    def test_busy_port_reports_recovery_before_terminal_token_prompt(self):
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0)); listener.listen()
            port = listener.getsockname()[1]
            for mode in (["--gui"], []):
                with self.subTest(mode=mode):
                    result = self.run_cli(*mode, "--port", str(port), "--data-dir", str(self.root),
                                          env={key: value for key, value in os.environ.items() if key != "QALENS_BRIDGE_TOKEN"})
                    self.assert_failure(result)
                    self.assertIn(f"port {port} is already in use", result.stderr)
                    self.assertIn(f"http://127.0.0.1:{port}", result.stderr)
                    # The copyable recovery command preserves quoted paths and mode, with the
                    # final override taking precedence over the original occupied --port.
                    self.assertIn("--port 0", result.stderr)
                    if os.name == "posix":
                        command = shlex.split(result.stderr.split("\n  ", 1)[1].strip())
                        self.assertEqual(command[-2:], ["--port", "0"])
                        self.assertIn(str(self.root), command)
                    self.assertEqual(listener.getsockname()[1], port)

    def test_free_port_serves_the_actual_gui_and_storage_then_stops_cleanly(self):
        process, url = self.start_cli("--gui", "--port", "0", "--data-dir", str(self.root))
        with urlopen(url, timeout=5) as response:
            self.assertIn(b"Landing", response.read())
        with urlopen(url + "/api/bootstrap", timeout=5) as response:
            session = json.load(response)["session"]
        request = Request(url + "/api/workbench", headers={"X-Qalens-Session": session})
        with urlopen(request, timeout=5) as response:
            self.assertEqual(json.load(response)["dataDir"], str(self.root.resolve()))
        self.assertEqual(list(self.root.rglob(".qalens-write-check-*")), [])
        stdout, stderr = self.stop_cli(process)
        self.assertNotIn("Traceback", stdout + stderr)
        if os.name == "posix": self.assertEqual(process.returncode, 0)
        port = int(url.rsplit(":", 1)[1])
        with socket.socket() as listener:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(("127.0.0.1", port))

    def test_invalid_pipeline_configuration_is_not_misreported_as_storage_failure(self):
        config = Path(self.temp.name) / "pipelines.json"
        config.write_text("{broken json")
        result = self.run_cli("--gui", "--port", "0", "--data-dir", str(self.root), "--pipeline-config", str(config))
        self.assert_failure(result)
        self.assertIn("workspace setup failed", result.stderr)
        self.assertIn("--pipeline-config", result.stderr)
        self.assertNotIn("cannot write", result.stderr)

    @unittest.skipUnless(os.name == "posix", "Executable adb fixture needs POSIX")
    def test_terminal_shutdown_removes_only_its_owned_forward_and_keeps_credentials_private(self):
        log = Path(self.temp.name) / "adb.jsonl"
        adb = Path(self.temp.name) / "fixture-adb"
        adb.write_text(f"#!{sys.executable}\n" +
            "import json, sys\n" +
            f"with open({str(log)!r}, 'a') as output: output.write(json.dumps(sys.argv[1:]) + '\\n')\n" +
            "if sys.argv[1:] == ['devices']: print('List of devices attached\\nfixture-device\\tdevice')\n" +
            "elif sys.argv[-3:] == ['forward', 'tcp:0', 'tcp:8766']: print('19333')\n" +
            "elif sys.argv[1:] == ['forward', '--list']: print('fixture-device tcp:19333 tcp:8766\\nother-device tcp:19334 tcp:8766')\n" +
            "elif sys.argv[-3:] != ['forward', '--remove', 'tcp:19333']: sys.exit(1)\n")
        adb.chmod(0o700)
        token = "synthetic-private-pairing-token"
        process, _ = self.start_cli("--port", "0", "--data-dir", str(self.root), "--adb", str(adb),
                                    env={**os.environ, "QALENS_BRIDGE_TOKEN": token})
        stdout, stderr = self.stop_cli(process)
        self.assertEqual(process.returncode, 0)
        self.assertNotIn(token, stdout + stderr + log.read_text())
        calls = [json.loads(line) for line in log.read_text().splitlines()]
        self.assertEqual(calls, [["devices"],
            ["-s", "fixture-device", "forward", "tcp:0", "tcp:8766"],
            ["forward", "--list"],
            ["-s", "fixture-device", "forward", "--remove", "tcp:19333"]])


if __name__ == "__main__": unittest.main()
