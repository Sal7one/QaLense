'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
const UI = require('../insights-ui.js');
const I = require('../insights.js');
const drain = async () => { for (let i = 0; i < 8; i++) await new Promise(setImmediate); };
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return {promise, resolve}; };
class Element {
  constructor(tag) { this.tagName = tag.toUpperCase(); this.children = []; this._text = ''; this.value = ''; this.checked = false; this.disabled = false; this.hidden = false; this.attributes = {}; }
  set innerHTML(_value) { throw Error('Untrusted markup must never enter this UI.'); }
  set textContent(value) { this._text = String(value); this.children = []; }
  get textContent() { return this._text; }
  append(...elements) { for (const child of elements) { child.parent = this; this.children.push(child); if (this.tagName === 'SELECT' && this.children.length === 1) this.value = child.value; } }
  replaceChildren(...elements) { this.children = []; this._text = ''; this.value = this.tagName === 'SELECT' ? '' : this.value; this.append(...elements); }
  setAttribute(name, value) { this.attributes[name] = value; }
  removeAttribute(name) { delete this.attributes[name]; if (name === 'src') this.src = ''; }
  remove() { if (this.parent) this.parent.children = this.parent.children.filter(c => c !== this); }
  allText() { return this._text + ' ' + this.children.map(c => c.allText()).join(' '); }
  all() { return [this, ...this.children.flatMap(c => c.all())]; }
}
const sample = {start: 1000, end: 31000, duration: 30000, timeline: [{ts: 15000, title: 'Play'}], network: [{ts: 15000, method: 'GET', url: '/video', status: 503}], logs: [], state: [], frames: []};
const report = {schema: I.REPORT_SCHEMA, summary: '<img src=x onerror="steal()"> visual text only', observations: [{text: '<script>steal()</script> 503 captured', evidenceIds: ['network:0']}], hypotheses: [{title: 'Possible request issue', confidence: 'low', reasoning: 'Not proved.', evidenceIds: ['network:0'], nextChecks: ['Review the recovery path.']}], missingEvidence: ['Player callback integration'], recommendedChecks: ['Repeat while collecting player state.']};
const png = {id: 'images:0', tMs: 14000, mediaType: 'image/png', data: 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aGZsAAAAASUVORK5CYII=', source: 'recording-frame', approximate: false};
function fixture() {
  const host = new Element('div'), calls = [], exports = [], seeks = [], copies = [], state = {session: {...sample}, models: [{id: 'synthetic-chat', name: 'Synthetic chat'}], held: null,
    image: png, capturePositions: [],
    runtime: {id: 'player:runtime', source: 'current-player', client: 'web', observedAtMillis: 1800000000000, recordingPositionMs: 14000, details: {mediaKind: 'video', mediaError: {code: 3, message: 'Synthetic current decoder error'}, videoCurrentTimeSeconds: 9}}};
  const document = {createElement: tag => new Element(tag), body: new Element('body')};
  const transport = {request: async (operation, payload) => {
    calls.push({operation, payload});
    if (operation === 'models') return {ok: true, protocol: 'openai', models: state.models};
    if (operation === 'analyze') return state.held ? state.held.promise : {ok: true, job: {id: 'synthetic-job', state: 'done', report}};
    if (operation === 'cancel') return {ok: true, job: {id: payload.id, state: 'cancelled'}};
    throw Error('Unexpected operation');
  }, close() {}};
  const controller = UI.install({document, container: host, session: () => state.session, name: () => 'synthetic.sal', playhead: () => 14000,
    seek: time => seeks.push(time), pause() {}, transport, copy: async text => copies.push(text), captureStill: async position => { state.capturePositions.push(position); return state.image; }, runtime: () => ({...state.runtime, details: {...state.runtime.details}}), download: (name, data) => exports.push({name, data})});
  const button = text => host.all().find(e => e.tagName === 'BUTTON' && e.textContent === text);
  const label = text => host.all().find(e => e.tagName === 'LABEL' && e.children[0]?.textContent === text)?.children[1];
  const check = () => host.all().find(e => e.tagName === 'INPUT' && e.type === 'checkbox');
  return {host, calls, exports, seeks, copies, state, controller, button, label, check};
}
async function run() {
  const classicSource = fs.readFileSync(path.join(__dirname, '../index.html'), 'utf8');
  assert.match(classicSource, /href="index-v2\.html\?desktop"[^>]*>Lens 2\.0/, 'Classic-to-modern navigation retains the authenticated desktop iframe transport');
  {
    const source = fs.readFileSync(path.join(__dirname, '../app-v2.js'), 'utf8');
    const receiver = source.slice(source.indexOf('  function receiveDesktopMessage(event)'), source.indexOf('  window.addEventListener("message", receiveDesktopMessage)'));
    const parent = {sent: [], postMessage(data, origin) { this.sent.push({data, origin}); }}, opened = [], own = {parent}, origin = 'http://127.0.0.1:8765';
    const context = {window: own, location: {origin, search: '?desktop'}, URLSearchParams, showImportedInvestigation: transfer => opened.push(transfer), pause() {}, investigation: {cancel() {}}};
    vm.runInNewContext(receiver, context);
    const data = {type: 'qalens-investigation', requestId: 'request_123', document: {safe: true}}, event = {origin, source: parent, data};
    context.receiveDesktopMessage({...event, origin: 'http://attacker'}); context.receiveDesktopMessage({...event, source: {}}); context.receiveDesktopMessage({...event, data: {...data, requestId: 'bad'}}); assert.equal(opened.length, 0);
    context.receiveDesktopMessage(event); assert.equal(opened.length, 1); assert.equal(parent.sent[0].data.ok, true); assert.equal(parent.sent[0].origin, origin);
    context.location.search = ''; context.receiveDesktopMessage(event); assert.equal(opened.length, 1, 'Standalone windows cannot accept an unsolicited transfer');
  }
  {
    const f = fixture(), original = I.withImage(I.buildBundle({...sample, timeline: [{ts: 15000, kind: 'ACTION', title: 'Click Play'}]}, {focusMs: 14000, name: 'phone.sal', qaContext: {expectedResult: 'Resume', actualResult: 'Spinner'}}), png, true);
    f.state.session = null;
    f.controller.importInvestigation({schema: 'qalens-investigation-transfer/1', bundle: original, report});
    assert.equal(f.calls.length, 0); assert.match(f.host.allText(), /Phone report · supplied interpretation/); assert.match(f.host.allText(), /Click Play/); assert.match(f.host.allText(), /full tracks or play video/);
    assert.equal(f.label('Bug moment · seconds').disabled, true); assert.equal(f.label('Evidence window').disabled, true); assert.equal(f.check().checked, false);
    f.host.all().find(e => e.tagName === 'BUTTON' && e.textContent.startsWith('network:0 ·')).onclick(); assert.deepEqual(f.seeks, [], 'Imported citations open copied evidence rather than seek an absent recording');
    f.button('Export investigation JSON').onclick(); assert.equal(f.exports.at(-1).data.schema, 'qalens-investigation-transfer/1'); assert.ok(f.exports.at(-1).data.bundle.images);
    await f.button('Connect model').onclick(); await f.button('Analyze with local model').onclick(); const first = f.calls.find(call => call.operation === 'analyze').payload;
    assert.equal(first.includeImage, false); assert.ok(!first.bundle.images); assert.deepEqual(first.bundle.items, original.items); assert.deepEqual(first.bundle.recording, original.recording);
    f.check().checked = true; f.check().onchange(); await f.button('Review transferred still').onclick(); assert.deepEqual(f.state.capturePositions, [], 'No live or unrelated recording pixels are captured for a phone handoff');
    await f.button('Analyze with local model').onclick(); const visual = f.calls.filter(call => call.operation === 'analyze').at(-1).payload;
    assert.equal(visual.includeImage, true); assert.equal(visual.bundle.images[0].data, png.data);
    f.label('Expected result · tester reported (optional)').value = 'New tester expectation'; f.label('Expected result · tester reported (optional)').oninput(); assert.equal(f.check().checked, false); assert.equal(f.button('Copy QA report'), undefined);
    await new Promise(resolve => setTimeout(resolve, 230)); f.button('Export selected evidence JSON').onclick(); const edited = f.exports.at(-1).data;
    assert.deepEqual(edited.items, original.items); assert.deepEqual(edited.recording, original.recording); assert.equal(edited.qaContext.expectedResult, 'New tester expectation'); assert.ok(!edited.images);
    f.controller.focus(20000); f.button('Export selected evidence JSON').onclick(); assert.equal(f.exports.at(-1).data.recording.focusMs, 14000);
    f.state.session = sample; f.controller.clear(); assert.equal(f.label('Bug moment · seconds').disabled, false); assert.equal(f.button('Review transferred still'), undefined); assert.ok(f.button('Prepare still at bug moment')); f.controller.destroy();
  }
  {
    const f = fixture(), runtime = {...f.state.runtime, client: 'android', details: {mediaKind: 'video', mediaError: {code: 3, message: 'Old Android decoder failure'}}};
    const bundle = I.buildBundle({...sample, timeline: [{ts: 15000, kind: 'ACTION', title: 'Old host click'}]}, {target: 'qalens-player', focusMs: 14000, runtime});
    f.controller.importInvestigation({schema: 'qalens-investigation-transfer/1', bundle, report: {...report, observations: [{text: 'Reported Android decode failure', evidenceIds: ['player:runtime']}]}});
    assert.match(f.host.allText(), /historical, not the current web player state/); assert.match(f.host.allText(), /No replay-control action trace/); assert.equal(f.button('Refresh current player facts').hidden, true);
    await f.button('Connect model').onclick(); await f.button('Analyze with local model').onclick(); assert.deepEqual(f.calls.find(call => call.operation === 'analyze').payload.bundle.investigation.runtime, JSON.parse(JSON.stringify(bundle.investigation.runtime))); f.controller.destroy();
  }
  {
    const f = fixture(); assert.equal(f.calls.length, 0, 'Opening a recording never contacts a model');
    assert.equal(f.check().checked, false); assert.equal(f.button('Analyze with local model').disabled, true);
    f.state.models = [{id: 'text-embedding-nomic-embed-text-v1.5'}]; await f.button('Connect model').onclick();
    assert.equal(f.button('Analyze with local model').disabled, true); assert.match(f.host.allText(), /no chat model is installed/);
    f.state.models = [{id: 'synthetic-chat'}]; await f.button('Connect model').onclick(); assert.equal(f.button('Analyze with local model').disabled, false);
    f.button('Use paused moment').onclick(); assert.equal(f.label('Bug moment · seconds').value, '14');
    await f.button('Analyze with local model').onclick(); const command = f.calls.find(x => x.operation === 'analyze').payload;
    assert.equal(command.includeImage, false); assert.ok(!command.bundle.images); assert.equal(command.bundle.coverage.media.pixelsSent, false);
    assert.match(f.host.allText(), /<script>steal/); assert.equal(f.host.all().filter(e => e.tagName === 'SCRIPT').length, 0);
    f.host.all().find(e => e.tagName === 'BUTTON' && e.textContent.startsWith('network:0 ·')).onclick(); assert.deepEqual(f.seeks, [14000]); assert.equal(f.label('Bug moment · seconds').value, '14');
    f.button('Export investigation JSON').onclick(); assert.equal(f.exports.length, 1); assert.ok(f.exports[0].data.report.observations.length);
    f.label('Local model URL').value = 'http://127.0.0.1:1235'; f.label('Local model URL').oninput(); assert.equal(f.button('Analyze with local model').disabled, true);
    f.controller.destroy();
  }
  {
    const f = fixture(); await f.button('Connect model').onclick(); f.state.held = deferred(); const running = f.button('Analyze with local model').onclick(); await drain();
    assert.equal(f.button('Cancel').hidden, false); assert.equal(f.label('What went wrong? (optional)').disabled, true);
    f.controller.cancel(); f.state.held.resolve({ok: true, job: {id: 'late-job', state: 'queued'}}); await running; await drain();
    assert.ok(f.calls.some(x => x.operation === 'cancel' && x.payload.id === 'late-job')); assert.doesNotMatch(f.host.allText(), /<img src=x/); assert.match(f.host.allText(), /Cancelled/);
    f.controller.destroy();
  }
  {
    const f = fixture(); await f.button('Connect model').onclick(); f.state.held = deferred(); const running = f.button('Analyze with local model').onclick(); await drain();
    const source = fs.readFileSync(path.join(__dirname, '../app-v2.js'), 'utf8');
    const setTrack = source.slice(source.indexOf('  function setTrack(track)'), source.indexOf('  // ── session lifecycle'));
    const plainElement = () => ({hidden: false, children: []});
    const context = {store: {track: 'insights', diffOpen: false, S: {}}, prefs: {}, LS: {set() {}}, investigation: f.controller,
      els: Object.fromEntries(['trackTabs', 'logChips', 'reportView', 'trackList', 'insightsView', 'tracksTools', 'aiBar', 'search', 'substats'].map(key => [key, plainElement()])), renderTrack() {}};
    vm.runInNewContext(setTrack + '\nsetTrack("logs");', context);
    assert.equal(context.els.insightsView.hidden, true); assert.equal(f.controller.busy(), false, 'The actual player track navigation cancels hidden model work');
    f.state.held.resolve({ok: true, job: {id: 'hidden-track-job', state: 'done', report}}); await running; await drain();
    assert.ok(f.calls.some(call => call.operation === 'cancel' && call.payload.id === 'hidden-track-job')); assert.doesNotMatch(f.host.allText(), /<img src=x/); f.controller.destroy();
  }
  {
    const f = fixture(); await f.button('Connect model').onclick(); f.state.held = deferred(); const running = f.button('Analyze with local model').onclick(); await drain();
    f.state.session = {...sample, name: 'replacement'}; f.controller.clear(); f.state.held.resolve({ok: true, job: {id: 'old-session-job', state: 'done', report}}); await running;
    assert.doesNotMatch(f.host.allText(), /<img src=x/); assert.ok(f.calls.some(x => x.operation === 'cancel' && x.payload.id === 'old-session-job')); f.controller.destroy();
  }
  {
    const f = fixture(); await f.button('Connect model').onclick(); f.controller.focus(14000);
    f.check().checked = true; f.check().onchange(); await f.button('Analyze with local model').onclick(); assert.equal(f.calls.filter(x => x.operation === 'analyze').length, 0, 'Checking image alone is not review/consent to an uncaptured image');
    await f.button('Prepare still at bug moment').onclick(); assert.match(f.host.allText(), /Ready for review/);
    assert.ok(f.host.all().find(e => e.tagName === 'IMG').src.startsWith('data:image/png;base64,'));
    await f.button('Analyze with local model').onclick(); const command = f.calls.find(x => x.operation === 'analyze').payload;
    assert.equal(command.includeImage, true); assert.equal(command.bundle.images.length, 1); assert.equal(command.bundle.coverage.media.pixelsSent, true);
    f.label('What went wrong? (optional)').value = 'Different symptom'; f.label('What went wrong? (optional)').oninput();
    assert.equal(f.check().checked, false); assert.equal(f.host.all().find(e => e.tagName === 'IMG').hidden, true); f.controller.destroy();
  }
  {
    const f = fixture(); f.state.session = {...sample, timeline: [{ts: 15000, kind: 'ACTION', title: 'Click Play'}]}; f.controller.focus(14000); await f.button('Connect model').onclick();
    f.label('Expected result · tester reported (optional)').value = 'Video resumes'; f.label('Expected result · tester reported (optional)').oninput();
    f.label('Actual result · tester reported (optional)').value = 'Spinner remains'; f.label('Actual result · tester reported (optional)').oninput();
    assert.equal(f.button('Analyze with local model').disabled, true); await f.button('Analyze with local model').onclick(); assert.equal(f.calls.filter(call => call.operation === 'analyze').length, 0);
    await new Promise(resolve => setTimeout(resolve, 230)); f.button('Export selected evidence JSON').onclick(); const reviewed = structuredClone(f.exports.at(-1).data);
    await f.button('Analyze with local model').onclick(); assert.equal(JSON.stringify(f.calls.find(call => call.operation === 'analyze').payload.bundle), JSON.stringify(reviewed));
    assert.match(f.host.allText(), /Expected result · tester reported/); assert.match(f.host.allText(), /Actual result · tester reported/); assert.match(f.host.allText(), /Click Play/);
    assert.ok(f.host.allText().indexOf('QA bug report') < f.host.allText().indexOf('Possible causes'));
    await f.button('Copy QA report').onclick(); assert.match(f.copies[0], /## Expected result/); assert.match(f.copies[0], /Source: Tester reported/); assert.match(f.copies[0], /timeline:0/);
    f.label('Actual result · tester reported (optional)').value = 'Different result'; f.label('Actual result · tester reported (optional)').oninput(); assert.equal(f.button('Copy QA report'), undefined, 'Editing tester results invalidates the previous QA interpretation immediately'); f.controller.destroy();
  }
  {
    const f = fixture(); f.controller.focus(14000); f.button('Review offline signals').onclick(); assert.equal(f.calls.length, 0); assert.match(f.host.allText(), /503/); assert.match(f.host.allText(), /Pixels and audio were not analyzed/); f.controller.destroy();
  }
  {
    const f = fixture(); assert.equal(f.label('Local model URL').value, 'http://127.0.0.1:1234'); assert.equal(f.label('Protocol').value, 'openai');
    f.label('What are you investigating?').value = 'qalens-player'; f.label('What are you investigating?').onchange();
    assert.match(f.host.allText(), /not a recorded host event/); assert.match(f.host.allText(), /Synthetic current decoder error/);
    await f.button('Connect model').onclick(); f.state.runtime.details.mediaError = {code: 4, message: 'Changed unreviewed media state'};
    await f.button('Analyze with local model').onclick(); const command = f.calls.find(x => x.operation === 'analyze').payload;
    assert.equal(command.bundle.investigation.target, 'qalens-player'); assert.equal(command.bundle.investigation.runtime.details.mediaError.code, 3, 'Analyze sends the reviewed snapshot, not silently refreshed current facts');
    f.button('Refresh current player facts').onclick(); assert.match(f.host.allText(), /Changed unreviewed media state/); assert.doesNotMatch(f.host.allText(), /<img src=x/);
    f.label('What are you investigating?').value = 'recorded-app'; f.label('What are you investigating?').onchange();
    f.button('Export selected evidence JSON').onclick(); assert.equal(f.exports.at(-1).data.investigation.target, 'recorded-app'); assert.ok(!f.exports.at(-1).data.investigation.runtime);
    f.controller.destroy();
  }
  {
    const f = fixture(); f.state.session = {...sample, duration: 60000, end: 61000, network: [
      {ts: sample.start + 10053, status: 503, url: '/reviewed'}, {ts: sample.start + 40080, status: 503, url: '/outside-the-reviewed-window'}
    ]};
    f.controller.focus(10053); assert.equal(f.label('Bug moment · seconds').value, '10.053');
    f.button('Export selected evidence JSON').onclick(); const reviewed = structuredClone(f.exports.at(-1).data);
    assert.equal(reviewed.recording.windowEndMs, 40053); assert.ok(!reviewed.items.some(item => item.id === 'network:1'));
    await f.button('Connect model').onclick();
    f.state.session.network.push({ts: sample.start + 10054, status: 500, url: '/not-in-the-reviewed-snapshot'});
    await f.button('Analyze with local model').onclick();
    assert.equal(JSON.stringify(f.calls.find(call => call.operation === 'analyze').payload.bundle), JSON.stringify(reviewed), 'Analyze sends exactly the reviewed bundle; neither display rounding nor later track changes widen consent');
    f.controller.destroy();
  }
  {
    const f = fixture(); f.state.session = {...sample, duration: 60000, end: 61000, network: [
      {ts: sample.start + 10053, status: 503, url: '/reviewed'}, {ts: sample.start + 40080, status: 503, url: '/outside-the-reviewed-window'}
    ]};
    f.state.image = {...png, tMs: 10053}; f.controller.focus(10053); await f.button('Connect model').onclick(); f.check().checked = true; f.check().onchange();
    f.button('Export selected evidence JSON').onclick(); const reviewed = structuredClone(f.exports.at(-1).data);
    f.state.session.network.push({ts: sample.start + 10054, status: 500, url: '/unreviewed-late-change'});
    await f.button('Prepare still at bug moment').onclick(); assert.deepEqual(f.state.capturePositions, [10053]);
    f.button('Export selected evidence JSON').onclick(); const withStill = f.exports.at(-1).data;
    assert.equal(JSON.stringify(withStill.items), JSON.stringify(reviewed.items), 'Still preparation adds only the reviewed saved image; it does not recompute the textual window or evidence');
    assert.equal(withStill.recording.windowEndMs, 40053); assert.equal(withStill.recording.focusMs, 10053);
    await f.button('Analyze with local model').onclick(); assert.deepEqual(f.calls.find(call => call.operation === 'analyze').payload.bundle, withStill); f.controller.destroy();
  }
  {
    const f = fixture(); await f.button('Connect model').onclick(); const originalBuilder = I.buildBundle; let builds = 0;
    I.buildBundle = (...args) => { builds++; return originalBuilder(...args); };
    try {
      for (const text of ['V', 'Vi', 'Vid', 'Video stalled']) { f.label('What went wrong? (optional)').value = text; f.label('What went wrong? (optional)').oninput(); }
      assert.equal(builds, 0, 'Typing invalidates review without repeatedly scanning heavy tracks'); assert.equal(f.button('Analyze with local model').disabled, true);
      await f.button('Connect model').onclick(); assert.equal(builds, 0); assert.equal(f.button('Analyze with local model').disabled, true, 'Fast discovery completion cannot re-enable sending while question evidence is still pending');
      await f.button('Analyze with local model').onclick(); assert.equal(f.calls.filter(call => call.operation === 'analyze').length, 0, 'Pending question preparation cannot silently flush and send a newly selected bundle');
      await new Promise(resolve => setTimeout(resolve, 230)); assert.equal(builds, 1); assert.equal(f.button('Analyze with local model').disabled, false);
      f.button('Export selected evidence JSON').onclick(); assert.equal(f.exports.at(-1).data.question, 'Video stalled'); assert.equal(f.calls.filter(call => call.operation === 'analyze').length, 0);
    } finally { I.buildBundle = originalBuilder; f.controller.destroy(); }
  }
  console.log('OK: actual shared UI explicit-send/discovery/embedding rejection, unsafe model text rendered as text, evidence seeking/export, stale session/cancelled-start guards, reviewed image consent/reset and offline use without model requests');
}
run().catch(error => { console.error(error); process.exitCode = 1; });
