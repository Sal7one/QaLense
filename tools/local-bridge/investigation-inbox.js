/* Phone investigations stay in bounded PC memory until an explicit Save.
 * Polling only updates metadata; Review opens the shared web investigation UI.
 */
(function (root, factory) {
  const api = factory(typeof module === 'object' && module.exports ? require('../../web/insights.js') : root.QaLensInsights);
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.QaLensInvestigationInbox = api;
})(typeof globalThis === 'object' ? globalThis : this, function (I) {
  'use strict';
  const hashPattern = /^[a-f0-9]{64}$/;
  function metadata(rows, limit) {
    if (!Array.isArray(rows)) throw Error('The investigation list has an invalid format.');
    const seen = new Set(), result = [];
    for (const row of rows) {
      if (!row || !hashPattern.test(row.hash || '') || seen.has(row.hash)) continue;
      seen.add(row.hash);
      result.push({hash: row.hash, recordingName: I.text(row.recordingName || 'Phone investigation', 128), question: I.text(row.question || '', 2000),
        target: row.target === 'qalens-player' ? 'qalens-player' : 'recorded-app', qaTitle: I.text(row.qaTitle || '', 400),
        hasReport: row.hasReport === true, hasImage: row.hasImage === true, receivedAt: I.text(row.receivedAt ?? row.savedAt ?? '', 64),
        byteSize: Number.isFinite(row.byteSize) && row.byteSize >= 0 ? row.byteSize : 0});
      if (result.length === limit) break;
    }
    return result;
  }
  class Inbox {
    constructor(hooks) {
      this.hooks = hooks; this.connectionId = ''; this.connected = false; this.generation = 0; this.inflight = false; this.opening = false;
      this.previews = []; this.saved = []; this.notice = 'Use Send to PC in phone Insights. Receive collects selected evidence; Review opens it without calling a model.';
      this.retryAt = 0; this.failures = 0; this.savedGeneration = 0;
    }
    notify() { this.hooks.changed?.(this); }
    checkConnection(id, connected) {
      const next = connected && id ? String(id) : '';
      if (next !== this.connectionId || !!connected !== this.connected) {
        this.generation++; this.connectionId = next; this.connected = !!connected; this.previews = []; this.retryAt = 0; this.failures = 0;
        this.notice = next ? 'Connected. Enable Receive or choose Receive now to collect phone investigations.' : 'Phone disconnected. Pending investigations are cleared; saved PC investigations remain available.';
        this.notify();
      }
    }
    sync() { const connection = this.hooks.connection(); this.checkConnection(connection?.id, !!connection?.connected); }
    current(generation, id) { const live = this.hooks.connection(); return generation === this.generation && !!live?.connected && live.id === id && this.connectionId === id; }
    async poll(manual = false) {
      this.sync(); const now = this.hooks.now || Date.now;
      if (this.inflight || !this.connected || !this.connectionId || !this.hooks.visible() || (!manual && (!this.hooks.enabled() || this.hooks.busy() || now() < this.retryAt))) return;
      this.inflight = true; const mine = this.generation, id = this.connectionId;
      try {
        const response = await this.hooks.inbox(id);
        if (!this.current(mine, id)) return;
        if (response.connectionId && response.connectionId !== id) return;
        this.previews = metadata(response.previews, 10); this.failures = 0; this.retryAt = now() + 3500;
        this.notice = `${this.previews.length} pending investigation${this.previews.length === 1 ? '' : 's'} · selected evidence only. Review and Save are explicit.${response.duplicates ? ` ${response.duplicates} duplicate handoffs were reused.` : ''}${response.blocked ? ` PC inbox is full; ${response.blocked} case(s) remain on the phone. Save reviewed cases to make room.` : ''}${response.invalid ? ` ${response.invalid} invalid handoff(s) rejected.` : ''}${response.dropped ? ` Phone queue dropped ${response.dropped} older handoff(s).` : ''}`;
        this.notify();
      } catch (error) {
        if (!this.current(mine, id)) return;
        this.failures++; this.retryAt = now() + Math.min(30000, 3500 * 2 ** Math.min(this.failures - 1, 3));
        this.notice = `${error.message} ${[401, 403, 409].includes(error.status) ? 'Reconnect before receiving again.' : 'Receive will retry while enabled.'}`;
        if ([401, 403, 409].includes(error.status)) this.hooks.pauseReceive?.();
        this.notify();
      } finally { this.inflight = false; this.notify(); }
    }
    async refreshSaved() {
      const mine = ++this.savedGeneration, response = await this.hooks.saved();
      if (mine !== this.savedGeneration) return;
      this.saved = metadata(response.saved, 100);
      if (response.corrupt || response.omitted) this.notice = `Saved library: ${response.corrupt || 0} invalid file(s) rejected; ${response.omitted || 0} older file(s) omitted from this bounded list.`;
      this.notify();
    }
    async open(hash, saved = false) {
      this.sync();
      if (this.opening) return false;
      if (!hashPattern.test(hash || '') || !(saved ? this.saved : this.previews).some(row => row.hash === hash)) throw Error('This investigation is no longer available. Refresh the list.');
      const mine = this.generation, id = this.connectionId;
      const current = () => saved || this.current(mine, id);
      if (!current()) throw Error('The phone connection changed. Refresh before opening a pending investigation.');
      this.opening = true;
      try {
        const response = await this.hooks.document(hash);
        if (!current()) return false;
        const checked = I.validateInvestigation(response.document);
        if (!current()) return false;
        return await this.hooks.open(checked, current);
      } finally { this.opening = false; }
    }
    async save(hash) {
      this.sync();
      if (!hashPattern.test(hash || '') || !this.previews.some(row => row.hash === hash)) throw Error('Refresh pending investigations before saving this case.');
      const mine = this.generation, id = this.connectionId, response = await this.hooks.save(hash);
      if (!this.current(mine, id)) return false;
      if (!response.saved || response.saved.hash !== hash) throw Error('The PC did not confirm this investigation was saved.');
      this.previews = this.previews.filter(row => row.hash !== hash); this.notice = 'Investigation saved on this PC. Its content stays sensitive; review before sharing.'; this.notify();
      await this.refreshSaved(); return true;
    }
  }
  async function sendToViewer({window: win, iframe, document, ready, current = () => true, timeoutMs = 10000, requestId}) {
    const checked = I.validateInvestigation(document);
    if (!current()) return false;
    await ready(); if (!current()) return false;
    const id = requestId || `case_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 12)}`;
    if (!/^[A-Za-z0-9_-]{8,80}$/.test(id)) throw Error('The investigation request identifier is invalid.');
    const source = iframe.contentWindow, origin = win.location.origin;
    if (!source) throw Error('The shared player is unavailable.');
    return new Promise((resolve, reject) => {
      const finish = (error, answer) => { clearTimeout(timer); win.removeEventListener('message', receive); if (error) reject(error); else resolve(answer); };
      const receive = event => {
        if (event.origin !== origin || event.source !== source || event.data?.type !== 'qalens-investigation-ack' || event.data.requestId !== id) return;
        if (!current()) { finish(null, false); return; }
        if (event.data.ok === true) finish(null, true); else finish(Error(I.text(event.data.error || 'The shared player rejected this investigation.', 400)));
      };
      const timer = setTimeout(() => finish(Error('The shared player did not acknowledge the investigation. Choose Review again.')), timeoutMs);
      win.addEventListener('message', receive);
      try { source.postMessage({type: 'qalens-investigation', requestId: id, document: checked}, origin); }
      catch { finish(Error('The investigation could not be handed to the shared player.')); }
    });
  }
  Inbox.sendToViewer = sendToViewer;
  return Inbox;
});
