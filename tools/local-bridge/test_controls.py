"""Desktop touch leases and real HTTP forwarding, including binary screenshot failures."""
import io
import json
from pathlib import Path
import struct
import tempfile
import threading
import time
import unittest
from unittest.mock import Mock, patch
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from http.server import BaseHTTPRequestHandler, HTTPServer
import server as bridge
from workbench import Workbench

PNG = b'\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR' + struct.pack('>II', 1080, 1920) + b'synthetic'


class ControlsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.bench = Workbench(self.temp.name, 'fake-adb')
        self.bench.connection = {'serial': 'synthetic', 'package': 'example.qa'}
        self.server = bridge.BridgeServer(('127.0.0.1', 0), None, 'synthetic-token', self.bench)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.body = {'connectionId': self.bench.connection_id, 'frameId': 'displayed', 'action': 'tap', 'x': .5, 'y': .5}
        self.headers = {'X-Qalens-Session': self.bench.session, 'X-Qalens-Connection': self.bench.connection_id, 'Content-Type': 'application/json'}
    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.bench.close(self.server); self.temp.cleanup()
    def request(self, path, data=None, headers=None):
        request = Request(f'http://127.0.0.1:{self.server.server_port}{path}',
                          data=json.dumps(data).encode() if data is not None else None, headers=self.headers if headers is None else headers)
        try: response = urlopen(request, timeout=3)
        except HTTPError as error: response = error
        with response: return response.code, response.read(), response.headers
    def frame(self):
        self.bench.desktop.preview({'enabled': True, 'mode': 'control', 'connectionId': self.bench.connection_id})
        self.bench.desktop.frames['displayed'] = (1080, 1920, time.monotonic())
    def test_touch_requires_explicit_control_current_lease_and_finite_screen_coordinates(self):
        with patch.object(self.bench, 'adb_call', return_value='') as adb:
            self.assertEqual(self.request('/api/input', self.body)[0], 400)
            self.frame()
            for update in ({'connectionId': 'old'}, {'frameId': 'missing'}, {'x': -1}, {'y': 1.01}, {'x': True}, {'x': '1'}, {'x': float('nan')}, {'action': 'shell'}):
                self.assertEqual(self.request('/api/input', dict(self.body, **update))[0], 400)
            self.assertEqual(self.request('/api/input', self.body, {})[0], 401)
            self.assertEqual(self.request('/api/input', self.body, dict(self.headers, Origin='https://external.example'))[0], 403)
            adb.assert_not_called()
            self.assertEqual(self.request('/api/input', self.body)[0], 200)
            adb.assert_called_once_with(['shell', 'input', 'tap', '540', '960'], 'synthetic')
            self.bench.desktop.frames['displayed'] = (1080, 1920, time.monotonic() - 7)
            self.assertEqual(self.request('/api/input', self.body)[0], 400)
    def test_preview_inspection_disconnect_and_mode_changes_revoke_touch_leases(self):
        with patch.object(self.bench, 'adb_call', return_value='') as adb:
            for mode in ('preview', 'inspect', 'control'):
                self.frame()
                self.bench.desktop.preview({'enabled': True, 'mode': mode, 'connectionId': self.bench.connection_id})
                self.assertEqual(self.bench.desktop.frames, {})
                self.assertEqual(self.request('/api/input', self.body)[0], 400)
            self.frame(); self.bench.phase = 'revoked'
            self.assertEqual(self.request('/api/input', self.body)[0], 400)
            self.bench.phase = 'connected'; self.bench.desktop.stop_preview()
            self.assertEqual(self.request('/api/input', self.body)[0], 400)
            adb.assert_not_called()
    def test_swipe_long_press_are_bounded_and_failure_never_retries(self):
        self.frame()
        with patch.object(self.bench, 'adb_call', return_value='') as adb:
            swipe = dict(self.body, action='swipe', endX=1, endY=0, duration=350)
            self.assertEqual(self.request('/api/input', swipe)[0], 200)
            adb.assert_called_once_with(['shell', 'input', 'swipe', '540', '960', '1079', '0', '350'], 'synthetic')
            for duration in (0, 3000, True, 350.5):
                self.assertEqual(self.request('/api/input', dict(swipe, duration=duration))[0], 400)
            adb.reset_mock(); adb.side_effect = ValueError('adb timed out')
            self.assertEqual(self.request('/api/input', dict(self.body, action='long-press', duration=700))[0], 400)
            adb.assert_called_once()
    def test_screen_lease_header_has_no_disk_pixels_and_rotation_revokes_previous_dimensions(self):
        self.frame()
        def capture(data):
            process = Mock(); process.stdout = io.BytesIO(data); process.wait.return_value = 0; process.poll.return_value = 0
            with patch('desktop.subprocess.Popen', return_value=process):
                self.bench.desktop.preview_time = 0
                return self.request('/api/screen')
        code, data, headers = capture(PNG)
        self.assertEqual(code, 200); self.assertEqual(data, PNG)
        lease = headers['X-Qalens-Frame']; self.assertIn(lease, self.bench.desktop.frames)
        capture(PNG[:16] + struct.pack('>II', 1920, 1080) + PNG[24:])
        self.assertNotIn(lease, self.bench.desktop.frames)
        self.assertEqual(list(self.bench.desktop.transfers.iterdir()), [])
    def test_sdk_controls_and_binary_screenshot_keep_auth_errors_and_never_repeat(self):
        calls = []
        class Device(BaseHTTPRequestHandler):
            def log_message(self, *_): pass
            def do_POST(inner):
                self.assertEqual(inner.headers['Authorization'], 'Bearer synthetic-token')
                data = json.loads(inner.rfile.read(int(inner.headers['Content-Length'])))
                calls.append((inner.path, data))
                if inner.path == '/v1/screenshot':
                    code, payload, mime = (409, b'{"ok":false,"error":"Secure window"}', 'application/json') if data.get('includeOverlay') else (200, PNG, 'image/png')
                else: code, payload, mime = 200, b'{"ok":true,"accepted":true}', 'application/json'
                if data.get('badPng'): payload = b'broken'
                declared = len(payload) + (100 if data.get('truncate') else 0)
                inner.send_response(code); inner.send_header('Content-Type', mime); inner.send_header('Content-Length', str(declared)); inner.end_headers(); inner.wfile.write(payload)
        device = HTTPServer(('127.0.0.1', 0), Device)
        threading.Thread(target=device.serve_forever, daemon=True).start(); self.server.device_port = device.server_port
        try:
            self.assertEqual(self.request('/api/recording', {'action': 'start', 'video': False}, dict(self.headers, **{'X-Qalens-Connection': 'old'}))[0], 409)
            code, data, _ = self.request('/api/recording', {'action': 'start', 'video': False})
            self.assertEqual(code, 200); self.assertEqual(json.loads(data)['connectionId'], self.bench.connection_id)
            self.assertEqual(self.request('/api/inspection', {'enabled': False})[0], 200)
            code, pixels, headers = self.request('/api/screenshot', {'includeOverlay': False})
            self.assertEqual(code, 200); self.assertEqual(pixels, PNG); self.assertEqual(headers['Content-Type'], 'image/png')
            code, error, _ = self.request('/api/screenshot', {'includeOverlay': True})
            self.assertEqual(code, 409); self.assertEqual(json.loads(error)['error'], 'Secure window')
            for failure in ('truncate', 'badPng'):
                code, error, _ = self.request('/api/screenshot', {'includeOverlay': False, failure: True})
                self.assertEqual(code, 502); self.assertFalse(json.loads(error)['ok'])
            self.assertEqual([c[0] for c in calls], ['/v1/recording', '/v1/inspection'] + ['/v1/screenshot'] * 4)
            self.assertEqual(list(self.bench.desktop.transfers.iterdir()), [])
        finally: device.shutdown(); device.server_close()

if __name__ == '__main__': unittest.main()
