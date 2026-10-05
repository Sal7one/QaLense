'use strict';
const assert = require('node:assert/strict');
const D = require('./diagnostics.js');
const drain = async () => { for (let n = 0; n < 8; n++) await new Promise(setImmediate); };
const deferred = () => { let resolve; const promise = new Promise(r => resolve = r); return {promise, resolve}; };
class Element {
  constructor() { this.children = []; this.textContent = ''; this.value = ''; this.hidden = false; this.listeners = {}; this.dataset = {}; this.attrs = {}; this.classList = {toggle() {}}; }
  append(...nodes) { this.children.push(...nodes); }
  replaceChildren(...nodes) { this.children = nodes; }
  addEventListener(event, fn) { this.listeners[event] = fn; }
  setAttribute(name, value) { this.attrs[name] = value; }
  scrollIntoView() {}
  allText() { return this.textContent + this.children.map(c => c.allText()).join(' '); }
}
function fixture() {
  const elements = new Map(), timers = [], calls = [], listeners = {};
  const get = id => { if (!elements.has(id)) elements.set(id, new Element()); return elements.get(id); };
  const tabs = ['network', 'logs', 'values'].map(view => { const e = new Element(); e.dataset.diagnostic = view; return e; });
  const document = {hidden: false, getElementById: get, createElement: () => new Element(), querySelectorAll: () => tabs, addEventListener: (e, f) => listeners[e] = f};
  get('data-tools').hidden = true;
  const state = {connection: {id: 'phone-a', connected: true}, busy: false, policyId: 1,
    events: [{type: 'LOG', time: 1, message: '[ERROR] Fixture failure'}, {type: 'BREADCRUMB', message: 'not a log'}],
    network: [{status: 200, method: 'GET', url: '/success'}, {status: 0, error: 'offline', method: 'POST', url: '/retry'}],
    dataSources: {Settings: {theme: 'day', accessToken: '[REDACTED]'}}, hold: null, sqlHold: null, sqlPhase: 'running'};
  const api = async (path, command, id) => {
    calls.push({path, command, id});
    if (state.hold && !command) return state.hold.promise;
    const base = {ok: true, connectionId: id, policyId: state.policyId};
    if (path === 'events') return {...base, events: state.events, network: state.network};
    if (path === 'data' && !command) return {...base, dataSources: state.dataSources, sourceStatus: {}, coverage: 'Cached decoded values'};
    if (path === 'sql' && !command) return {...base, databases: [{id: 'db', name: 'fixture.db'}], saved: []};
    if (path === 'sql' && command.action === 'start') return state.sqlHold ? state.sqlHold.promise : {...base, id: 'read-1', phase: 'running'};
    if (path === 'sql' && command.action === 'status') return {...base, id: command.id, phase: state.sqlPhase, result: {columns: ['value'], rows: [['42']], durationMs: 10}};
    if (path === 'sql' && command.action === 'cancel') return {...base, id: command.id, phase: 'cancelling'};
    throw Error('Unexpected request');
  };
  const controller = D.install({document, api, connection: () => state.connection, workBusy: () => state.busy,
    navigate: () => {}, interval: (fn, delay) => timers.push({fn, delay})});
  return {get, state, document, controller, calls, listeners, tabs,
    live: () => timers.find(t => t.delay === 2000).fn(), pollSql: () => timers.find(t => t.delay === 600).fn(),
    open: async () => { get('live-diagnostics').open = true; get('live-diagnostics').listeners.toggle(); await drain(); },
    sql: async () => { get('landing').hidden = true; get('data-tools').hidden = false; controller.pageChanged('data-tools'); await drain(); get('sql-database').value = 'db'; }};
}
async function run() {
  const values = new D.Values();
  values.update({policyId: 1, dataSources: {Settings: {theme: 'day', token: '[REDACTED]'}}});
  const id = values.rows()[0].id; values.pin(id);
  values.update({policyId: 1, dataSources: {Settings: {theme: 'night'}}});
  assert.equal(values.rows('night')[0].before, 'day'); assert.equal(values.rows()[0].pinned, true);
  values.update({policyId: 1, dataSources: {}});
  assert.equal(values.rows()[0].value, undefined); assert.equal(values.rows()[0].before, null);
  values.update({policyId: 2, dataSources: {Settings: {theme: '[REDACTED]'}}});
  assert.equal(values.pins.size, 0); assert.equal(values.rows()[0].changed, false);
  values.update({policyId: 2, dataSources: {Settings: {allowedField: 'earlier-plaintext'}}});
  values.compare();
  values.update({policyId: 2, dataSources: {Settings: {allowedField: '[REDACTED]'}}});
  assert.equal(values.rows()[0].before, null);
  assert.doesNotMatch(JSON.stringify([...values.baseline.values()]), /earlier-plaintext/);
  values.update({policyId: 2, dataSources: {Settings: Object.fromEntries(Array.from({length: 500}, (_, n) => [`f${n}`, 'v']))}});
  for (const row of values.rows()) values.pin(row.id);
  assert.equal(values.pins.size, 10); assert.equal(values.current.size, 100);
  assert.equal(D.tableQuery('a"; DROP TABLE notes;--'), 'SELECT * FROM "a""; DROP TABLE notes;--" LIMIT 100');
  assert.equal(D.clipLabel('  Checkout froze  ', 10), 'Checkout froze'); assert.equal(D.clipLabel(' ', 60), 'Bug clip · last 60s');
  {
    const f = fixture(); f.live(); await drain(); assert.equal(f.calls.length, 0);
    await f.open(); assert.equal(f.calls.length, 1);
    f.get('diagnostic-errors').checked = true; f.get('diagnostic-errors').onchange();
    assert.match(f.get('diagnostic-content').allText(), /retry/); assert.doesNotMatch(f.get('diagnostic-content').allText(), /success/);
    f.get('diagnostic-follow').onclick(); await drain(); const count = f.calls.length;
    f.live(); await drain(); assert.equal(f.calls.length, count + 1);
    f.document.hidden = true; f.live(); await drain(); assert.equal(f.calls.length, count + 1);
    f.document.hidden = false; f.state.busy = true; f.live(); await drain(); assert.equal(f.calls.length, count + 1);
    f.state.busy = false; f.get('diagnostic-follow').onclick(); f.live(); await drain(); assert.equal(f.calls.length, count + 1);
    f.tabs[1].onclick(); await drain(); assert.match(f.get('diagnostic-content').allText(), /Fixture failure/); assert.doesNotMatch(f.get('diagnostic-content').allText(), /not a log/);
  }
  {
    const f = fixture(); await f.open(); f.tabs[2].onclick(); await drain();
    assert.match(f.get('diagnostic-content').allText(), /day/);
    f.state.dataSources = {Settings: {theme: 'night'}}; f.get('diagnostic-refresh').onclick(); await drain();
    assert.match(f.get('diagnostic-content').allText(), /Before: day/);
    f.state.policyId = 2; f.state.dataSources = {Settings: {theme: '[REDACTED]'}}; f.get('diagnostic-refresh').onclick(); await drain();
    assert.doesNotMatch(f.get('diagnostic-content').allText(), /night|Before: day/);
    f.state.hold = deferred(); f.get('diagnostic-refresh').onclick(); await drain();
    f.state.connection = {id: 'phone-b', connected: true}; f.controller.sync();
    f.state.hold.resolve({connectionId: 'phone-a', policyId: 1, dataSources: {Settings: {theme: 'stale-secret'}}}); await drain();
    assert.doesNotMatch(f.get('diagnostic-content').allText(), /stale-secret/);
  }
  {
    const f = fixture(); await f.sql(); f.get('sql-query').value = 'SELECT 42'; f.get('sql-run').onclick(); await drain();
    assert.equal(f.get('sql-cancel').disabled, false);
    f.get('sql-cancel').onclick(); await drain(); assert.equal(f.calls.filter(c => c.command?.action === 'cancel').length, 1);
    f.state.sqlPhase = 'cancelled'; f.pollSql(); await drain(); assert.equal(f.get('sql-cancel').disabled, true);
    f.state.sqlPhase = 'complete'; f.get('sql-run').onclick(); await drain(); assert.match(f.get('sql-results').allText(), /42/);
    f.state.sqlHold = deferred(); f.get('sql-run').onclick(); await drain();
    f.get('data-tools').hidden = true; f.get('landing').hidden = false; f.controller.pageChanged('landing');
    f.state.sqlHold.resolve({connectionId: 'phone-a', id: 'late-read', phase: 'running'}); await drain();
    assert.equal(f.calls.filter(c => c.command?.action === 'cancel' && c.command.id === 'late-read').length, 1);
    assert.equal(f.get('sql-results').allText(), '');
  }
  console.log('OK: live follow/pause/search/error views, bounds/pins/change baseline/privacy reset, stale devices, cancellable SQL and late-start navigation compensation');
}
run().catch(error => { console.error(error); process.exitCode = 1; });
