'use strict';
// Meaningful cross-track evidence, provider and stale/grounding boundaries. No model required.
const assert = require('node:assert/strict');
const I = require('../insights.js');
const drain = async () => { for (let i = 0; i < 5; i++) await new Promise(setImmediate); };
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return {promise, resolve, reject}; };
const goodReport = (id = 'network:0') => ({schema: I.REPORT_SCHEMA, summary: 'The request failed; cause needs verification.', observations: [{text: 'A 503 response was recorded.', evidenceIds: [id]}], hypotheses: [{title: 'Unavailable stream endpoint', confidence: 'low', reasoning: 'Timing is a lead, not proof of cause.', evidenceIds: [id], nextChecks: ['Inspect recovery behavior.']}], missingEvidence: ['No player-specific callbacks.'], recommendedChecks: ['Compare the failure time with playback.']});
function session() {
  return {start: 1000000, end: 4600000, duration: 3600000, frames: [], videoUrl: null,
    timeline: [{ts: 3100000, title: 'Play', kind: 'action'}],
    logs: Array.from({length: 18000}, (_, i) => ({ts: 1000000 + i * 200, type: 'LOG', message: i === 10500 ? 'ERROR player retry exhausted' : 'background tick'})),
    network: [{ts: 3100000, method: 'GET', url: 'https://video.example/stream?access_token=private', status: 503, latencyMs: 2010, responseBodyPreview: 'captured failure body'}],
    state: [{ts: 1000000, screen: 'Home'}, {ts: 3010000, screen: 'Player', dataSources: {Preferences: {theme: 'dark', accessToken: 'do-not-send'}}}],
    crashes: [{ts: 3102000, type: 'COROUTINE_EXCEPTION', throwable: 'DecoderException', stackTrace: 'decoder at line 5'}],
    performance: [{ts: 3101000, totalMs: 760, frozen: true, jank: true}],
    connectivity: [{ts: 1000000, type: 'WIFI'}], memory: [{ts: 3100000, freeKb: 12, nativeKb: 900, trimLevel: 15}],
    marks: [{ts: 3103000, severity: 'BUG', label: 'Video stuck loading'}],
    analysis: {coverage: {recording: {truncated: true, tracks: {logs: {observed: 25000, dropped: 7000, retained: 18000}}}}, anomalies: [{tMs: 2100000, title: 'failed request', kind: 'failed_request'}]}};
}
function response(data, status = 200) { return new Response(JSON.stringify(data), {status, headers: {'Content-Type': 'application/json'}}); }
function fakeWindow(origin = 'http://pc.fixture') {
  const listeners = new Map(), sent = [], parent = {postMessage: (data, destination) => sent.push({data, destination})};
  const win = {location: {origin}, parent, addEventListener: (event, fn) => { if (!listeners.has(event)) listeners.set(event, new Set()); listeners.get(event).add(fn); }, removeEventListener: (event, fn) => listeners.get(event)?.delete(fn)};
  return {win, sent, emit: event => { for (const fn of listeners.get('message') || []) void fn(event); }};
}
async function run() {
  const s = session(), original = JSON.stringify(s), b = I.buildBundle(s, {name: 'synthetic.sal', focusMs: 2100000, windowMs: 60000, question: 'Player stuck loading'});
  assert.equal(b.schema, I.EVIDENCE_SCHEMA); assert.equal(JSON.stringify(s), original);
  assert.equal(b.recording.t0, s.start); assert.equal(b.recording.focusMs, 2100000);
  assert.equal(b.items.find(x => x.id === 'logs:10500').tMs, 2100000, 'Late error preserves original position instead of selected index');
  assert.equal(b.items.find(x => x.id === 'state:1').tMs, 2010000, 'Previous state keeps real time outside selected window');
  assert.match(b.items.find(x => x.id === 'connectivity:0').details.windowContext, /historical context/);
  assert.equal(b.items.find(x => x.id === 'connectivity:0').tMs, 0);
  for (const kind of ['network', 'crashes', 'performance', 'memory', 'marks', 'anomalies']) assert.ok(b.items.some(x => x.kind === kind));
  assert.doesNotMatch(JSON.stringify(b), /private|do-not-send/); assert.match(JSON.stringify(b), /REDACTED/);
  assert.equal(b.coverage.media.pixelsSent, false); assert.equal(b.coverage.recorded.recording.truncated, true);
  assert.ok(b.items.length <= 300); assert.ok(JSON.stringify(b).length <= 48000); assert.ok(b.omissions.outsideWindow > 17000);
  const whole = I.buildBundle(s, {focusMs: 2100000, windowMs: null});
  assert.ok(whole.omissions.itemLimit > 0 || whole.omissions.contextLimit > 0); assert.ok(whole.items.some(x => x.id === 'logs:10500')); assert.ok(JSON.stringify(whole).length <= 48000);
  assert.deepEqual(whole.items.map(x => x.id), I.buildBundle(s, {focusMs: 2100000, windowMs: null}).items.map(x => x.id), 'Repeated selection is deterministic');
  const floodSession = {...s,
    logs: Array.from({length: 4000}, (_, i) => ({ts: s.start + 2080000 + i * 10, type: 'ERROR', message: 'ERROR video player retry exhausted stream network bug lost offline decoder rendering reproduction ' + 'x'.repeat(900)})),
    state: [{ts: s.start, screen: 'Initial state'}, {ts: s.start + 2099900, screen: 'Player before'}, {ts: s.start + 2100100, screen: 'Player after'}],
    connectivity: [{ts: s.start, type: 'WIFI'}, {ts: s.start + 2099950, type: 'OFFLINE'}]};
  const flood = I.buildBundle(floodSession, {focusMs: 2100000, question: 'video player retry exhausted stream network bug lost offline decoder rendering reproduction'});
  for (const id of ['timeline:0', 'network:0', 'logs:2000', 'state:0', 'state:1', 'state:2', 'crashes:0', 'performance:0', 'connectivity:0', 'connectivity:1', 'memory:0', 'marks:0', 'anomalies:0']) {
    assert.ok(flood.items.some(item => item.id === id), `Continuous relevant ERROR logs cannot crowd out cross-source evidence ${id}`);
  }
  assert.ok(flood.items.length <= 300); assert.ok(JSON.stringify(flood).length <= 48000); assert.ok(flood.omissions.contextLimit > 0);
  assert.equal(flood.items.find(item => item.id === 'logs:2000').tMs, 2100000); assert.equal(flood.items.find(item => item.id === 'state:0').tMs, 0);
  for (const item of flood.items) assert.ok(item.tMs >= flood.recording.windowStartMs && item.tMs <= flood.recording.windowEndMs || /historical context/.test(item.details.windowContext || ''), 'Only labeled historical state/connectivity anchors cross the selected window');
  for (const count of Object.values(flood.omissions.byKind)) assert.equal(count.selected + count.omitted, count.observed);
  assert.equal(flood.omissions.byKind.logs.omitted, 4000 - flood.items.filter(item => item.kind === 'logs').length);
  assert.equal(flood.omissions.selection.reservePerSource, 3);
  const compactFlood = I.buildBundle({...floodSession, logs: floodSession.logs.map(entry => ({ts: entry.ts, message: 'ERROR'}))}, {focusMs: 2100000});
  assert.equal(compactFlood.items.length, 300); assert.ok(compactFlood.omissions.itemLimit > 0); assert.ok(JSON.stringify(compactFlood).length <= 48000);
  for (const kind of ['network', 'crashes', 'timeline', 'performance', 'state', 'connectivity', 'memory', 'marks', 'anomalies']) assert.ok(compactFlood.items.some(item => item.kind === kind), `The item limit preserves ${kind} under a compact ERROR flood`);
  const invalid = I.buildBundle({...s, timeline: [{ts: NaN}, {title: 'no time'}, {ts: s.start - 1}, {ts: s.end + 1}]}, {focusMs: 2100000});
  assert.equal(invalid.omissions.invalidTimestamp, 4); assert.equal(invalid.items.filter(x => x.kind === 'timeline').length, 0);
  const legacy = I.buildBundle({start: 1, duration: 10, logs: []}, {focusMs: 999});
  assert.equal(legacy.recording.focusMs, 10); assert.equal(legacy.coverage.retentionKnown, false); assert.match(I.offlineReport(legacy).summary, /does not confirm/);
  assert.ok(I.offlineReport(legacy).missingEvidence.some(x => /retention/.test(x)));
  const body = I.buildBundle({...s, network: [{...s.network[0], requestBodyPreview: 'large captured content '.repeat(1000), responseBodyPreview: 'large captured content '.repeat(1000), extra: Object.fromEntries(Array.from({length: 30}, (_, i) => [i, 'x'.repeat(1000)]))}]}, {focusMs: 2100000});
  assert.equal(body.items.find(x => x.kind === 'network').details.status, 503); assert.ok(body.omissions.detailsTruncated > 0); assert.ok(I.offlineReport(body).observations.some(x => /503/.test(x.text)));
  const healthyNetwork = {ts: 8000, payload: Object.fromEntries(Array.from({length: 20}, (_, i) => [i, Array(20).fill('x')])), status: 200, error: null, method: 'GET', url: 'https://media.example/segment'};
  const healthyBefore = JSON.stringify(healthyNetwork), healthyBundle = I.buildBundle({start: 1000, duration: 20000, network: [healthyNetwork]}, {focusMs: 7000});
  assert.equal(JSON.stringify(healthyNetwork), healthyBefore, 'Budget sampling never mutates the selected archive');
  assert.equal(healthyBundle.items[0].details.status, 200); assert.equal(healthyBundle.items[0].details.error, null); assert.equal(healthyBundle.items[0].details.method, 'GET');
  assert.equal(healthyBundle.omissions.detailsTruncated, 1); assert.ok(healthyBundle.omissions.detailShortening.nodes > 0); assert.equal(healthyBundle.items[0].details.truncated, true);
  assert.ok(JSON.stringify(healthyBundle).length <= 48000); assert.equal(I.offlineReport(healthyBundle).observations.length, 0); assert.match(I.offlineReport(healthyBundle).summary, /No matching failure signals/);
  const limitsBundle = I.buildBundle({start: 1000, duration: 20000, network: [{ts: 8000,
    deep: {a: {b: {c: {d: {e: {f: 'not reached'}}}}}}, wide: Object.fromEntries(Array.from({length: 45}, (_, i) => [i, i])), values: Array(35).fill('x'), long: 'x'.repeat(801), status: 200, error: null
  }]}, {focusMs: 7000});
  for (const key of ['depth', 'keys', 'arrayItems', 'strings']) assert.ok(limitsBundle.omissions.detailShortening[key] > 0, `The ${key} limit is disclosed even when final JSON is short`);
  assert.equal(limitsBundle.omissions.detailsTruncated, 1); assert.equal(I.offlineReport(limitsBundle).observations.length, 0);
  const healthyWords = I.buildBundle({start: 1000, duration: 20000,
    logs: [{ts: 8000, message: 'canRetry=true canRender=true canRestart=true'}],
    network: [{ts: 8000, status: 200, error: '[TRUNCATED]'}],
    performance: [{ts: 8000, frozen: '[TRUNCATED]', jank: 'true', totalMs: 10}],
    timeline: [{ts: 8000, title: 'Healthy action', isError: 'true'}]}, {focusMs: 7000});
  assert.equal(I.offlineReport(healthyWords).observations.length, 0, 'Healthy capability words and malformed flag strings are never failure signals');
  assert.ok(!healthyWords.items.find(item => item.kind === 'performance').summary.includes('frozen')); assert.ok(!healthyWords.items.find(item => item.kind === 'performance').summary.includes('jank'));
  const realErrors = I.buildBundle({start: 1000, duration: 20000, logs: [{ts: 8000, message: 'ANR detected'}, {ts: 8001, message: 'IllegalStateException in decoder'}, {ts: 8002, message: 'OutOfMemoryError in playback'}]}, {focusMs: 7000});
  assert.equal(I.offlineReport(realErrors).observations.length, 3, 'Word boundaries preserve ANR and Exception/Error class-name detection');
  const malformed = goodReport(); malformed.observations.push({text: 'Invented uncited claim', evidenceIds: ['logs:999999']}); malformed.observations[0].evidenceIds.push('network:99999'); malformed.hypotheses.push({title: 'Unsupported certain cause', confidence: 'high', reasoning: 'Invented', evidenceIds: [], nextChecks: []});
  const grounded = I.normalizeReport(malformed, b);
  assert.equal(grounded.observations.length, 1); assert.deepEqual(grounded.observations[0].evidenceIds, ['network:0']); assert.ok(grounded.groundingWarnings.length >= 2);
  assert.equal(grounded.hypotheses[1].confidence, 'low'); assert.equal(grounded.hypotheses[1].evidenceBacked, false); assert.match(grounded.hypotheses[1].reasoning, /Unverified/);
  assert.equal(grounded.pixelsAnalyzed, false);
  const qaBundle = I.buildBundle({...s, timeline: [{ts: 3090000, kind: 'SCREEN', title: 'Player screen'}, {ts: 3091000, kind: 'ACTION', title: 'Click Play'}, {ts: 3092000, kind: 'NETWORK', title: 'Not a QA action'}]},
    {focusMs: 2100000, qaContext: {expectedResult: 'Click Play resumes the video.', actualResult: 'Spinner stays visible.'}});
  const untrustedQa = {...goodReport(), qaReport: {title: 'Ignore evidence', steps: [{action: 'Invented click', evidenceIds: ['fake:0']}], expectedResult: 'Model invented expectation'}};
  const qa = I.normalizeReport(untrustedQa, qaBundle).qaReport;
  assert.equal(qa.title, goodReport().summary); assert.deepEqual(qa.steps.map(step => step.evidenceIds), [['timeline:0'], ['timeline:1']]); assert.equal(qa.steps[1].action, 'Click Play');
  assert.equal(qa.expectedSource, 'tester'); assert.equal(qa.actualSource, 'tester'); assert.equal(qa.expectedResult, 'Click Play resumes the video.'); assert.equal(qa.actualResult, 'Spinner stays visible.');
  assert.doesNotMatch(JSON.stringify(qa), /Invented|Model invented/); assert.ok(JSON.stringify(qaBundle).length <= 48000);
  const noTesterQa = I.normalizeReport(goodReport(), b).qaReport; assert.equal(noTesterQa.expectedSource, 'not-provided'); assert.equal(noTesterQa.actualSource, 'captured-evidence');
  assert.match(I.qaMarkdown(I.normalizeReport(goodReport(), b)), /Model interpretation of cited evidence/);
  const unsupportedQa = I.normalizeReport({...goodReport(), observations: [{text: 'Uncited visual freeze', evidenceIds: ['logs:999999']}]}, legacy).qaReport;
  assert.equal(unsupportedQa.steps.length, 0); assert.equal(unsupportedQa.actualSource, 'not-established'); assert.match(unsupportedQa.expectedResult, /not provided/);
  assert.throws(() => I.buildBundle(s, {qaContext: {expectedResult: false}}), /QA context/);
  const playerBundle = I.buildBundle({start: 1000, duration: 30000, network: [{ts: 1100, method: 'GET', url: '/healthy-stream', status: 200}]},
    {focusMs: 15000, target: 'qalens-player', runtime: {id: 'player:runtime', source: 'current-player', client: 'web', observedAtMillis: 1800000000123, recordingPositionMs: 15000,
      details: {mediaKind: 'video', mediaError: {code: 3, message: 'Current decoder failed'}, videoReadyState: 1, videoCurrentTimeSeconds: 10, playheadMs: 15000}}});
  assert.equal(playerBundle.investigation.runtime.observedAtMillis, 1800000000123);
  assert.ok(!playerBundle.items.some(item => item.id === 'player:runtime'), 'Current replay snapshot is never disguised as a captured host event');
  const playerReport = I.normalizeReport(goodReport('player:runtime'), playerBundle); assert.equal(playerReport.observations.length, 1);
  const playerWithHostActions = I.buildBundle({...s, timeline: [{ts: 3100000, kind: 'ACTION', title: 'Captured host Play click'}]}, {focusMs: 2100000, target: 'qalens-player', runtime: playerBundle.investigation.runtime});
  assert.equal(I.normalizeReport(goodReport('player:runtime'), playerWithHostActions).qaReport.steps.length, 0, 'Archived host actions cannot become current replay-player reproduction steps');
  assert.ok(I.offlineReport(playerBundle).hypotheses.some(h => h.evidenceIds.includes('player:runtime')));
  assert.ok(!I.offlineReport(playerBundle).hypotheses.some(h => /Request failure/.test(h.title)));
  assert.equal(I.normalizeReport(goodReport('player:runtime'), b).observations.length, 0, 'Unprovided player runtime is not a valid citation');
  assert.throws(() => I.buildBundle(s, {target: 'qalens-player', runtime: {...playerBundle.investigation.runtime, source: 'recorded-host'}}), /current player|Current player/i);
  const secretReport = goodReport(); secretReport.summary = 'Echoed synthetic-key'; secretReport.hypotheses[0].nextChecks = ['Never expose synthetic-key'];
  assert.doesNotMatch(JSON.stringify(I.normalizeReport(secretReport, b, {secrets: ['synthetic-key']})), /synthetic-key/);
  assert.throws(() => I.normalizeReport({...goodReport(), schema: 'wrong'}, b), /does not match/);
  assert.throws(() => I.normalizeReport({...goodReport(), summary: '   \n\t'}, b), /does not match/, 'A blank model response cannot become a successful assessment');
  for (const bad of [
    {...goodReport(), observations: [null]},
    {...goodReport(), observations: [{text: 'Claim missing required citations array'}]},
    {...goodReport(), hypotheses: [{...goodReport().hypotheses[0], title: ' '}]},
    {...goodReport(), hypotheses: [{...goodReport().hypotheses[0], confidence: 'certain'}]},
    {...goodReport(), hypotheses: [{...goodReport().hypotheses[0], nextChecks: [false]}]},
    {...goodReport(), missingEvidence: [null]},
    {...goodReport(), observations: Array.from({length: 31}, () => goodReport().observations[0])}
  ]) assert.throws(() => I.normalizeReport(bad, b), /does not match/, 'Malformed required fields are rejected instead of producing a partial successful report');
  assert.throws(() => I.parseReport('not json'), /required JSON/); assert.throws(() => I.parseReport('x'.repeat(64001)), /oversized/);
  for (const baseUrl of ['https://public.example', 'http://169.254.169.254', 'http://0.0.0.0', 'http://172.32.1.1', 'ftp://127.0.0.1', 'http://u:p@127.0.0.1', 'http://127.0.0.1?q=secret', 'http://127.0.0.1:0', 'http://127.0.0.1/v1/chat/completions']) assert.throws(() => I.localConfig({baseUrl}));
  assert.equal(I.localConfig({baseUrl: 'http://localhost:11434'}).baseUrl, 'http://127.0.0.1:11434');
  for (const baseUrl of ['http://127.3.4.5:1234/v1', 'http://10.1.1.1:11434/api', 'http://192.168.1.4', 'http://172.20.1.4', 'http://[::1]:11434', 'http://[fd00::1]:11434']) assert.ok(I.localConfig({baseUrl}));
  assert.throws(() => I.localConfig({baseUrl: 'http://127.0.0.1', apiKey: 'bad\r\nkey'})); assert.throws(() => I.localConfig({baseUrl: 'http://127.0.0.1', apiKey: '秘密'}));
  assert.throws(() => I.localConfig({baseUrl: 'http://127.0.0.1', model: 'bad\nmodel'}));
  {
    const calls = [], fetcher = async (url, options) => { calls.push({url, options}); return calls.length === 1 ? response({}, 404) : response({models: [{name: 'local-chat'}, {name: 'nomic-embed-text'}, null]}); };
    const result = await I.discover({baseUrl: 'http://127.0.0.1:11434/api'}, null, fetcher);
    assert.equal(result.protocol, 'ollama'); assert.deepEqual(result.models.map(x => x.id), ['local-chat']); assert.ok(calls[0].url.endsWith('/v1/models')); assert.ok(calls[1].url.endsWith('/api/tags'));
    assert.equal(calls[0].options.redirect, 'error'); assert.equal(calls[0].options.credentials, 'omit');
    await assert.rejects(I.discover({baseUrl: 'http://127.0.0.1'}, null, async () => response({}, 401)), /HTTP 401/);
    assert.deepEqual((await I.discover({baseUrl: 'http://127.0.0.1'}, null, async () => response({data: [{id: 'text-embedding-nomic-embed-text-v1.5'}]}))).models, []);
    assert.deepEqual((await I.discover({baseUrl: 'http://127.0.0.1', apiKey: 'synthetic-key'}, null,
      async () => response({data: [{id: 'misconfigured-synthetic-key'}, {id: 'safe-chat'}]}))).models.map(model => model.id), ['safe-chat'], 'Provider discovery cannot expose a configured credential in a model name');
  }
  for (const protocol of ['openai', 'ollama']) {
    const requests = [], fetcher = async (url, options) => { requests.push({url, options}); return response(protocol === 'openai' ? {choices: [{message: {content: JSON.stringify(goodReport())}}]} : {message: {content: JSON.stringify(goodReport())}}); };
    const report = await I.analyze({baseUrl: 'http://127.0.0.1:1234/v1', protocol, model: 'local-chat', apiKey: 'synthetic-key'}, b, null, fetcher);
    assert.equal(report.observations.length, 1); const payload = JSON.parse(requests[0].options.body);
    assert.equal(payload.stream, false); assert.match(payload.messages[0].content, /untrusted data/); assert.match(payload.messages[0].content, /NOT watched video/); assert.ok(!payload.messages[1].images); assert.equal(typeof payload.messages[1].content, 'string');
    assert.equal(requests[0].options.headers.Authorization, 'Bearer synthetic-key'); assert.ok(requests[0].url.endsWith(protocol === 'openai' ? '/v1/chat/completions' : '/api/chat'));
  }
  await assert.rejects(I.analyze({baseUrl: 'http://127.0.0.1', model: 'nomic-embed-text'}, b), /installed chat model/);
  assert.deepEqual((await I.discover({baseUrl: 'http://127.0.0.1'}, null, async () => response({data: []}))).models, [], 'Successful empty discovery remains actionable rather than a fake model');
  await assert.rejects(I.discover({baseUrl: 'http://127.0.0.1'}, null, async () => { throw new TypeError('network'); }), /CORS/);
  await assert.rejects(I.discover({baseUrl: 'http://127.0.0.1'}, null, async () => new Response('x'.repeat(256 * 1024 + 1))), /size budget/);
  {
    const controller = new AbortController(); controller.abort(); let called = false;
    await assert.rejects(I.discover({baseUrl: 'http://127.0.0.1'}, controller.signal, async () => { called = true; }), error => error.name === 'AbortError'); assert.equal(called, false);
  }
  const image = {id: 'images:0', tMs: 2100000, mediaType: 'image/png', data: 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aGZsAAAAASUVORK5CYII=', source: 'recording-frame', approximate: false};
  assert.throws(() => I.withImage(b, image), /explicit/); assert.throws(() => I.withImage(b, {...image, data: 'https://example.com/pixel.png'}, true));
  const visual = I.withImage(b, image, true); assert.equal(visual.coverage.media.pixelsSent, true); assert.equal(b.coverage.media.pixelsSent, false); assert.equal(visual.images[0].width, 1);
  {
    const transfer = {schema: 'qalens-investigation-transfer/1', bundle: visual, report: {...goodReport('images:0'), qaReport: {steps: [{action: 'Invented click'}]}}};
    const checked = I.validateInvestigation(transfer); assert.equal(checked.report.observations.length, 1); assert.doesNotMatch(JSON.stringify(checked.report.qaReport), /Invented click/);
    checked.bundle.question = 'Independent copy'; assert.notEqual(transfer.bundle.question, 'Independent copy');
    const reviewed = I.reviewImportedBundle(visual, {question: 'Tester follow-up', qaContext: {expectedResult: 'Resume', actualResult: 'Stuck'}});
    assert.deepEqual(reviewed.items, JSON.parse(JSON.stringify(visual.items))); assert.deepEqual(reviewed.recording, visual.recording); assert.equal(reviewed.question, 'Tester follow-up'); assert.ok(!reviewed.images); assert.equal(reviewed.coverage.media.pixelsSent, false);
    for (const invalid of [
      {...transfer, config: {apiKey: 'must-never-transfer'}},
      {...transfer, bundle: {...visual, config: {baseUrl: 'http://127.0.0.1'}}},
      {...transfer, bundle: {...visual, items: [...visual.items, visual.items[0]]}},
      {...transfer, bundle: {...visual, recording: {...visual.recording, focusMs: visual.recording.durationMs + 1}}},
      {...transfer, bundle: {...visual, items: [{...visual.items[0], id: 'fake:0'}]}},
      {...transfer, bundle: {...visual, qaContext: {actualResult: 'x'.repeat(1601)}}}
    ]) assert.throws(() => I.validateInvestigation(invalid), /transfer|Transferred|unique|bounded/i);
    assert.deepEqual(I.reviewImportedBundle(playerBundle).investigation.runtime, JSON.parse(JSON.stringify(playerBundle.investigation.runtime)), 'Phone runtime clocks/client are kept verbatim, never refreshed as web facts');
  }
  assert.equal(I.normalizeReport(goodReport('images:0'), visual).observations.length, 1); assert.equal(I.normalizeReport(goodReport('images:0'), visual).pixelsAnalyzed, null);
  assert.equal(I.offlineReport(visual).pixelsProvided, false, 'Offline signal review never claims it sent or analyzed the optional image');
  await assert.rejects(I.analyze({baseUrl: 'http://127.0.0.1', protocol: 'openai', model: 'vision'}, visual, null, async () => { throw Error('must not call'); }), /explicit/);
  for (const protocol of ['openai', 'ollama']) {
    let payload;
    await I.analyze({baseUrl: 'http://127.0.0.1', protocol, model: 'vision'}, visual, null, async (_url, options) => { payload = JSON.parse(options.body); return response(protocol === 'openai' ? {choices: [{message: {content: JSON.stringify(goodReport('images:0'))}}]} : {message: {content: JSON.stringify(goodReport('images:0'))}}); }, {includeImage: true});
    if (protocol === 'openai') { assert.equal(payload.messages[1].content[1].type, 'image_url'); assert.ok(payload.messages[1].content[1].image_url.url.startsWith('data:image/png;base64,')); }
    else assert.deepEqual(payload.messages[1].images, [image.data]);
    const textual = protocol === 'openai' ? payload.messages[1].content[0].text : payload.messages[1].content; assert.ok(!textual.includes(image.data), 'Image bytes do not duplicate into textual context');
  }
  {
    const f = fakeWindow(), transport = I.frameTransport(f.win), controller = new AbortController(); const answer = transport.request('models', {config: {baseUrl: 'http://127.0.0.1'}}, controller.signal), request = f.sent[0].data;
    assert.equal(f.sent[0].destination, f.win.location.origin); let resolved = false; answer.then(() => resolved = true);
    f.emit({origin: 'http://attacker', source: f.win.parent, data: {type: 'qalens-insights-response', requestId: request.requestId, result: {stolen: true}}});
    f.emit({origin: f.win.location.origin, source: {}, data: {type: 'qalens-insights-response', requestId: request.requestId, result: {stolen: true}}}); await drain(); assert.equal(resolved, false);
    f.emit({origin: f.win.location.origin, source: f.win.parent, data: {type: 'qalens-insights-response', requestId: request.requestId, result: {ok: true}}}); assert.deepEqual(await answer, {ok: true});
    const held = transport.request('models', {}, controller.signal); const rejected = assert.rejects(held, error => error.name === 'AbortError'); controller.abort(); await rejected; assert.equal(f.sent.at(-1).data.type, 'qalens-insights-abort'); transport.close();
  }
  {
    const f = fakeWindow(), messages = [], frameListeners = {}, iframe = {contentWindow: {postMessage: (data, origin) => messages.push({data, origin})}, addEventListener: (event, fn) => frameListeners[event] = fn, removeEventListener() {}}, held = deferred(), calls = [];
    const bridge = I.installHostBridge({window: f.win, iframe, api: async (path, body) => { calls.push({path, body}); if (path === 'insights/analyze') return held.promise; return {ok: true, job: {id: body?.id, state: 'cancelled'}}; }});
    const event = data => ({origin: f.win.location.origin, source: iframe.contentWindow, data});
    f.emit({...event({type: 'qalens-insights-request', requestId: 'request-1', operation: 'models', payload: {}}), origin: 'http://attacker'}); await drain(); assert.equal(calls.length, 0);
    f.emit(event({type: 'qalens-insights-request', requestId: 'request-2', operation: 'jobs', payload: {id: 'another-frames-job'}})); await drain(); assert.equal(calls.length, 0); assert.match(messages.at(-1).data.error, /does not own/);
    f.emit(event({type: 'qalens-insights-request', requestId: 'request-3', operation: 'analyze', payload: {bundle: b}})); await drain();
    f.emit(event({type: 'qalens-insights-abort', requestId: 'request-3'})); held.resolve({ok: true, job: {id: 'owned-job', state: 'queued'}}); await drain();
    assert.ok(calls.some(x => x.path === 'insights/cancel' && x.body.id === 'owned-job')); assert.ok(!messages.some(x => x.data.requestId === 'request-3'), 'Abandoned start is cancelled without accepting a late job');
    f.emit(event({type: 'qalens-insights-request', requestId: 'request-4', operation: 'analyze', payload: {bundle: b}})); await drain();
    assert.ok(messages.some(x => x.data.requestId === 'request-4'), 'The response was posted but is not yet delivered to the child');
    const cancels = calls.filter(x => x.path === 'insights/cancel').length;
    f.emit(event({type: 'qalens-insights-abort', requestId: 'request-4'})); await drain();
    assert.equal(calls.filter(x => x.path === 'insights/cancel').length, cancels + 1, 'Abort after parent posting still cancels the unknown job');
    frameListeners.load(); bridge.close();
  }
  console.log('OK: bounded late-session evidence/stable IDs/all raw tracks/anchors/redaction/omissions, grounded reports, local-only provider discovery/limits/CORS/cancellation, explicit still consent and both provider payloads, exact iframe guards/job ownership/late-start cancellation');
}
run().catch(error => { console.error(error); process.exitCode = 1; });
