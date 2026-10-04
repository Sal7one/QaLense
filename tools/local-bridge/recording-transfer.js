'use strict';
(function(root) {
  class RecordingTransfer {
    constructor(hooks) {
      this.hooks = hooks; this.enabled = false; this.inflight = false; this.generation = 0;
      this.connectionId = ''; this.baselinePending = true; this.known = new Set(); this.retryAt = 0; this.failures = 0;
    }
    enable() {
      this.generation++; this.enabled = true; this.connectionId = ''; this.baselinePending = true;
      this.known.clear(); this.retryAt = 0; this.failures = 0; this.hooks.changed(this);
      this.hooks.status('Automatic transfer requested. Waiting to check the phone library.');
      return this.poll();
    }
    disable(message) {
      this.generation++; this.enabled = false; this.connectionId = ''; this.known.clear(); this.retryAt = 0;
      this.hooks.changed(this); if (message) this.hooks.status(message);
    }
    checkConnection(id, connected) {
      if (this.enabled && this.connectionId && (!connected || id !== this.connectionId)) {
        this.disable('Connection changed. Enable automatic transfer again for this phone.');
      }
    }
    noteCopied(name, id) { if (this.enabled && !this.baselinePending && id === this.connectionId) this.known.add(name); }
    async poll() {
      const now = this.hooks.now || Date.now;
      if (!this.enabled || this.inflight || this.hooks.busy() || now() < this.retryAt) return;
      this.inflight = true;
      const generation = this.generation;
      const current = () => this.enabled && generation === this.generation;
      try {
        const connection = await this.hooks.connection();
        if (!current()) return;
        if (!connection.connected || !connection.id) {
          const error = Error('Connect a phone first.'); error.status = 409; throw error;
        }
        if (this.connectionId && connection.id !== this.connectionId) {
          this.disable('Connection changed. Enable automatic transfer again for this phone.'); return;
        }
        this.connectionId = connection.id;
        const library = await this.hooks.list(connection.id);
        if (!current()) return;
        if (library.connectionId !== connection.id) {
          this.disable('Connection changed during discovery. Enable automatic transfer again.'); return;
        }
        if (this.baselinePending) {
          this.known = new Set(library.items.map(entry => entry.name)); this.baselinePending = false;
          this.hooks.status('Automatic transfer enabled for new recordings. Existing recordings require Copy.');
        } else {
          for (const entry of library.items) {
            if (!current()) return;
            if (!this.known.has(entry.name)) {
              await this.hooks.copy(entry.name, connection.id);
              if (!current()) return;
              this.known.add(entry.name);
            }
          }
          if (this.failures) this.hooks.status('Automatic transfer resumed. Waiting for new recordings.');
        }
        this.failures = 0; this.retryAt = 0; this.hooks.changed(this);
      } catch (error) {
        if (!current()) return;
        if ([401, 403, 409].includes(error.status)) {
          this.disable(`${error.message} Reconnect and enable automatic transfer again.`);
        } else {
          // Discovery and content-addressed archive copies are safe to retry; host actions are not.
          this.failures++; this.retryAt = now() + Math.min(30000, 5000 * 2 ** Math.min(this.failures - 1, 3));
          this.hooks.status(`${error.message} Automatic transfer remains enabled and will retry.`);
        }
      } finally {
        this.inflight = false;
        // A new enable request may have arrived while the old request was still completing.
        if (this.enabled && generation !== this.generation) void this.poll();
      }
    }
  }
  if (typeof module !== 'undefined' && module.exports) module.exports = RecordingTransfer;
  else root.QaLensRecordingTransfer = RecordingTransfer;
})(globalThis);
