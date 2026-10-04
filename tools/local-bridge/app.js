'use strict';
const $ = id => document.getElementById(id);
let autoConnectAttempted = false;
let selectionGeneration = 0, previewGeneration = 0, previewEnabled = false, previewBusy = false, previewUrl = '', connectPolling = false, discoveredApps = [], liveSelecting = false;
let snapshot = null, selected = null, busy = false, session = '', workbench = null, component = null, previews = [], activeProfile = null, polling = false, savedHashes = new Set();
const status = text => { $('status').textContent = text; };
async function api(path, command) {
  const headers = {'X-Qalens-Session': session};
  if (workbench?.connectionId) headers['X-Qalens-Connection'] = workbench.connectionId;
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
const pageNames = {landing: 'Landing', devices: 'Device tools', library: 'Saved elements', automation: 'Automation', recordings: 'Recordings', replay: 'Replay'};
function tab(id, push = true) {
  if (!pageNames[id]) { id = 'landing'; if (!push) history.replaceState(history.state, '', '#landing'); }
  if (id !== 'landing' && previewEnabled) void stopPreview();
  if (push && location.hash !== `#${id}`) history.pushState({qalens: true}, '', `#${id}`);
  document.querySelectorAll('.page').forEach(page => { page.hidden = page.id !== id; });
  document.querySelectorAll('[data-tab]').forEach(button => button.classList.toggle('active', button.dataset.tab === id));
  $('page-label').textContent = pageNames[id];
  document.body.classList.toggle('replaying', id === 'replay');
  window.scrollTo({top: 0});
  $('back').disabled = id === 'landing';
  $('viewer').contentWindow?.postMessage({type: 'qalens-visibility', visible: id === 'replay'}, location.origin);
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
  if (selected && !liveSelecting) await choose(selected);
}
function filtered() {
  const needle = $('search').value.toLowerCase();
  return (snapshot?.nodes || []).filter(n => (!$('tagged').checked || n.tag) && `${n.tag || ''} ${n.label || ''} ${n.id}`.toLowerCase().includes(needle));
}
function render() {
  $('tree').replaceChildren(); $('map').replaceChildren();
  if (!snapshot) return;
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
    const rect = document.createElementNS('http://www.w3.org/2000/svg', 'rect'), b = node.bounds;
    rect.setAttribute('x', b.left + (snapshot.viewport.originX || 0)); rect.setAttribute('y', b.top + (snapshot.viewport.originY || 0)); rect.setAttribute('width', b.right-b.left); rect.setAttribute('height', b.bottom-b.top); rect.setAttribute('class', selected?.id === node.id ? 'selected' : '');
    const title = document.createElementNS('http://www.w3.org/2000/svg', 'title'); title.textContent = node.tag || node.label;
    rect.append(title); rect.onclick = () => choose(node); $('map').append(rect);
  }
}
async function choose(node) {
  if (!snapshot || snapshot.connectionId !== workbench?.connectionId) { status('Connection changed. Refresh the tree before selecting.'); return; }
  const generation = ++selectionGeneration, connectionId = workbench?.connectionId;
  selected = node; component = null; render(); details(); componentButtons();
  $('component-title').textContent = node.tag || node.label || 'Component';
  $('component-meta').textContent = 'Loading all public attributes…';
  $('attributes').replaceChildren(); $('semantic-attributes').replaceChildren();
  $('attributes-empty').hidden = false; $('attributes-empty').textContent = 'Loading attributes…';
  try {
    const result = await api('component', {id: node.id});
    if (generation !== selectionGeneration || connectionId !== workbench?.connectionId) return;
    showComponent(result.document, true); await loadPreviews();
  } catch (error) {
    if (generation !== selectionGeneration) return;
    $('component-meta').textContent = error.message; $('attributes-empty').textContent = 'Refresh the tree and select again.';
    status(error.message);
  }
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
function componentButtons() { connectionButtons(); $('copy-tag').disabled = !(component?.content.component.tag || selected?.tag) || busy; $('save').disabled = !component || busy; $('download').disabled = !component || busy; $('run').disabled = !component || busy || !$('pipeline').value || !savedHashes.has(component.hash); }
function showComponent(entry, live = false) {
  if (!live) { ++selectionGeneration; selected = null; render(); details(); }
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
  recordingTransfer.checkConnection(workbench.connectionId, workbench.connected);
  document.querySelector('[data-adb="mirror"]').title = workbench.scrcpyAvailable ? 'Start installed scrcpy' : 'Install scrcpy to enable this tool';
  $('auto-connect').checked = workbench.preferences.autoConnect;
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
$('events').onclick = () => perform(async () => { $('observations').textContent = JSON.stringify(await api('events'), null, 2); status('Read recent observations.'); });
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
    if (inbox.received && previews.length) { showComponent(previews.at(-1)); status(`Received from phone · review attributes, then Save. PC preview omissions: ${inbox.omittedPreviews || 0}.${inbox.dropped ? ` ${inbox.dropped} older phone previews were dropped by the queue budget.` : ''}`); }
  } catch (error) {
    if ([401, 403, 409].includes(error.status)) { $('receive').checked = false; status(`${error.message} Reconnect and enable Receive again.`); }
    else status(`${error.message} Receive remains enabled and will check again.`);
  }
  finally { polling = false; }
}, 2500);
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
  const result = await api('recordings/device');
  if (result.connectionId !== connectionId || workbench.connectionId !== connectionId) { const error = Error('Device changed during recording discovery; refresh before copying'); error.status = 409; throw error; }
  $('recording-state').textContent = result.recording ? 'Phone is recording. Bug clips save after the session stops.' : result.saving ? 'Phone is saving; waiting for completed files.' : 'Phone is ready. Completed archives appear below.';
  $('phone-recordings').replaceChildren();
  for (const entry of result.items) {
    const line = documentElement('div', 'toolbar'); $('phone-recordings').append(line);
    const label = documentElement('span', ''); label.textContent = `${entry.name} · ${(entry.size / 1048576).toFixed(1)} MiB`; line.append(label);
    button('Copy to PC', () => perform(() => copyRecording(entry.name, connectionId)), line);
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
async function stopPreview() {
  previewGeneration++; previewEnabled = false; $('preview-toggle').textContent = 'Start preview';
  $('screen-image').hidden = true; $('screen-image').removeAttribute('src');
  if (previewUrl) URL.revokeObjectURL(previewUrl); previewUrl = '';
  $('mirror-empty').hidden = false; $('map').classList.remove('selectable');
  $('mirror-state').textContent = 'Preview off · no screen frames are captured';
  if (workbench?.connection && session) { try { await api('preview', {enabled: false, connectionId: workbench.connectionId}); } catch (_) { /* Local UI always stops sampling. */ } }
}
$('preview-toggle').onclick = () => perform(async () => {
  if (previewEnabled) { await stopPreview(); return; }
  await api('preview', {enabled: true, connectionId: workbench?.connectionId});
  previewGeneration++; previewEnabled = true; $('preview-toggle').textContent = 'Stop preview';
  $('mirror-state').textContent = 'Live preview · up to 1 frame/sec · memory only';
  await previewFrame();
});
async function previewFrame() {
  if (!previewEnabled || previewBusy || document.hidden || location.hash !== '#landing') return;
  previewBusy = true;
  const id = workbench?.connectionId, generation = previewGeneration;
  try {
    const response = await fetch('/api/screen', {headers: {'X-Qalens-Session': session, 'X-Qalens-Connection': id}, cache: 'no-store'});
    if (!response.ok) throw Error((await response.json()).error);
    const blob = await response.blob();
    if (!previewEnabled || id !== workbench?.connectionId || generation !== previewGeneration) return;
    const previous = previewUrl; previewUrl = URL.createObjectURL(blob);
    $('screen-image').src = previewUrl; $('screen-image').hidden = false; $('mirror-empty').hidden = true;
    $('map').classList.add('selectable'); if (previous) URL.revokeObjectURL(previous);
    $('mirror-state').textContent = 'Live preview · click to inspect · memory only';
  } catch (error) { if (previewEnabled && generation === previewGeneration) $('mirror-state').textContent = `${error.message} · preview will retry`; }
  finally { previewBusy = false; }
}
setInterval(() => { void previewFrame(); }, 1200);
$('map').onclick = async event => {
  if (!snapshot || liveSelecting || busy) return;
  liveSelecting = true;
  try { await refresh(); } catch (error) { status(error.message); liveSelecting = false; return; }
  liveSelecting = false;
  const viewport = snapshot.screenViewport || snapshot.viewport, image = $('screen-image');
  if (!previewEnabled || image.naturalWidth !== viewport.width || image.naturalHeight !== viewport.height) { status('Refresh the tree with the app in front to align selection with this screen.'); return; }
  const box = $('map').getBoundingClientRect(), scale = Math.min(box.width / viewport.width, box.height / viewport.height);
  const x = (event.clientX - box.left - (box.width - viewport.width * scale) / 2) / scale - (snapshot.viewport.originX || 0);
  const y = (event.clientY - box.top - (box.height - viewport.height * scale) / 2) / scale - (snapshot.viewport.originY || 0);
  const candidates = snapshot.nodes.filter(n => x >= n.bounds.left && x <= n.bounds.right && y >= n.bounds.top && y <= n.bounds.bottom);
  candidates.sort((a,b) => (a.bounds.right-a.bounds.left)*(a.bounds.bottom-a.bounds.top) - (b.bounds.right-b.bounds.left)*(b.bounds.bottom-b.bounds.top));
  if (candidates[0]) await choose(candidates[0]); else status('No public Compose element at that position.');
};
document.addEventListener('visibilitychange', () => { if (document.hidden) void stopPreview(); });
window.addEventListener('pagehide', () => { previewEnabled = false; if (previewUrl) URL.revokeObjectURL(previewUrl); });

function connectionButtons() {
  const ready = !!workbench?.connected;
  $('disconnect').disabled = !workbench?.connection && !ready || busy;
  $('preview-toggle').disabled = !ready || busy;
  $('receive').disabled = !ready;
  $('auto-recordings').disabled = !ready;
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
  $('component-title').textContent = 'Choose an element';
  $('component-meta').textContent = 'Attributes load when you select. Nothing is saved until you choose Save JSON.';
  for (const id of ['attributes', 'semantic-attributes', 'component-context', 'component-map']) $(id).replaceChildren();
  $('attributes-empty').hidden = false; $('attributes-empty').textContent = 'Select an element to read its attributes.';
  $('component-json').textContent = 'No component selected';
  componentButtons();
}
