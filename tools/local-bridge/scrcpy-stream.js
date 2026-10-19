/* scrcpy 5.0 framing + WebCodecs. No recording, clipboard scraping or persisted frames. */
(function(root, factory) {
  const value = factory();
  if (typeof module === 'object' && module.exports) module.exports = value;
  else root.QaLensScrcpy = value;
})(typeof globalThis !== 'undefined' ? globalThis : this, function() {
  'use strict';
  const MAX_PACKET = 2 * 1024 * 1024;
  class Packets {
    constructor(callback) { this.callback = callback; this.buffer = new Uint8Array(); this.header = false; }
    feed(bytes) {
      if (bytes.byteLength > MAX_PACKET * 4) throw Error('Video transport exceeded its buffer budget');
      const data = new Uint8Array(this.buffer.length + bytes.length); data.set(this.buffer); data.set(bytes, this.buffer.length);
      let offset = 0;
      if (!this.header && data.length >= 4) {
        if (String.fromCharCode(...data.subarray(0, 4)) !== 'h264') throw Error('Expected the pinned scrcpy H.264 stream');
        this.header = true; offset = 4;
      }
      if (this.header) while (data.length - offset >= 12) {
        const header = new DataView(data.buffer, data.byteOffset + offset, 12);
        if (data[offset] & 0x80) {
          const width = header.getUint32(4), height = header.getUint32(8);
          if (!width || !height || width > 4096 || height > 4096 || width * height > 8000000) throw Error('Invalid video dimensions');
          this.callback({kind: 'size', width, height}); offset += 12;
        } else {
          const length = header.getUint32(8);
          if (!length || length > MAX_PACKET) throw Error('Video packet exceeded its budget');
          if (data.length - offset < length + 12) break;
          const flags = header.getBigUint64(0), config = !!(flags & (1n << 62n)), key = !!(flags & (1n << 61n));
          const timestamp = Number(flags & ((1n << 61n) - 1n));
          this.callback({kind: config ? 'config' : 'frame', key, timestamp, data: data.slice(offset + 12, offset + 12 + length)});
          offset += 12 + length;
        }
      }
      this.buffer = data.slice(offset);
      if (this.buffer.length > MAX_PACKET + 12) throw Error('Incomplete video packet exceeded its budget');
    }
    finish() { if (this.buffer.length || !this.header) throw Error('Phone video stream was interrupted'); }
  }
  function codec(config) {
    for (let i = 0; i + 7 < config.length; i++) {
      let nal = -1;
      if (config[i] === 0 && config[i+1] === 0 && config[i+2] === 1) nal = i + 3;
      else if (config[i] === 0 && config[i+1] === 0 && config[i+2] === 0 && config[i+3] === 1) nal = i + 4;
      if (nal >= 0 && (config[nal] & 31) === 7) return 'avc1.' + [...config.slice(nal+1, nal+4)].map(x => x.toString(16).padStart(2, '0')).join('');
    }
    throw Error('The phone did not send a usable H.264 configuration');
  }
  function aligned(frame, viewport) {
    if (!frame || !viewport || !frame.width || !frame.height || !viewport.width || !viewport.height) return false;
    return Math.abs((frame.width / frame.height) / (viewport.width / viewport.height) - 1) < .015;
  }
  function create(options) {
    const env = options.env || globalThis;
    let active = null, decoder = null, abort = null, heartbeat = null, firstFrameTimer = null, pending = 0;
    let generation = 0, revision = 0, width = 0, height = 0, csd = null, needKey = true, displayed = null;
    let pipeline = Promise.resolve(), inputQueue = [], sending = false;
    const supported = () => !!env.VideoDecoder && !!env.EncodedVideoChunk;
    function clearFirstFrameTimer() { if (firstFrameTimer !== null) env.clearTimeout(firstFrameTimer); firstFrameTimer = null; }
    function waitForFirstFrame(mine, expectedRevision) {
      clearFirstFrameTimer();
      firstFrameTimer = env.setTimeout(() => {
        if (mine !== generation || expectedRevision !== revision || displayed) return;
        fail(Error('No phone video appeared within 8 seconds. Stop and Start mirror again; check the phone and USB connection if it persists.'), mine);
      }, 8000);
    }
    function closeDecoder() { if (decoder) { try { decoder.close(); } catch (_) {} decoder = null; } }
    function stop() {
      generation++; active = null; csd = null; displayed = null; needKey = true; inputQueue = []; options.onFrame?.(null);
      if (abort) abort.abort(); abort = null;
      if (heartbeat) env.clearInterval(heartbeat); heartbeat = null;
      clearFirstFrameTimer();
      closeDecoder();
      options.canvas?.getContext?.('2d')?.clearRect(0, 0, options.canvas.width, options.canvas.height);
    }
    function fail(error, mine) {
      if (mine !== generation) return;
      const message = error.message || String(error); stop(); options.onError?.(message);
    }
    async function configure(packet, mine, expectedRevision) {
      if (!width || !height || packet.data.length > 65536) throw Error('Invalid phone codec configuration');
      const config = {codec: codec(packet.data), codedWidth: width, codedHeight: height, optimizeForLatency: true, hardwareAcceleration: 'prefer-hardware'};
      let support = await env.VideoDecoder.isConfigSupported(config);
      if (!support.supported) { config.hardwareAcceleration = 'no-preference'; support = await env.VideoDecoder.isConfigSupported(config); }
      if (!support.supported) throw Error('This browser cannot decode the phone video. Use Chrome/Edge or Open desktop window.');
      if (mine !== generation || expectedRevision !== revision) return;
      closeDecoder(); csd = packet.data; needKey = true;
      let lastTimestamp = null;
      decoder = new env.VideoDecoder({
        output(frame) {
          try {
            if (!active || mine !== generation || expectedRevision !== revision || !options.visible()) return;
            // The startup key is decoded again after flush to restore prediction state. Its
            // duplicate output still needs closing, but must not repaint or refresh input age.
            if (frame.timestamp === lastTimestamp) return;
            const canvas = options.canvas;
            if (frame.displayWidth !== width || frame.displayHeight !== height) throw Error('Phone video dimensions changed without a fresh configuration');
            if (canvas.width !== width || canvas.height !== height) { canvas.width = width; canvas.height = height; }
            canvas.getContext('2d').drawImage(frame, 0, 0, width, height);
            lastTimestamp = frame.timestamp;
            displayed = {id: active.streamId, connectionId: active.connectionId, width, height, revision, modeEpoch: active.modeEpoch, at: Date.now()};
            clearFirstFrameTimer();
            options.onFrame?.(displayed);
          } catch (error) { fail(error, mine); }
          finally { frame.close(); }
        }, error: error => { if (expectedRevision === revision) fail(error, mine); }
      });
      decoder.configure(support.config || config);
    }
    async function start(state, connectionId) {
      if (!supported()) throw Error('Live mirror needs a browser with WebCodecs, such as Chrome or Edge. Open desktop window is also available.');
      stop(); active = {...state, connectionId}; const mine = generation;
      revision = 0; width = height = 0; pending = 0; pipeline = Promise.resolve();
      options.onStage?.('Connecting phone video…'); waitForFirstFrame(mine, revision);
      const controller = new env.AbortController(); abort = controller;
      let beatBusy = false;
      heartbeat = env.setInterval(async () => {
        if (!active || mine !== generation || beatBusy) return;
        if (!options.visible()) { stop(); options.onHidden?.(); return; }
        beatBusy = true;
        try {
          const result = await options.api('mirror/heartbeat', {streamId: state.streamId, connectionId}, connectionId);
          if (mine === generation && active) {
            // A delayed heartbeat may predate a completed Control/Inspect change.
            if (result.modeEpoch < active.modeEpoch) return;
            active.modeEpoch = result.modeEpoch;
            // Static screens remain usable. Size/revision must still match the exact displayed image.
            options.onAlive?.(result);
          }
        } catch (error) { fail(error, mine); }
        finally { beatBusy = false; }
      }, 2000);
      const parser = new Packets(packet => {
        if (!active || mine !== generation) return;
        if (packet.kind === 'size') {
          revision++; width = packet.width; height = packet.height; csd = null; needKey = true;
          closeDecoder(); displayed = null; options.onFrame?.(null);
          options.onStage?.('Waiting for the first phone frame…'); waitForFirstFrame(mine, revision);
        }
        const expectedRevision = revision;
        if (++pending > 90) throw Error('Browser decoding could not keep up. Restart the mirror.');
        pipeline = pipeline.then(async () => {
          if (mine !== generation || expectedRevision !== revision) return;
          if (packet.kind === 'config') await configure(packet, mine, expectedRevision);
          else if (packet.kind === 'frame' && decoder) {
            if (decoder.decodeQueueSize > 8) throw Error('Browser decoding could not keep up. Restart the mirror.');
            if (needKey && !packet.key) return;
            const firstKey = needKey, currentDecoder = decoder;
            let data = packet.data;
            if (packet.key && csd) { data = new Uint8Array(csd.length + packet.data.length); data.set(csd); data.set(packet.data, csd.length); }
            const chunk = new env.EncodedVideoChunk({type: packet.key ? 'key' : 'delta', timestamp: packet.timestamp, data});
            if (firstKey) options.onStage?.('Displaying the first phone frame…');
            currentDecoder.decode(chunk);
            needKey = false;
            if (firstKey) {
              // optimizeForLatency is only a hint: a hardware decoder may hold an idle
              // screen's first key until more inputs arrive. Flush exactly once per codec
              // configuration, then re-seed with the same key because flush requires a
              // key before subsequent deltas. Never depend on QA interacting with the app.
              await currentDecoder.flush();
              if (mine === generation && expectedRevision === revision && decoder === currentDecoder) currentDecoder.decode(chunk);
            }
          }
        }).catch(error => { if (expectedRevision === revision) fail(error, mine); }).finally(() => { if (mine === generation) pending--; });
      });
      void (async () => {
        let reader;
        try {
          const response = await options.fetch('/api/mirror/video', {headers: {'X-Qalens-Session': options.session(), 'X-Qalens-Connection': connectionId, 'X-Qalens-Mirror': state.streamId}, cache: 'no-store', signal: controller.signal});
          if (!response.ok) throw Error((await response.json()).error || 'Video connection failed');
          reader = response.body.getReader();
          while (active && mine === generation) {
            const {done, value} = await reader.read();
            if (done) { parser.finish(); throw Error('Phone video stopped. Reconnect and Start mirror again.'); }
            parser.feed(value);
          }
        } catch (error) { if (error.name !== 'AbortError') fail(error, mine); }
        finally { if (reader) { try { await reader.cancel(); } catch (_) {} } }
      })();
      return state;
    }
    function mode(state) {
      if (!active || active.streamId !== state.streamId || state.modeEpoch < active.modeEpoch) return;
      active.modeEpoch = state.modeEpoch; inputQueue = []; options.onMode?.(state);
      if (displayed && displayed.revision === state.revision) {
        displayed = {...displayed, modeEpoch: state.modeEpoch, at: Date.now()}; options.onFrame?.(displayed);
      }
    }
    function send(body, frame) {
      const cancelling = body.action === 'touch' && body.state === 'cancel';
      if (!active || !frame || frame.id !== active.streamId || frame.connectionId !== active.connectionId || frame.revision !== revision || (!cancelling && !options.canControl())) return false;
      const item = {body: {...body, streamId: active.streamId, connectionId: active.connectionId, revision, modeEpoch: active.modeEpoch}, generation};
      const last = inputQueue[inputQueue.length - 1];
      if (body.action === 'touch' && body.state === 'move' && last?.body.action === 'touch' && last.body.state === 'move') inputQueue[inputQueue.length - 1] = item;
      else if (body.action === 'scroll' && last?.body.action === 'scroll') {
        item.body.vertical = Math.max(-16, Math.min(16, item.body.vertical + last.body.vertical));
        item.body.horizontal = Math.max(-16, Math.min(16, item.body.horizontal + last.body.horizontal)); inputQueue[inputQueue.length - 1] = item;
      } else inputQueue.push(item);
      if (inputQueue.length > 24) { fail(Error('Phone input could not keep up; mirror stopped without retrying input'), generation); return false; }
      void drain(); return true;
    }
    async function drain() {
      if (sending) return;
      sending = true;
      try {
        while (inputQueue.length) {
          const next = inputQueue.shift();
          const cancelling = next.body.action === 'touch' && next.body.state === 'cancel';
          if (!active || next.generation !== generation || (!cancelling && !options.canControl()) || next.body.modeEpoch !== active.modeEpoch || next.body.revision !== revision) continue;
          try { await options.api('mirror/input', next.body, next.body.connectionId); }
          catch (error) {
            if (!active || next.generation !== generation || next.body.modeEpoch !== active.modeEpoch || next.body.revision !== revision) continue;
            fail(Error(`${error.message} Input was not retried.`), next.generation); break;
          }
        }
      } finally { sending = false; }
    }
    return {supported, start, stop, mode, send};
  }
  return {Packets, codec, aligned, create};
});
