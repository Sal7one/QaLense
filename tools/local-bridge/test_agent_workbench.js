'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

class Element {
  constructor() {
    this.value = '';
    this.open = false;
    this.events = {};
  }
  addEventListener(event, callback) { this.events[event] = callback; }
  showModal() { this.open = true; }
  close() { this.open = false; this.events.close?.(); }
  focus() {}
  select() {}
}

async function test() {
  const nodes = new Map(), calls = [];
  const element = id => {
    if (!nodes.has(id)) nodes.set(id, new Element());
    return nodes.get(id);
  };
  const document = {getElementById: id => element(id.replace(/^agent-/, '')), hidden: false};
  let clipboard = '', highlight, shown, revision = 1;
  const config = {mcpServers: {qalens: {command: 'python3', args: ['mcp_server.py']}}};
  const node = {id: 'a', tag: 'button.submit', role: 'Button', label: 'Submit', bounds: {left: 10, top: 20}, actions: ['tap']};
  const selected = {connectionId: 'phone', ready: true, busy: false, selected: node,
    snapshot: {screen: 'Home', route: 'home', connectionId: 'phone', nodes: [node]}};
  const state = {enabled: false, connectionId: 'phone', revision, config, handoff: 'Test harness'};
  const api = async (route, body, connection) => {
    calls.push({route, body, connection});
    if (route === 'agent/status') return {...state};
    assert.equal(connection, selected.connectionId);
    if (route === 'agent/access') {
      Object.assign(state, body, {revision: ++revision, focus: null});
      return {...state};
    }
    if (route === 'agent/draft') {
      assert.equal(body.target.element.tag, node.tag);
      assert.equal(body.target.route, 'home');
      assert.equal(body.task, 'edit');
      return {revision, request: {id: 'draft-id', prompt: 'Exact reviewed prompt'}};
    }
    if (route === 'agent/send') return {delivery: body.deliver ? 'accepted' : 'mcp'};
    throw Error(route);
  };
  const sandbox = vm.createContext({document, navigator: {clipboard: {writeText: async value => { clipboard = value; }}}});
  const code = fs.readFileSync(path.join(__dirname, 'agent-workbench.js'), 'utf8');
  const Agents = vm.runInContext(code + '\nQaLensAgents;', sandbox);
  const controller = Agents.install({document, api, context: () => selected,
    focusChanged: reference => { highlight = reference; }, showElement: reference => { shown = reference; }});
  await controller.poll();
  assert.equal(element('ask').disabled, false);
  element('ask').onclick();
  assert.equal(element('request-dialog').open, true);
  assert.match(element('request-element').textContent, /button\.submit.*home/);
  element('task').value = 'edit';
  element('intent').value = 'Keep Submit visible';
  element('intent').oninput();
  await element('review').onclick();
  assert.equal(element('share').disabled, true);
  await element('copy').onclick();
  assert.equal(clipboard, 'Exact reviewed prompt');
  assert.ok(!calls.some(call => call.route === 'agent/send'));

  // An external policy change hides an already reviewed copy-only prompt.
  state.revision = ++revision;
  await controller.poll();
  assert.equal(element('reviewed').hidden, true);
  assert.equal(element('prompt').value, '');

  element('request-access').onclick();
  assert.equal(element('access-dialog').open, true);
  assert.equal(element('request-dialog').open, false);
  await Promise.resolve();
  element('allow-images').checked = true;
  await element('enable').onclick();
  await element('copy-config').onclick();
  assert.equal(JSON.parse(clipboard).mcpServers.qalens.command, 'python3');
  element('access-close').onclick();
  assert.equal(element('request-dialog').open, true);
  await element('review').onclick();
  await element('share').onclick();
  assert.equal(calls.at(-1).body.deliver, false);
  assert.match(element('request-status').textContent, /Shared with MCP/);
  assert.equal(element('share').disabled, true);
  assert.equal(element('send').disabled, true);

  // A reference is only applied to the exact node on the same screen and phone.
  state.focus = {id: 'focus-id', node, screen: 'Home', route: 'home', connectionId: 'phone'};
  await controller.poll();
  assert.equal(Agents.match(highlight, selected.snapshot), node);
  assert.equal(Agents.match(highlight, {...selected.snapshot, route: 'checkout'}), null);
  assert.equal(Agents.match(highlight, {...selected.snapshot, nodes: [node, node]}), null);
  assert.equal(Agents.match(highlight, {...selected.snapshot, nodes: [{...node, bounds: {left: 20}}]}), null);
  element('reference-open').onclick();
  assert.equal(shown, highlight);
  element('request-close').onclick();
  element('access').onclick();
  await Promise.resolve();
  await element('disable').onclick();
  assert.equal(highlight, null);
  assert.equal(element('config-block').hidden, true);
  selected.ready = false;
  controller.sync();
  assert.equal(element('enable').disabled, true);
  assert.equal(element('ask').disabled, true);
  console.log('OK: prompt review, copy, MCP share, setup navigation, policy changes, matched references and revocation');
}

test().catch(error => { console.error(error); process.exitCode = 1; });
