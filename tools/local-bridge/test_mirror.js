'use strict';
const assert = require('node:assert/strict'), fs = require('node:fs'), vm = require('node:vm');
const Mirror = require('./mirror-controls.js'), Transfer = require('./recording-transfer.js');
const Diagnostics = require('./diagnostics.js');
const drain = async () => { for (let i = 0; i < 12; i++) await new Promise(setImmediate); };
function fixture(live = false) {
  const elements = new Map(), requests = [], timers = [], posts = [], urls = [];
  const make = () => ({children: [], options: [], checked: false, value: '', dataset: {}, style: {}, attrs: {}, listeners: {},
    classList: {toggle() {}, add() {}, remove() {}}, setAttribute(k,v) { this.attrs[k] = v; }, removeAttribute() {},
    addEventListener(event, callback) { this.listeners[event] = callback; }, replaceChildren(...nodes) { this.children = nodes; this.options = nodes; },
    append(...nodes) { this.children.push(...nodes); this.options = this.children; }, focus() {}, setPointerCapture() {}, click() {}, remove() {}, showModal() { this.open = true; }, close() { this.open = false; },
    getContext: () => ({drawImage() {}, clearRect() {}}), getBoundingClientRect: () => ({left: 0, top: 0, width: 400, height: 600}), contentWindow: {postMessage: (...args) => posts.push(args)}});
  const get = id => { if (!elements.has(id)) elements.set(id, make()); return elements.get(id); };
  const nodes = [{id: 'a', tag: 'cart.buy', label: 'Buy', actions: ['tap'], enabled: true, bounds: {left: 0, top: 0, right: 30, bottom: 40}}];
  const state = {connected: true, phase: 'connected', mirrorBackend: live ? 'scrcpy' : 'legacy', connectionId: 'phone-a', connection: {serial: 'phone', package: 'example.qa'}, profiles: [], pipelines: [], jobs: [], preferences: {autoConnect: false},
    mirrorReady: true, stream: {streamId:'stream', revision:1, modeEpoch:1}, controls: {phase: 'idle', mode: 'frames', allowVideo: true, canClip: false, markedClips: 0, inspection: false, sessionName: 'session_123.sal', capabilities: ['recording-control', 'masked-screenshot']}, items: [], holdInput: null};
  const json = payload => ({ok: true, headers: {get: () => 'frame'}, json: async () => payload, blob: async () => ({}), arrayBuffer: async () => new ArrayBuffer(3)});
  const document = {hidden: false, body: get('body'), getElementById: get, querySelectorAll: () => [], querySelector: get, createElement: make, createElementNS: make, addEventListener() {}};
  class FakeDecoder {
    constructor(callbacks) { this.callbacks=callbacks; this.decodeQueueSize=0; }
    static async isConfigSupported(config) { return {supported:true,config}; }
    configure(config) { this.config=config; }
    decode() { this.callbacks.output({displayWidth:this.config.codedWidth,displayHeight:this.config.codedHeight,close(){}}); }
    close() {}
  }
  function liveBytes() {
    const size=Buffer.alloc(12); size.writeUInt32BE(0x80000000); size.writeUInt32BE(100,4); size.writeUInt32BE(200,8);
    const csd=Buffer.from([0,0,0,1,0x67,0x42,0xc0,0x1e,0,0,0,1,0x68,0]);
    const config=Buffer.alloc(12); config.writeBigUInt64BE(1n<<62n); config.writeUInt32BE(csd.length,8);
    const frame=Buffer.from([0,0,1,0x65,1]); const header=Buffer.alloc(12); header.writeBigUInt64BE((1n<<61n)|123n); header.writeUInt32BE(frame.length,8);
    return new Uint8Array(Buffer.concat([Buffer.from('h264'),size,config,csd,header,frame]));
  }
  const context = vm.createContext({document, location: {hash: '#landing', origin: 'http://fixture'}, history: {replaceState() {}, pushState() {}}, window: {scrollTo() {}, addEventListener() {}},
    navigator: {clipboard: {writeText: async () => {}}}, QaLensMirror: {...Mirror, installLayout() {}}, QaLensScrcpy: require('./scrcpy-stream.js'), QaLensRecordingTransfer: Transfer,
    QaLensDiagnostics: {...Diagnostics, install: () => ({sync() {}, buttons() {}, pageChanged() {}})},
    VideoDecoder: FakeDecoder, EncodedVideoChunk: class {constructor(data){Object.assign(this,data);}}, AbortController, clearInterval() {},
    Date, URL: {createObjectURL: () => { const url = `blob:${urls.length}`; urls.push(url); return url; }, revokeObjectURL() {}},
    setInterval: (callback, delay) => timers.push({callback, delay}), setTimeout, clearTimeout,
    fetch: async (url, options = {}) => {
      const body = options.body && JSON.parse(options.body); requests.push({url, body, headers: options.headers});
      if (url === '/api/bootstrap') return new Promise(() => {});
      if (url === '/api/mirror/video') return {ok:true,body:new ReadableStream({start(c){c.enqueue(liveBytes());options.signal.addEventListener('abort',()=>c.error(Object.assign(Error('aborted'),{name:'AbortError'})),{once:true});}})};
      if (url === '/api/mirror/input') return state.holdInput || json({ok:true});
      if (url === '/api/mirror/heartbeat') return json({...state.stream,width:100,height:200});
      if (url === '/api/screen') return json({});
      if (url === '/api/input') return state.holdInput ? state.holdInput : json({ok: true});
      if (url === '/api/inspection') { state.controls.inspection = body.enabled; return state.holdInspection || json({ok: true}); }
      if (url === '/api/preview') { state.stream.modeEpoch++; return json({ok:true,...state.stream}); }
      if (url === '/api/adb') return json({ok: true, notice: 'Phone Back sent'});
      if (url === '/api/snapshot') return json({connectionId: state.connectionId, viewport: {width: 100, height: 200}, nodes, screen: 'Cart', omittedNodes: 0});
      if (url === '/api/component') return json({document: {hash: 'a'.repeat(64), content: {component: {...nodes[0], attributes: []}, tree: {path: []}}}});
      if (url === '/api/selectors') return json({connectionId: state.connectionId, suggestions: [], omittedNodes: 0});
      if (url === '/api/command') { state.controls.inspection = true; return json({ok: true}); }
      if (url === '/api/previews') return json({documents: []});
      if (url === '/api/recordings/device') return json({connectionId: state.connectionId, controls: {...state.controls}, items: state.items});
      if (url === '/api/recording') {
        if (body.action === 'start') { state.controls.phase = body.video ? 'awaiting_consent' : 'capturing'; state.controls.canClip = !body.video; }
        if (body.action === 'stop') { state.controls.phase = 'saving'; state.controls.canClip = false; }
        if (body.action === 'clip') state.controls.markedClips++;
        return json({connectionId: state.connectionId, controls: {...state.controls}, notice: 'requested'});
      }
      if (url === '/api/recordings/receive') return json({id: 'hash', size: 128});
      if (url === '/api/recordings/local') return json({items: []});
      if (url.startsWith('/api/recordings/file')) return json({});
      if (url === '/api/screenshot') return json({});
      throw Error(`Unexpected request ${url}`);
    }
  });
  vm.runInContext(fs.readFileSync(`${__dirname}/app.js`, 'utf8'), context);
  context.fixtureState = state;
  vm.runInContext('busy = false; session = "session"; workbench = {...fixtureState}; captureConnection = "phone-a"; captureState = fixtureState.controls; previewEnabled = true; mirrorFrame = {id:"frame",connectionId:"phone-a",width:100,height:200,at:Date.now()}; viewerLoaded = true;', context);
  if (live) vm.runInContext('previewEnabled=false; mirrorFrame=null;',context);
  get('record-mode').value = 'frames'; get('clip-duration').value = '10'; get('link-selections').checked = true;
  const event = (x=200,y=300) => ({button: 0, pointerId: 1, clientX: x, clientY: y, preventDefault() {}});
  return {get, context, state, requests, posts, document, event};
}
async function run() {
  {
    const f=fixture(true), canvas=f.get('phone-canvas');
    await f.get('preview-toggle').onclick(); await drain();
    assert.equal(f.get('screen-video').hidden,false,'Live video never appeared');
    canvas.listeners.pointerdown(f.event()); canvas.listeners.pointermove(f.event(210,320)); canvas.listeners.pointerup(f.event(215,330)); await drain();
    assert.deepEqual(f.requests.filter(r=>r.url==='/api/mirror/input').map(r=>r.body.state),['down','move','up']);
    assert.equal(f.requests.filter(r=>r.url==='/api/screen'||r.url==='/api/input').length,0,'Default mirror used the legacy capture/input path');
    await vm.runInContext('setMirrorMode("preview")',f.context); await drain();
    canvas.listeners.pointerdown(f.event()); canvas.listeners.pointerup(f.event()); await drain();
    assert.equal(f.requests.filter(r=>r.url==='/api/mirror/input').length,3,'Preview sent a live host input');
    await f.get('mode-inspect').onclick(); await drain();
    canvas.listeners.pointerdown(f.event(95,60)); canvas.listeners.pointerup(f.event(95,60)); await drain();
    assert.equal(f.requests.filter(r=>r.url==='/api/mirror/input').length,3,'Inspect sent a live host input');
    assert.deepEqual(f.requests.filter(r=>r.url==='/api/command').map(r=>r.body.action),['select']);
    const updates=f.requests.filter(r=>r.url==='/api/preview' && r.body.mode==='inspect');
    assert.ok(updates.every(r=>r.body.streamId==='stream'),'Selection did not update the owned video mode');
    await vm.runInContext('setMirrorMode("control")',f.context); await drain();
    canvas.listeners.wheel({...f.event(),deltaY:120,deltaX:0}); canvas.listeners.keydown({...f.event(),key:'Enter'}); await drain();
    assert.deepEqual(f.requests.filter(r=>r.url==='/api/mirror/input').slice(-2).map(r=>r.body.action),['scroll','key']);
    await vm.runInContext('stopPreview()',f.context); assert.equal(f.get('screen-video').hidden,true);
    assert.equal(f.requests.filter(r=>r.url==='/api/preview').at(-1).body.streamId,'stream');
  }
  {
    const f=fixture(true), canvas=f.get('phone-canvas');
    await f.get('preview-toggle').onclick(); await drain();
    canvas.listeners.pointerdown(f.event());
    vm.runInContext('mirrorPointer.frame={...mirrorPointer.frame,at:Date.now()-6000};',f.context);
    canvas.listeners.pointerup(f.event()); await drain();
    assert.deepEqual(f.requests.filter(r=>r.url==='/api/mirror/input').map(r=>r.body.state),['down','cancel'],'Expired long press must release the held finger without clicking');
    await vm.runInContext('stopPreview()',f.context);
  }
  const box = {left:10,top:20,width:400,height:600};
  assert.deepEqual(Mirror.point(box,100,200,210,320),{x:.5,y:.5});
  assert.equal(Mirror.point(box,100,200,15,320),null,'Letterbox padding is not a touch target');
  assert.deepEqual(Mirror.point(box,100,200,1000,320,true),{x:1,y:.5});
  assert.equal(Mirror.gesture({x:.5,y:.5},{x:.5,y:.5},700,100,200).action,'long-press');
  const swipe = Mirror.wheel({x:.5,y:.5},0,200,100,200); assert.ok(swipe.endY < swipe.y,'Wheel down must swipe upwards');
  assert.ok(Mirror.split([.38,.4,.22],1,1000,1200)[2]*1200>=180,'Tree must keep its minimum width');
  {
    const f = fixture(), canvas = f.get('phone-canvas');
    canvas.listeners.pointerdown(f.event()); canvas.listeners.pointerup(f.event()); await drain();
    assert.equal(f.requests.filter(r=>r.url==='/api/input').length,1); assert.equal(f.requests.find(r=>r.url==='/api/input').body.action,'tap');
    assert.equal(f.requests.filter(r=>r.url==='/api/command').length,0,'Control tap must not open the SDK inspector');
    await vm.runInContext('setMirrorMode("preview")',f.context); await drain();
    canvas.listeners.pointerdown(f.event()); canvas.listeners.pointerup(f.event()); canvas.listeners.wheel({...f.event(),deltaY:120,deltaX:0}); await drain();
    assert.equal(f.requests.filter(r=>r.url==='/api/input').length,1,'Preview sent host input');
  }
  {
    const f = fixture(), canvas = f.get('phone-canvas');
    for (const condition of ['previewEnabled=false', 'previewEnabled=true; mirrorInspect=true', 'mirrorInspect=false; mirrorMode="preview"', 'mirrorMode="control"; mirrorInputBusy=true']) {
      vm.runInContext(condition, f.context);
      canvas.listeners.keydown({...f.event(), key:'Escape'}); await drain();
    }
    assert.equal(f.requests.filter(r=>r.url==='/api/adb').length,0,'Escape sent Phone Back while mirror input was paused');
    vm.runInContext('mirrorInputBusy=false', f.context);
    canvas.listeners.keydown({...f.event(), key:'Escape'}); await drain();
    assert.deepEqual(f.requests.filter(r=>r.url==='/api/adb').map(r=>r.body.action),['back']);
  }
  {
    const f = fixture(); vm.runInContext('mirrorInspect=true; mirrorFrame={id:"frame",connectionId:"phone-a",width:100,height:200,at:Date.now()};', f.context);
    f.get('phone-canvas').listeners.pointerdown(f.event(95,60)); f.get('phone-canvas').listeners.pointerup(f.event(95,60)); await drain();
    assert.equal(f.requests.filter(r=>r.url==='/api/input').length,0,'Inspect clicked the host');
    assert.deepEqual(f.requests.filter(r=>r.url==='/api/command').map(r=>r.body.action),['select']);
    assert.equal(f.get('component-title').textContent,'Buy');
  }
  {
    const f = fixture(); vm.runInContext('mirrorFrame.connectionId="old";',f.context);
    f.get('phone-canvas').listeners.pointerdown(f.event()); f.get('phone-canvas').listeners.pointerup(f.event()); await drain();
    assert.equal(f.requests.filter(r=>r.url==='/api/input').length,0);
    vm.runInContext('mirrorFrame.connectionId="phone-a"; mirrorFrame.at=Date.now()-6000;',f.context);
    f.get('phone-canvas').listeners.pointerdown(f.event()); f.get('phone-canvas').listeners.pointerup(f.event()); await drain();
    assert.equal(f.requests.filter(r=>r.url==='/api/input').length,0,'Expired screen frame dispatched');
  }
  {
    const f=fixture(); let release;
    f.state.holdInspection=new Promise(resolve=>{release=resolve;});
    const starting=vm.runInContext('captureCommand("start")',f.context); await drain();
    vm.runInContext('workbench.connectionId="replacement";',f.context);
    release({ok:true,json:async()=>({ok:true})});
    await assert.rejects(starting,/Connection changed/);
    assert.equal(f.requests.find(r=>r.url==='/api/inspection').headers['X-Qalens-Connection'],'phone-a');
    assert.equal(f.requests.filter(r=>r.url==='/api/recording').length,0,'An awaited mode change started recording on a replacement phone');
  }
  {
    const f = fixture(); await vm.runInContext('captureCommand("start")',f.context);
    f.get('clip-note').value = 'Checkout stalls after Pay';
    await vm.runInContext('captureCommand("clip")',f.context);
    assert.equal(f.requests.find(r => r.url === '/api/recording' && r.body.action === 'clip').body.label, 'Checkout stalls after Pay');
    assert.equal(f.get('clip-note').value, '');
    assert.equal(f.state.controls.phase,'capturing'); assert.equal(f.state.controls.markedClips,1);
    assert.match(f.get('status').textContent,/export happens after Stop/);
    f.get('clip-duration').value='custom'; f.get('clip-custom').value='1.5';
    await assert.rejects(vm.runInContext('captureCommand("clip")',f.context),/whole seconds/);
    f.get('watch-after-stop').checked=true; await vm.runInContext('captureCommand("stop")',f.context);
    await vm.runInContext('pollCapture()',f.context); assert.equal(f.requests.filter(r=>r.url==='/api/recordings/receive').length,0,'Saving session was copied too early');
    f.state.controls.phase='idle'; f.state.items=[{name:'session_123.sal',size:128}];
    await vm.runInContext('pollCapture()',f.context);
    assert.deepEqual(f.requests.filter(r=>r.url==='/api/recordings/receive').map(r=>r.body.name),['session_123.sal']);
    assert.equal(f.posts.some(p=>p[0].type==='qalens-recording'),true,'Finished session did not reach shared replay');
  }
  {
    const f=fixture(); f.get('record-mode').value='hd'; await vm.runInContext('captureCommand("start")',f.context);
    assert.match(f.get('capture-state').textContent,/Approve HD/); assert.equal(f.get('record-clip').disabled,true);
    assert.equal(f.get('record-stop').textContent,'Cancel HD request');
    f.get('screenshot-overlay').checked=false; await f.get('screenshot').onclick(); await drain();
    assert.deepEqual(f.requests.find(r=>r.url==='/api/screenshot').body,{includeOverlay:false});
    assert.equal(f.get('screenshot-review').open,true);
    f.get('screenshot-overlay').checked=true; await f.get('screenshot').onclick(); await drain();
    assert.deepEqual(f.requests.filter(r=>r.url==='/api/screenshot')[1].body,{includeOverlay:true});
  }
  console.log('OK: mirror letterboxing/scroll/long-press, Control/Preview/Inspect routing, stale frames, capture/clip validation, saving then shared replay, HD consent state and screenshot option');
}
run().catch(error=>{console.error(error);process.exitCode=1;});
