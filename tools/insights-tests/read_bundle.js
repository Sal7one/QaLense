/* Contract harness: execute the real web archive reader and local-model client in Node 18+. */
'use strict';
const fs = require('node:fs');
const path = require('node:path');
global.window = global;
console.info = () => {}; // Archive version notices cannot mix with this harness's JSON output.
require(path.join(__dirname, '../../web/sal.js'));
const insights = require(path.join(__dirname, '../../web/insights.js'));

async function main() {
  const input = JSON.parse(fs.readFileSync(0, 'utf8'));
  let bundle = input.bundle;
  let tracks;
  if (input.archive) {
    const bytes = fs.readFileSync(input.archive);
    const session = await SAL.read(bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength));
    bundle = insights.buildBundle(session, {
      name: 'synthetic-video-player.sal', focusMs: 52000, windowMs: 10000,
      question: 'Playback remains buffering after Play. What might cause it?', ...input.options
    });
    tracks = Object.fromEntries(['timeline', 'network', 'logs', 'state', 'crashes', 'performance', 'connectivity', 'memory', 'marks'].map(kind => [kind, session[kind].length]));
    for (const frame of session.frames) URL.revokeObjectURL(frame.url);
    if (session.videoUrl) URL.revokeObjectURL(session.videoUrl);
  }
  if (input.investigation) bundle = {...bundle, investigation: input.investigation};
  if (input.image) bundle = insights.withImage(bundle, input.image, input.includeImage === true);
  if (input.operation === 'discover') {
    return insights.discover(input.config);
  }
  if (input.operation === 'import') {
    return insights.validateInvestigation(input.document);
  }
  if (input.operation === 'analyze') {
    return insights.analyze(input.config, bundle, undefined, undefined, {includeImage: input.includeImage === true});
  }
  return {bundle, tracks};
}
main().then(result => process.stdout.write(JSON.stringify(result))).catch(error => {
  process.stderr.write(error.message + '\n'); process.exitCode = 1;
});
