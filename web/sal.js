/*
 * sal.js — dependency-free .sal (ZIP) reader for the browser.
 *
 * A .sal is a standard ZIP (written by java.util.zip.ZipOutputStream). We read the **central
 * directory** for authoritative entry sizes (local headers may use data descriptors), then inflate
 * DEFLATE entries with the browser-native DecompressionStream('deflate-raw'). No external library,
 * fully offline. Exposes a global `SAL`.
 */
(function () {
  "use strict";

  const SIG_EOCD = 0x06054b50;
  const SIG_CEN = 0x02014b50;
  const td = new TextDecoder("utf-8");

  function u16(dv, o) { return dv.getUint16(o, true); }
  function u32(dv, o) { return dv.getUint32(o, true); }

  function findEOCD(dv) {
    // EOCD is at the end; scan back over the (max 64KB) comment region.
    const len = dv.byteLength;
    const min = Math.max(0, len - 22 - 0xffff);
    for (let i = len - 22; i >= min; i--) {
      if (u32(dv, i) === SIG_EOCD) return i;
    }
    throw new Error("Not a valid .sal/ZIP (no end-of-central-directory record).");
  }

  function readCentralDirectory(dv) {
    const eocd = findEOCD(dv);
    const count = u16(dv, eocd + 10);
    let ptr = u32(dv, eocd + 16); // central dir offset
    const entries = [];
    for (let i = 0; i < count; i++) {
      if (u32(dv, ptr) !== SIG_CEN) break;
      const method = u16(dv, ptr + 10);
      const compSize = u32(dv, ptr + 20);
      const uncompSize = u32(dv, ptr + 24);
      const nameLen = u16(dv, ptr + 28);
      const extraLen = u16(dv, ptr + 30);
      const commentLen = u16(dv, ptr + 32);
      const localOffset = u32(dv, ptr + 42);
      const name = td.decode(new Uint8Array(dv.buffer, ptr + 46, nameLen));
      entries.push({ name, method, compSize, uncompSize, localOffset });
      ptr += 46 + nameLen + extraLen + commentLen;
    }
    return entries;
  }

  async function inflateRaw(bytes) {
    if (typeof DecompressionStream === "undefined") {
      throw new Error("This browser lacks DecompressionStream; use a recent Chrome/Edge/Safari/Firefox.");
    }
    const ds = new DecompressionStream("deflate-raw");
    const stream = new Blob([bytes]).stream().pipeThrough(ds);
    const buf = await new Response(stream).arrayBuffer();
    return new Uint8Array(buf);
  }

  // Returns Map<name, Uint8Array>
  async function unzip(arrayBuffer) {
    const dv = new DataView(arrayBuffer);
    const u8 = new Uint8Array(arrayBuffer);
    const entries = readCentralDirectory(dv);
    const out = new Map();
    for (const e of entries) {
      if (e.name.endsWith("/")) continue; // directory
      // Local header: name/extra lengths are reliable even with data descriptors.
      const lo = e.localOffset;
      const nameLen = u16(dv, lo + 26);
      const extraLen = u16(dv, lo + 28);
      const dataStart = lo + 30 + nameLen + extraLen;
      const comp = u8.subarray(dataStart, dataStart + e.compSize);
      let data;
      if (e.method === 0) data = comp.slice();
      else if (e.method === 8) data = await inflateRaw(comp);
      else throw new Error("Unsupported ZIP compression method " + e.method + " for " + e.name);
      out.set(e.name, data);
    }
    return out;
  }

  function jsonOf(files, name, fallback) {
    const bytes = files.get(name);
    if (!bytes) return fallback;
    try { return JSON.parse(td.decode(bytes)); } catch (_) { return fallback; }
  }
  function textOf(files, name) {
    const bytes = files.get(name);
    return bytes ? td.decode(bytes) : "";
  }
  function blobUrl(files, name, mime) {
    const bytes = files.get(name);
    if (!bytes) return null;
    return URL.createObjectURL(new Blob([bytes], { type: mime }));
  }

  // Parse the extracted files into a structured session.
  function parse(files) {
    const manifest = jsonOf(files, "manifest.json", {});
    const start = manifest.startMillis || 0;
    const end = manifest.endMillis || start + 1;

    // Frames: { ts, url } sorted by time
    const frames = [];
    const fi = manifest.frameIndex || {};
    for (const k of Object.keys(fi)) {
      const url = blobUrl(files, fi[k], "image/jpeg");
      if (url) frames.push({ ts: Number(k), url });
    }
    frames.sort((a, b) => a.ts - b.ts);

    const videoUrl = manifest.video ? blobUrl(files, manifest.video, "video/mp4") : null;

    return {
      manifest,
      start,
      end,
      duration: Math.max(1, end - start),
      frames,
      videoUrl,
      summary: jsonOf(files, "summary.json", null),
      // Precomputed on-device digest (qalens-analysis/1); null in older recordings.
      analysis: jsonOf(files, "analysis.json", null),
      timeline: jsonOf(files, "timeline.json", []),
      network: jsonOf(files, "network.json", []),
      logs: jsonOf(files, "logs.json", []),
      state: jsonOf(files, "state.json", []),
      report: textOf(files, "report.txt"),
    };
  }

  async function read(arrayBuffer) {
    const files = await unzip(arrayBuffer);
    return parse(files);
  }

  window.SAL = { read, unzip, parse };
})();
