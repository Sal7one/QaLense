'use strict';
// Exercise media waiting/clock mapping/limits without claiming real browser codecs.
const assert = require('node:assert/strict');
const Still = require('../recording-still.js');
const drain = async () => { for (let i = 0; i < 5; i++) await new Promise(setImmediate); };
class Media {
  constructor() { this.events = new Map(); this.naturalWidth = 2048; this.naturalHeight = 4096; this.videoWidth = 2048; this.videoHeight = 4096; this.readyState = 2; this.currentTime = 0; this.seeking = false; }
  addEventListener(event, fn) { if (!this.events.has(event)) this.events.set(event, new Set()); this.events.get(event).add(fn); }
  removeEventListener(event, fn) { this.events.get(event)?.delete(fn); }
  emit(event) { for (const fn of [...this.events.get(event) || []]) fn(); }
  set src(value) { this._src = value; if (this.onSource) this.onSource(); else queueMicrotask(() => this.emit('load')); }
}
function fixture(video = false) {
  const state = {session: {start: 10000, duration: 60000, videoUrl: video ? 'blob:recorded-video' : null}, paused: 0, seeked: [], canvases: [], image: null, draws: [], holdImage: false, tooLarge: false};
  const media = new Media();
  const document = {createElement: tag => {
    if (tag === 'img') { const img = new Media(); state.image = img; if (state.holdImage) img.onSource = () => {}; return img; }
    if (tag === 'canvas') {
      const canvas = {width: 0, height: 0, getContext: () => ({drawImage: (...args) => state.draws.push({args, width: canvas.width, height: canvas.height})}),
        toDataURL: () => 'data:image/jpeg;base64,' + (state.tooLarge ? 'A'.repeat(180000) : 'synthetic-jpeg')}; state.canvases.push(canvas); return canvas;
    }
    throw Error('Unexpected DOM element');
  }};
  const options = {document, session: () => state.session, video: media, pause: () => state.paused++,
    seek: time => { state.seeked.push(time); if (video) { media.currentTime = (time - 1000) / 1000; media.seeking = true; queueMicrotask(() => { media.seeking = false; media.emit('seeked'); }); } },
    frameAt: () => ({ts: 22000, url: 'blob:recorded-frame'}), videoBase: () => 11000};
  return {state, options, media};
}
async function run() {
  {
    const f = fixture(); const image = await Still.capture(f.options, 12300);
    assert.equal(image.source, 'recording-frame'); assert.equal(image.tMs, 12000); assert.equal(image.approximate, true);
    assert.equal(f.state.image._src, 'blob:recorded-frame'); assert.equal(f.state.paused, 1); assert.deepEqual(f.state.seeked, [12300]);
    assert.equal(f.state.draws[0].width, 480); assert.equal(f.state.draws[0].height, 960); assert.equal(f.state.canvases[0].width, 1); assert.equal(f.state.canvases[0].height, 1);
  }
  {
    const f = fixture(true); const image = await Still.capture(f.options, 12300);
    assert.equal(image.source, 'recording-video'); assert.equal(image.tMs, 12300, 'Video-start offset maps image time to original recording t0'); assert.equal(image.approximate, true);
    assert.equal(f.state.seeked.length, 2); assert.ok(f.state.draws[0].args[0] === f.media);
  }
  {
    const f = fixture(), controller = new AbortController(); controller.abort(); await assert.rejects(Still.capture(f.options, 12300, controller.signal), e => e.name === 'AbortError'); assert.equal(f.state.paused, 0); assert.equal(f.state.seeked.length, 0);
  }
  {
    const f = fixture(), controller = new AbortController(); f.state.holdImage = true; const capture = Still.capture(f.options, 12300, controller.signal), rejection = assert.rejects(capture, e => e.name === 'AbortError'); await drain(); controller.abort(); await rejection; f.state.image.emit('load'); assert.equal(f.state.draws.length, 0);
  }
  {
    const f = fixture(); f.state.holdImage = true; const capture = Still.capture(f.options, 12300); await drain(); f.state.session = {...f.state.session}; f.state.image.emit('load'); await assert.rejects(capture, e => e.name === 'AbortError'); assert.equal(f.state.draws.length, 0, 'A prior recording image never draws into a replacement review');
  }
  {
    const f = fixture(); f.state.tooLarge = true; await assert.rejects(Still.capture(f.options, 12300), /128 KiB/); assert.equal(f.state.draws.length, 5); assert.equal(f.state.canvases[0].width, 1); assert.equal(f.state.canvases[0].height, 1);
  }
  {
    const f = fixture(); f.options.frameAt = () => null; await assert.rejects(Still.capture(f.options, 12300), /no saved image/); assert.equal(f.state.canvases.length, 0);
  }
  for (const ts of [9999, 70001, NaN, undefined]) {
    const f = fixture(); f.options.frameAt = () => ({ts, url: 'blob:invalid-frame-clock'});
    await assert.rejects(Still.capture(f.options, 12300), /actual timestamp/);
    assert.equal(f.state.image, null, 'Invalid saved frame clocks are rejected before image decoding'); assert.equal(f.state.canvases.length, 0);
  }
  {
    const f = fixture(); f.options.frameAt = () => ({ts: 22000.125, url: 'blob:fractional-valid-clock'});
    assert.equal((await Still.capture(f.options, 12300)).tMs, 12000.125, 'A valid saved timestamp remains unchanged, including sub-millisecond precision');
  }
  for (const base of [NaN, -100000, 100000]) {
    const f = fixture(true); f.options.videoBase = () => base;
    await assert.rejects(Still.capture(f.options, 12300), /actual image timestamp/); assert.equal(f.state.canvases.length, 0);
  }
  for (const time of [NaN, Infinity, -1]) {
    const f = fixture(true); f.options.seek = () => {}; f.media.currentTime = time;
    await assert.rejects(Still.capture(f.options, 12300), /actual image timestamp/); assert.equal(f.state.canvases.length, 0);
  }
  {
    const f = fixture(); await assert.rejects(Still.capture(f.options, 60001), /actual clock/); assert.equal(f.state.paused, 0); assert.equal(f.state.seeked.length, 0);
  }
  console.log('OK: saved-frame/video still timing/seek/pause, bounded dimensions/encoding attempts, temporary canvas disposal, pre-abort/no movement, late decode cancellation and recording replacement guards');
}
run().catch(error => { console.error(error); process.exitCode = 1; });
