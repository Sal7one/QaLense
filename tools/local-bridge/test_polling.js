'use strict';
// Execute the real desktop wiring with deterministic timers and held HTTP reads. The transfer
// controller's own tests do not cover competing inbox/health/transfer callbacks in app.js.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const RecordingTransfer = require('./recording-transfer.js');
const source = fs.readFileSync(process.argv[2] || `${__dirname}/app.js`, 'utf8');
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return {promise, resolve}; };
const drain = async () => { for (let i = 0; i < 8; i++) await new Promise(setImmediate); };

function fixture() {
  const elements = new Map(), timers = [], requests = [], inbox = deferred();
  const element = id => {
    if (!elements.has(id)) elements.set(id, {
      checked: false, disabled: false, value: '', style: {}, dataset: {},
      classList: {toggle() {}, add() {}, remove() {}},
      replaceChildren() {}, append() {}, addEventListener() {},
      contentWindow: {postMessage() {}}
    });
    return elements.get(id);
  };
  const state = {connected: true, phase: 'connected', mirrorBackend: 'legacy', connectionId: 'phone-a',
    connection: {package: 'synthetic.sample'}, preferences: {autoConnect: false},
    profiles: [], pipelines: [], jobs: [], items: [{name: 'existing.sal'}], health: null};
  const json = payload => ({ok: true, json: async () => payload});
  const document = {hidden: false, body: element('body'), getElementById: element,
    querySelectorAll: () => [], querySelector: element, createElement: element, addEventListener() {}};
  const context = vm.createContext({document, location: {hash: '#landing', origin: 'http://fixture'},
    history: {replaceState() {}}, window: {scrollTo() {}, addEventListener() {}},
    QaLensScrcpy: require('./scrcpy-stream.js'), QaLensRecordingTransfer: RecordingTransfer,
    setInterval: (callback, delay) => { timers.push({callback, delay}); },
    fetch: async (url, options) => {
      requests.push({url, body: options.body && JSON.parse(options.body)});
      if (url === '/api/bootstrap') return new Promise(() => {}); // Keep unrelated startup out of this fixture.
      if (url === '/api/inbox') return inbox.promise;
      if (url === '/api/connection/check') return state.health ? state.health.promise : json({...state});
      if (url === '/api/workbench') return json({...state});
      if (url === '/api/recordings/device') return json({connectionId: state.connectionId, items: state.items});
      if (url === '/api/recordings/receive') return json({size: 1024});
      if (url === '/api/recordings/local') return json({items: []});
      throw Error(`Unexpected fixture request: ${url}`);
    }
  });
  vm.runInContext(source, context);
  context.fixtureState = state;
  vm.runInContext('busy = false; session = "synthetic-session"; workbench = {...fixtureState};', context);
  element('receive').checked = true; element('auto-reconnect').checked = true;
  const [receive, health] = timers.filter(t => t.delay === 2500).map(t => t.callback);
  const transfer = timers.find(t => t.delay === 5000).callback;
  return {context, state, requests, inbox, document, element, receive, health, transfer};
}

async function run() {
  {
    const f = fixture();
    const receiving = f.receive(); // A read remains in flight at every aligned timer tick.
    await drain();
    await f.health();
    assert.equal(f.requests.filter(r => r.url === '/api/connection/check').length, 1,
      'Receive must not starve the owned-forward repair check');
    await vm.runInContext('recordingTransfer.enable()', f.context);
    assert.equal(f.element('auto-recordings').checked, true);
    assert.equal(f.requests.filter(r => r.url === '/api/recordings/device').length, 1,
      'Receive must not starve the automatic-transfer baseline');
    f.state.items = [{name: 'existing.sal'}, {name: 'new.sal'}];
    f.transfer(); await drain();
    assert.deepEqual(f.requests.filter(r => r.url === '/api/recordings/receive').map(r => r.body.name), ['new.sal']);
    f.transfer(); await drain();
    assert.equal(f.requests.filter(r => r.url === '/api/recordings/receive').length, 1);
    f.inbox.resolve({ok: true, json: async () => ({documents: [], connectionId: 'phone-a'})});
    await receiving;
  }
  {
    const f = fixture(); f.state.health = deferred();
    vm.runInContext('recordingTransfer.inflight = true;', f.context);
    const checking = f.health(); await drain();
    await f.health();
    assert.equal(f.requests.filter(r => r.url === '/api/connection/check').length, 1,
      'Health checks must not overlap themselves');
    vm.runInContext('workbench = {...workbench, connectionId: "replacement"};', f.context);
    f.state.health.resolve({ok: true, json: async () => ({connected: false, connectionId: 'phone-a'})});
    await checking;
    assert.equal(vm.runInContext('workbench.connectionId', f.context), 'replacement');
    assert.equal(f.element('receive').checked, true, 'Stale health response changed the replacement connection');
  }
  {
    const f = fixture(); f.document.hidden = true;
    await f.receive(); await f.health();
    await vm.runInContext('recordingTransfer.enable()', f.context);
    f.transfer(); await drain();
    assert.deepEqual(f.requests.map(r => r.url), ['/api/bootstrap']);
    f.document.hidden = false; vm.runInContext('busy = true;', f.context);
    await f.health(); f.transfer(); await drain();
    assert.deepEqual(f.requests.map(r => r.url), ['/api/bootstrap']);
  }
  console.log('OK: competing inbox/health/transfer timers, baseline/new copy/dedup, stale connection and hidden/busy guards');
}
run().catch(error => { console.error(error); process.exitCode = 1; });
