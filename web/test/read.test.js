/*
 * Node regression test for the dependency-free .sal reader (web/sal.js).
 * Run: node web/test/read.test.js
 *
 * Loads sal.js with a minimal browser shim and reads the bundled web/sample.sal, asserting the
 * reader's ZIP parsing (central directory + native deflate-raw inflate) and track decoding.
 */
const fs = require("fs");
const path = require("path");

// Minimal browser shim for the IIFE in sal.js.
global.window = global;
global.URL = global.URL || require("url").URL;
if (!global.URL.createObjectURL) global.URL.createObjectURL = () => "blob://stub";

require(path.join(__dirname, "..", "sal.js"));
const SAL = global.SAL;

let failures = 0;
function ok(cond, msg) {
  console.log((cond ? "✓" : "✗") + " " + msg);
  if (!cond) failures++;
}

(async () => {
  if (typeof DecompressionStream === "undefined") {
    console.error("This Node lacks DecompressionStream (need Node 18+).");
    process.exit(2);
  }
  const buf = fs.readFileSync(path.join(__dirname, "..", "sample.sal"));
  const ab = buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength);
  const s = await SAL.read(ab);

  ok(s.manifest.app && s.manifest.app.name === "QaLens Sample", "manifest app name");
  ok(s.manifest.environment === "staging", "environment");
  ok(s.duration === 32000, "duration = 32000ms");
  ok(s.summary && s.summary.score === 58, "summary score");
  ok(s.summary.category === "Backend/API" && s.summary.confidence === "HIGH", "likely owner");
  ok(s.timeline.length === 13, "timeline entries");
  ok(s.timeline.some((e) => e.isError), "timeline has an error");
  ok(s.network.length === 6 && s.network[4].status === 500, "network entries");
  ok(s.logs.length === 11, "log entries");
  ok(s.state.length === 17 && s.state[0].featureFlags.checkout_v2 === true, "state + flags");
  ok(s.state[0].featureFlags.wallet_refactor === false &&
     s.state[s.state.length - 1].featureFlags.wallet_refactor === true, "flag flips mid-session");
  ok(s.frames.length === 64, "frame index (64 entries)");
  ok(s.analysis && s.analysis.schema === "qalens-analysis/1", "analysis digest present");
  ok(s.analysis.anomalies.length === 5 && s.analysis.coverage.network === true, "analysis anomalies + coverage");
  ok(s.report.startsWith("QaLens Full Report"), "report text");

  console.log(failures === 0 ? "\nALL PASS" : `\n${failures} FAILURE(S)`);
  process.exit(failures === 0 ? 0 : 1);
})().catch((e) => { console.error("ERROR", e); process.exit(1); });
