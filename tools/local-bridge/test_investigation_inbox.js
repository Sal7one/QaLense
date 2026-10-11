'use strict';
const assert = require('node:assert/strict'), fs = require('node:fs'), vm = require('node:vm');
const Inbox = require('./investigation-inbox.js'), I = require('../../web/insights.js');
const drain = async () => { for (let n = 0; n < 8; n++) await new Promise(setImmediate); };
const deferred = () => { let resolve; const promise = new Promise(r => resolve = r); return {promise, resolve}; };
const hash = 'a'.repeat(64), entry = {hash, recordingName: 'phone.sal', question: 'Stalled after Play', target: 'recorded-app', hasReport: false, hasImage: false};
const transfer = {schema: 'qalens-investigation-transfer/1', bundle: I.buildBundle({start: 1000, duration: 30000, timeline: [{ts: 15000, kind: 'ACTION', title: 'Click Play'}]}, {focusMs: 14000, qaContext: {expectedResult: 'Resume', actualResult: 'Spinner'}})};
function fixture() {
  const state = {connection: {id: 'phone-a', connected: true}, visible: true, enabled: false, busy: false, now: 1000, held: null, heldDocument: null, previews: [entry], saved: []}, calls = [], changes = [];
  const controller = new Inbox({connection: () => state.connection, visible: () => state.visible, enabled: () => state.enabled, busy: () => state.busy, now: () => state.now,
    inbox: async id => { calls.push({op: 'inbox', id}); return state.held ? state.held.promise : {ok: true, previews: state.previews, connectionId: id}; },
    saved: async () => { calls.push({op: 'saved'}); return {ok: true, saved: state.saved}; },
    document: async hash => { calls.push({op: 'document', hash}); return state.heldDocument ? state.heldDocument.promise : {ok: true, document: transfer}; },
    save: async hash => { calls.push({op: 'save', hash}); state.saved = [entry]; return {ok: true, saved: entry}; },
    open: async (document, current) => { calls.push({op: 'open', document}); return current(); }, changed: model => changes.push(model.notice), pauseReceive: () => state.enabled = false});
  return {state, calls, changes, controller};
}
async function run() {
  {
    const f = fixture(); await f.controller.poll(); assert.equal(f.calls.length, 0, 'Receive is opt-in');
    f.state.enabled = true; f.state.visible = false; await f.controller.poll(); assert.equal(f.calls.length, 0);
    f.state.visible = true; f.state.busy = true; await f.controller.poll(); assert.equal(f.calls.length, 0);
    f.state.busy = false; f.state.held = deferred(); const pending = f.controller.poll(); await drain(); await f.controller.poll(); assert.equal(f.calls.length, 1, 'Polling never overlaps');
    f.state.held.resolve({ok: true, previews: [entry, entry, {hash: 'bad'}], connectionId: 'phone-a'}); await pending;
    assert.equal(f.controller.previews.length, 1); assert.deepEqual(f.calls.map(call => call.op), ['inbox'], 'Reception does not open, save or call a model');
    await f.controller.poll(); assert.equal(f.calls.length, 1, 'Successful polling waits at least 3.5 seconds');
    f.state.held = null; f.state.now += 3500; f.state.previews = Array.from({length: 20}, (_, index) => ({...entry, hash: index.toString(16).padStart(64, '0')})); await f.controller.poll(); assert.equal(f.controller.previews.length, 10);
  }
  {
    const f = fixture(); f.state.held = deferred(); const read = f.controller.poll(true); await drain();
    f.state.connection = {id: 'phone-b', connected: true}; f.controller.checkConnection('phone-b', true); f.state.held.resolve({previews: [entry], connectionId: 'phone-a'}); await read;
    assert.equal(f.controller.previews.length, 0, 'A stale phone inbox cannot repopulate a replacement connection');
  }
  {
    const f = fixture(); await f.controller.poll(true); f.state.heldDocument = deferred(); const opening = f.controller.open(hash); await drain();
    f.state.connection = {id: 'phone-b', connected: true}; f.state.heldDocument.resolve({document: transfer}); assert.equal(await opening, false); assert.equal(f.calls.filter(call => call.op === 'open').length, 0, 'Late pending-case read cannot navigate a new phone session');
  }
  {
    const f = fixture(); await f.controller.poll(true); assert.equal(await f.controller.open(hash), true); assert.deepEqual(f.calls.find(call => call.op === 'open').document.bundle.items, JSON.parse(JSON.stringify(transfer.bundle.items)));
    assert.equal(await f.controller.save(hash), true); assert.equal(f.controller.previews.length, 0); assert.equal(f.controller.saved.length, 1);
    f.state.connection = {id: '', connected: false}; f.controller.checkConnection('', false); assert.equal(await f.controller.open(hash, true), true, 'Saved cases work without a paired phone'); assert.equal(f.controller.saved.length, 1);
    await assert.rejects(f.controller.open('invalid'), /no longer available/);
  }
  {
    const f = fixture(); f.state.enabled = true;
    f.controller.hooks.inbox = async () => { const e = Error('Phone approval expired'); e.status = 401; throw e; };
    await f.controller.poll(); assert.equal(f.state.enabled, false); assert.match(f.controller.notice, /Reconnect/);
  }
  {
    const listeners = new Set(), posts = [], origin = 'http://127.0.0.1:8765';
    const win = {location: {origin}, addEventListener: (_type, callback) => listeners.add(callback), removeEventListener: (_type, callback) => listeners.delete(callback)};
    const iframe = {contentWindow: {postMessage: (data, destination) => posts.push({data, destination})}}, current = {yes: true};
    const opened = Inbox.sendToViewer({window: win, iframe, document: transfer, ready: async () => {}, current: () => current.yes, requestId: 'request_case1'}); await drain();
    assert.equal(posts[0].destination, origin); assert.equal(posts[0].data.type, 'qalens-investigation');
    const emit = event => { for (const listener of [...listeners]) listener(event); }, event = {origin, source: iframe.contentWindow, data: {type: 'qalens-investigation-ack', requestId: 'request_case1', ok: true}};
    emit({...event, origin: 'http://attacker'}); emit({...event, source: {}}); emit({...event, data: {...event.data, requestId: 'other_request'}}); assert.equal(listeners.size, 1, 'Only this exact iframe/origin/request can acknowledge');
    emit(event); assert.equal(await opened, true); assert.equal(listeners.size, 0);
    const waiting = deferred(), stale = Inbox.sendToViewer({window: win, iframe, document: transfer, ready: () => waiting.promise, current: () => current.yes}); current.yes = false; waiting.resolve(); assert.equal(await stale, false); assert.equal(posts.length, 1, 'Connection changes while loading never post a stale case');
    current.yes = true; await assert.rejects(Inbox.sendToViewer({window: win, iframe, document: transfer, ready: async () => {}, timeoutMs: 5}), /acknowledge/); assert.equal(listeners.size, 0);
  }
  {
    // Execute the actual desktop functions, with the shared controller; this tests
    // session/header wiring and the explicit tab transition after an iframe ACK.
    const source = fs.readFileSync(`${__dirname}/app.js`, 'utf8'), elements = new Map(), requests = [], posts = [], listeners = new Set();
    const get = id => { if (!elements.has(id)) elements.set(id, {hidden: false, checked: false, src: '/web/index-v2.html?desktop', textContent: '', children: [], append(...rows) { this.children.push(...rows); }, replaceChildren() { this.children = []; }, addEventListener() {}, removeEventListener() {}}); return elements.get(id); };
    const origin = 'http://127.0.0.1:8765', win = {location: {origin}, addEventListener: (_type, fn) => listeners.add(fn), removeEventListener: (_type, fn) => listeners.delete(fn)};
    get('viewer').contentWindow = {postMessage(data, destination) { posts.push({data, destination}); setImmediate(() => { for (const callback of [...listeners]) callback({origin, source: get('viewer').contentWindow, data: {type: 'qalens-investigation-ack', requestId: data.requestId, ok: true}}); }); }};
    const document = {createElement: tag => ({tag, children: [], append(...nodes) { this.children.push(...nodes); }})}, tabs = [];
    const context = vm.createContext({QaLensInvestigationInbox: Inbox, window: win, document, location: {origin, href: `${origin}/#landing`}, URL, setTimeout, clearTimeout, setInterval() {}, $: get,
      recordingTransfer: {inflight: false}, workbench: {connected: true, connectionId: 'phone-a'}, busy: false, session: 'session-only', investigationInbox: null, viewerLoaded: true,
      status: text => get('status').textContent = text, tab: page => tabs.push(page), perform: work => work(),
      documentElement: (tag, className) => ({tag, className, children: [], append(...nodes) { this.children.push(...nodes); }}),
      button: (text, action, parent) => { const el = {textContent: text, onclick: action}; parent.append(el); return el; },
      fetch: async (url, options) => { requests.push({url, options}); const result = url.endsWith('/inbox') ? {ok: true, connectionId: 'phone-a', previews: [entry]} : url.includes('/document?') ? {ok: true, document: transfer} : {ok: true, saved: []}; return {ok: true, json: async () => result}; }});
    const api = source.slice(source.indexOf('async function api('), source.indexOf('// The model service'));
    const wiring = source.slice(source.indexOf('async function readyInvestigationViewer()'), source.indexOf('async function openRecording(id)'));
    vm.runInContext(api + '\n' + wiring, context); await get('investigations-receive').onclick();
    assert.equal(requests[0].options.headers['X-Qalens-Session'], 'session-only'); assert.equal(requests[0].options.headers['X-Qalens-Connection'], 'phone-a'); assert.equal(posts.length, 0); assert.deepEqual(tabs, []);
    const review = get('investigations-pending').children[0].children[2].children[0]; await review.onclick();
    assert.deepEqual(tabs, ['replay']); assert.equal(posts[0].data.document.schema, 'qalens-investigation-transfer/1'); assert.equal(requests[1].options.headers['X-Qalens-Connection'], undefined, 'Local document reads require the PC session without a pairing token');
    assert.ok(!requests.some(request => /insights\/(analyze|models)|investigations\/save/.test(request.url)), 'Review invokes neither a model nor automatic persistence');
    const html = fs.readFileSync(`${__dirname}/index.html`, 'utf8'); assert.match(html, /src="\/investigation-inbox\.js"/); assert.match(html, /Receive components &amp; investigations/);
  }
  console.log('OK: bounded opt-in Landing inbox, explicit review/save, dedup/backoff, stale device guards, saved unpaired access, exact iframe ACK guards and actual authenticated desktop wiring');
}
run().catch(error => { console.error(error); process.exitCode = 1; });
