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
  const MAX_ENTRIES = 4096;
  const MAX_ENTRY_BYTES = 256 * 1024 * 1024;
  const MAX_EXPANDED_BYTES = 512 * 1024 * 1024;
  const MAX_TEXT_BYTES = 16 * 1024 * 1024;
  const MAX_MANIFEST_BYTES = 1024 * 1024;

  function u16(dv, o) { return dv.getUint16(o, true); }
  function u32(dv, o) { return dv.getUint32(o, true); }

  function findEOCD(dv) {
    // EOCD is at the end; scan back over the (max 64KB) comment region.
    const len = dv.byteLength;
    const min = Math.max(0, len - 22 - 0xffff);
    for (let i = len - 22; i >= min; i--) {
      if (u32(dv, i) === SIG_EOCD && i + 22 + u16(dv, i + 20) === len) return i;
    }
    throw new Error("Not a valid .sal/ZIP (no end-of-central-directory record).");
  }

  function requireRange(dv, offset, size, label, limit = dv.byteLength) {
    if (offset < 0 || size < 0 || offset > limit || size > limit - offset) {
      throw new Error("Invalid .sal/ZIP: " + label + " exceeds archive bounds.");
    }
  }

  function readCentralDirectory(dv) {
    const eocd = findEOCD(dv);
    const count = u16(dv, eocd + 10);
    if (count > MAX_ENTRIES) throw new Error("Too many .sal/ZIP entries.");
    if (u16(dv, eocd + 4) || u16(dv, eocd + 6) || u16(dv, eocd + 8) !== count) {
      throw new Error("Unsupported multi-disk .sal/ZIP.");
    }
    const size = u32(dv, eocd + 12);
    let ptr = u32(dv, eocd + 16);
    requireRange(dv, ptr, size, "central directory", eocd);
    const end = ptr + size;
    const entries = [], names = new Set();
    let expandedSize = 0;
    for (let i = 0; i < count; i++) {
      requireRange(dv, ptr, 46, "central entry", end);
      if (u32(dv, ptr) !== SIG_CEN) throw new Error("Invalid .sal/ZIP central entry.");
      if (u16(dv, ptr + 8) & 1) throw new Error("Encrypted .sal/ZIP entries are unsupported.");
      const method = u16(dv, ptr + 10);
      const zipCrc = u32(dv, ptr + 16);
      const compSize = u32(dv, ptr + 20), uncompSize = u32(dv, ptr + 24);
      const nameLen = u16(dv, ptr + 28), extraLen = u16(dv, ptr + 30), commentLen = u16(dv, ptr + 32);
      const localOffset = u32(dv, ptr + 42);
      requireRange(dv, ptr + 46, nameLen + extraLen + commentLen, "entry name", end);
      const name = td.decode(new Uint8Array(dv.buffer, dv.byteOffset + ptr + 46, nameLen));
      const parts = name.replace(/\/$/, "").split("/");
      if (!name || name.includes("\\") || name.includes("\0") || name.startsWith("/") ||
          parts.some((part) => !part || part === "." || part === "..") || parts[0].includes(":")) {
        throw new Error("Invalid .sal/ZIP entry name: " + name);
      }
      if (names.has(name)) throw new Error("Duplicate .sal/ZIP entry: " + name);
      if (method !== 0 && method !== 8) throw new Error("Unsupported ZIP compression method " + method);
      if (uncompSize > MAX_ENTRY_BYTES) throw new Error(".sal/ZIP entry size limit exceeded: " + name);
      expandedSize += uncompSize;
      if (expandedSize > MAX_EXPANDED_BYTES) throw new Error(".sal/ZIP expanded size limit exceeded.");
      names.add(name);
      entries.push({ name, method, compSize, uncompSize, localOffset, zipCrc });
      ptr += 46 + nameLen + extraLen + commentLen;
    }
    if (ptr !== end) throw new Error("Invalid .sal/ZIP central directory size.");
    return { entries, dataEnd: u32(dv, eocd + 16) };
  }

  async function inflate(bytes, format, limit) {
    if (typeof DecompressionStream === "undefined") {
      throw new Error("This browser lacks DecompressionStream; use a recent Chrome/Edge/Safari/Firefox.");
    }
    const ds = new DecompressionStream(format);
    const stream = new Blob([bytes]).stream().pipeThrough(ds);
    const reader = stream.getReader();
    const chunks = [];
    let length = 0;
    try {
      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        length += value.length;
        if (length > limit) {
          await reader.cancel().catch(() => {});
          throw new Error(".sal expanded size limit exceeded.");
        }
        chunks.push(value);
      }
    } finally {
      reader.releaseLock();
    }
    const out = new Uint8Array(length);
    let offset = 0;
    for (const chunk of chunks) { out.set(chunk, offset); offset += chunk.length; }
    return out;
  }

  // RFC 1952 gzip magic bytes (1f 8b).
  const isGzip = (bytes) => bytes.length >= 2 && bytes[0] === 0x1f && bytes[1] === 0x8b;

  // Returns Map<name, Uint8Array>
  async function unzip(arrayBuffer) {
    const dv = new DataView(arrayBuffer);
    const u8 = new Uint8Array(arrayBuffer);
    const { entries, dataEnd } = readCentralDirectory(dv);
    const out = new Map();
    let decodedTotal = 0;
    for (const e of entries) {
      if (e.name.endsWith("/")) {
        if (e.uncompSize || e.compSize) throw new Error("Invalid .sal/ZIP directory: " + e.name);
        continue;
      }
      // Local header: name/extra lengths are reliable even with data descriptors.
      const lo = e.localOffset;
      requireRange(dv, lo, 30, "local header", dataEnd);
      if (u32(dv, lo) !== 0x04034b50 || u16(dv, lo + 8) !== e.method) {
        throw new Error("Invalid .sal/ZIP local header for " + e.name);
      }
      const nameLen = u16(dv, lo + 26);
      const extraLen = u16(dv, lo + 28);
      const dataStart = lo + 30 + nameLen + extraLen;
      requireRange(dv, lo + 30, nameLen + extraLen, "local name", dataEnd);
      requireRange(dv, dataStart, e.compSize, "entry data", dataEnd);
      const localName = td.decode(u8.subarray(lo + 30, lo + 30 + nameLen));
      if (localName !== e.name) throw new Error("Mismatched .sal/ZIP local name for " + e.name);
      const comp = u8.subarray(dataStart, dataStart + e.compSize);
      const isText = /\.(json|txt|md)$/.test(e.name);
      const textLimit = e.name === "manifest.json" ? MAX_MANIFEST_BYTES : MAX_TEXT_BYTES;
      const outerLimit = isText ? Math.min(MAX_ENTRY_BYTES, textLimit + 65536) : MAX_ENTRY_BYTES;
      if (e.uncompSize > outerLimit) throw new Error(".sal/ZIP entry size limit exceeded: " + e.name);
      let data;
      if (e.method === 0) {
        data = comp.slice();
      } else if (e.method === 8) {
        data = await inflate(comp, "deflate-raw", e.uncompSize);
      }
      if (data.length !== e.uncompSize) throw new Error("Invalid .sal/ZIP entry size for " + e.name);
      if (crc32(data) !== e.zipCrc) throw new Error(".sal/ZIP checksum mismatch for " + e.name);
      // ZIP compression is the outer layer. Android v2 writes gzip JSON inside DEFLATE entries.
      if (e.name.endsWith(".json") && isGzip(data)) data = await inflate(data, "gzip", textLimit);
      if (isText && data.length > textLimit) throw new Error(".sal expanded text limit exceeded: " + e.name);
      decodedTotal += data.length;
      if (decodedTotal > MAX_EXPANDED_BYTES) throw new Error(".sal decoded size limit exceeded.");
      out.set(e.name, data);
    }
    return out;
  }

  function jsonOf(files, name, fallback) {
    const bytes = files.get(name);
    if (!bytes) return fallback;
    try { return JSON.parse(td.decode(bytes)); } catch (_) { throw new Error("Invalid .sal JSON: " + name); }
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
    if (!(files instanceof Map) || files.size > MAX_ENTRIES) throw new Error("Invalid .sal file list.");
    let decodedTotal = 0;
    for (const [name, data] of files) {
      if (typeof name !== "string" || !(data instanceof Uint8Array)) throw new Error("Invalid .sal entry.");
      const limit = name === "manifest.json" ? MAX_MANIFEST_BYTES :
        /\.(json|txt|md)$/.test(name) ? MAX_TEXT_BYTES : MAX_ENTRY_BYTES;
      if (data.length > limit) throw new Error(".sal entry size limit exceeded: " + name);
      decodedTotal += data.length;
      if (decodedTotal > MAX_EXPANDED_BYTES) throw new Error(".sal decoded size limit exceeded.");
    }
    const manifest = jsonOf(files, "manifest.json", null);
    if (!manifest || typeof manifest !== "object" || Array.isArray(manifest)) {
      throw new Error("Invalid .sal: missing or invalid manifest.json.");
    }
    // C15: Validate formatVersion for forward-compatibility. A newer major version means
    // the archive layout/semantics may have changed — refuse loudly instead of misrendering.
    const fv = manifest.formatVersion;
    if (fv !== undefined && (!Number.isInteger(fv) || fv < 1)) {
      throw new Error("Invalid .sal formatVersion.");
    }
    if (fv !== undefined && fv > 2) {
      throw new Error(`.sal formatVersion ${fv} is newer than this player supports (2). Update the QaLens web player / sal_report.js before opening this recording.`);
    }
    if (fv === 2) console.info("QaLens: reading .sal formatVersion 2");
    else if (fv !== undefined) console.info(`QaLens: reading .sal formatVersion ${fv}`);

    // v2: per-entry CRC-32 checksums over decoded content. Reject damaged or missing evidence.
    const filesList = manifest.files;
    if (filesList !== undefined) {
      if (!Array.isArray(filesList) || filesList.length > MAX_ENTRIES) throw new Error("Invalid .sal files list.");
      for (const f of filesList) {
        const name = typeof f === "string" ? f : f && typeof f === "object" ? f.name : null;
        if (typeof name !== "string" || !files.has(name)) throw new Error("Missing .sal entry: " + name);
        if (typeof f === "object" && Object.hasOwn(f, "crc32")) {
          if (typeof f.crc32 !== "string" || !/^[0-9a-f]{8}$/i.test(f.crc32) ||
              crc32Hex(files.get(name)) !== f.crc32.toLowerCase()) {
            throw new Error(".sal checksum mismatch for " + name);
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
      // Keep the original track positions for grounded investigation citations. The
      // older UI can ignore these; no archive or capture format changes are needed.
      crashes: jsonOf(files, "crashes.json", []),
      performance: jsonOf(files, "performance.json", []),
      connectivity: jsonOf(files, "connectivity.json", []),
      memory: jsonOf(files, "memory.json", []),
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

  // Additive metadata: older archives remain readable, with retention coverage unknown.
  function recordingCoverage(session) {
    const recording = session.analysis?.coverage?.recording;
    if (!recording) return { known: false, partial: false, warnings: [] };
    const warnings = [];
    for (const [name, track] of Object.entries(recording.tracks || {})) {
      if (Number(track.dropped) > 0)
        warnings.push(`${recording.policy === "keep-latest-buffer" ? "Recent buffer lifetime · " : ""}${name}: ${track.dropped} observations omitted, ${track.retained} retained.`);
    }
    if (recording.truncated && !warnings.length) warnings.push("Recording evidence was truncated.");
    if (Number(recording.droppedFrameCallbacks) > 0)
      warnings.push(`Android dropped ${recording.droppedFrameCallbacks} frame-metrics callbacks; performance statistics are partial.`);
    return { known: true, partial: warnings.length > 0, warnings };
  }

  function showRecordingCoverage(session, container) {
    let banner = container.querySelector(".recording-coverage");
    if (!banner) {
      banner = document.createElement("div");
      banner.className = "recording-coverage";
      banner.setAttribute("role", "status");
      banner.style.cssText = "padding:12px 16px;background:#382b12;color:#ffe4a3;border:1px solid #967039;border-radius:8px;margin:8px;line-height:1.5;grid-column:1/-1";
      container.prepend(banner);
    }
    let clipBanner = container.querySelector(".recording-clip");
    const candidate = session.analysis?.clip;
    const clip = candidate && typeof candidate === "object" && typeof candidate.label === "string" && Number.isInteger(candidate.requestedSeconds) && candidate.requestedSeconds >= 1 && candidate.requestedSeconds <= 300 ? candidate : null;
    if (!clipBanner) {
      clipBanner = document.createElement("div"); clipBanner.className = "recording-clip";
      clipBanner.style.cssText = "padding:12px 16px;background:#16283e;color:#bedcff;border-radius:8px;margin:8px;grid-column:1/-1";
      container.prepend(clipBanner);
    }
    clipBanner.hidden = !clip;
    clipBanner.textContent = clip ? `Bug clip · ${String(clip.label || "Marked moment").slice(0, 256)} · requested last ${clip.requestedSeconds}s. Video may begin earlier at its preceding keyframe; timestamps match the exported window.` : "";
    const coverage = recordingCoverage(session);
    banner.hidden = !coverage.partial;
    banner.textContent = coverage.partial ? "Partial recording — " + coverage.warnings.join(" ") + " Conclusions cover captured evidence only." : "";
  }

  window.SAL = { read, unzip, parse, recordingCoverage, showRecordingCoverage };
})();
