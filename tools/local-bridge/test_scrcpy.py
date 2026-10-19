"""Versioned wire contracts, real HTTP authorization/leases and safe owned cleanup."""
import io
import json
from pathlib import Path
import socket
import struct
import tempfile
import threading
import time
import unittest
from unittest.mock import Mock, patch
from urllib.request import Request, urlopen
from urllib.error import HTTPError
import server as bridge
from workbench import Workbench
import scrcpy_mirror as scrcpy


class ScrcpyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.bench = Workbench(self.temp.name, 'synthetic-adb')
        self.bench.connection = {'serial': 'synthetic', 'package': 'example.qa'}
        self.server = bridge.BridgeServer(('127.0.0.1', 0), None, 'synthetic-token', self.bench)
        self.bench.phase = 'connected'
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.headers = {'X-Qalens-Session': self.bench.session, 'X-Qalens-Connection': self.bench.connection_id, 'Content-Type': 'application/json'}
        self.owned = scrcpy.Session('synthetic-adb', 'synthetic', self.bench.connection_id)
        self.a, self.b = socket.socketpair()
        self.owned.control = self.a
        self.owned.width, self.owned.height, self.owned.revision = 720, 1600, 1
        self.bench.desktop.video_session = self.owned
        self.bench.desktop.preview_active = True
        self.body = {'streamId': self.owned.id, 'connectionId': self.bench.connection_id, 'revision': 1, 'modeEpoch': 1, 'action': 'touch', 'state': 'down', 'x': .5, 'y': .25}

    def tearDown(self):
        self.owned.close(); self.b.close()
        self.server.shutdown(); self.server.server_close(); self.bench.close(self.server); self.temp.cleanup()

    def request(self, path, body=None, headers=None):
        request = Request(f'http://127.0.0.1:{self.server.server_port}{path}', data=json.dumps(body).encode() if body is not None else None, headers=self.headers if headers is None else headers)
        try: response = urlopen(request, timeout=3)
        except HTTPError as error: response = error
        with response: return response.code, response.read(), response.headers

    def test_video_and_input_require_desktop_auth_approval_current_connection_and_stream(self):
        self.assertEqual(self.request('/api/preview',{'enabled':True,'connectionId':self.bench.connection_id}, {'Authorization':'Bearer synthetic-token','Content-Type':'application/json'})[0],401)
        for path in ('/api/mirror/video', '/api/mirror/heartbeat', '/api/mirror/input'):
            body = None if path.endswith('video') else self.body
            self.assertEqual(self.request(path, body, {})[0], 401)
            self.assertEqual(self.request(path, body, {'Authorization': 'Bearer synthetic-token', 'Content-Type': 'application/json'})[0], 401)
            self.assertEqual(self.request(path, body, dict(self.headers, Origin='https://outside.example'))[0], 403)
        for bad in ({'connectionId': 'old'}, {'streamId': 'old'}, {'revision': 0}, {'modeEpoch': 0}, {'revision':True}, {'modeEpoch':True}, {'x': True}, {'y': 2}, {'x': float('nan')}, {'state': 'shell'}):
            self.assertEqual(self.request('/api/mirror/input', dict(self.body, **bad))[0], 400)
        for phase in ('awaiting-approval', 'reconnecting', 'revoked'):
            self.bench.phase = phase
            self.assertEqual(self.request('/api/mirror/input', self.body)[0], 400)
        self.bench.phase = 'connected'
        self.assertEqual(self.request('/api/mirror/input', self.body)[0], 200)
        wire = self.b.recv(32)
        self.assertEqual(struct.unpack('>BBQiiHHHII', wire), (2, 0, 0, 360, 400, 720, 1600, 65535, 0, 0))

    def test_inspection_preview_and_stop_cancel_touches_instead_of_clicking_and_revoke_old_input(self):
        self.owned.input(self.body); self.b.recv(32)
        state = self.owned.set_mode('inspect')
        self.assertEqual(self.b.recv(32)[1], 3, 'Mode changes must CANCEL, never UP/click')
        self.assertIsNone(self.owned.pointer)
        self.assertEqual(self.request('/api/mirror/input', dict(self.body, modeEpoch=state['modeEpoch']))[0], 400)
        self.owned.set_mode('preview')
        self.assertEqual(self.request('/api/mirror/input', dict(self.body, modeEpoch=self.owned.mode_epoch))[0], 400)
        state = self.owned.set_mode('control')
        self.assertEqual(self.request('/api/mirror/input', self.body)[0], 400)
        self.owned.input(dict(self.body, modeEpoch=state['modeEpoch'])); self.b.recv(32)
        self.owned.close(); self.assertEqual(self.b.recv(32)[1], 3)

    def test_continuous_drag_scroll_key_and_text_match_v5_protocol_and_do_not_spawn_adb_inputs(self):
        with patch('scrcpy_mirror.subprocess.run') as command:
            self.owned.input(self.body); self.b.recv(32)
            self.owned.input(dict(self.body, state='move', x=.7)); self.assertEqual(self.b.recv(32)[1], 2)
            self.owned.input(dict(self.body, state='up', x=.7)); self.assertEqual(self.b.recv(32)[1], 1)
            self.owned.input(dict(self.body, action='scroll', horizontal=1, vertical=-2))
            wire = self.b.recv(21); self.assertEqual(wire[0], 3); self.assertEqual(struct.unpack('>hhI', wire[13:]), (2048, -4096, 0))
            self.owned.input(dict(self.body, action='key', keycode=66)); self.assertEqual(len(self.b.recv(28)), 28)
            self.owned.input(dict(self.body, action='text', text='QA')); self.assertEqual(self.b.recv(7), b'\x01\x00\x00\x00\x02QA')
            command.assert_not_called()
        for bad in ({'action':'key','keycode':True}, {'action':'key','keycode':999}, {'action':'text','text':'x'*301}, {'action':'scroll','vertical':float('inf')}):
            self.assertRaises(ValueError, self.owned.input, dict(self.body, **bad))

    def test_stream_chunking_is_authenticated_single_viewer_and_closes_resources(self):
        self.owned.enqueue(b'h264')
        self.owned.enqueue(struct.pack('>III', 0x80000000, 720, 1600))
        headers = dict(self.headers, **{'X-Qalens-Mirror': self.owned.id})
        with urlopen(Request(f'http://127.0.0.1:{self.server.server_port}/api/mirror/video', headers=headers), timeout=3) as response:
            self.assertEqual(response.headers['Transfer-Encoding'], 'chunked')
            self.assertEqual(response.read(16), b'h264' + struct.pack('>III', 0x80000000, 720, 1600))
            self.assertEqual(self.request('/api/mirror/video', headers=headers)[0], 409)
            self.owned.close()
            self.assertEqual(response.read(), b'')

    def test_default_hides_legacy_capture_and_other_pages_cannot_stop_or_adopt_a_mirror(self):
        self.assertEqual(self.request('/api/screen')[0], 400)
        self.assertEqual(self.request('/api/input', self.body)[0], 400)
        for data in ({'enabled': True, 'connectionId': self.bench.connection_id}, {'enabled': False, 'connectionId': self.bench.connection_id}):
            self.assertEqual(self.request('/api/preview', data)[0], 400)
        self.assertFalse(self.owned.closed.is_set())
        self.assertEqual(self.request('/api/preview', {'enabled': False, 'streamId': self.owned.id, 'connectionId': self.bench.connection_id})[0], 200)
        self.assertTrue(self.owned.closed.is_set())

    def test_packet_limits_partial_header_and_rotation_invalidate_touch_revision(self):
        # Actual socket reader: a rotation is a separate session record, not a huge media packet.
        video, producer = socket.socketpair(); self.owned.video = video
        thread = threading.Thread(target=self.owned.read_video); thread.start()
        producer.sendall(struct.pack('>III', 0x80000000, 1600, 720))
        deadline = time.monotonic()+2
        while self.owned.revision == 1 and time.monotonic()<deadline: time.sleep(.01)
        self.assertEqual((self.owned.width, self.owned.height, self.owned.revision), (1600, 720, 2))
        self.assertRaises(ValueError, self.owned.input, self.body)
        producer.sendall(struct.pack('>QI', 0, scrcpy.MAX_PACKET+1))
        thread.join(2); producer.close()
        self.assertTrue(self.owned.closed.is_set()); self.assertIn('budget', self.owned.error)
        source=Mock(); source.recv.side_effect=[b'x', socket.timeout()]
        self.assertRaises(ConnectionError, scrcpy.exact, source, 12)

    def test_queue_budget_expired_page_and_owned_cleanup(self):
        self.assertRaises(ValueError, self.owned.enqueue, bytes(scrcpy.MAX_QUEUED+1))
        self.owned.port = 12345
        with patch.object(self.owned, 'command', return_value='synthetic tcp:12345 localabstract:somebody-else') as command:
            self.owned.cleanup()
            self.assertFalse(any('--remove' in call.args[0] for call in command.call_args_list))
            self.assertEqual(command.call_args.args[0], ['shell','rm','-f',self.owned.remote])
        self.owned.deadline = time.monotonic()-1; self.owned.process=Mock(); self.owned.process.poll.return_value=None
        with patch.object(self.owned, 'cleanup'):
            thread=threading.Thread(target=self.owned.watch); thread.start(); thread.join(2)
            self.assertTrue(self.owned.closed.is_set()); self.assertIn('page', self.owned.error)

    def test_install_digest_rejects_corruption_and_preserves_previous_file(self):
        cache=Path(self.temp.name)/'scrcpy'
        with patch('scrcpy_mirror.CACHE', cache), patch('scrcpy_mirror.urlopen') as download:
            cache.mkdir(); original=cache/'scrcpy-server'; original.write_bytes(b'old')
            download.return_value.__enter__.return_value.read.return_value=b'corrupt'
            self.assertRaises(ValueError, scrcpy.install)
            self.assertEqual(original.read_bytes(), b'old'); self.assertEqual({p.name for p in cache.iterdir()}, {'scrcpy-server','LICENSE'})
            original.write_bytes(b'valid')
            with patch('scrcpy_mirror.SERVER_SHA256', __import__('hashlib').sha256(b'valid').hexdigest()):
                self.assertTrue(scrcpy.available()); scrcpy.install(); self.assertEqual(download.call_count, 1)

    def test_slow_recording_copy_keeps_mirror_input_and_connection_lock_available(self):
        import zipfile
        blob=io.BytesIO()
        with zipfile.ZipFile(blob,'w') as archive: archive.writestr('manifest.json','{"formatVersion":1}')
        started,release=threading.Event(),threading.Event()
        class Slow(io.BytesIO):
            def read(self,count=-1):
                started.set(); release.wait(3)
                return super().read(count)
        results=[]
        with patch('desktop.urlopen',return_value=Slow(blob.getvalue())):
            worker=threading.Thread(target=lambda:results.append(self.bench.desktop.receive(self.server,{'name':'session_test.sal','connectionId':self.bench.connection_id})))
            worker.start(); self.assertTrue(started.wait(2))
            acquired=self.bench.lock.acquire(timeout=.3)
            try:
                self.assertTrue(acquired,'Recording copy still holds the connection lock')
                self.owned.input(self.body); self.assertEqual(self.b.recv(32)[1],0)
            finally:
                if acquired: self.bench.lock.release()
                release.set(); worker.join(3)
        self.assertTrue(results[0]['ok']); self.assertEqual(len(list(self.bench.desktop.recordings.glob('*.sal'))),1)

    def test_phone_auth_revocation_on_heartbeat_closes_video_even_if_viewer_keeps_polling(self):
        with patch.object(self.bench,'check_connection',side_effect=lambda *_args,**_kwargs:self.bench.disconnect(self.server)):
            self.assertEqual(self.request('/api/mirror/heartbeat',self.body)[0],400)
        self.assertTrue(self.owned.closed.is_set()); self.assertFalse(self.bench.desktop.preview_active)


if __name__ == '__main__': unittest.main()
