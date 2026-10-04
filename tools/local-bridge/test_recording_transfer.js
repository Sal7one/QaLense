'use strict';
const assert = require('node:assert/strict');
const RecordingTransfer = require('./recording-transfer.js');
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return {promise, resolve}; };
function fixture() {
  const state = {busy: false, now: 0, id: 'phone-a', connected: true, items: ['existing.sal'], copied: [], messages: [], error: null};
  const hooks = {
    busy: () => state.busy, now: () => state.now, changed: t => { state.checked = t.enabled; }, status: m => state.messages.push(m),
    connection: async () => ({id: state.id, connected: state.connected}),
    list: async id => { if (state.error) throw state.error; return {connectionId: id, items: state.items.map(name => ({name}))}; },
    copy: async (name, id) => state.copied.push({name, id})
  };
  return {state, hooks, transfer: new RecordingTransfer(hooks)};
}
async function run() {
  {
    const {state, transfer} = fixture(); state.busy = true;
    await transfer.enable(); assert.equal(state.checked, true); assert.equal(transfer.baselinePending, true);
    state.busy = false; await transfer.poll(); assert.equal(transfer.baselinePending, false);
    assert.deepEqual(state.copied, []);
    state.items.push('new.sal'); await transfer.poll(); await transfer.poll();
    assert.deepEqual(state.copied, [{name: 'new.sal', id: 'phone-a'}]);
  }
  {
    const {state, transfer} = fixture(); await transfer.enable();
    state.error = Object.assign(Error('temporary no host/network'), {status: 503});
    await transfer.poll(); assert.equal(state.checked, true);
    state.error = null; state.items.push('after-background.sal');
    await transfer.poll(); assert.deepEqual(state.copied, []); // Backoff is bounded, not a hot loop.
    state.now = 5000; await transfer.poll();
    assert.deepEqual(state.copied, [{name: 'after-background.sal', id: 'phone-a'}]);
    assert.equal(state.checked, true);
  }
  {
    const {state, transfer} = fixture(); state.error = Object.assign(Error('temporary startup'), {status: 502});
    await transfer.enable(); assert.equal(state.checked, true);
    state.error = null; state.now = 5000; await transfer.poll();
    assert.deepEqual(state.copied, []); assert.equal(transfer.baselinePending, false);
    state.error = Object.assign(Error('token revoked'), {status: 401});
    await transfer.poll(); assert.equal(state.checked, false);
  }
  {
    const {state, hooks, transfer} = fixture(); await transfer.enable(); state.items.push('new.sal');
    const wait = deferred(); hooks.list = () => wait.promise;
    const polling = transfer.poll(); await Promise.resolve();
    transfer.checkConnection('phone-b', true);
    wait.resolve({connectionId: 'phone-a', items: [{name: 'new.sal'}]}); await polling;
    assert.equal(state.checked, false); assert.deepEqual(state.copied, []);
  }
  {
    const {state, hooks, transfer} = fixture(); const wait = deferred(); hooks.list = () => wait.promise;
    const enabling = transfer.enable(); await Promise.resolve(); transfer.disable();
    wait.resolve({connectionId: 'phone-a', items: []}); await enabling;
    assert.equal(state.checked, false); assert.deepEqual(state.copied, []);
  }
  {
    const {state, hooks, transfer} = fixture(); await transfer.enable(); state.items.push('interrupted.sal');
    const copy = hooks.copy; let failed = false;
    hooks.copy = async (...args) => { if (!failed) { failed = true; throw Error('interrupted file transfer'); } return copy(...args); };
    await transfer.poll(); assert.equal(state.checked, true); assert.deepEqual(state.copied, []);
    state.now = 5000; await transfer.poll(); await transfer.poll();
    assert.deepEqual(state.copied, [{name: 'interrupted.sal', id: 'phone-a'}]);
  }
  console.log('OK: queued enable, baseline, transient recovery, copy retry, token revocation, device change and disable races');
}
run().catch(error => { console.error(error); process.exitCode = 1; });
