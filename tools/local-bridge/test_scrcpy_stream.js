'use strict';
const assert = require('node:assert/strict');
const {Packets, codec, aligned, create} = require('./scrcpy-stream.js');
const csd = Uint8Array.from([0,0,0,1,0x67,0x42,0xc0,0x1e,0,0,0,1,0x68,0]);
const concat = (...parts) => Uint8Array.from(parts.flatMap(x=>[...x]));
function size(w=720,h=1600) { const b=Buffer.alloc(12); b.writeUInt32BE(0x80000000); b.writeUInt32BE(w,4); b.writeUInt32BE(h,8); return b; }
function packet(data, flag=0n, timestamp=123456n) { const b=Buffer.alloc(12); b.writeBigUInt64BE(flag | timestamp); b.writeUInt32BE(data.length,8); return concat(b,data); }
const initial = concat(Buffer.from('h264'), size(), packet(csd,1n<<62n), packet([0,0,1,0x65,1],1n<<61n));
const drain = async () => { for(let i=0;i<12;i++) await new Promise(setImmediate); };

function fixture({buffered=false, holdFlush=false, ignoreAbort=false} = {}) {
  let stream, frame=null, control=true, visible=true, support=true;
  const requests=[],draws=[],errors=[],closed=[],timers=[],timeouts=[],configs=[],decoders=[],stages=[];
  class Decoder {
    constructor(callbacks) { this.callbacks=callbacks; this.decodeQueueSize=0; this.buffer=[]; this.flushes=0; decoders.push(this); }
    static async isConfigSupported(config) { return {supported:support || config.hardwareAcceleration==='no-preference',config}; }
    configure(config) { this.config=config; this.keyRequired=true; configs.push(config); }
    output(chunk) { this.callbacks.output({timestamp:chunk.timestamp,displayWidth:this.config.codedWidth,displayHeight:this.config.codedHeight,close:()=>closed.push(chunk.timestamp)}); }
    decode(chunk) {
      if(this.isClosed) throw Error('Closed decoder');
      if(this.keyRequired && chunk.type!=='key') throw Error('Flush requires a key before deltas');
      this.keyRequired=false; this.chunk=chunk;
      // Hardware is allowed to retain an output until another input arrives, even with the
      // latency hint. A static phone produces no extra inputs in this regression fixture.
      if(buffered) { this.buffer.push(chunk); if(this.buffer.length>1) this.output(this.buffer.shift()); }
      else this.output(chunk);
    }
    async flush() {
      this.keyRequired=true; this.flushes++;
      if(holdFlush) await new Promise((resolve,reject)=>{this.releaseFlush=resolve;this.rejectFlush=reject;});
      if(this.isClosed) throw Error('Closed decoder');
      while(this.buffer.length) this.output(this.buffer.shift());
    }
    close() { this.isClosed=true; this.rejectFlush?.(Object.assign(Error('Decoder closed'),{name:'AbortError'})); }
  }
  const env={VideoDecoder:Decoder,EncodedVideoChunk:class {constructor(data){Object.assign(this,data);}},AbortController,
    setInterval:callback=>{timers.push(callback);return timers.length;},clearInterval:id=>{timers[id-1]=null;},
    setTimeout:(callback,delay)=>{timeouts.push({callback,delay});return timeouts.length;},clearTimeout:id=>{timeouts[id-1]=null;}};
  const canvas={width:0,height:0,getContext:()=>({drawImage:(...args)=>draws.push(args),clearRect(){}})};
  let release, pendingInput=null, releaseBeat, pendingBeat=null;
  const api=async(name,body)=>{
    requests.push({name,body});
    if(name==='mirror/input' && pendingInput) await pendingInput;
    if(name==='mirror/heartbeat' && pendingBeat) return await pendingBeat;
    return {streamId:'owned',revision:frame?.revision||1,width:frame?.width||720,height:frame?.height||1600,modeEpoch:body.modeEpoch||1};
  };
  const mirror=create({env,canvas,api,fetch:async(url,options)=>({ok:true,body:new ReadableStream({start(c){stream=c;if(!ignoreAbort) options.signal.addEventListener('abort',()=>c.error(Object.assign(Error('aborted'),{name:'AbortError'})),{once:true});}})}),
    session:()=> 'private-desktop-session',visible:()=>visible,canControl:()=>control,onFrame:value=>{frame=value;},onStage:value=>stages.push(value),onError:value=>errors.push(value),onHidden:()=>errors.push('hidden')});
  return {mirror,env,requests,draws,errors,closed,timers,timeouts,configs,decoders,canvas,stages,
    get frame(){return frame;},get stream(){return stream;},send:data=>stream.enqueue(data),control:value=>{control=value;},visible:value=>{visible=value;},
    software:()=>{support=false;},hold:()=>{pendingInput=new Promise(resolve=>{release=resolve;});},release:()=>{pendingInput=null;release();},
    holdBeat:()=>{pendingBeat=new Promise(resolve=>{releaseBeat=resolve;});},releaseBeat:value=>{pendingBeat=null;releaseBeat(value);}};
}
async function run() {
  {
    const expected=[]; const complete=new Packets(p=>expected.push(p)); complete.feed(initial); complete.finish();
    for(let split=1;split<initial.length;split++) {
      const actual=[]; const parser=new Packets(p=>actual.push(p)); parser.feed(initial.slice(0,split)); parser.feed(initial.slice(split)); parser.finish();
      assert.deepEqual(actual,expected,`framing at byte ${split}`);
    }
    assert.equal(codec(csd),'avc1.42c01e');
    assert.throws(()=>codec(new Uint8Array(8))); assert.throws(()=>new Packets(()=>{}).feed(Buffer.from('oops')));
    const bad=Buffer.alloc(16); bad.write('h264'); bad.writeUInt32BE(2097153,12); assert.throws(()=>new Packets(()=>{}).feed(bad),/budget/);
    const partial=new Packets(()=>{}); partial.feed(initial.slice(0,-1)); assert.throws(()=>partial.finish(),/interrupted/);
    assert.equal(aligned({width:718,height:1600},{width:1344,height:2992}),true);
    assert.equal(aligned({width:1600,height:718},{width:1344,height:2992}),false);
  }
  {
    const f=fixture({buffered:true}); await f.mirror.start({streamId:'owned',modeEpoch:1},'phone');
    f.send(initial); await drain();
    assert.equal(f.draws.length,1,'An idle screen must appear with only its initial key frame');
    assert.equal(f.frame.width,720); assert.equal(f.decoders[0].flushes,1);
    assert.ok(f.timeouts.every(x=>!x),'Successful first frame must cancel the startup watchdog');
    f.send(concat(packet([0,0,1,0x41,2],0n,123457n),packet([0,0,1,0x41,3],0n,123458n))); await drain();
    assert.deepEqual(f.errors,[],'Prediction state must survive first-frame flushing');
    assert.equal(f.draws.length,2,'The re-seeded key must not repaint, and following deltas must render');
    assert.equal(f.decoders[0].flushes,1,'Continuous video must not flush on every frame');
    f.send(concat(size(1600,720),packet(csd,1n<<62n),packet([0,0,1,0x65,1],1n<<61n))); await drain();
    assert.equal(f.frame.width,1600,'An idle rotated screen must also appear without extra input');
    assert.equal(f.decoders[1].flushes,1); f.mirror.stop();
  }
  {
    const f=fixture({buffered:true,holdFlush:true}); await f.mirror.start({streamId:'owned',modeEpoch:1},'phone');
    f.send(initial); await drain(); assert.equal(f.frame,null);
    f.send(concat(size(1600,720),packet(csd,1n<<62n),packet([0,0,1,0x65,1],1n<<61n))); await drain();
    assert.equal(f.decoders[0].isClosed,true);
    f.decoders[1].releaseFlush(); await drain();
    assert.equal(f.frame.revision,2); assert.equal(f.frame.width,1600);
    assert.deepEqual(f.errors,[],'Aborting a previous rotation\'s pending flush must not stop current video');
    f.mirror.stop();
  }
  {
    const f=fixture(); await f.mirror.start({streamId:'owned',modeEpoch:1},'phone');
    const timeout=f.timeouts.find(Boolean); assert.equal(timeout.delay,8000); timeout.callback(); await drain();
    assert.match(f.errors[0],/within 8 seconds/); assert.equal(f.frame,null);
    assert.ok(f.timers.every(x=>!x)); assert.ok(f.timeouts.every(x=>!x));
    await f.mirror.start({streamId:'replacement',modeEpoch:1},'phone'); f.send(initial); await drain();
    timeout.callback(); assert.equal(f.errors.length,1,'An old watchdog must not stop a replacement session');
    assert.equal(f.frame.id,'replacement'); f.mirror.stop();
  }
  {
    const f=fixture({buffered:true,holdFlush:true}); await f.mirror.start({streamId:'owned',modeEpoch:1},'phone');
    f.send(initial); await drain();
    f.timeouts.find(Boolean).callback(); await drain();
    assert.match(f.errors[0],/within 8 seconds/); assert.equal(f.errors.length,1);
    assert.equal(f.decoders[0].isClosed,true,'A stalled flush must release the decoder on timeout');
    assert.ok(f.timers.every(x=>!x)); assert.ok(f.timeouts.every(x=>!x));
  }
  {
    const f=fixture({ignoreAbort:true}); await f.mirror.start({streamId:'old',modeEpoch:1},'old-phone');
    const oldStream=f.stream; await f.mirror.start({streamId:'replacement',modeEpoch:1},'phone');
    f.send(initial); await drain();
    oldStream.enqueue(concat(Buffer.from('h264'),size(1600,720))); await drain();
    assert.equal(f.frame.id,'replacement'); assert.equal(f.frame.width,720);
    assert.equal(f.decoders[0].isClosed,undefined,'A late read from the old video connection closed the current decoder');
    assert.deepEqual(f.errors,[]); f.mirror.stop();
  }
  {
    const f=fixture(); f.software(); await f.mirror.start({streamId:'owned',modeEpoch:1},'phone'); f.send(initial); await drain();
    assert.equal(f.draws.length,1); assert.equal(f.closed.length,2); assert.equal(f.frame.revision,1);
    assert.equal(f.configs[0].hardwareAcceleration,'no-preference','Hardware preference must fall back when unsupported');
    f.hold(); f.mirror.send({action:'touch',state:'down',x:.1,y:.2},f.frame);
    for(let i=0;i<100;i++) f.mirror.send({action:'touch',state:'move',x:i/100,y:.2},f.frame);
    f.mirror.send({action:'touch',state:'up',x:1,y:.2},f.frame); f.release(); await drain();
    assert.deepEqual(f.requests.filter(x=>x.name==='mirror/input').map(x=>x.body.state),['down','move','up'],'Moves coalesce without dropping Down/Up');
    assert.equal(f.requests[1].body.x,.99);
    f.control(false); assert.equal(f.mirror.send({action:'key',keycode:66},f.frame),false);
    assert.equal(f.mirror.send({action:'touch',state:'cancel'},f.frame),true,'Busy UI must still cancel the held phone finger');
    await drain(); assert.equal(f.requests.at(-1).body.state,'cancel');
    f.control(true); f.mirror.mode({streamId:'owned',modeEpoch:2,revision:1}); assert.equal(f.frame.modeEpoch,2);
    f.send(concat(size(1600,720),packet(csd,1n<<62n),packet([0,0,1,0x65,1],1n<<61n))); await drain();
    assert.equal(f.frame.revision,2); assert.equal(f.frame.width,1600); assert.equal(f.decoders[0].isClosed,true);
    f.mirror.stop(); assert.equal(f.frame,null); assert.equal(f.decoders.at(-1).isClosed,true); assert.ok(f.timers.every(x=>!x));
  }
  {
    const f=fixture(); await f.mirror.start({streamId:'owned',modeEpoch:1},'phone'); f.send(initial); await drain();
    f.hold(); f.mirror.send({action:'touch',state:'down',x:.1,y:.2},f.frame); f.mirror.send({action:'touch',state:'move',x:.3,y:.2},f.frame);
    f.mirror.mode({streamId:'owned',modeEpoch:2,revision:1}); f.release(); await drain();
    assert.equal(f.requests.filter(x=>x.name==='mirror/input').length,1,'Mode change dispatched queued host input');
    f.visible(false); await f.timers.find(Boolean)(); await drain(); assert.equal(f.frame,null); assert.deepEqual(f.errors,['hidden']);
  }
  {
    const f=fixture(); await f.mirror.start({streamId:'owned',modeEpoch:1},'phone'); f.send(initial); await drain();
    f.holdBeat(); const beat=f.timers.find(Boolean)();
    f.mirror.mode({streamId:'owned',modeEpoch:2,revision:1});
    f.releaseBeat({modeEpoch:1,revision:1,width:720,height:1600}); await beat;
    f.mirror.mode({streamId:'owned',modeEpoch:1,revision:1});
    assert.equal(f.frame.modeEpoch,2,'Late mode/heartbeat must not revert a completed mode change');
    f.mirror.send({action:'key',keycode:66},f.frame); await drain();
    assert.equal(f.requests.at(-1).body.modeEpoch,2,'Input must retain the current mode after a late heartbeat'); f.mirror.stop();
  }
  {
    const f=fixture(); await f.mirror.start({streamId:'old',modeEpoch:1},'old-phone'); f.send(initial); await drain();
    const old=f.decoders.at(-1), previous=f.draws.length;
    f.mirror.stop(); old.callbacks.output({displayWidth:720,displayHeight:1600,close:()=>f.closed.push('stale')});
    assert.equal(f.draws.length,previous,'Stopped session drew a stale frame'); assert.ok(f.closed.includes('stale'));
    assert.equal(f.mirror.send({action:'text',text:'QA'}, {id:'old',connectionId:'old-phone',revision:1}),false);
  }
  console.log('OK: idle first-frame/rotation flushing and prediction, startup timeout/restart/read races, scrcpy framing/budgets, WebCodecs fallback/disposal, coalesced input, mode/hidden/stale guards');
}
run().catch(error=>{console.error(error);process.exitCode=1;});
