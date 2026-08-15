#!/usr/bin/env node
/*
 * enrich_sample.js — adds the recorder's bookmarks (marks.json) to the bundled web/sample.sal.
 *
 * Unlike make_sample.js (which re-renders the fake app screens with headless Chromium), this tool
 * opens the existing .sal, verifies the for_ai.md entry, injects marks.json, and repacks ALL
 * entries into a new .sal with a STORE-only ZIP writer (no compression lib needed).
 *
 * Run:  node web/tools/enrich_sample.js
 */
"use strict";
const fs = require("fs");
const path = require("path");
const zlib = require("zlib");

const IN = path.join(__dirname, "..", "sample.sal");

// ── Minimal ZIP writer (STORE only) — copied verbatim from make_sample.js ─────
function crc32(buf) {
  if (zlib.crc32) return zlib.crc32(buf) >>> 0;
  // Fallback table-based CRC32
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

function zipStore(entries) {
  const locals = [], centrals = [];
  let offset = 0;
  for (const [name, data] of entries) {
    const nameB = Buffer.from(name, "utf8");
    const crc = crc32(data);
    const lh = Buffer.alloc(30);
    lh.writeUInt32LE(0x04034b50, 0);
    lh.writeUInt16LE(20, 4);          // version
    lh.writeUInt16LE(0, 6);           // flags
    lh.writeUInt16LE(0, 8);           // method STORE
    lh.writeUInt32LE(0, 10);          // time/date
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
    // extra/comment/disk/attrs = 0
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

// Minimal browser shim for the sal.js IIFE (same approach as web/test/read.test.js).
global.window = global;
if (!global.URL.createObjectURL) global.URL.createObjectURL = () => "blob://stub";
require(path.join(__dirname, "..", "sal.js"));
const SAL = global.SAL;

(async () => {
  const buf = fs.readFileSync(IN);
  const ab = buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength);
  const files = await SAL.unzip(ab);
  const td = new TextDecoder("utf-8");

  if (!files.has("for_ai.md")) throw new Error("for_ai.md entry missing from sample.sal");
  const manifest = JSON.parse(td.decode(files.get("manifest.json")));
  const startMillis = manifest.startMillis;

  const marks = [
    { id: "m1", ts: startMillis + 5200, label: "Home renders cleanly", severity: "info" },
    { id: "m2", ts: startMillis + 24600, label: "Transfer 500 — verify retry!", severity: "bug" },
  ];
  files.set("marks.json", new TextEncoder().encode(JSON.stringify(marks)));

  const entries = [...files.entries()].map(([name, data]) => [name, data]);
  fs.writeFileSync(IN, zipStore(entries));

  const kb = Math.round(fs.statSync(IN).size / 1024);
  console.log("Enriched " + IN + ": for_ai.md present, added marks.json (" + marks.length + " marks) -> " + entries.length + " entries, " + kb + " KB");
})().catch((e) => { console.error(e); process.exit(1); });
