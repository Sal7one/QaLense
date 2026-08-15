/*
 * Node regression test for the dependency-free .sal reader (web/sal.js).
 * Run: node web/test/read.test.js
 *
 * Loads sal.js with a minimal browser shim and reads the bundled web/sample.sal, asserting the
 * reader's ZIP parsing (central directory + native deflate-raw inflate) and track decoding.
 */
const fs = require("fs");
const path = require("path");
const zlib = require("zlib");

// Minimal browser shim for the IIFE in sal.js.
global.window = global;
global.URL = global.URL || require("url").URL;
if (!global.URL.createObjectURL) global.URL.createObjectURL = () => "blob://stub";

require(path.join(__dirname, "..", "sal.js"));
const SAL = global.SAL;

// ── v2 fixture builders (crc32 + STORE zip writer copied from web/tools/enrich_sample.js) ──
function crc32(buf) {
  if (zlib.crc32) return zlib.crc32(buf) >>> 0;
  if (!crc32.t) {
    crc32.t = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      crc32.t[n] = c;
    }
  }
  let c = ~0;
  for (let i = 0; i < buf.length; i++) c = crc32.t[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return ~c >>> 0;
}
function crc32Hex(buf) {
  return crc32(buf).toString(16).padStart(8, "0");
}
function zipStore(entries) {
  const locals = [], centrals = [];
  let offset = 0;
  for (const [name, data] of entries) {
    const nameB = Buffer.from(name, "utf8");
    const crc = crc32(data);
    const lh = Buffer.alloc(30);
    lh.writeUInt32LE(0x04034b50, 0);
    lh.writeUInt16LE(20, 4);
    lh.writeUInt16LE(0, 6);
    lh.writeUInt16LE(0, 8);
    lh.writeUInt32LE(0, 10);
    lh.writeUInt32LE(crc, 14);
    lh.writeUInt32LE(data.length, 18);
    lh.writeUInt32LE(data.length, 22);
    lh.writeUInt16LE(nameB.length, 26);
    lh.writeUInt16LE(0, 28);
    locals.push(lh, nameB, data);

    const ch = Buffer.alloc(46);
    ch.writeUInt32LE(0x02014b50, 0);
    ch.writeUInt16LE(20, 4);
    ch.writeUInt16LE(20, 6);
    ch.writeUInt16LE(0, 8);
    ch.writeUInt16LE(0, 10);
    ch.writeUInt32LE(0, 12);
    ch.writeUInt32LE(crc, 16);
    ch.writeUInt32LE(data.length, 20);
    ch.writeUInt32LE(data.length, 24);
    ch.writeUInt16LE(nameB.length, 28);
    ch.writeUInt32LE(offset, 42);
    centrals.push(Buffer.concat([ch, nameB]));
    offset += 30 + nameB.length + data.length;
  }
  const centralBuf = Buffer.concat(centrals);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(entries.length, 8);
  eocd.writeUInt16LE(entries.length, 10);
  eocd.writeUInt32LE(centralBuf.length, 12);
  eocd.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, centralBuf, eocd]);
}

// Build a v2 .sal in memory from a v1 files Map: gzip every *.json entry (STORE), rebuild
// manifest.json with formatVersion 2 and files[] as {name, crc32, compressed} objects.
// manifest.json is omitted from files[] — its own checksum would be self-referential (the reader
// treats files[] as informational; the ZIP entry names are authoritative).
function buildV2(srcFiles, corruptName) {
  const td = new TextDecoder("utf-8");
  const manifest = JSON.parse(td.decode(srcFiles.get("manifest.json")));
  manifest.formatVersion = 2;
  const fileObjs = [];
  const entries = [];
  for (const [name, data] of srcFiles) {
    if (name === "manifest.json") continue;
    const isJson = name.endsWith(".json");
    fileObjs.push({
      name,
      crc32: isJson && name === corruptName ? "00000000" : crc32Hex(data),
      compressed: isJson,
    });
    entries.push([name, isJson ? new Uint8Array(zlib.gzipSync(data)) : data]);
  }
  manifest.files = fileObjs;
  entries.push(["manifest.json", new Uint8Array(zlib.gzipSync(new TextEncoder().encode(JSON.stringify(manifest))))]);
  return zipStore(entries);
}
function toAB(buf) {
  return buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength);
}

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
  ok(s.forAi.startsWith("# How to analyze"), "forAi brief present");
  ok(s.marks.length === 2 && s.marks[0].label === "Home renders cleanly", "marks present");

  // ── v2 coverage: gzip JSON tracks + per-entry CRC32 ─────────────────────────
  ok(s.formatVersion === 1, "v1 formatVersion exposed (default 1)");

  const files = await SAL.unzip(ab);
  const origWarn = console.warn;

  // Positive: a valid v2 file parses identically to v1 and reports formatVersion 2.
  const warnPos = [];
  console.warn = (...a) => warnPos.push(a.join(" "));
  const v2 = await SAL.read(toAB(buildV2(files)));
  console.warn = origWarn;

  ok(v2.formatVersion === 2, "v2: formatVersion === 2");
  ok(v2.manifest.app && v2.manifest.app.name === "QaLens Sample", "v2: manifest app name");
  ok(v2.summary && v2.summary.score === 58, "v2: summary score");
  ok(v2.timeline.length === 13, "v2: timeline entries");
  ok(v2.network.length === 6, "v2: network entries");
  ok(v2.logs.length === 11, "v2: log entries");
  ok(v2.state.length === 17, "v2: state entries");
  ok(v2.analysis && v2.analysis.anomalies.length === 5, "v2: analysis anomalies");
  ok(v2.report.startsWith("QaLens Full Report"), "v2: report text");
  ok(v2.frames.length === 64, "v2: frame index (64 entries)");
  ok(warnPos.length === 0, "v2: no crc32 mismatch warnings (valid checksums)");

  // Negative: corrupt one crc32 → parse still succeeds (warn path, no hard fail).
  const warnBad = [];
  console.warn = (...a) => warnBad.push(a.join(" "));
  let corruptParsed = false;
  try {
    const c = await SAL.read(toAB(buildV2(files, "timeline.json")));
    corruptParsed = c.formatVersion === 2 && c.timeline.length === 13;
  } catch (_) { corruptParsed = false; }
  console.warn = origWarn;
  ok(corruptParsed, "v2: crc32 mismatch still parses");
  ok(warnBad.some((w) => w.includes("crc32 mismatch for timeline.json")), "v2: crc32 mismatch warned");

  // Negative: formatVersion 3 must throw.
  const td = new TextDecoder("utf-8");
  const v3entries = [...files.entries()].map(([name, data]) => [name, data]);
  const v3manifest = JSON.parse(td.decode(files.get("manifest.json")));
  v3manifest.formatVersion = 3;
  for (const e of v3entries) if (e[0] === "manifest.json") e[1] = new TextEncoder().encode(JSON.stringify(v3manifest));
  let v3Threw = false;
  try { await SAL.read(toAB(zipStore(v3entries))); } catch (_) { v3Threw = true; }
  ok(v3Threw, "v2: formatVersion 3 throws");

  console.log(failures === 0 ? "\nALL PASS" : `\n${failures} FAILURE(S)`);
  process.exit(failures === 0 ? 0 : 1);
})().catch((e) => { console.error("ERROR", e); process.exit(1); });
