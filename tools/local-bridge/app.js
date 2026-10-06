'use strict';
const $ = id => document.getElementById(id);
let autoConnectAttempted = false;
let selectionGeneration = 0, previewGeneration = 0, previewEnabled = false, previewBusy = false, previewUrl = '', connectPolling = false, discoveredApps = [], liveSelecting = false;
let snapshot = null, selected = null, busy = false, session = '', workbench = null, component = null, previews = [], activeProfile = null, polling = false, savedHashes = new Set();
let selectorBundle = null, queryGeneration = 0, selectionPolling = false, lastPhoneSelection = null, choosing = 0;
let highlightQueue = Promise.resolve();
let mirrorMode = 'control', mirrorInspect = false, mirrorInputBusy = false, mirrorPointer = null, mirrorFrame = null;
let captureState = null, captureConnection = null, capturePolling = false, phoneArchiveList = [], pendingReplay = null;
let screenshotUrl = '', screenshotBlob = null;
let mirrorModeGeneration = 0;
let lastPreviewRequest = 0, treeRefreshTimer = null;
let captureChecked = false, captureIssue = '';
let diagnostics = null;
let mirrorStream = null;
const legacyMirror = () => workbench?.mirrorBackend === 'legacy';
const status = text => { $('status').textContent = text; };
async function api(path, command, connectionId = workbench?.connectionId) {
  const headers = {'X-Qalens-Session': session};
  if (connectionId) headers['X-Qalens-Connection'] = connectionId;
  if (command) headers['Content-Type'] = 'application/json';
  const response = await fetch(`/api/${path}`, {method: command ? 'POST' : 'GET', headers, body: command ? JSON.stringify(command) : undefined, cache: 'no-store'});
  const result = await response.json();
  if (!response.ok || result.ok === false) { const error = Error(result.error || `HTTP ${response.status}`); error.status = response.status; throw error; }
  return result;
}
async function perform(work) {
  if (busy) return;
  busy = true; document.querySelectorAll('button:not([data-tab]):not(#back)').forEach(b => { b.disabled = true; });
  try { await work(); } catch (error) { status(error.message); }
  finally { busy = false; document.querySelectorAll('button').forEach(b => { b.disabled = false; }); componentButtons(); $('back').disabled = location.hash === '#landing'; void recordingTransfer.poll(); }
}
const pageNames = {landing: 'Landing', 'data-tools': 'Data tools', devices: 'Device tools', library: 'Saved elements', automation: 'Automation', recordings: 'Recordings', replay: 'Replay'};
function tab(id, push = true) {
  if (!pageNames[id]) { id = 'landing'; if (!push) history.replaceState(history.state, '', '#landing'); }
  if (id !== 'landing' && previewEnabled) void stopPreview();
  if (!['landing', 'recordings'].includes(id)) pendingReplay = null;
  if (push && location.hash !== `#${id}`) history.pushState({qalens: true}, '', `#${id}`);
  document.querySelectorAll('.page').forEach(page => { page.hidden = page.id !== id; });
  document.querySelectorAll('[data-tab]').forEach(button => button.classList.toggle('active', button.dataset.tab === id));
  $('page-label').textContent = pageNames[id];
  document.body.classList.toggle('replaying', id === 'replay');
  window.scrollTo({top: 0});
  $('back').disabled = id === 'landing';
  $('viewer').contentWindow?.postMessage({type: 'qalens-visibility', visible: id === 'replay'}, location.origin);
  diagnostics?.pageChanged(id);
}
document.querySelectorAll('[data-tab]').forEach(button => { button.onclick = () => tab(button.dataset.tab); });
$('back').onclick = () => { if (history.state?.qalens) history.back(); else tab('landing', false); };
window.addEventListener('popstate', () => tab(location.hash.slice(1), false));
history.replaceState({qalens: false}, '', location.hash || '#landing');
tab(location.hash.slice(1), false);
function button(text, onclick, parent, className = '') {
  const element = document.createElement('button'); element.textContent = text; element.onclick = onclick; element.className = className; parent.append(element); return element;
}
async function refresh() {
  const fresh = await api('snapshot');
  if (fresh.connectionId !== workbench?.connectionId) return;
  snapshot = fresh; selected = snapshot.nodes.find(n => n.id === selected?.id) || null;
  $('screen').textContent = `${snapshot.screen} · ${snapshot.nodes.length} visible nodes`;
  status(`${snapshot.nodes.length} visible elements · ${snapshot.omittedNodes} omitted. Select any element to read its attributes.`);
  render(); details();
  renderRoles();
  if (selected && !liveSelecting) await choose(selected, 'refresh');
}
function filtered() {
  const needle = $('search').value.trim().toLowerCase(), action = $('action-filter').value, role = $('role-filter').value;
  return (snapshot?.nodes || []).filter(n => (!$('tagged').checked || n.tag) && (!role || n.role === role) &&
    (!action || (action === 'none' ? !n.actions.length : n.actions.includes(action))) &&
    [n.tag, n.label, n.role, n.state, ...(n.text || []), ...(n.description || []), ...n.actions].filter(Boolean).join(' ').toLowerCase().includes(needle));
}
function renderRoles() {
  const current = $('role-filter').value; $('role-filter').replaceChildren();
  for (const role of ['', ...new Set((snapshot?.nodes || []).map(n => n.role).filter(Boolean))]) {
    const option = document.createElement('option'); option.value = role; option.textContent = role || 'Any role'; $('role-filter').append(option);
  }
  if ([...$('role-filter').options].some(o => o.value === current)) $('role-filter').value = current;
}
function render() {
  $('tree').replaceChildren(); $('map').replaceChildren();
  $('search-count').textContent = snapshot ? `${filtered().length} matches / ${snapshot.nodes.length} visible elements${snapshot.omittedNodes ? ` · ${snapshot.omittedNodes} omitted` : ''}` : 'Connect and refresh to search.';
  if (!snapshot) { clearSelectors(); return; }
  const viewport = snapshot.screenViewport || snapshot.viewport;
  $('map').setAttribute('viewBox', `0 0 ${viewport.width} ${viewport.height}`);
  const frame = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
  frame.setAttribute('width', viewport.width); frame.setAttribute('height', viewport.height); frame.setAttribute('class', 'viewport'); $('map').append(frame);
  const parents = new Map(snapshot.nodes.map(n => [n.id, n.parentId]));
  for (const node of filtered()) {
    let depth = 0, ancestor = node.parentId; const seen = new Set();
    while (ancestor && !seen.has(ancestor) && depth < 12) { seen.add(ancestor); depth++; ancestor = parents.get(ancestor); }
    const element = button(`${node.tag ? `[${node.tag}] ` : ''}${node.label} · ${node.actions.join(', ') || node.role || 'node'}`, () => choose(node), $('tree'), `node${selected?.id === node.id ? ' selected' : ''}`);
    element.style.paddingInlineStart = `${10 + depth * 12}px`;
  }
  // Inspection exposes the whole visible tree even while the side tree is filtered.
  for (const node of snapshot.nodes) {
    const rect = document.createElementNS('http://www.w3.org/2000/svg', 'rect'), b = node.bounds;
    rect.setAttribute('x', b.left + (snapshot.viewport.originX || 0)); rect.setAttribute('y', b.top + (snapshot.viewport.originY || 0)); rect.setAttribute('width', b.right-b.left); rect.setAttribute('height', b.bottom-b.top); rect.setAttribute('class', selected?.id === node.id ? 'selected' : '');
    const title = document.createElementNS('http://www.w3.org/2000/svg', 'title'); title.textContent = node.tag || node.label;
    rect.append(title); rect.onclick = event => {
      // Live preview resolves the smallest current element once in the parent click handler.
      if (!previewEnabled) { event.stopPropagation(); void choose(node); }
    }; $('map').append(rect);
  }
}
async function choose(node, origin = 'pc') {
  if (!snapshot || snapshot.connectionId !== workbench?.connectionId) { status('Connection changed. Refresh the tree before selecting.'); return; }
  const generation = ++selectionGeneration, connectionId = workbench?.connectionId;
  choosing++;
  selected = node; component = null; clearSelectors(); render(); details(); componentButtons();
  $('component-title').textContent = node.tag || node.label || 'Component';
  $('component-meta').textContent = 'Loading all public attributes…';
  $('attributes').replaceChildren(); $('semantic-attributes').replaceChildren();
  $('attributes-empty').hidden = false; $('attributes-empty').textContent = 'Loading attributes…';
  try {
    if (origin === 'pc' && $('link-selections').checked) {
      // Inspection only: choosing a node must never tap/type/scroll the host.
      try {
        const highlight = highlightQueue.catch(() => {}).then(async () => {
          if (generation !== selectionGeneration || connectionId !== workbench?.connectionId || !$('link-selections').checked) return;
          await api('command', {action: 'select', id: node.id});
          if (generation === selectionGeneration) {
            lastPhoneSelection = node.id;
            ++mirrorModeGeneration; mirrorInspect = true; mirrorPointer = null; mirrorFrame = null; mirrorUi();
            if (previewEnabled) await syncMirrorMode(connectionId);
          }
        });
        highlightQueue = highlight; await highlight;
      }
      catch (error) { status(`Selected on web. Phone highlight unavailable: ${error.message}`); }
      if (generation !== selectionGeneration || connectionId !== workbench?.connectionId) return;
    }
    const result = await api('component', {id: node.id});
    if (generation !== selectionGeneration || connectionId !== workbench?.connectionId) return;
    showComponent(result.document, true); await loadSelectors(node.id, generation, connectionId); await loadPreviews();
  } catch (error) {
    if (generation !== selectionGeneration) return;
    $('component-meta').textContent = error.message; $('attributes-empty').textContent = 'Refresh the tree and select again.';
    status(error.message);
  } finally { choosing--; }
}
function details() {
  $('summary').textContent = selected ? `${selected.label}\nTag: ${selected.tag || 'none'} · ${selected.role || 'Component'} · ${selected.enabled ? 'Enabled' : 'Disabled'}\n${selected.bounds.right-selected.bounds.left} × ${selected.bounds.bottom-selected.bounds.top} px` : 'Choose a tree node or a rectangle.';
  $('details').textContent = selected ? JSON.stringify(selected, null, 2) : 'No selection'; $('actions').replaceChildren();
  if (!selected) return;
  for (const action of ['select', ...selected.actions]) {
    button(action === 'select' ? 'Highlight on phone' : action, () => perform(async () => {
      if (snapshot?.connectionId !== workbench?.connectionId) throw Error('Connection changed. Refresh the tree first.');
      const command = {action, id: selected.id};
      if (action === 'type') { const text = prompt('Set field text (sent to the host)'); if (text === null) return; command.text = text; }
      if (action === 'scroll') { const dy = prompt('Vertical scroll pixels (positive = forward)', '400'); if (dy === null) return; command.dy = Number(dy); if (!Number.isFinite(command.dy)) throw Error('Enter a finite scroll delta'); }
      await api('command', command); await refresh();
    }), $('actions'));
  }
}
function row(name, value, target = 'attributes') {
  const tr = document.createElement('tr'), key = document.createElement('td'), val = document.createElement('td');
  key.textContent = name; val.textContent = typeof value === 'string' ? value : JSON.stringify(value, null, 2); tr.append(key, val); $(target).append(tr);
}
function componentButtons() { connectionButtons(); diagnostics?.buttons(); $('copy-tag').disabled = !(component?.content.component.tag || selected?.tag) || busy; $('save').disabled = !component || busy; $('download').disabled = !component || busy; $('run').disabled = !component || busy || !$('pipeline').value || !savedHashes.has(component.hash); $('download-tree').disabled = !selectorBundle || busy; $('export-selectors').disabled = !selectorBundle || busy; }
function showComponent(entry, live = false) {
  if (!live) { ++selectionGeneration; selected = null; clearSelectors(); render(); details(); }
  component = entry;
  const data = entry.content, node = data.component;
  $('component-title').textContent = node.label || node.tag || 'Component';
  $('component-meta').textContent = `${data.package || 'Imported component'} · ${new Date(entry.capturedAtMillis || Date.now()).toLocaleTimeString()} · ${entry.hash.slice(0, 12)}`;
  $('component-meta').title = `SHA-256 ${entry.hash}`;
  $('summary').textContent = `${node.tag ? `Tag: ${node.tag}\n` : ''}${node.label || ''} · ${node.role || 'Component'}\n${node.enabled === false ? 'Disabled' : 'Enabled'}${live ? ' · Live selection' : ' · Captured snapshot'}`;
  $('attributes-empty').hidden = true;
  $('component-context').replaceChildren();
  const context = documentElement('div', 'card');
  context.textContent = `Tree position: ${(data.tree.path || []).map(p => `${p.tag || p.role || 'node'} [${p.siblingIndex}]`).join(' › ')}\n${data.coverage || 'Imported file: redaction and coverage are determined by its producer.'}`;
  $('component-context').append(context); $('attributes').replaceChildren(); $('semantic-attributes').replaceChildren();
  $('component-map').replaceChildren();
  const bounds = node.bounds, viewport = data.viewport;
  if (bounds && viewport && [viewport.width, viewport.height, bounds.left, bounds.top, bounds.right, bounds.bottom].every(Number.isFinite) && viewport.width > 0 && viewport.height > 0) {
    $('component-map').setAttribute('viewBox', `0 0 ${viewport.width} ${viewport.height}`);
    const rect = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
    rect.setAttribute('x', bounds.left); rect.setAttribute('y', bounds.top);
    rect.setAttribute('width', Math.max(0, bounds.right - bounds.left)); rect.setAttribute('height', Math.max(0, bounds.bottom - bounds.top));
    rect.setAttribute('class', 'selected'); $('component-map').append(rect);
  }
  for (const [name, value] of Object.entries(node)) if (name !== 'attributes') row(name, value);
  for (const attribute of node.attributes || []) row(attribute.name, attribute.value === null ? attribute.coverage : attribute.value, 'semantic-attributes');
  row('Viewport', data.viewport || null);
  $('component-json').textContent = JSON.stringify(entry, null, 2);
  $('pipeline-component').textContent = `Current: ${node.tag || node.label || 'Component'} · ${entry.hash}`; componentButtons();
}
function documentElement(tag, className) { const node = document.createElement(tag); node.className = className; return node; }
async function loadPreviews() {
  previews = (await api('previews')).documents; renderPreviews();
}
function renderPreviews() {
  $('previews').replaceChildren();
  for (const document of previews) button(document.content.component.tag || document.content.component.label || document.hash.slice(0, 12), () => showComponent(document), $('previews'));
}
async function loadWorkbench() {
  workbench = await api('workbench');
  const connection = workbench.connection;
  if (captureConnection !== workbench.connectionId || !workbench.connected) resetCapture();
  recordingTransfer.checkConnection(workbench.connectionId, workbench.connected);
  diagnostics?.sync();
  document.querySelector('[data-adb="mirror"]').title = workbench.scrcpyAvailable ? 'Start installed scrcpy' : 'Install scrcpy to enable this tool';
  $('auto-connect').checked = workbench.preferences.autoConnect;
  $('fast-mirror').textContent = 'Open desktop window ↗';
  connectionStatus(workbench); connectionButtons();
  $('storage').textContent = `Local storage: ${workbench.dataDir}`;
  $('profiles').replaceChildren();
  for (const profile of workbench.profiles) button(`${profile.name} · ${profile.serial}`, () => fillProfile(profile), $('profiles'));
  const current = $('pipeline').value; $('pipeline').replaceChildren();
  for (const pipeline of workbench.pipelines) { const option = document.createElement('option'); option.value = pipeline.id; option.textContent = pipeline.name; $('pipeline').append(option); }
  if (!workbench.pipelines.length) { const option = document.createElement('option'); option.value = ''; option.textContent = 'Start with --pipeline-config'; $('pipeline').append(option); }
  if (workbench.pipelines.some(p => p.id === current)) $('pipeline').value = current;
  $('jobs').replaceChildren();
  for (const job of [...workbench.jobs].reverse()) {
    const card = documentElement('div', 'job'); card.textContent = `${job.pipeline} · ${job.status}\nComponent: ${job.hash}\nOutput: ${job.output}${job.error ? `\n${job.error}` : ''}\nSteps: ${job.steps.map(s => s.exitCode).join(', ') || 'running'}`;
    card.style.whiteSpace = 'pre-wrap'; $('jobs').append(card);
    button('View output files', () => perform(async () => {
      const result = await api('artifacts', {job: job.id});
      const files = documentElement('div', 'toolbar'); card.append(files);
      for (const file of result.files) button(`${file.name} (${file.size} bytes)`, () => perform(async () => {
        const output = await api('artifact', {job: job.id, name: file.name});
        const preview = documentElement('pre', ''); preview.textContent = output.text; card.append(preview);
        button(`Download ${file.name}`, () => download(output.name, output.text), card);
      }), files);
    }), card);
  }
  componentButtons();
}
function formProfile() { return {name: $('profile-name').value || $('package').value, serial: $('serial').value, package: $('package').value, activity: $('activity').value, platformVersion: $('platform-version').value, devicePort: Number($('device-port').value)}; }
function fillProfile(profile) {
  activeProfile = profile;
  if (![...$('serial').options].some(option => option.value === profile.serial)) {
    const option = document.createElement('option'); option.value = profile.serial; option.textContent = `${profile.serial} · saved`; $('serial').append(option);
  }
  $('serial').value = profile.serial; $('profile-name').value = profile.name; $('package').value = profile.package; $('activity').value = profile.activity;
  $('platform-version').value = profile.platformVersion; $('device-port').value = profile.devicePort;
  $('profile-token').value = ''; tab('devices'); status('Saved profile loaded. Use Connect on Landing, or Advanced manual pairing for an older SDK.');
}
async function saved() {
  const data = await api('saved'); savedHashes = new Set(data.items.map(item => item.hash)); $('saved').replaceChildren(); componentButtons();
  if (!data.items.length) $('saved').textContent = 'No files saved yet. Capture a component, review it, then Save JSON.';
  for (const item of data.items) button(`${item.component} · ${item.package || 'imported'} · ${item.hash.slice(0, 16)}`, () => perform(async () => {
    showComponent((await api('document', {hash: item.hash})).document); tab('landing'); status('Opened saved snapshot. Live commands require a fresh tree selection.');
  }), $('saved'), 'node');
  if (data.omitted) status(`Library shows 500 files; ${data.omitted} older files remain on disk.`);
}
$('refresh').onclick = () => perform(refresh); $('search').oninput = render; $('tagged').onchange = render;
$('action-filter').onchange = render; $('role-filter').onchange = render;
$('events').onclick = () => diagnostics?.open();
$('save').onclick = () => perform(async () => {
  await api('import', {document: component}); // saved snapshots can be re-opened after preview eviction
  const result = await api('save', {hash: component.hash}); status(result.duplicate ? `Already saved · ${result.file}` : `Saved · ${result.file}`); await saved(); savedHashes.add(component.hash);
});
function download(name, text) {
  const url = URL.createObjectURL(new Blob([text], {type: 'application/octet-stream'}));
  const link = document.createElement('a'); link.href = url; link.download = name; document.body.append(link); link.click(); link.remove(); setTimeout(() => URL.revokeObjectURL(url), 10000);
}
$('download').onclick = () => {
  if (!component) return;
  download(`${component.hash}.json`, JSON.stringify(component) + '\n');
  status('JSON download requested. Use Save JSON to keep a file in PC storage.');
};
$('import-component').onchange = event => perform(async () => {
  const file = event.target.files[0]; if (!file) return;
  if (file.size > 256 * 1024 + 4096) throw Error('Component file exceeds 256 KiB');
  const result = await api('import', {document: JSON.parse(await file.text())}); await loadPreviews(); showComponent(result.document);
  status('Imported into memory. Review values before saving; imported data follows its producer’s privacy rules.'); event.target.value = '';
});
$('profile-form').onsubmit = event => { event.preventDefault(); perform(async () => { const result = await api('profile', {profile: formProfile()}); activeProfile = result.profile; await loadWorkbench(); status(result.notice); }); };
$('scan').onclick = () => perform(async () => {
  const data = await api('devices', {}), current = $('serial').value; $('serial').replaceChildren();
  for (const device of data.devices) { const option = document.createElement('option'); option.value = device.serial; option.textContent = `${device.serial} · ${device.state} · ${device.description}`; option.disabled = device.state !== 'device'; $('serial').append(option); }
  if (data.devices.some(d => d.serial === current)) $('serial').value = current;
  status(data.devices.length ? 'Devices scanned. Unauthorized phones need USB debugging approval on the phone.' : 'No adb devices found.');
});
$('packages-button').onclick = () => perform(async () => {
  const data = await api('packages', {serial: $('serial').value}); $('packages').replaceChildren();
  for (const value of data.packages) { const option = document.createElement('option'); option.value = value; $('packages').append(option); }
  status(`${data.packages.length} installed user packages available in the package field.`);
});
$('pair').onclick = () => perform(async () => {
  ++selectionGeneration;
  const result = await api('connect', {profile: formProfile(), token: $('profile-token').value});
  $('profile-token').value = ''; snapshot = null; selected = null; render(); details();
  resetRecordingTransfer(); await loadWorkbench(); tab('landing'); $('receive').checked = true;
  const expected = result.connection.platformVersion, actual = result.connection.actualPlatformVersion;
  status(`Forward connected. Start QaLens in the app, then refresh.${expected && expected !== actual ? ` Expected Android ${expected}; device is ${actual}.` : ''}`);
});
$('launch').onclick = () => perform(async () => { await api('launch', {}); status('Launch requested. App data preserved. Start its QaLens bridge if needed.'); });
$('disconnect').onclick = () => perform(async () => { await api('preferences', {autoConnect: false}); autoConnectAttempted = true; await stopPreview(); ++selectionGeneration; await api('disconnect', {}); resetRecordingTransfer(); $('receive').checked = false; $('profile-token').value = ''; snapshot = null; selected = null; render(); details(); clearComponent(); await loadWorkbench(); status('Disconnected; saved files and profiles remain.'); });
$('delete-profile').onclick = () => perform(async () => { if (!activeProfile) throw Error('Choose a saved profile first'); await api('profile/delete', {id: activeProfile.id}); activeProfile = null; await loadWorkbench(); status('Profile removed. App and saved components remain.'); });
$('import-profile').onclick = () => perform(async () => { const result = await api('profile', {profile: JSON.parse($('capabilities').value)}); fillProfile(result.profile); await loadWorkbench(); $('capabilities').value = ''; status(result.notice); });
$('reload-saved').onclick = () => perform(saved); $('reload-jobs').onclick = () => perform(loadWorkbench); $('pipeline').onchange = componentButtons;
$('run').onclick = () => perform(async () => {
  if (!savedHashes.has(component.hash)) throw Error('Save this component before running a pipeline');
  const result = await api('run', {hash: component.hash, pipeline: $('pipeline').value}); await loadWorkbench(); status(`Pipeline ${result.job.pipeline} started. Refresh results to check completion.`);
});
setInterval(async () => {
  if (!$('receive').checked || document.hidden || busy || polling || recordingTransfer.inflight) return;
  polling = true;
  try {
    const inbox = await api('inbox'); previews = inbox.documents; renderPreviews();
    if (inbox.connectionId !== workbench?.connectionId) return;
    if (inbox.received && previews.length && !choosing) {
      const entry = previews.at(-1), target = snapshot?.nodes.find(n => n.id === entry.liveNodeId);
      if (target && location.hash === '#landing' && $('link-selections').checked) await choose(target, 'phone');
      else showComponent(entry);
      status(`Received from phone · review attributes, then Save. PC preview omissions: ${inbox.omittedPreviews || 0}.${inbox.dropped ? ` ${inbox.dropped} older phone previews were dropped by the queue budget.` : ''}`);
    }
  } catch (error) {
    if ([401, 403, 409].includes(error.status)) { $('receive').checked = false; status(`${error.message} Reconnect and enable Receive again.`); }
    else status(`${error.message} Receive remains enabled and will check again.`);
  }
  finally { polling = false; }
}, 2500);

function clearSelectors() {
  selectorBundle = null; queryGeneration++;
  $('selector-candidates').replaceChildren(); $('query-matches').replaceChildren();
  $('selector-note').textContent = 'Choose a live element to generate selectors.';
  $('download-tree').disabled = true; $('export-selectors').disabled = true;
}
async function loadSelectors(id, generation, connectionId) {
  try {
    const result = await api('selectors', {id});
    if (generation !== selectionGeneration || connectionId !== workbench?.connectionId || result.connectionId !== connectionId) return;
    selectorBundle = result; $('selector-candidates').replaceChildren();
    $('selector-note').textContent = `${result.suggestions.length} selectors · ${result.omittedNodes ? `${result.omittedNodes} nodes omitted · counts are partial` : 'counts checked against the visible tree'}`;
    for (const suggestion of result.suggestions) {
      const card = documentElement('div', 'selector-card'), title = documentElement('strong', suggestion.matches === 1 ? '' : 'ambiguous');
      title.textContent = `${suggestion.title} · ${suggestion.matches} match${suggestion.matches === 1 ? '' : 'es'}`;
      const code = documentElement('code', ''); code.textContent = suggestion.xpath;
      const hint = documentElement('p', 'helper'); hint.textContent = suggestion.stability === 'position' ? 'Changes when visible tree position changes.' : suggestion.stability === 'content' ? 'Depends on displayed content and language.' : suggestion.matches === 1 ? 'Tag selector; revalidate after navigation.' : 'Scope to a parent to distinguish duplicates.';
      const actions = documentElement('div', 'toolbar');
      button('Copy XPath', () => perform(async () => { await navigator.clipboard.writeText(suggestion.xpath); status('QaLens XPath copied.'); }), actions);
      button('Check matches', () => { $('xpath-input').value = suggestion.xpath; $('selector-builder').open = true; perform(checkSelector); }, actions);
      card.append(title, code, hint, actions); $('selector-candidates').append(card);
    }
    $('xpath-input').value = result.suggestions[0]?.xpath || '';
    $('selector-attribute').value = selected?.tag ? 'tag' : 'label'; $('selector-value').value = selected?.tag || selected?.label || '';
    $('selector-parent').value = ''; $('selector-action').value = '';
    $('download-tree').disabled = false; $('export-selectors').disabled = false;
  } catch (error) {
    if (generation === selectionGeneration && connectionId === workbench?.connectionId) $('selector-note').textContent = error.status === 404 ? 'Element changed. Refresh the tree and select again.' : `Selectors unavailable: ${error.message}`;
  }
}
function xpathLiteral(value) {
  if (!value.includes("'")) return `'${value}'`;
  if (!value.includes('"')) return `"${value}"`;
  return `concat(${value.split("'").map(part => `'${part}'`).join(',"\'",')})`;
}
$('build-selector').onclick = () => {
  const value = $('selector-value').value, attribute = $('selector-attribute').value;
  if (!value) { status('Enter an exact attribute value.'); return; }
  const clauses = [`@${attribute}=${xpathLiteral(value)}`];
  if ($('selector-action').value) clauses.push(`@${$('selector-action').value}='true'`);
  $('xpath-input').value = `${$('selector-parent').value ? `//node[@tag=${xpathLiteral($('selector-parent').value)}]` : ''}//node[${clauses.join(' and ')}]`;
};
async function checkSelector() {
  const generation = ++queryGeneration, connectionId = workbench?.connectionId;
  if (!workbench?.connected) throw Error('Connect to check this selector against the phone.');
  const result = await api('query', {xpath: $('xpath-input').value});
  if (generation !== queryGeneration || result.connectionId !== connectionId || connectionId !== workbench?.connectionId) return;
  $('query-matches').replaceChildren();
  const count = documentElement('p', 'helper'); count.textContent = `${result.count} matches${result.omittedNodes ? ' · tree coverage is partial' : ''}${result.omittedMatches ? ` · showing ${result.nodes.length}` : ''}`; $('query-matches').append(count);
  for (const node of result.nodes) button(`${node.attributes.tag || 'No tag'} · ${node.attributes.label || node.attributes.role || 'Component'}`, () => perform(async () => {
    if (connectionId !== workbench?.connectionId) throw Error('Connection changed. Check the selector again.');
    const fresh = await api('snapshot'); if (fresh.connectionId !== connectionId) return;
    snapshot = fresh; renderRoles(); const target = fresh.nodes.find(n => n.id === node.id);
    if (!target) throw Error('Element changed. Check the selector again.');
    await choose(target);
  }), $('query-matches'), 'node');
  status(count.textContent);
}
$('query-selector').onclick = () => perform(checkSelector);
$('copy-xpath').onclick = () => perform(async () => { if (!$('xpath-input').value) throw Error('Build or choose a selector first.'); await navigator.clipboard.writeText($('xpath-input').value); status('QaLens XPath copied.'); });
$('download-tree').onclick = () => { if (selectorBundle) download('qalens-tree.xml', selectorBundle.xml); };
$('export-selectors').onclick = () => { if (selectorBundle) { const {connectionId, ...document} = selectorBundle; download('qalens-selectors.json', JSON.stringify(document, null, 2)); } };
async function syncPhoneSelection() {
  if (!$('link-selections').checked || !workbench?.connected || document.hidden || location.hash !== '#landing' || busy || choosing || liveSelecting || selectionPolling) return;
  selectionPolling = true;
  const connectionId = workbench.connectionId, generation = selectionGeneration;
  try {
    const result = await api('selection');
    if (connectionId !== workbench?.connectionId || result.connectionId !== connectionId || generation !== selectionGeneration || !$('link-selections').checked) return;
    if (!result.selectedId) {
      if (lastPhoneSelection && selected) { ++selectionGeneration; selected = null; render(); details(); clearComponent(); status('Phone selection cleared.'); }
      lastPhoneSelection = null; return;
    }
    if (result.selectedId === lastPhoneSelection || result.selectedId === selected?.id) { lastPhoneSelection = result.selectedId; return; }
    const fresh = await api('snapshot');
    if (fresh.connectionId !== connectionId || connectionId !== workbench?.connectionId || generation !== selectionGeneration || !$('link-selections').checked || document.hidden || location.hash !== '#landing') return;
    snapshot = fresh; renderRoles();
    const target = fresh.nodes.find(n => n.id === result.selectedId);
    if (target) { lastPhoneSelection = result.selectedId; await choose(target, 'phone'); status('Selected on phone · tags, actions and selectors loaded.'); }
  } catch (error) {
    if (connectionId === workbench?.connectionId && [401, 403, 404].includes(error.status)) { $('link-selections').checked = false; status(`${error.message} Selection linking paused.`); }
  } finally { selectionPolling = false; }
}
setInterval(() => { void syncPhoneSelection(); }, 1500);
$('link-selections').onchange = () => { lastPhoneSelection = null; if ($('link-selections').checked) void syncPhoneSelection(); };
perform(async () => { session = (await api('bootstrap')).session; await loadWorkbench(); await loadPreviews(); await saved(); await discover(); await localRecordings(); await maybeAutoConnect(); if (workbench.connected && location.hash === '#landing') await refresh(); });

const recordingTransfer = new QaLensRecordingTransfer({
  // The inbox's 2.5s timer also precedes every 5s transfer tick. Transfer owns its own
  // in-flight guard; a bounded inbox read must not prevent every archive-discovery tick.
  busy: () => busy || document.hidden,
  connection: async () => { await loadWorkbench(); return {id: workbench.connectionId, connected: workbench.connected}; },
  list: id => phoneRecordings(id), copy: (name, id) => copyRecording(name, id),
  changed: transfer => { $('auto-recordings').checked = transfer.enabled; }, status
});
function resetRecordingTransfer() { recordingTransfer.disable(); $('phone-recordings').replaceChildren(); }
async function localRecordings() {
  const result = await api('recordings/local'); $('pc-recordings').replaceChildren();
  if (!result.items.length) $('pc-recordings').textContent = 'No recordings copied yet.';
  for (const entry of result.items) button(`${entry.app || 'Recording'} · ${entry.name} · ${(entry.size / 1048576).toFixed(1)} MiB · Open replay`, () => perform(() => openRecording(entry.id)), $('pc-recordings'), 'node');
}
let viewerLoaded = false;
$('viewer').addEventListener('load', () => { viewerLoaded = true; });
async function openRecording(id) {
  const response = await fetch(`/api/recordings/file?id=${encodeURIComponent(id)}`, {headers: {'X-Qalens-Session': session}, cache: 'no-store'});
  if (!response.ok) throw Error('Saved recording unavailable');
  const file = await response.arrayBuffer();
  tab('replay');
  if (!viewerLoaded) await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(Error('Replay viewer is still loading; try opening again')), 10000);
    $('viewer').addEventListener('load', () => { clearTimeout(timeout); resolve(); }, {once:true});
  });
  $('viewer').contentWindow.postMessage({type: 'qalens-recording', name: `${id}.sal`, bytes: file}, location.origin, [file]);
  status('Recording opened.');
}
async function copyRecording(name, connectionId = workbench.connectionId) {
  status(`Copying ${name} to PC…`);
  const result = await api('recordings/receive', {name, connectionId});
  recordingTransfer.noteCopied(name, connectionId);
  status(`${result.duplicate ? 'Already saved' : 'Saved to PC'} · ${name} · ${(result.size / 1048576).toFixed(1)} MiB`);
  await localRecordings(); return result;
}
async function phoneRecordings(connectionId = workbench.connectionId) {
  const modeGeneration = mirrorModeGeneration;
  const result = await api('recordings/device');
  if (result.connectionId !== connectionId || workbench.connectionId !== connectionId) { const error = Error('Device changed during recording discovery; refresh before copying'); error.status = 409; throw error; }
  $('recording-state').textContent = result.recording ? 'Phone is recording. Bug clips save after the session stops.' : result.saving ? 'Phone is saving; waiting for completed files.' : 'Phone is ready. Completed archives appear below.';
  captureConnection = connectionId; captureState = result.controls || null; captureChecked = true; captureIssue = ''; phoneArchiveList = result.items; captureUi();
  if (modeGeneration === mirrorModeGeneration && typeof captureState?.inspection === 'boolean' && mirrorInspect !== captureState.inspection) {
    ++mirrorModeGeneration; mirrorInspect = captureState.inspection; mirrorPointer = null; mirrorFrame = null; mirrorUi();
    if (previewEnabled) { ++previewGeneration; await syncMirrorMode(connectionId); }
  }
  $('phone-recordings').replaceChildren();
  for (const entry of result.items) {
    const line = documentElement('div', 'toolbar'); $('phone-recordings').append(line);
    const label = documentElement('span', ''); label.textContent = `${entry.name} · ${(entry.size / 1048576).toFixed(1)} MiB`; line.append(label);
    button('Copy to PC', () => perform(() => copyRecording(entry.name, connectionId)), line);
    button('Watch', () => perform(async () => { const copied = await copyRecording(entry.name, connectionId); if (connectionId === workbench.connectionId) await openRecording(copied.id); }), line);
  }
  return result;
}
$('auto-recordings').onchange = () => {
  if ($('auto-recordings').checked) void recordingTransfer.enable();
  else recordingTransfer.disable('Automatic transfer disabled. An already-started copy may finish.');
};
$('refresh-recordings').onclick = () => perform(() => phoneRecordings());
$('refresh-local-recordings').onclick = () => perform(localRecordings);
setInterval(() => { void recordingTransfer.poll(); }, 5000);
for (const button of document.querySelectorAll('[data-adb]')) button.onclick = () => perform(async () => {
  const result = await api('adb', {action: button.dataset.adb, connectionId: workbench.connectionId}); status(result.notice);
});
$('pull-file').onclick = () => perform(async () => { status((await api('adb', {action: 'pull', remote: $('pull-path').value, connectionId: workbench.connectionId})).notice); });
$('push-file').onchange = event => perform(async () => {
  const file = event.target.files[0]; if (!file) return;
  if (file.size > 32 * 1048576) throw Error('File exceeds 32 MiB');
  const response = await fetch('/api/file/push', {method: 'POST', headers: {'X-Qalens-Session': session, 'X-Qalens-Connection': workbench.connectionId, 'X-Qalens-File': file.name}, body: file});
  const result = await response.json(); if (!response.ok) throw Error(result.error); status(result.notice); event.target.value = '';
});
$('viewer-modern').onclick = () => { viewerLoaded = false; $('viewer').src = '/web/index-v2.html?desktop'; status('Modern replay viewer selected.'); };
$('viewer-classic').onclick = () => { viewerLoaded = false; $('viewer').src = '/web/index.html?desktop'; status('Classic replay viewer selected.'); };

function connectionStatus(state) {
  const connected = state.connected, connection = state.connection;
  $('connection').textContent = state.phase === 'awaiting-approval' ? 'Waiting for phone approval' : state.phase === 'reconnecting' ? 'Reconnecting…' : connected ? connection?.package || 'Terminal-paired bridge' : 'Not connected';
  $('connection').className = `badge ${state.phase || ''}`;
  $('connect-hint').textContent = state.notice || (connected ? 'Connected' : 'Connect USB and approve debugging.');
  $('quick-connect').textContent = state.phase === 'awaiting-approval' ? 'Cancel pairing' : connected ? 'Connect again' : 'Connect';
}
async function discover() {
  const result = await api('devices', {}), current = $('quick-device').value || workbench?.connection?.serial;
  $('quick-device').replaceChildren();
  for (const device of result.devices) {
    const option = document.createElement('option'); option.value = device.serial;
    const model = device.description.match(/model:([^ ]+)/)?.[1]?.replaceAll('_', ' ');
    option.textContent = `${model || device.serial}${device.state !== 'device' ? ` · ${device.state}` : ''}`;
    option.disabled = device.state !== 'device'; $('quick-device').append(option);
  }
  const authorized = result.devices.filter(d => d.state === 'device');
  const remembered = workbench.profiles.find(p => p.id === workbench.preferences.profileId && authorized.some(d => d.serial === p.serial)) || workbench.profiles.find(p => authorized.some(d => d.serial === p.serial));
  if (authorized.some(d => d.serial === current)) $('quick-device').value = current;
  else if (remembered) $('quick-device').value = remembered.serial;
  else if (authorized.length === 1) $('quick-device').value = authorized[0].serial;
  else {
    const option = document.createElement('option'); option.value = ''; option.textContent = authorized.length ? 'Choose your phone' : 'No authorized phone'; $('quick-device').prepend(option); $('quick-device').value = '';
  }
  await discoverApps();
  status(authorized.length ? 'Phone found. Choose your QaLens app, then Connect and approve on the phone.' : 'Connect a phone by USB, enable USB debugging and approve the computer on the phone.');
}
async function discoverApps() {
  const serial = $('quick-device').value; $('quick-app').replaceChildren();
  if (!serial) { const option = document.createElement('option'); option.value = ''; option.textContent = 'Choose a phone first'; $('quick-app').append(option); return; }
  discoveredApps = (await api('apps', {serial})).apps.filter(a => a.qalens);
  for (const app of discoveredApps) { const option = document.createElement('option'); option.value = app.package; option.textContent = app.package; $('quick-app').append(option); }
  const saved = workbench.profiles.find(p => p.id === workbench.preferences.profileId && p.serial === serial) || workbench.profiles.find(p => p.serial === serial && discoveredApps.some(a => a.package === p.package));
  if (saved) $('quick-app').value = saved.package;
  else if (discoveredApps.length !== 1) { const option = document.createElement('option'); option.value = ''; option.textContent = discoveredApps.length ? 'Choose your app' : 'No QaLens app found'; $('quick-app').prepend(option); $('quick-app').value = ''; }
  $('connect-hint').textContent = discoveredApps.length ? 'Connect, then approve on your phone. No token to copy.' : 'Install a debug app with QaLens, then Scan. Older SDK? Use Device tools.';
}
$('quick-scan').onclick = () => perform(discover);
$('quick-device').onchange = () => perform(discoverApps);
$('quick-connect').onclick = () => perform(async () => {
  if (workbench?.phase === 'awaiting-approval') { await api('disconnect', {}); await loadWorkbench(); status('Pairing cancelled.'); return; }
  const app = discoveredApps.find(a => a.package === $('quick-app').value);
  if (!app || !$('quick-device').value) throw Error('Choose an authorized phone and a QaLens app first.');
  const remembered = workbench.profiles.find(p => p.serial === $('quick-device').value && p.package === app.package);
  await requestConnection(remembered || {serial: $('quick-device').value, package: app.package, activity: app.activity, name: app.package});
});
async function requestConnection(profile) {
  autoConnectAttempted = true;
  await stopPreview(); ++selectionGeneration;
  await api('pair', {profile});
  resetRecordingTransfer(); snapshot = null; selected = null; component = null; render(); details(); componentButtons();
  $('receive').checked = false; clearComponent(); await loadWorkbench(); status('Approve desktop access on your phone. Credentials are handled automatically.');
}

setInterval(async () => {
  // Receive shares this timer cadence. Skipping while it is polling can starve the health
  // check forever, so an owned forward never repairs. Health checks are bounded reads;
  // keep them independent of inbox/archive reads and guard their connection generation below.
  if (!session || !workbench?.connection || connectPolling || busy || document.hidden) return;
  connectPolling = true;
  try {
    const previous = workbench.phase, id = workbench.connectionId;
    const state = await api('connection/check', {reconnect: $('auto-reconnect').checked});
    if (id !== workbench.connectionId) return;
    Object.assign(workbench, state); connectionStatus(state); connectionButtons(); recordingTransfer.checkConnection(state.connectionId, state.connected);
    diagnostics?.sync();
    if (state.connectionId !== id) { ++selectionGeneration; snapshot = null; selected = null; render(); details(); $('receive').checked = false; await stopPreview(); }
    if (previous !== 'connected' && state.phase === 'connected') {
      $('receive').checked = true; status('Connected. Select an element to read its attributes.');
      if (location.hash === '#landing') await refresh();
    } else if (previous !== state.phase) status(state.notice);
  } catch (error) { status(error.message); }
  finally { connectPolling = false; }
}, 2500);
for (const item of document.querySelectorAll('[data-detail]')) item.onclick = () => {
  document.querySelectorAll('[data-detail]').forEach(b => b.classList.toggle('active', b === item));
  document.querySelectorAll('[data-detail-panel]').forEach(p => { p.hidden = p.dataset.detailPanel !== item.dataset.detail; });
};
$('copy-tag').onclick = () => perform(async () => {
  const tag = component?.content.component.tag || selected?.tag;
  if (!tag) return;
  await navigator.clipboard.writeText(tag); status('Test tag copied.');
});
function mirrorUi() {
  const control = mirrorMode === 'control' && !mirrorInspect;
  $('mode-control').classList.toggle('active', control);
  $('mode-preview').classList.toggle('active', mirrorMode === 'preview' && !mirrorInspect);
  $('mode-inspect').classList.toggle('active', mirrorInspect);
  for (const [id, active] of [['mode-control', control], ['mode-preview', mirrorMode === 'preview' && !mirrorInspect], ['mode-inspect', mirrorInspect]]) $(id).setAttribute?.('aria-pressed', String(active));
  $('phone-canvas').classList.toggle('control', control);
  $('phone-canvas').classList.toggle('inspect', mirrorInspect);
  $('map').classList.toggle('inspecting', mirrorInspect);
  $('map').hidden = !previewEnabled;
  $('mode-inspect').textContent = mirrorInspect ? 'Done inspecting' : 'Inspect elements';
  $('mirror-note').textContent = mirrorInspect ? 'Click any outlined Compose element to inspect it. Host taps are paused. Tree search does not hide outlines.' : control ? 'Click, drag, wheel scroll, hold to long press or type with the mirror focused. The whole phone is visible; frames stay in memory.' : 'Read-only preview. Touch and navigation controls are paused. Enable Inspect to select elements.';
  connectionButtons();
}
async function setMirrorMode(mode, inspect = false) {
  const connectionId = workbench?.connectionId;
  ++mirrorModeGeneration;
  mirrorPointer = null; mirrorFrame = null;
  // Wait for an already-dispatched highlight before closing inspection. Queued highlights are
  // superseded, and never reopen the phone inspector after Control is restored.
  ++selectionGeneration;
  await highlightQueue.catch(() => {});
  if (connectionId !== workbench?.connectionId) throw Error('Connection changed. Choose the mirror mode again.');
  if (workbench?.connected) {
    try { await api('inspection', {enabled: inspect}, connectionId); }
    catch (error) { if (error.status !== 404) throw error; status('This app uses an older SDK. Update QaLens for desktop inspection mode control.'); }
  }
  if (connectionId !== workbench?.connectionId) throw Error('Connection changed. Choose the mirror mode again.');
  mirrorMode = mode; mirrorInspect = inspect; mirrorUi();
  if (previewEnabled) {
    ++previewGeneration;
    await syncMirrorMode(workbench.connectionId);
    if (legacyMirror()) await previewFrame();
  }
}
async function syncMirrorMode(connectionId) {
  const result = await api('preview', {enabled: true, mode: mirrorInspect ? 'inspect' : mirrorMode, streamId: mirrorStream?.streamId, connectionId}, connectionId);
  if (!legacyMirror() && connectionId === workbench?.connectionId && previewEnabled && result.streamId === mirrorStream?.streamId && result.modeEpoch >= mirrorStream.modeEpoch) { mirrorStream = {...result, connectionId}; liveMirror.mode(result); }
}
$('mode-control').onclick = () => perform(() => setMirrorMode('control'));
$('mode-preview').onclick = () => perform(() => setMirrorMode('preview'));
$('mode-inspect').onclick = () => perform(async () => {
  const enabled = !mirrorInspect;
  if (enabled) await refresh();
  await setMirrorMode(mirrorMode, enabled);
});
async function stopPreview() {
  const owned = mirrorStream; mirrorStream = null; liveMirror?.stop();
  previewGeneration++; previewEnabled = false; mirrorPointer = null; mirrorFrame = null; $('preview-toggle').textContent = 'Start mirror';
  $('screen-image').hidden = true; $('screen-image').removeAttribute('src');
  $('screen-video').hidden = true;
  if (previewUrl) URL.revokeObjectURL(previewUrl); previewUrl = '';
  $('mirror-empty').hidden = false; $('map').classList.remove('selectable'); $('map').hidden = true;
  $('mirror-state').textContent = 'Mirror off · no screen frames are captured';
  if (workbench?.connection && session && (legacyMirror() || owned)) { try { await api('preview', {enabled: false, streamId: owned?.streamId, connectionId: owned?.connectionId || workbench.connectionId}); } catch (_) { /* Local UI always stops displaying. */ } }
  connectionButtons();
}
$('preview-toggle').onclick = () => perform(async () => {
  if (previewEnabled) { await stopPreview(); return; }
  await setMirrorMode(mirrorMode, mirrorInspect);
  if (!legacyMirror() && !liveMirror.supported()) throw Error('Live mirror needs Chrome/Edge or another browser with WebCodecs. Open desktop window is also available.');
  const connectionId = workbench?.connectionId;
  if (!legacyMirror() && !workbench.mirrorReady) { status('Setting up the verified scrcpy 5.0 server…'); await api('mirror/setup', {}, connectionId); workbench.mirrorReady = true; }
  if (connectionId !== workbench?.connectionId || document.hidden || location.hash !== '#landing') throw Error('Mirror start cancelled because the page or phone changed.');
  const result = await api('preview', {enabled: true, mode: mirrorInspect ? 'inspect' : mirrorMode, connectionId});
  if (connectionId !== workbench?.connectionId || document.hidden || location.hash !== '#landing') {
    if (result.streamId) { try { await api('preview', {enabled: false, streamId: result.streamId, connectionId}, connectionId); } catch (_) {} }
    throw Error('Mirror start cancelled because the page or phone changed.');
  }
  previewGeneration++; previewEnabled = true; $('preview-toggle').textContent = 'Stop mirror';
  if (legacyMirror()) await previewFrame();
  else { mirrorStream = {...result, connectionId}; await liveMirror.start(result, connectionId); }
  mirrorUi();
});
async function previewFrame() {
  if (!legacyMirror()) return;
  if (!previewEnabled || previewBusy || mirrorPointer || mirrorInputBusy || document.hidden || location.hash !== '#landing' || Date.now() - lastPreviewRequest < 1000) return;
  previewBusy = true;
  lastPreviewRequest = Date.now();
  const id = workbench?.connectionId, generation = previewGeneration;
  try {
    const response = await fetch('/api/screen', {headers: {'X-Qalens-Session': session, 'X-Qalens-Connection': id}, cache: 'no-store'});
    if (!response.ok) throw Error((await response.json()).error);
    const frameId = response.headers.get('X-Qalens-Frame'), blob = await response.blob();
    if (!previewEnabled || id !== workbench?.connectionId || generation !== previewGeneration) return;
    const previous = previewUrl, url = URL.createObjectURL(blob); previewUrl = url;
    // Publish the lease only after the exact frame decoded. A pending/new URL is not touchable.
    mirrorFrame = null;
    $('screen-image').onload = () => {
      if (url !== previewUrl || generation !== previewGeneration || id !== workbench?.connectionId || !previewEnabled) return;
      mirrorFrame = {id: frameId, connectionId: id, width: $('screen-image').naturalWidth, height: $('screen-image').naturalHeight, at: Date.now()};
      $('screen-image').hidden = false; $('mirror-empty').hidden = true; $('map').hidden = false;
      $('mirror-state').textContent = `${mirrorInspect ? 'Inspect' : mirrorMode === 'control' ? 'Control' : 'Preview'} · about 1 frame/sec · memory only`;
    };
    $('screen-image').onerror = () => { mirrorFrame = null; $('mirror-state').textContent = 'Frame could not be decoded; wait for the next frame.'; };
    $('screen-image').src = url;
    if (previous) URL.revokeObjectURL(previous);
  } catch (error) { if (previewEnabled && generation === previewGeneration) { mirrorFrame = null; $('mirror-state').textContent = `${error.message} · waiting for a fresh frame`; } }
  finally { previewBusy = false; }
}
setInterval(() => { void previewFrame(); }, 1200);
function mirrorPoint(event, outside = false, frame = mirrorFrame) {
  if (!previewEnabled || !frame || frame.connectionId !== workbench?.connectionId || Date.now() - frame.at > 5500) return null;
  return QaLensMirror.point($('phone-canvas').getBoundingClientRect(), frame.width, frame.height, event.clientX, event.clientY, outside);
}
async function inspectPoint(point, frame) {
  if (!mirrorInspect || liveSelecting || busy) return;
  liveSelecting = true;
  const connectionId = workbench.connectionId;
  try {
    await refresh();
    if (!mirrorInspect || connectionId !== workbench.connectionId || !snapshot || snapshot.connectionId !== connectionId) return;
    const viewport = snapshot.screenViewport || snapshot.viewport;
    if (!QaLensScrcpy.aligned(frame, viewport)) throw Error('Screen rotated. Wait for a fresh frame and inspect again.');
    const x = point.x * viewport.width - (snapshot.viewport.originX || 0), y = point.y * viewport.height - (snapshot.viewport.originY || 0);
    const selected = QaLensMirror.hit(snapshot.nodes, snapshot.windows, x, y);
    if (selected) await choose(selected); else status('No public Compose element at that position.');
  } catch (error) { status(error.message); }
  finally { liveSelecting = false; }
}
async function sendMirrorGesture(gesture, frame) {
  if (!gesture || busy || mirrorInputBusy || mirrorInspect || mirrorMode !== 'control' || !previewEnabled || frame.connectionId !== workbench?.connectionId || document.hidden) return;
  mirrorInputBusy = true; connectionButtons();
  try {
    await api('input', {...gesture, frameId: frame.id, connectionId: frame.connectionId});
    if (frame.connectionId !== workbench?.connectionId) return;
    status(`${gesture.action === 'long-press' ? 'Long press' : gesture.action === 'swipe' ? 'Swipe' : 'Tap'} sent to phone.`);
    // Refresh the tree after input; the phone may have navigated. No retry of the action itself.
    clearTimeout(treeRefreshTimer);
    treeRefreshTimer = setTimeout(async () => {
      if (frame.connectionId !== workbench?.connectionId || busy || choosing || document.hidden || location.hash !== '#landing') return;
      try { await refresh(); } catch (_) { $('screen').textContent = 'Return to the QaLens app, then Refresh the tree.'; }
    }, 350);
  } catch (error) { status(`${error.message} Touch was not retried.`); }
  finally { mirrorInputBusy = false; connectionButtons(); void previewFrame(); }
}
const canvas = $('phone-canvas');
const liveMirror = QaLensScrcpy.create({
  env: globalThis,
  canvas: $('screen-video'), api, fetch: (...args) => fetch(...args), session: () => session,
  visible: () => !document.hidden && location.hash === '#landing' && previewEnabled && workbench?.connected,
  canControl: () => !document.hidden && previewEnabled && !mirrorInspect && mirrorMode === 'control' && !busy && workbench?.connected,
  onFrame(frame) {
    mirrorFrame = frame;
    if (!frame) { mirrorPointer = null; $('phone-canvas').classList.remove('gesturing'); return; }
    $('screen-video').hidden = false; $('screen-image').hidden = true; $('mirror-empty').hidden = true; $('map').hidden = false;
    const note = `${mirrorInspect ? 'Inspect' : mirrorMode === 'control' ? 'Control' : 'Preview'} · scrcpy 5.0 live video · memory only`;
    if ($('mirror-state').textContent !== note) $('mirror-state').textContent = note;
  },
  onAlive(state) { if (mirrorFrame && mirrorFrame.revision === state.revision && mirrorFrame.width === state.width && mirrorFrame.height === state.height) { mirrorFrame.at = Date.now(); mirrorFrame.modeEpoch = state.modeEpoch; } },
  onMode(state) { if (mirrorFrame) mirrorFrame.modeEpoch = state.modeEpoch; },
  onHidden: () => { void stopPreview(); },
  onError(message) { void stopPreview().then(() => { $('mirror-state').textContent = message; status(message); }); }
});
canvas.addEventListener('pointerdown', event => {
  if (event.button !== 0 || busy || mirrorInputBusy || liveSelecting || mirrorPointer || (!mirrorInspect && mirrorMode !== 'control')) return;
  const point = mirrorPoint(event); if (!point) return;
  event.preventDefault(); canvas.focus({preventScroll: true}); canvas.setPointerCapture(event.pointerId);
  mirrorPointer = {pointerId: event.pointerId, point, frame: mirrorFrame, at: Date.now()}; canvas.classList.add('gesturing');
  if (!legacyMirror() && !mirrorInspect) liveMirror.send({action: 'touch', state: 'down', ...point}, mirrorFrame);
});
canvas.addEventListener('pointermove', event => {
  if (legacyMirror() || mirrorInspect || !mirrorPointer || event.pointerId !== mirrorPointer.pointerId) return;
  const point = mirrorPoint(event, true); if (point) liveMirror.send({action: 'touch', state: 'move', ...point}, mirrorFrame);
});
canvas.addEventListener('pointerup', event => {
  const start = mirrorPointer; mirrorPointer = null; canvas.classList.remove('gesturing');
  if (!start || event.pointerId !== start.pointerId) return;
  const end = mirrorPoint(event, true, start.frame);
  if (!end) {
    // A long press can outlive its original displayed frame. Never leave a finger held down.
    if (!legacyMirror() && !mirrorInspect) liveMirror.send({action: 'touch', state: 'cancel'}, mirrorFrame);
    return;
  }
  if (mirrorInspect) {
    if (Math.hypot((end.x - start.point.x) * start.frame.width, (end.y - start.point.y) * start.frame.height) < 15) void inspectPoint(end, start.frame);
  } else if (legacyMirror()) void sendMirrorGesture(QaLensMirror.gesture(start.point, end, Date.now() - start.at, start.frame.width, start.frame.height), start.frame);
  else { liveMirror.send(busy ? {action: 'touch', state: 'cancel'} : {action: 'touch', state: 'up', ...end}, mirrorFrame); scheduleMirrorTree(); }
});
for (const event of ['pointercancel', 'lostpointercapture']) canvas.addEventListener(event, () => {
  if (mirrorPointer && !legacyMirror() && !mirrorInspect) liveMirror.send({action: 'touch', state: 'cancel'}, mirrorFrame);
  mirrorPointer = null; canvas.classList.remove('gesturing');
});
canvas.addEventListener('wheel', event => {
  const point = mirrorPoint(event);
  if (!point || mirrorInspect || mirrorMode !== 'control' || mirrorPointer) return;
  event.preventDefault();
  const scale = event.deltaMode === 1 ? 32 : event.deltaMode === 2 ? mirrorFrame.height : 1;
  if (legacyMirror()) void sendMirrorGesture(QaLensMirror.wheel(point, event.deltaX * scale, event.deltaY * scale, mirrorFrame.width, mirrorFrame.height), mirrorFrame);
  else { liveMirror.send({action: 'scroll', ...point, horizontal: Math.max(-16, Math.min(16, -event.deltaX * scale / 100)), vertical: Math.max(-16, Math.min(16, -event.deltaY * scale / 100))}, mirrorFrame); scheduleMirrorTree(); }
}, {passive: false});
canvas.addEventListener('keydown', event => {
  if (!legacyMirror()) {
    if (!mirrorFrame || !previewEnabled || !workbench?.connected || mirrorMode !== 'control' || mirrorInspect || busy || event.ctrlKey || event.metaKey || event.altKey || event.isComposing) return;
    const keycode = {Escape: 4, Enter: 66, Backspace: 67, Delete: 112, Tab: 61, ArrowUp: 19, ArrowDown: 20, ArrowLeft: 21, ArrowRight: 22, Home: 122, End: 123}[event.key];
    if (keycode || event.key?.length === 1) {
      event.preventDefault(); liveMirror.send(keycode ? {action: 'key', keycode} : {action: 'text', text: event.key}, mirrorFrame); scheduleMirrorTree();
    }
    return;
  }
  if (event.key !== 'Escape' || !previewEnabled || !workbench?.connected || mirrorMode !== 'control' || mirrorInspect || busy || mirrorInputBusy) return;
  event.preventDefault(); void perform(async () => { const result = await api('adb', {action: 'back', connectionId: workbench.connectionId}); status(result.notice); });
});
canvas.addEventListener('contextmenu', event => event.preventDefault());
$('fast-mirror').onclick = () => perform(async () => { status((await api('adb', {action: 'mirror', connectionId: workbench.connectionId})).notice); });
// The former map click path is deliberately replaced by pointer routing on the complete canvas.
$('map').onclick = null;
document.addEventListener('visibilitychange', () => { if (document.hidden) { mirrorPointer = null; void stopPreview(); } });
window.addEventListener('pagehide', () => { liveMirror.stop(); previewEnabled = false; mirrorPointer = null; if (previewUrl) URL.revokeObjectURL(previewUrl); if (screenshotUrl) URL.revokeObjectURL(screenshotUrl); });
function scheduleMirrorTree() {
  clearTimeout(treeRefreshTimer);
  const id = workbench?.connectionId;
  treeRefreshTimer = setTimeout(async () => {
    if (id !== workbench?.connectionId || busy || choosing || document.hidden || location.hash !== '#landing') return;
    try { await refresh(); } catch (_) { $('screen').textContent = 'Return to the QaLens app, then Refresh the tree.'; }
  }, 350);
}

function connectionButtons() {
  const ready = !!workbench?.connected && (!workbench.phase || workbench.phase === 'connected');
  $('disconnect').disabled = !workbench?.connection && !ready || busy;
  $('preview-toggle').disabled = !ready || busy || mirrorInputBusy;
  for (const id of ['mode-control', 'mode-preview', 'mode-inspect']) $(id).disabled = !ready || busy || mirrorInputBusy || (id === 'mode-inspect' && (captureState?.phase === 'capturing' || captureState?.phase === 'awaiting_consent'));
  $('fast-mirror').disabled = !ready || busy || !workbench?.scrcpyAvailable;
  $('receive').disabled = !ready;
  $('auto-recordings').disabled = !ready;
  for (const item of document.querySelectorAll('#landing [data-adb]')) item.disabled = !ready || busy || mirrorInputBusy || mirrorInspect || mirrorMode !== 'control';
  captureUi();
}

$('auto-connect').onchange = () => perform(async () => {
  const profile = workbench.profiles.find(p => p.serial === $('quick-device').value && p.package === $('quick-app').value);
  try {
    workbench.preferences = (await api('preferences', {autoConnect: $('auto-connect').checked, profileId: profile?.id || ''})).preferences;
    autoConnectAttempted = false;
    status(workbench.preferences.autoConnect ? 'This app is remembered for auto connect. Phone approval is required each new session.' : 'Auto connect disabled.');
    await maybeAutoConnect();
  } finally { $('auto-connect').checked = workbench.preferences.autoConnect; }
});
async function maybeAutoConnect() {
  if (autoConnectAttempted || !workbench?.preferences.autoConnect || workbench.connection || workbench.connected) return;
  const profile = workbench.profiles.find(p => p.id === workbench.preferences.profileId);
  if (!profile) return;
  const devices = (await api('devices', {})).devices;
  if (!devices.some(d => d.serial === profile.serial && d.state === 'device')) return;
  const apps = (await api('apps', {serial: profile.serial})).apps;
  if (!apps.some(a => a.package === profile.package && a.qalens)) return;
  await requestConnection(profile);
}
setInterval(() => {
  if (!busy && !connectPolling && !document.hidden && !autoConnectAttempted && workbench?.preferences.autoConnect && !workbench.connection) void perform(maybeAutoConnect);
}, 10000);

function clearComponent() {
  component = null;
  clearSelectors(); lastPhoneSelection = null;
  $('component-title').textContent = 'Choose an element';
  $('component-meta').textContent = 'Attributes load when you select. Nothing is saved until you choose Save JSON.';
  for (const id of ['attributes', 'semantic-attributes', 'component-context', 'component-map']) $(id).replaceChildren();
  $('attributes-empty').hidden = false; $('attributes-empty').textContent = 'Select an element to read its attributes.';
  $('component-json').textContent = 'No component selected';
  componentButtons();
}

function resetCapture() {
  captureState = null; captureChecked = false; captureIssue = ''; captureConnection = workbench?.connectionId || null; phoneArchiveList = []; pendingReplay = null;
  $('watch-after-stop').checked = false; captureUi();
  $('clip-note').value = '';
}
function captureUi() {
  const ready = !!workbench?.connected && (!workbench.phase || workbench.phase === 'connected') && captureConnection === workbench.connectionId;
  const capable = ready && captureState?.capabilities?.includes('recording-control');
  const phase = captureState?.phase, active = phase === 'capturing', awaiting = phase === 'awaiting_consent';
  const video = $('record-mode').value === 'hd';
  $('record-start').disabled = !capable || busy || mirrorInputBusy || phase !== 'idle' || (video && !captureState.allowVideo);
  $('record-stop').disabled = !capable || busy || mirrorInputBusy || (!active && !awaiting);
  $('record-stop').textContent = awaiting ? 'Cancel HD request' : '■ Stop';
  $('record-clip').disabled = !capable || busy || mirrorInputBusy || !captureState.canClip;
  $('record-mode').disabled = !capable || busy || phase !== 'idle';
  for (const option of $('record-mode').options || []) if (option.value === 'hd') option.disabled = !captureState?.allowVideo;
  $('screenshot').disabled = !ready || busy || mirrorInputBusy || !captureState?.capabilities?.includes('masked-screenshot');
  $('watch-latest').disabled = busy || mirrorInputBusy || !!pendingReplay;
  const text = !ready ? (workbench?.phase === 'reconnecting' ? 'Reconnecting · capture controls paused' : 'Connect to use capture controls') : captureIssue ? 'Phone unavailable · capture controls paused' : !capable ? (captureChecked ? 'Update the app’s QaLens SDK for capture controls' : 'Checking capture support…') : awaiting ? 'Approve HD on the phone · no capture yet' : active ? `Recording ${captureState.mode === 'hd' ? 'HD' : 'frames'} · ${captureState.markedClips} clip(s) marked` : phase === 'saving' ? 'Saving recording & clips…' : 'Ready to record';
  $('capture-state').textContent = text; $('capture-state').className = `capture-state ${active ? 'recording' : phase === 'saving' || awaiting ? 'saving' : ''}`;
  if (pendingReplay) $('capture-note').textContent = 'Waiting for this session to finish saving, then copying it to replay…';
  else $('capture-note').textContent = captureIssue ? captureIssue : !capable && ready && captureChecked ? 'Mirror and tree still work. New capture controls need the updated SDK in the app.' : video && !captureState?.allowVideo && ready ? 'This host has not enabled unmasked HD video. Choose Frames for privacy-masked capture.' : 'Capture starts only when you click. Clips export after Stop. Screenshots mask private semantics; HD video has no masks.';
}
async function captureCommand(action) {
  const connectionId = workbench.connectionId;
  const command = {action};
  if (action === 'start') {
    pendingReplay = null;
    await setMirrorMode(mirrorMode, false);
    command.video = $('record-mode').value === 'hd';
  }
  if (connectionId !== workbench?.connectionId) throw Error('Connection changed. Request recording again on the intended phone.');
  if (action === 'clip') {
    const value = $('clip-duration').value === 'custom' ? $('clip-custom').value : $('clip-duration').value;
    const seconds = Number(value);
    if (!Number.isInteger(seconds) || seconds < 1 || seconds > 300) throw Error('Choose 1–300 whole seconds for the clip.');
    command.seconds = seconds; command.label = typeof QaLensDiagnostics !== 'undefined' ? QaLensDiagnostics.clipLabel($('clip-note').value, seconds) : `Bug clip · last ${seconds}s`;
  }
  const before = captureState;
  const result = await api('recording', command, connectionId);
  if (connectionId !== workbench.connectionId || result.connectionId !== connectionId) return;
  captureState = result.controls; captureConnection = connectionId;
  if (action === 'clip' && (typeof QaLensDiagnostics === 'undefined' || QaLensDiagnostics.clipLabel($('clip-note').value, command.seconds) === command.label)) $('clip-note').value = '';
  if (action === 'stop' && before?.phase === 'capturing' && $('watch-after-stop').checked) {
    pendingReplay = {connectionId, name: before.sessionName, deadline: Date.now() + 120000};
  }
  status(action === 'clip' ? `Last ${command.seconds}s marked. Recording continues; clip export happens after Stop.` : result.notice);
  captureUi(); await phoneRecordings(connectionId);
}
$('record-start').onclick = () => perform(() => captureCommand('start'));
$('record-stop').onclick = () => perform(() => captureCommand('stop'));
$('record-clip').onclick = () => perform(() => captureCommand('clip'));
$('record-mode').onchange = captureUi;
$('clip-duration').onchange = () => { $('clip-custom-label').hidden = $('clip-duration').value !== 'custom'; };
$('watch-after-stop').onchange = () => { if (!$('watch-after-stop').checked) { pendingReplay = null; captureUi(); } };
async function pollCapture() {
  if (!session || !workbench?.connected || busy || capturePolling || mirrorInputBusy || document.hidden || !['landing', 'recordings'].includes(location.hash.slice(1))) return;
  capturePolling = true; const connectionId = workbench.connectionId;
  try {
    await phoneRecordings(connectionId);
    if (pendingReplay && pendingReplay.connectionId === connectionId && captureState?.phase === 'idle') {
      const pending = pendingReplay, saved = phoneArchiveList.find(item => item.name === pending.name);
      if (saved) {
        pendingReplay = null;
        await perform(async () => { const copied = await copyRecording(saved.name, connectionId); if (workbench.connectionId === connectionId && $('watch-after-stop').checked) await openRecording(copied.id); });
      }
    }
    if (pendingReplay && Date.now() > pendingReplay.deadline) { pendingReplay = null; status('Saving took longer than expected. Check Recordings and choose Watch when the session appears.'); }
  } catch (error) {
    if (connectionId !== workbench?.connectionId) return;
    captureState = null; captureIssue = error.message; captureUi();
    if (pendingReplay) { pendingReplay = null; status(`${error.message} Automatic replay cancelled; reconnect and choose Watch.`); }
  } finally { capturePolling = false; }
}
setInterval(() => { void pollCapture(); }, 2000);
$('watch-latest').onclick = () => perform(async () => {
  if (workbench?.connected) {
    const connectionId = workbench.connectionId, phone = await phoneRecordings(connectionId);
    if (phone.saving) throw Error('Recording is still saving. Wait for Ready, then choose Watch latest.');
    if (phone.items.length) {
      const copied = await copyRecording(phone.items[0].name, connectionId);
      if (connectionId === workbench.connectionId) await openRecording(copied.id);
      return;
    }
  }
  const local = await api('recordings/local');
  if (!local.items.length) throw Error('No finished recordings yet. Stop a recording, then choose Watch latest.');
  await openRecording(local.items[0].id);
});
function saveScreenshot() {
  if (!screenshotUrl) return;
  const link = document.createElement('a'); link.href = screenshotUrl; link.download = `qalens-${Date.now()}.png`; document.body.append(link); link.click(); link.remove();
}
$('screenshot').onclick = () => perform(async () => {
  const connectionId = workbench.connectionId, includeOverlay = $('screenshot-overlay').checked;
  const response = await fetch('/api/screenshot', {method: 'POST', headers: {'X-Qalens-Session': session, 'X-Qalens-Connection': connectionId, 'Content-Type': 'application/json'}, body: JSON.stringify({includeOverlay}), cache: 'no-store'});
  if (!response.ok) throw Error((await response.json()).error);
  const blob = await response.blob();
  if (connectionId !== workbench.connectionId) return;
  if (screenshotUrl) URL.revokeObjectURL(screenshotUrl);
  screenshotBlob = blob; screenshotUrl = URL.createObjectURL(blob); $('screenshot-image').src = screenshotUrl;
  $('screenshot-description').textContent = includeOverlay ? 'App window with the current QaLens overlay. Private semantics are masked.' : 'App window with QaLens overlay hidden during capture and restored afterwards. Private semantics are masked.';
  $('screenshot-review').showModal(); status('Screenshot ready. Review, copy or save the PNG.');
});
$('screenshot-save').onclick = saveScreenshot;
$('screenshot-close').onclick = () => $('screenshot-review').close();
$('screenshot-copy').onclick = () => perform(async () => {
  if (!screenshotBlob || typeof ClipboardItem === 'undefined' || !navigator.clipboard?.write) throw Error('Image clipboard is unavailable in this browser. Use Save PNG.');
  await navigator.clipboard.write([new ClipboardItem({'image/png': screenshotBlob})]); status('Screenshot copied.');
});
try { if (typeof QaLensMirror !== 'undefined') QaLensMirror.installLayout(document, window.localStorage); } catch (_) { /* Storage restrictions do not block capture/inspection. */ }
mirrorUi(); captureUi();
if (typeof QaLensDiagnostics !== 'undefined') diagnostics = QaLensDiagnostics.install({document, api,
  connection: () => ({id: workbench?.connectionId, connected: !!workbench?.connected && (!workbench.phase || workbench.phase === 'connected')}),
  workBusy: () => busy || mirrorInputBusy, navigate: tab});
