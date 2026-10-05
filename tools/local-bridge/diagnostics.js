/* Bounded live evidence and read-only SQL. Captured values/rows stay in memory. */
(function (root, factory) {
  const exports = factory();
  if (typeof module === 'object' && module.exports) module.exports = exports;
  else root.QaLensDiagnostics = exports;
})(typeof globalThis === 'object' ? globalThis : this, function () {
  'use strict';
  const keyOf = (source, key) => JSON.stringify([source, key]);
  const text = value => value == null ? '' : String(value);
  function flatten(sources) {
    const fields = new Map();
    for (const [source, fieldsInSource] of Object.entries(sources || {}).slice(0, 30)) {
      for (const [key, value] of Object.entries(fieldsInSource || {}).slice(0, 100)) {
        if (fields.size === 300) return fields;
        fields.set(keyOf(source, key), {source, key, value: text(value)});
      }
    }
    return fields;
  }
  class Values {
    constructor() { this.reset(); }
    reset() { this.current = new Map(); this.baseline = null; this.pins = new Set(); this.policy = null; }
    update(snapshot) {
      if (this.policy !== null && this.policy !== snapshot.policyId) this.reset();
      this.policy = snapshot.policyId; this.current = flatten(snapshot.dataSources);
      if (this.baseline === null) this.baseline = new Map(this.current);
      // Per-hook redactKeys/redactAll can tighten without changing the global config revision.
      // Purge an earlier plaintext baseline whenever the current field becomes masked.
      for (const [id, field] of this.current) if (field.value.includes('[REDACTED]') && this.baseline.has(id))
        this.baseline.set(id, {...field});
    }
    compare() { this.baseline = new Map(this.current); }
    pin(id) {
      if (this.pins.has(id)) this.pins.delete(id);
      else if (this.current.has(id) && this.pins.size < 10) this.pins.add(id);
    }
    rows(search = '') {
      const needle = search.trim().toLowerCase();
      const ids = [...this.pins, ...[...this.current.keys()].filter(id => !this.pins.has(id))];
      return ids.map(id => {
        const now = this.current.get(id), before = this.baseline?.get(id);
        const [source, key] = JSON.parse(id);
        // A missing field can be stopped, masked or omitted. Do not call it deleted or show
        // its previous value when current availability/privacy cannot be checked.
        const changed = !!now && !!before && before.value !== now.value;
        return {id, source, key, value: now?.value, pinned: this.pins.has(id), changed,
          added: !!now && !before, before: changed ? before.value : null};
      }).filter(row => !needle || [row.source, row.key, row.value].some(value => text(value).toLowerCase().includes(needle)));
    }
  }
  const isLogError = event => /\b(error|fatal|exception|crash)\b/i.test(`${event.tag || ''} ${event.message || ''}`);
  const isNetworkError = event => !!event.error || event.status >= 400 && event.status <= 599;
  const clipLabel = (note, seconds) => note.trim().slice(0, 256) || `Bug clip · last ${seconds}s`;
  const tableQuery = name => `SELECT * FROM "${name.replace(/"/g, '""')}" LIMIT 100`;

  function install({document: doc, api, connection, workBusy, navigate, interval = setInterval}) {
    const $ = id => doc.getElementById(id);
    let contextId = null, wasConnected = false, generation = 0, activeView = 'network', follow = false;
    let observations = null, valuesSnapshot = null, policy = null, readBusy = false;
    const values = new Values();
    let catalog = null, sqlJob = null, sqlStarting = false, sqlPolling = false, sqlWorking = false;
    const time = millis => millis ? new Date(millis).toLocaleTimeString() : '—';
    const visible = id => !doc.hidden && !$(id).hidden;
    const liveVisible = () => visible('landing') && $('live-diagnostics').open;
    const ready = () => !!connection().connected;
    function node(tag, label = '', className = '') {
      const element = doc.createElement(tag); element.textContent = label; element.className = className; return element;
    }
    function button(label, action, parent, disabled = false) {
      const element = node('button', label); element.onclick = action; element.disabled = disabled; parent.append(element); return element;
    }
    function state(message) { $('diagnostic-state').textContent = message; }
    function sqlState(message) { $('sql-state').textContent = message; }
    function clearData() {
      observations = null; valuesSnapshot = null; values.reset(); policy = null; catalog = null;
      for (const id of ['diagnostic-content', 'sql-results', 'sql-saved', 'data-file-list', 'preference-values']) $(id).replaceChildren();
      $('sql-database').replaceChildren(node('option', 'Connect and rescan'));
    }
    function sync() {
      const current = connection();
      if (current.id !== contextId || current.connected !== wasConnected) {
        void cancelOwned(); ++generation; contextId = current.id; wasConnected = current.connected;
        clearData(); follow = false; sqlStarting = false; sqlWorking = false;
        $('sql-query').value = ''; $('sql-name').value = '';
        state(current.connected ? 'Connected · choose Refresh or Follow live' : 'Connect to read live evidence');
        sqlState(current.connected ? 'Rescan to list app databases and saved queries' : 'Connect to read app databases');
      }
      buttons();
    }
    async function request(path, command, captured = {id: contextId, generation}) {
      const result = await api(path, command, captured.id);
      if (captured.id !== connection().id || captured.generation !== generation || !ready() || result.connectionId !== captured.id) return null;
      if (result.policyId != null && policy !== null && policy !== result.policyId) {
        clearData();
      }
      if (result.policyId != null) policy = result.policyId;
      return result;
    }
    function failure(error, target = 'diagnostic') {
      if ([401, 403, 503].includes(error.status) || /privacy settings changed/i.test(error.message)) { clearData(); follow = false; }
      const message = error.status === 404 && /unknown.*endpoint/i.test(error.message) ? 'Update the app’s QaLens SDK for these data tools, then reconnect.' : error.message;
      if (target === 'sql') sqlState(message); else state(`Read paused: ${message}`);
      buttons();
    }
    function buttons() {
      const connected = ready(), busy = workBusy();
      for (const id of ['diagnostic-refresh', 'diagnostic-follow', 'values-baseline']) $(id).disabled = !connected || busy || readBusy;
      $('diagnostic-follow').textContent = follow ? 'Pause live' : 'Follow live';
      $('diagnostic-follow').setAttribute('aria-pressed', String(follow));
      for (const id of ['sql-run', 'sql-tables', 'sql-save']) $(id).disabled = !connected || busy || sqlWorking || sqlStarting || !!sqlJob || !catalog?.databases?.length;
      $('sql-rescan').disabled = !connected || busy || sqlWorking || sqlStarting || !!sqlJob;
      $('sql-cancel').disabled = !sqlJob || !connected || !!sqlJob.cancelling;
      $('sql-database').disabled = sqlStarting || !!sqlJob || !connected;
      $('data-files').disabled = !connected || busy;
    }
    function table(headers) {
      const element = node('table', '', 'evidence-table'), head = node('thead'), tr = node('tr'), body = node('tbody');
      for (const title of headers) tr.append(node('th', title)); head.append(tr); element.append(head, body); return {element, body};
    }
    function render() {
      const host = $('diagnostic-content'), nearEnd = host.scrollHeight - host.scrollTop - host.clientHeight < 50, top = host.scrollTop;
      host.replaceChildren();
      const search = $('diagnostic-search').value.trim().toLowerCase(), errors = $('diagnostic-errors').checked;
      $('diagnostic-errors-label').hidden = activeView === 'values'; $('values-baseline').hidden = activeView !== 'values';
      if (activeView === 'values') {
        if (!valuesSnapshot) { host.append(node('p', 'Refresh to read decoded app values.', 'empty')); return; }
        const rows = values.rows(search), view = table(['Pin', 'Source / field', 'Current value / comparison']);
        for (const field of rows) {
          const tr = node('tr', '', field.changed || field.added ? 'value-changed' : ''), pin = node('td'), name = node('td'), value = node('td');
          button(field.pinned ? 'Unpin' : 'Pin', () => { values.pin(field.id); render(); }, pin, !field.pinned && values.pins.size === 10);
          name.append(node('strong', field.source), node('div', field.key));
          value.append(node('div', field.value === undefined ? 'Not exposed in this snapshot' : field.value));
          if (field.changed) value.append(node('small', `Before: ${field.before}`, 'value-before'));
          else if (field.added) value.append(node('small', 'New since comparison baseline', 'value-before'));
          const sourceStatus = valuesSnapshot.sourceStatus?.[field.source];
          if (sourceStatus) name.append(node('small', `${sourceStatus.phase} · received ${time(sourceStatus.updatedAtMillis)}`, 'helper'));
          tr.append(pin, name, value); view.body.append(tr);
        }
        host.append(view.element);
        if (!rows.length) host.append(node('p', search ? 'No matching exposed values.' : 'No decoded fields connected. Ask the developer to map the existing DataStore Flow with QaLens.observeDataStoreValues, or expose cached Room/app state with registerDataSource.', 'empty'));
        for (const [name, source] of Object.entries(valuesSnapshot.sourceStatus || {}).filter(([name]) => !valuesSnapshot.dataSources?.[name])) host.append(node('p', `${name}: ${source.phase} · no exposed fields`, 'helper'));
        $('diagnostic-coverage').textContent = `${valuesSnapshot.coverage}. ${valuesSnapshot.omittedSources || 0} sources / ${valuesSnapshot.omittedFields || 0} fields omitted. Pin up to ten fields; comparison starts at the first read. File-only DataStore is metadata in Data tools.`;
      } else {
        const items = activeView === 'network' ? observations?.network : (observations?.logs || observations?.events?.filter(e => e.type === 'LOG'));
        const matched = (items || []).filter(e => (!errors || (activeView === 'network' ? isNetworkError(e) : isLogError(e))) && JSON.stringify(e).toLowerCase().includes(search));
        const view = table(activeView === 'network' ? ['Time', 'Request', 'Status / duration', 'Details'] : ['Time', 'Tag', 'Message']);
        for (const event of matched) {
          const tr = node('tr', '', (activeView === 'network' ? isNetworkError(event) : isLogError(event)) ? 'evidence-error' : '');
          tr.append(node('td', time(event.time)));
          if (activeView === 'network') {
            tr.append(node('td', `${event.method} ${event.url}`), node('td', `${event.error ? 'ERR' : event.status || '—'} · ${event.durationMs || 0} ms`));
            const detail = node('td'), disclosure = node('details'); disclosure.append(node('summary', 'Read details'));
            disclosure.append(node('pre', [`Error: ${event.error || 'none'}`, `Connection: ${event.connectivity || 'unknown'}`, `Request: ${event.requestBytes || 0} B · Response: ${event.responseBytes || 0} B`, `Request preview: ${event.requestPreview ?? 'not captured'}`, `Response preview: ${event.responsePreview ?? 'not captured'}`].join('\n')));
            detail.append(disclosure); tr.append(detail);
          } else tr.append(node('td', event.tag || '—'), node('td', event.message || ''));
          view.body.append(tr);
        }
        host.append(view.element);
        if (!matched.length) host.append(node('p', observations ? 'No matching retained observations. Network/log hooks must be connected in the app.' : 'Refresh to read recent observations.', 'empty'));
        const omitted = activeView === 'network' ? observations?.omittedNetwork : (observations?.omittedLogs ?? observations?.omittedEvents);
        $('diagnostic-coverage').textContent = `${matched.length} matches · at most 100 retained observations · ${omitted || 0} older dashboard entries omitted. Earlier dashboard evictions are not counted; this is not complete recording coverage. Errors in Logs are inferred from message/tag text.`;
      }
      host.scrollTop = follow && nearEnd && activeView !== 'values' ? host.scrollHeight : top;
    }
    async function refreshLive(manual = false) {
      sync(); if (!ready() || readBusy || workBusy() || !liveVisible() || !manual && !follow) return;
      const captured = {id: contextId, generation}, view = activeView; readBusy = true; buttons();
      try {
        const result = await request(view === 'values' ? 'data' : 'events', undefined, captured);
        if (!result || !liveVisible() || view !== activeView) return;
        if (view === 'values') { valuesSnapshot = result; values.update(result); } else observations = result;
        state(`${follow ? 'Live' : 'Paused snapshot'} · read ${time(Date.now())}${view === 'values' ? ' · decoded host fields' : ' · recent dashboard evidence'}`); render();
      } catch (error) { if (captured.generation === generation) { follow = false; failure(error); } }
      finally { readBusy = false; buttons(); if (view !== activeView && liveVisible() && captured.generation === generation) void refreshLive(true); }
    }
    async function loadCatalog() {
      sync(); if (!ready() || sqlWorking || sqlStarting || sqlJob) return;
      sqlWorking = true; const captured = {id: contextId, generation}; buttons();
      try {
        const result = await request('sql', undefined, captured); if (!result || !visible('data-tools')) return;
        catalog = result; const previous = $('sql-database').value; $('sql-database').replaceChildren();
        for (const db of result.databases) { const option = node('option', db.name); option.value = db.id; $('sql-database').append(option); }
        if (result.databases.some(db => db.id === previous)) $('sql-database').value = previous;
        if (!result.databases.length) $('sql-database').append(node('option', 'No SQLite databases found'));
        $('sql-saved').replaceChildren();
        for (const query of result.saved) {
          const card = node('div', '', 'selector-card'); card.append(node('strong', query.name), node('small', ` · ${query.databaseName}`, 'helper'), node('pre', query.sql));
          const actions = node('div', '', 'toolbar');
          button(query.masked ? 'Contains masked text' : 'Load query', () => {
            if (!ready() || captured.generation !== generation) return;
            $('sql-database').value = query.database; $('sql-query').value = query.sql; $('sql-name').value = '';
            $('sql-results').replaceChildren();
            sqlState('Query loaded. Click Run read query to execute; desktop rejects writes.');
          }, actions, query.masked || !result.databases.some(db => db.id === query.database));
          button('Delete saved query', () => {
            if (captured.generation === generation && captured.id === contextId) return savedAction({action: 'delete', id: query.id});
          }, actions);
          card.append(actions); $('sql-saved').append(card);
        }
        if (!result.saved.length) $('sql-saved').append(node('p', 'No saved queries yet.', 'helper'));
        sqlState(`${result.databases.length} databases · ${result.saved.length} saved queries · read-only · ten-second deadline`);
      } catch (error) { if (captured.generation === generation) failure(error, 'sql'); }
      finally { if (captured.generation === generation) sqlWorking = false; buttons(); }
    }
    async function startQuery(sql = $('sql-query').value) {
      sync(); if (!ready() || sqlStarting || sqlWorking || sqlJob || !visible('data-tools')) return;
      const database = $('sql-database').value, captured = {id: contextId, generation};
      if (!database || !sql.trim()) { sqlState('Choose a database and enter a SELECT query.'); return; }
      sqlStarting = true; $('sql-results').replaceChildren(); sqlState('Starting read…'); buttons();
      try {
        const result = await api('sql', {action: 'start', database, sql}, captured.id);
        if (captured.generation !== generation || captured.id !== connection().id || !visible('data-tools') || result.connectionId !== captured.id) {
          if (result.id && connection().id === captured.id) void api('sql', {action: 'cancel', id: result.id}, captured.id).catch(() => {});
          return;
        }
        sqlJob = {id: result.id, connectionId: captured.id, generation: captured.generation};
        sqlState('Reading on the phone… You can cancel while other desktop controls remain available.');
      } catch (error) { if (captured.generation === generation) failure(error, 'sql'); }
      finally { if (captured.generation === generation) sqlStarting = false; buttons(); }
      void pollSql();
    }
    function renderSql(result) {
      const host = $('sql-results'); host.replaceChildren(); const view = table(result.columns);
      for (const row of result.rows) { const tr = node('tr'); for (const cell of row) tr.append(node('td', cell)); view.body.append(tr); }
      host.append(view.element);
      sqlState(`${result.rows.length} preview rows · ${result.durationMs} ms${result.limited ? ' · preview truncated / bounded' : ''}${result.omittedColumns ? ` · ${result.omittedColumns} columns omitted` : ''}. Cells are masked by credential-like keys and current text rules, then bounded.`);
      if (result.columns[0] === 'name' && result.columns[1] === 'type') {
        const actions = node('div', '', 'toolbar'); host.prepend(actions);
        for (const row of result.rows.filter(row => row[1] === 'table' || row[1] === 'view').slice(0, 30)) button(`Read ${row[0]}`, () => {
          $('sql-query').value = tableQuery(row[0]); void startQuery();
        }, actions);
      }
    }
    async function pollSql() {
      const job = sqlJob; if (!job || sqlPolling || !visible('data-tools') || !ready()) return;
      sqlPolling = true;
      try {
        const result = await request('sql', {action: 'status', id: job.id}, {id: job.connectionId, generation: job.generation});
        if (!result || sqlJob !== job || !visible('data-tools')) return;
        if (['running', 'cancelling'].includes(result.phase)) sqlState(result.phase === 'cancelling' ? 'Cancelling read…' : 'Reading on the phone…');
        else { sqlJob = null; if (result.phase === 'complete' && result.result) renderSql(result.result); else sqlState(result.error || `Read ${result.phase}`); }
      } catch (error) { if (job.generation === generation) { sqlJob = null; failure(error, 'sql'); } }
      finally { sqlPolling = false; buttons(); }
    }
    async function cancelOwned() {
      const job = sqlJob; sqlJob = null;
      if (job && connection().id === job.connectionId && ready()) {
        try { await api('sql', {action: 'cancel', id: job.id}, job.connectionId); } catch (_) { /* Native deadline remains. */ }
      }
    }
    async function savedAction(command) {
      sync(); if (!ready() || sqlWorking || sqlStarting || sqlJob || !visible('data-tools')) return;
      const captured = {id: contextId, generation}; sqlWorking = true; buttons();
      try { if (await request('sql', command, captured)) { sqlWorking = false; await loadCatalog(); } }
      catch (error) { if (captured.generation === generation) failure(error, 'sql'); }
      finally { if (captured.generation === generation) sqlWorking = false; buttons(); }
    }
    async function files() {
      sync(); if (!ready()) return; const captured = {id: contextId, generation};
      try {
        const result = await request('data', {action: 'files'}, captured); if (!result || !visible('data-tools')) return;
        const host = $('data-file-list'); host.replaceChildren(); host.append(node('p', result.coverage, 'helper'));
        for (const file of result.preferences) button(`Read prefs: ${file.name}`, async () => {
          try {
            const data = await request('data', {action: 'preferences', id: file.id}, captured); if (!data || !visible('data-tools')) return;
            const display = $('preference-values'); display.replaceChildren(node('p', `${data.name}: ${data.coverage}`, 'helper'));
            const view = table(['Field', 'Snapshot value']);
            for (const [key, value] of Object.entries(data.values)) { const tr = node('tr'); tr.append(node('td', key), node('td', value)); view.body.append(tr); }
            if (!data.encrypted) display.append(view.element);
          } catch (error) { if (captured.generation === generation) failure(error, 'sql'); }
        }, host);
        for (const file of result.dataStore) host.append(node('p', `DataStore: ${file.name} · ${file.size} B · metadata only`, 'helper'));
        if (!result.preferences.length && !result.dataStore.length) host.append(node('p', 'No storage files found in standard app directories. Custom stores can still expose decoded fields.', 'helper'));
      } catch (error) { if (captured.generation === generation) failure(error, 'sql'); }
    }
    for (const item of doc.querySelectorAll('[data-diagnostic]')) item.onclick = () => {
      activeView = item.dataset.diagnostic;
      for (const other of doc.querySelectorAll('[data-diagnostic]')) other.classList.toggle('active', other === item);
      render(); void refreshLive(true);
    };
    $('live-diagnostics').addEventListener('toggle', () => { if ($('live-diagnostics').open) void refreshLive(true); });
    $('diagnostic-refresh').onclick = () => refreshLive(true);
    $('diagnostic-follow').onclick = () => { follow = !follow; buttons(); if (follow) void refreshLive(true); else state('Paused · showing the last snapshot'); };
    $('diagnostic-search').oninput = render; $('diagnostic-errors').onchange = render;
    $('values-baseline').onclick = () => { values.compare(); render(); state('Comparison baseline updated to current exposed values'); };
    $('sql-rescan').onclick = loadCatalog; $('sql-run').onclick = () => startQuery();
    $('sql-database').onchange = () => { $('sql-results').replaceChildren(); sqlState('Database changed. Run a read query for this database.'); };
    $('sql-tables').onclick = () => { $('sql-query').value = "SELECT name, type FROM sqlite_master WHERE type IN ('table', 'view') ORDER BY name"; return startQuery(); };
    $('sql-cancel').onclick = async () => {
      const job = sqlJob; if (!job || job.cancelling) return; job.cancelling = true; buttons();
      try { await request('sql', {action: 'cancel', id: job.id}, {id: job.connectionId, generation: job.generation}); if (job === sqlJob) sqlState('Cancelling read…'); }
      catch (error) { if (job === sqlJob) { job.cancelling = false; failure(error, 'sql'); } } void pollSql();
    };
    $('sql-save').onclick = () => savedAction({action: 'save', name: $('sql-name').value.trim(), database: $('sql-database').value, sql: $('sql-query').value});
    $('data-files').onclick = files;
    doc.addEventListener('visibilitychange', () => { if (doc.hidden) { void cancelOwned(); ++generation; sqlStarting = false; sqlWorking = false; buttons(); } });
    interval(() => { void refreshLive(); }, 2000); interval(() => { void pollSql(); }, 600); sync();
    return {sync, buttons, open() { navigate('landing'); $('live-diagnostics').open = true; $('live-diagnostics').scrollIntoView({block: 'nearest', behavior: 'smooth'}); void refreshLive(true); },
      pageChanged(id) { if (id !== 'data-tools') { void cancelOwned(); ++generation; sqlStarting = false; sqlWorking = false; $('sql-results').replaceChildren(); $('preference-values').replaceChildren(); }
        else void loadCatalog(); buttons(); }};
  }
  return {Values, clipLabel, isLogError, isNetworkError, tableQuery, install};
});
