'use strict';
// Exercise the actual browser wiring: inspection-only two-way selection, stale replies and search.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const RecordingTransfer = require('./recording-transfer.js');
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return {promise, resolve}; };
const response = data => ({ok: true, json: async () => data});
const drain = async () => { for (let i = 0; i < 12; i++) await new Promise(setImmediate); };
function fixture() {
  const elements = new Map(), requests = [], timers = [];
  function element() {
    return {children: [], options: [], value: '', checked: false, disabled: false, hidden: false, dataset: {}, style: {},
      classList: {toggle() {}, add() {}, remove() {}}, setAttribute() {}, addEventListener() {},
      replaceChildren(...nodes) { this.children = [...nodes]; this.options = this.children; },
      append(...nodes) { this.children.push(...nodes); this.options = this.children; },
      contentWindow: {postMessage() {}}};
  }
  const get = id => { if (!elements.has(id)) elements.set(id, element()); return elements.get(id); };
  const nodes = [
    {id: 'a', tag: 'cart.add', label: 'Add', role: 'Button', actions: ['tap'], enabled: true, parentId: null, bounds: {left: 0, top: 0, right: 30, bottom: 30}},
    {id: 'b', tag: 'cart.name', label: 'Name', description: ['Customer'], actions: ['type'], enabled: true, parentId: null, bounds: {left: 0, top: 31, right: 30, bottom: 60}},
    {id: 'c', label: 'Read only', role: 'Text', actions: [], enabled: true, parentId: null, bounds: {left: 0, top: 61, right: 30, bottom: 90}}
  ];
  const state = {connectionId: 'phone-a', connected: true, selectedId: 'a', heldSelection: null, heldSelectors: null, heldHighlight: null};
  const document = {hidden: false, body: get('body'), getElementById: get, querySelectorAll: () => [], querySelector: get,
    createElement: element, createElementNS: element, addEventListener() {}};
  const context = vm.createContext({document, location: {hash: '#landing', origin: 'http://fixture'},
    history: {replaceState() {}}, window: {scrollTo() {}, addEventListener() {}},
    navigator: {clipboard: {writeText: async () => {}}}, QaLensScrcpy: require('./scrcpy-stream.js'), QaLensRecordingTransfer: RecordingTransfer, QaLensInvestigationInbox: require('./investigation-inbox.js'),
    setInterval: (callback, delay) => timers.push({callback, delay}),
    fetch: async (url, options) => {
      const body = options.body && JSON.parse(options.body); requests.push({url, body});
      if (url === '/api/bootstrap') return new Promise(() => {});
      if (url === '/api/selection') return state.heldSelection ? state.heldSelection.promise : response({connectionId: state.connectionId, selectedId: state.selectedId});
      if (url === '/api/snapshot') return response({connectionId: state.connectionId, nodes, screen: 'Cart', viewport: {width: 100, height: 100}, omittedNodes: 0});
      if (url === '/api/command') { if (state.heldHighlight) { const held = state.heldHighlight; state.heldHighlight = null; return held.promise; } state.selectedId = body.id; return response({ok: true}); }
      if (url === '/api/component') { const n = nodes.find(n => n.id === body.id); return response({document: {hash: n.id.repeat(64), liveNodeId: n.id, content: {component: {...n, attributes: []}, tree: {path: []}}}}); }
      if (url === '/api/selectors') return state.heldSelectors ? state.heldSelectors.promise : response({connectionId: state.connectionId, xml: '<qalens/>', omittedNodes: 0, suggestions: [{title: 'Test tag', xpath: `//node[@tag='${body.id}']`, matches: 1, stability: 'tag'}]});
      if (url === '/api/previews') return response({documents: []});
      if (url === '/api/query') return response({connectionId: state.connectionId, count: 1, nodes: [{id: 'b', attributes: {tag: 'cart.name', label: 'Name'}}]});
      throw Error(`Unexpected request ${url}`);
    }
  });
  vm.runInContext(fs.readFileSync(`${__dirname}/app.js`, 'utf8'), context);
  context.fixtureNodes = nodes;
  vm.runInContext('busy = false; session = "session"; workbench = {connected: true, connectionId: "phone-a", pipelines: []}; snapshot = {connectionId: "phone-a", nodes: fixtureNodes, viewport: {width: 100, height: 100}};', context);
  get('link-selections').checked = true;
  return {get, nodes, state, requests, context, document};
}
async function run() {
  {
    const f = fixture(); await vm.runInContext('choose(snapshot.nodes[0])', f.context);
    assert.deepEqual(f.requests.filter(r => r.url === '/api/command').map(r => r.body.action), ['select']);
    assert.equal(f.get('selector-candidates').children.length, 1);
    await f.get('selector-candidates').children[0].children[3].children[1].onclick(); await drain();
    assert.equal(f.get('selector-builder').open, true, 'Checking a generated selector must reveal its results');
    assert.equal(f.get('query-matches').children.length, 2);
    f.state.selectedId = 'b'; await vm.runInContext('syncPhoneSelection()', f.context);
    assert.equal(vm.runInContext('selected.id', f.context), 'b');
    assert.equal(f.requests.filter(r => r.url === '/api/command').length, 1, 'Phone selection must not echo back as another command');
    assert.equal(f.requests.filter(r => r.url === '/api/inbox').length, 0, 'Linking must not require Send to PC');
    assert.equal(f.requests.filter(r => r.url === '/api/snapshot').length, 1, 'A changed phone selection must refresh its tree bounds/actions');
    f.state.selectedId = null; await vm.runInContext('syncPhoneSelection()', f.context);
    assert.equal(vm.runInContext('selected', f.context), null, 'Clearing phone selection must clear the live desktop selection');
    assert.equal(vm.runInContext('selectorBundle', f.context), null);
  }
  {
    const f = fixture(); f.get('link-selections').checked = false;
    await vm.runInContext('choose(snapshot.nodes[1])', f.context); await vm.runInContext('syncPhoneSelection()', f.context);
    assert.equal(f.requests.filter(r => ['/api/command', '/api/selection'].includes(r.url)).length, 0);
    f.get('search').value = 'customer'; assert.equal(vm.runInContext('filtered()[0].id', f.context), 'b');
    f.get('search').value = ''; f.get('action-filter').value = 'none'; assert.equal(vm.runInContext('filtered()[0].id', f.context), 'c');
    f.get('action-filter').value = 'tap'; f.get('role-filter').value = 'Text'; assert.equal(vm.runInContext('filtered().length', f.context), 0);
  }
  {
    const f = fixture(); f.state.heldSelection = deferred();
    const reading = vm.runInContext('syncPhoneSelection()', f.context); await drain();
    await vm.runInContext('choose(snapshot.nodes[1])', f.context);
    f.state.heldSelection.resolve(response({connectionId: 'phone-a', selectedId: 'a'})); await reading;
    assert.equal(vm.runInContext('selected.id', f.context), 'b', 'An old phone read replaced a newer PC choice');
  }
  {
    const f = fixture(); f.state.heldSelectors = deferred();
    const choosing = vm.runInContext('choose(snapshot.nodes[0])', f.context); await drain();
    vm.runInContext('workbench.connectionId = "replacement"; selected = null; clearSelectors();', f.context);
    f.state.heldSelectors.resolve(response({connectionId: 'phone-a', suggestions: [{title: 'old', xpath: '//node', matches: 1}]})); await choosing;
    assert.equal(vm.runInContext('selectorBundle', f.context), null, 'Old selectors crossed a device switch');
  }
  {
    const f = fixture(), held = deferred(); f.state.heldHighlight = held;
    const first = vm.runInContext('choose(snapshot.nodes[0])', f.context); await drain();
    const second = vm.runInContext('choose(snapshot.nodes[1])', f.context); await drain();
    const third = vm.runInContext('choose(snapshot.nodes[2])', f.context); await drain();
    assert.equal(f.requests.filter(r => r.url === '/api/command').length, 1, 'Phone highlights overlapped');
    held.resolve(response({ok: true})); await Promise.all([first, second, third]);
    assert.deepEqual(f.requests.filter(r => r.url === '/api/command').map(r => r.body.id), ['a', 'c'], 'A superseded queued highlight was dispatched');
    assert.equal(f.state.selectedId, 'c'); assert.equal(vm.runInContext('selected.id', f.context), 'c');
  }
  {
    const f = fixture(); vm.runInContext('render()', f.context);
    let stopped = false;
    f.get('map').children[1].onclick({stopPropagation() { stopped = true; }}); await drain();
    assert.equal(stopped, true);
    assert.equal(f.requests.filter(r => r.url === '/api/command').length, 1, 'Schematic rectangle should select once');
    vm.runInContext('previewEnabled = true', f.context);
    f.get('map').children[1].onclick({stopPropagation() { throw Error('Live preview must reach its fresh coordinate handler'); }}); await drain();
    assert.equal(f.requests.filter(r => r.url === '/api/command').length, 1, 'Preview rectangle dispatched before the fresh coordinate handler');
  }
  {
    const f = fixture(); f.document.hidden = true; await vm.runInContext('syncPhoneSelection()', f.context);
    assert.equal(f.requests.filter(r => r.url === '/api/selection').length, 0);
    const value = 'Bob\'s "cart"'; f.context.literalValue = value;
    assert.equal(vm.runInContext('xpathLiteral(literalValue)', f.context), 'concat(\'Bob\',"\'",\'s "cart"\')');
  }
  console.log('OK: two-way inspection-only selection, selector rendering/search, disabled/hidden linking, stale replies, preview bubbling and serialized latest highlights');
}
run().catch(error => { console.error(error); process.exitCode = 1; });
