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

  async function inflate(bytes, format) {
    if (typeof DecompressionStream === "undefined") {
      throw new Error("This browser lacks DecompressionStream; use a recent Chrome/Edge/Safari/Firefox.");
    }
    const ds = new DecompressionStream(format);
    const stream = new Blob([bytes]).stream().pipeThrough(ds);
    const buf = await new Response(stream).arrayBuffer();
    return new Uint8Array(buf);
  }

  // RFC 1952 gzip magic bytes (1f 8b).
  const isGzip = (bytes) => bytes.length >= 2 && bytes[0] === 0x1f && bytes[1] === 0x8b;

  // Inflate a ZIP method-8 entry body: gzip (v2 JSON tracks) or raw DEFLATE (v1), by magic bytes.
  async function inflateEntry(bytes) {
    return inflate(bytes, isGzip(bytes) ? "gzip" : "deflate-raw");
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
      if (e.method === 0) {
        data = comp.slice();
        // v2 gzip-compressed JSON tracks may be STOREd (already compressed); detect via the gzip
        // magic bytes and inflate. Plain STORE entries (JPEG frames, text) pass through unchanged.
        if (isGzip(data)) data = await inflate(data, "gzip");
      } else if (e.method === 8) {
        data = await inflateEntry(comp);
      } else {
        throw new Error("Unsupported ZIP compression method " + e.method + " for " + e.name);
      }
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

  // CRC-32 (IEEE 802.3, table-based) over uncompressed bytes — used for v2 per-entry checksums.
  const CRC32_T = (() => {
    const t = new Uint32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      t[n] = c >>> 0;
    }
    return t;
  })();
  function crc32(bytes) {
    let c = 0xffffffff;
    for (let i = 0; i < bytes.length; i++) c = CRC32_T[(c ^ bytes[i]) & 0xff] ^ (c >>> 8);
    return (c ^ 0xffffffff) >>> 0;
  }
  function crc32Hex(bytes) {
    return crc32(bytes).toString(16).padStart(8, "0");
  }

  // Parse the extracted files into a structured session.
  function parse(files) {
    const manifest = jsonOf(files, "manifest.json", {});
    // C15: Validate formatVersion for forward-compatibility. A newer major version means
    // the archive layout/semantics may have changed — refuse loudly instead of misrendering.
    const fv = manifest.formatVersion;
    if (fv !== undefined && fv > 2) {
      throw new Error(`.sal formatVersion ${fv} is newer than this player supports (2). Update the QaLens web player / sal_report.js before opening this recording.`);
    }
    if (fv === 2) console.info("QaLens: reading .sal formatVersion 2");
    else if (fv !== undefined) console.info(`QaLens: reading .sal formatVersion ${fv}`);

    // v2: per-entry CRC-32 checksums over the UNCOMPRESSED content. manifest.files is an array of
    // strings (v1) or objects {name, crc32, compressed} (v2); accept both. A mismatch warns and
    // CONTINUES — a single corrupt entry must not hard-fail the whole recording.
    const filesList = manifest.files;
    if (Array.isArray(filesList)) {
      for (const f of filesList) {
        if (f && typeof f === "object" && typeof f.name === "string" && typeof f.crc32 === "string") {
          const data = files.get(f.name);
          if (data && crc32Hex(data) !== f.crc32.toLowerCase()) {
            console.warn(`QaLens: crc32 mismatch for ${f.name}`);
          }
        }
      }
    }

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
      formatVersion: fv ?? 1,
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
      marks: jsonOf(files, "marks.json", []),
      report: textOf(files, "report.txt"),
      // C12: the self-describing AI brief (for_ai.md) embedded by the recorder.
      forAi: textOf(files, "for_ai.md"),
    };
  }

  async function read(arrayBuffer) {
    const files = await unzip(arrayBuffer);
    return parse(files);
  }

  window.SAL = { read, unzip, parse };
})();
