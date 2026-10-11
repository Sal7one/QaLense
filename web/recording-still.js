/* Capture one explicit still from an already opened recording. No live phone API.
 * The returned JPEG is bounded and remains in memory until an explicit export/send.
 */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.QaLensRecordingStill = api;
})(typeof globalThis === 'object' ? globalThis : this, function () {
  'use strict';
  function waitMedia(element, event, signal) {
    if (signal?.aborted) { const error = Error('Cancelled'); error.name = 'AbortError'; return Promise.reject(error); }
    return new Promise((resolve, reject) => {
      const finish = error => { clearTimeout(timer); element.removeEventListener(event, ready); element.removeEventListener('error', failed); signal?.removeEventListener('abort', abort); error ? reject(error) : resolve(); };
      const ready = () => finish(), failed = () => finish(Error('The saved recording image could not be decoded.'));
      const abort = () => { const error = Error('Cancelled'); error.name = 'AbortError'; finish(error); };
      const timer = setTimeout(() => finish(Error('The saved recording is not ready for a still. Wait for playback to load, then try again.')), 5000);
      element.addEventListener(event, ready, {once: true}); element.addEventListener('error', failed, {once: true}); signal?.addEventListener('abort', abort, {once: true});
    });
  }
  async function capture(options, tMs, signal) {
    const session = options.session(); if (!session) throw Error('Open a saved recording first.');
    const current = () => { if (signal?.aborted || options.session() !== session) { const error = Error('Recording changed or cancelled.'); error.name = 'AbortError'; throw error; } };
    if (!Number.isFinite(session.start) || !Number.isFinite(session.duration) || session.duration < 1 || !Number.isFinite(tMs) || tMs < 0 || tMs > session.duration) throw Error('The selected image moment must be inside this recording’s actual clock.');
    current(); options.pause(); options.seek(tMs); current();
    let source, actualMs, sourceKind, approximate;
    if (session.videoUrl) {
      const video = options.video;
      if (video.readyState < 1) await waitMedia(video, 'loadedmetadata', signal);
      current();
      // Loading metadata may have completed after the first seek request.
      options.seek(tMs);
      if (video.seeking) await waitMedia(video, 'seeked', signal);
      if (video.readyState < 2) await waitMedia(video, 'loadeddata', signal);
      current(); source = video; const base = options.videoBase();
      actualMs = base - session.start + video.currentTime * 1000;
      if (!Number.isFinite(base) || !Number.isFinite(video.currentTime) || video.currentTime < 0 || !Number.isFinite(actualMs) || actualMs < 0 || actualMs > session.duration) throw Error('The saved video’s actual image timestamp is outside the recording; it cannot be relabeled at the beginning or end.');
      sourceKind = 'recording-video'; approximate = true;
    } else {
      const frame = options.frameAt(session.start + tMs);
      if (!frame?.url) throw Error('This recording has no saved image at the selected moment.');
      actualMs = frame.ts - session.start;
      if (!Number.isFinite(frame.ts) || !Number.isFinite(actualMs) || actualMs < 0 || actualMs > session.duration) throw Error('The saved frame’s actual timestamp is outside the recording; it cannot be relabeled at the beginning or end.');
      source = options.document.createElement('img'); const loaded = waitMedia(source, 'load', signal); source.src = frame.url; await loaded;
      current(); sourceKind = 'recording-frame'; approximate = Math.abs(actualMs - tMs) > 100;
    }
    const width = source.videoWidth || source.naturalWidth, height = source.videoHeight || source.naturalHeight;
    if (!width || !height) throw Error('The selected recording frame is empty.');
    const canvas = options.document.createElement('canvas'); const ctx = canvas.getContext('2d'); if (!ctx) throw Error('This browser cannot prepare recording images.');
    let edge = 960, encoded = '';
    try {
      for (let attempt = 0; attempt < 5; attempt++) {
        current(); const scale = Math.min(1, edge / Math.max(width, height)); canvas.width = Math.max(1, Math.round(width * scale)); canvas.height = Math.max(1, Math.round(height * scale));
        ctx.drawImage(source, 0, 0, canvas.width, canvas.height); encoded = canvas.toDataURL('image/jpeg', Math.max(0.4, 0.76 - attempt * 0.08));
        if (encoded.startsWith('data:image/jpeg;base64,') && encoded.length - 23 <= 174764) break;
        edge = Math.floor(edge * 0.8);
      }
      if (!encoded.startsWith('data:image/jpeg;base64,') || encoded.length - 23 > 174764) throw Error('The selected still exceeds the 128 KiB image budget.');
      current(); return {id: 'images:0', tMs: actualMs, source: sourceKind, approximate, mediaType: 'image/jpeg', data: encoded.slice(encoded.indexOf(',') + 1)};
    } finally { canvas.width = 1; canvas.height = 1; }
  }
  return {capture};
});
