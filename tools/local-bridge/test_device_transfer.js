'use strict';
// Opt-in end-to-end transport check. Build/install the Android test APKs first.
const assert = require('node:assert/strict');
const {spawn, execFileSync} = require('node:child_process');
const {mkdtempSync, readFileSync, rmSync, statSync} = require('node:fs');
const {tmpdir} = require('node:os');
const {join} = require('node:path');
const {randomBytes, createHash} = require('node:crypto');
const net = require('node:net');
const RecordingTransfer = require('./recording-transfer.js');
const serial = process.argv[2];
if (!serial?.startsWith('emulator-')) {
  console.error('Usage: node tools/local-bridge/test_device_transfer.js emulator-SERIAL (disposable emulator only)');
  process.exit(2);
}
const adb = args => execFileSync(process.env.ADB || 'adb', ['-s', serial, ...args], {encoding: 'utf8', timeout: 15000, stdio: ['ignore', 'pipe', 'ignore']});
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(condition, message, timeout = 90000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) { if (await condition()) return; await delay(200); }
  throw Error(message);
}
async function unusedPort() {
  const socket = net.createServer();
  await new Promise(resolve => socket.listen(0, '127.0.0.1', resolve));
  const port = socket.address().port;
  await new Promise(resolve => socket.close(resolve)); return port;
}
async function main() {
  const root = mkdtempSync(join(tmpdir(), 'qalens-transfer-test-'));
  const port = await unusedPort(), url = `http://127.0.0.1:${port}`;
  const server = spawn(process.env.PYTHON || 'python3', [join(__dirname, 'server.py'), '--gui', '--port', String(port), '--data-dir', root], {stdio: 'ignore'});
  let instrument, instrumentOutput = '', session = '', forwarding = '';
  const stage = () => { try { return adb(['shell', 'run-as', 'com.qalens.sample', 'cat', 'files/qalens-transfer-test/stage']).trim(); } catch { return ''; } };
  const signal = text => adb(['shell', 'run-as', 'com.qalens.sample', 'sh', '-c', `"printf ${text} > files/qalens-transfer-test/signal"`]);
  async function api(path, body) {
    const response = await fetch(`${url}/api/${path}`, {method: body ? 'POST' : 'GET',
      headers: {'X-Qalens-Session': session, 'Content-Type': 'application/json'}, body: body ? JSON.stringify(body) : undefined});
    const result = await response.json();
    if (!response.ok || result.ok === false) throw Object.assign(Error(result.error || `HTTP ${response.status}`), {status: response.status});
    return result;
  }
  try {
    await until(async () => { try { session = (await api('bootstrap')).session; return !!session; } catch { return false; } }, 'PC server did not start', 15000);
    const token = randomBytes(24).toString('hex');
    instrument = spawn(process.env.ADB || 'adb', ['-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'desktopTransferToken', token,
      'com.qalens.sample.test/com.qalens.sample.RecordingRetentionInstrumentation']);
    instrument.stdout.on('data', chunk => { instrumentOutput += chunk; });
    instrument.stderr.on('data', chunk => { instrumentOutput += chunk; });
    await until(() => stage() === 'ready', 'Android transfer fixture did not become ready');
    await api('connect', {profile: {name: 'Synthetic transfer test', serial, package: 'com.qalens.sample', activity: '.MainActivity', devicePort: 18768}, token});
    const bench = await api('workbench');
    const owned = adb(['forward', '--list']).split('\n').map(row => row.trim().split(/\s+/))
      .find(row => row[0] === serial && row[2] === 'tcp:18768');
    assert.ok(owned, 'This test server did not own an adb forward'); forwarding = owned[1];
    let busy = true, now = Date.now(), checked = false;
    const copied = [];
    const transfer = new RecordingTransfer({busy: () => busy, now: () => now,
      changed: t => { checked = t.enabled; }, status: () => {},
      connection: async () => { const current = await api('workbench'); return {id: current.connectionId, connected: current.connected}; },
      list: () => api('recordings/device'),
      copy: async (name, connectionId) => { copied.push(await api('recordings/receive', {name, connectionId})); }
    });
    await transfer.enable(); assert.equal(checked, true); assert.equal(transfer.baselinePending, true);
    busy = false; await transfer.poll(); assert.equal(transfer.baselinePending, false); assert.deepEqual(copied, []);
    // Interrupt only the forward created by this test server; no device change or token rotation.
    adb(['forward', '--remove', forwarding]); await transfer.poll();
    assert.equal(checked, true); assert.equal(transfer.failures, 1);
    adb(['forward', forwarding, 'tcp:18768']);
    signal('record'); await until(() => stage() === 'saved', 'Device did not finish its recording');
    now += 31000; await transfer.poll(); assert.equal(checked, true);
    assert.equal(copied.length, 2, 'Both newly finished master and clip must transfer');
    assert.equal(copied.filter(entry => entry.name.startsWith('session_')).length, 1);
    assert.equal(copied.filter(entry => entry.name.startsWith('clip_')).length, 1);
    for (const entry of copied) {
      const path = join(root, 'recordings', `${entry.id}.sal`);
      const bytes = readFileSync(path);
      assert.equal(bytes.length, entry.size); assert.equal(createHash('sha256').update(bytes).digest('hex'), entry.id);
      if (process.platform !== 'win32') assert.equal(statSync(path).mode & 0o777, 0o600);
    }
    assert.equal((await api('recordings/local')).items.length, 2);
    await transfer.poll(); assert.equal(copied.length, 2, 'Polling duplicated a finished recording');
    signal('copied'); await until(() => stage() === 'rotated', 'Android pairing did not rotate');
    await transfer.poll(); assert.equal(checked, false, 'Revoked pairing must clear automatic collection');
    signal('done');
    await until(() => instrument.exitCode !== null, 'Android instrumentation did not finish', 15000);
    assert.match(instrumentOutput, /OK: Real desktop transfer/); assert.doesNotMatch(instrumentOutput, /FAIL|Exception/);
    assert.equal((await api('workbench')).connectionId, bench.connectionId);
    console.log('OK: real phone/Python/shared controller; busy enable, adb outage recovery, paused-host master+clip copy, SHA-256/private files, dedup and revoked pairing');
  } finally {
    if (forwarding) { try { adb(['forward', forwarding, 'tcp:18768']); } catch {} }
    if (instrument && instrument.exitCode === null) {
      try { signal('done'); } catch {}
      try { adb(['shell', 'am', 'force-stop', 'com.qalens.sample']); } catch {}
      instrument.kill();
    }
    server.kill('SIGTERM'); await until(() => server.exitCode !== null, 'PC server did not shut down', 10000);
    rmSync(root, {recursive: true, force: true});
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
