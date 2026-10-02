'use strict';
const $ = id => document.getElementById(id);
let snapshot = null, selected = null, busy = false, session = '', workbench = null, component = null, previews = [], activeProfile = null, polling = false, savedHashes = new Set();
const status = text => { $('status').textContent = text; };
async function api(path, command) {
  const headers = {'X-Qalens-Session': session};
  if (snapshot?.connectionId) headers['X-Qalens-Connection'] = snapshot.connectionId;
  if (command) headers['Content-Type'] = 'application/json';
  const response = await fetch(`/api/${path}`, {method: command ? 'POST' : 'GET', headers, body: command ? JSON.stringify(command) : undefined, cache: 'no-store'});
  const result = await response.json();
  if (!response.ok || result.ok === false) throw Error(result.error || `HTTP ${response.status}`);
  return result;
}
async function perform(work) {
  if (busy) return;
  busy = true; document.querySelectorAll('button:not([data-tab]):not(#back)').forEach(b => { b.disabled = true; });
  try { await work(); } catch (error) { status(error.message); }
  finally { busy = false; document.querySelectorAll('button').forEach(b => { b.disabled = false; }); componentButtons(); $('back').disabled = location.hash === '#home'; }
}
const pageNames = {home: 'Start', inspect: 'Inspector', devices: 'Devices & apps', library: 'Saved components', automation: 'Automation', recordings: 'Recordings', replay: 'Replay viewer'};
function tab(id, push = true) {
  if (!pageNames[id]) id = 'home';
  if (push && location.hash !== `#${id}`) history.pushState({qalens: true}, '', `#${id}`);
  document.querySelectorAll('.page').forEach(page => { page.hidden = page.id !== id; });
  document.querySelectorAll('[data-tab]').forEach(button => button.classList.toggle('active', button.dataset.tab === id));
  $('page-label').textContent = pageNames[id];
  document.body.classList.toggle('replaying', id === 'replay');
  window.scrollTo({top: 0});
  $('back').disabled = id === 'home';
  $('viewer').contentWindow?.postMessage({type: 'qalens-visibility', visible: id === 'replay'}, location.origin);
}
document.querySelectorAll('[data-tab]').forEach(button => { button.onclick = () => tab(button.dataset.tab); });
$('back').onclick = () => { if (history.state?.qalens) history.back(); else tab('home', false); };
window.addEventListener('popstate', () => tab(location.hash.slice(1), false));
history.replaceState({qalens: false}, '', location.hash || '#home');
tab(location.hash.slice(1), false);
function button(text, onclick, parent, className = '') {
  const element = document.createElement('button'); element.textContent = text; element.onclick = onclick; element.className = className; parent.append(element); return element;
}
async function refresh() {
  snapshot = await api('snapshot'); selected = snapshot.nodes.find(n => n.id === selected?.id) || null;
  $('screen').textContent = `${snapshot.screen} · ${snapshot.nodes.length} visible nodes`;
  status(`Tree refreshed · ${snapshot.omittedNodes} nodes omitted. Refresh after actions to verify results.`);
  render(); details();
}
function filtered() {
  const needle = $('search').value.toLowerCase();
  return (snapshot?.nodes || []).filter(n => (!$('tagged').checked || n.tag) && `${n.tag || ''} ${n.label || ''} ${n.id}`.toLowerCase().includes(needle));
}
function render() {
  $('tree').replaceChildren(); $('map').replaceChildren();
  if (!snapshot) return;
  $('map').setAttribute('viewBox', `0 0 ${snapshot.viewport.width} ${snapshot.viewport.height}`);
  const frame = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
  frame.setAttribute('width', snapshot.viewport.width); frame.setAttribute('height', snapshot.viewport.height); frame.setAttribute('class', 'viewport'); $('map').append(frame);
  const parents = new Map(snapshot.nodes.map(n => [n.id, n.parentId]));
  for (const node of filtered()) {
    let depth = 0, ancestor = node.parentId; const seen = new Set();
    while (ancestor && !seen.has(ancestor) && depth < 12) { seen.add(ancestor); depth++; ancestor = parents.get(ancestor); }
    const element = button(`${node.tag ? `[${node.tag}] ` : ''}${node.label} · ${node.actions.join(', ') || node.role || 'node'}`, () => choose(node), $('tree'), `node${selected?.id === node.id ? ' selected' : ''}`);
    element.style.paddingInlineStart = `${10 + depth * 12}px`;
    const rect = document.createElementNS('http://www.w3.org/2000/svg', 'rect'), b = node.bounds;
    rect.setAttribute('x', b.left); rect.setAttribute('y', b.top); rect.setAttribute('width', b.right-b.left); rect.setAttribute('height', b.bottom-b.top); rect.setAttribute('class', selected?.id === node.id ? 'selected' : '');
    const title = document.createElementNS('http://www.w3.org/2000/svg', 'title'); title.textContent = node.tag || node.label;
    rect.append(title); rect.onclick = () => choose(node); $('map').append(rect);
  }
}
function choose(node) { selected = node; render(); details(); }
function details() {
  $('summary').textContent = selected ? `${selected.label}\nTag: ${selected.tag || 'none'} · ${selected.role || 'Component'} · ${selected.enabled ? 'Enabled' : 'Disabled'}\n${selected.bounds.right-selected.bounds.left} × ${selected.bounds.bottom-selected.bounds.top} px` : 'Choose a tree node or a rectangle.';
  $('details').textContent = selected ? JSON.stringify(selected, null, 2) : 'No selection'; $('actions').replaceChildren();
  if (!selected) return;
  button('Capture attributes', () => perform(async () => {
    const result = await api('component', {id: selected.id}); await loadPreviews(); showComponent(result.document); status('Attributes captured into a preview. Save to keep a file.');
  }), $('actions'));
  for (const action of ['select', ...selected.actions]) {
    button(action === 'select' ? 'Highlight on phone' : action, () => perform(async () => {
      const command = {action, id: selected.id};
      if (action === 'type') { const text = prompt('Set field text (sent to the host)'); if (text === null) return; command.text = text; }
      if (action === 'scroll') { const dy = prompt('Vertical scroll pixels (positive = forward)', '400'); if (dy === null) return; command.dy = Number(dy); if (!Number.isFinite(command.dy)) throw Error('Enter a finite scroll delta'); }
      await api('command', command); await refresh();
    }), $('actions'));
  }
}
function row(name, value) {
  const tr = document.createElement('tr'), key = document.createElement('td'), val = document.createElement('td');
  key.textContent = name; val.textContent = typeof value === 'string' ? value : JSON.stringify(value, null, 2); tr.append(key, val); $('attributes').append(tr);
}
function componentButtons() { $('save').disabled = !component || busy; $('download').disabled = !component || busy; $('run').disabled = !component || busy || !$('pipeline').value || !savedHashes.has(component.hash); }
function showComponent(entry) {
  component = entry;
  const data = entry.content, node = data.component;
  $('component-title').textContent = node.label || node.tag || 'Component';
  $('component-meta').textContent = `${data.package || 'Imported component'} · SHA-256 ${entry.hash} · ${new Date(entry.capturedAtMillis || Date.now()).toLocaleString()}`;
  $('component-context').replaceChildren();
  const context = documentElement('div', 'card');
  context.textContent = `Tree position: ${(data.tree.path || []).map(p => `${p.tag || p.role || 'node'} [${p.siblingIndex}]`).join(' › ')}\n${data.coverage || 'Imported file: redaction and coverage are determined by its producer.'}`;
  $('component-context').append(context); $('attributes').replaceChildren();
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
  for (const attribute of node.attributes || []) row(`Semantics · ${attribute.name}`, attribute.value === null ? attribute.coverage : attribute.value);
  row('Tree context', data.tree); row('Viewport', data.viewport || null);
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
  if (recordingConnection && recordingConnection !== workbench.connectionId) resetRecordingTransfer();
  document.querySelector('[data-adb="mirror"]').title = workbench.scrcpyAvailable ? 'Start installed scrcpy' : 'Install scrcpy to enable this tool';
  $('connection').textContent = workbench.connected ? connection ? `${connection.serial} · Android ${connection.actualPlatformVersion} · ${connection.package}` : 'Terminal-paired bridge' : 'Not connected';
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
  $('profile-token').value = ''; tab('devices'); status('Profile loaded. Enter the current pairing token to connect.');
}
async function saved() {
  const data = await api('saved'); savedHashes = new Set(data.items.map(item => item.hash)); $('saved').replaceChildren(); componentButtons();
  if (!data.items.length) $('saved').textContent = 'No files saved yet. Capture a component, review it, then Save JSON.';
  for (const item of data.items) button(`${item.component} · ${item.package || 'imported'} · ${item.hash.slice(0, 16)}`, () => perform(async () => {
    showComponent((await api('document', {hash: item.hash})).document); tab('inspect'); status('Opened saved snapshot. Live commands require a fresh tree selection.');
  }), $('saved'), 'node');
  if (data.omitted) status(`Library shows 500 files; ${data.omitted} older files remain on disk.`);
}
$('refresh').onclick = () => perform(refresh); $('search').oninput = render; $('tagged').onchange = render;
$('events').onclick = () => perform(async () => { $('observations').textContent = JSON.stringify(await api('events'), null, 2); $('observations').closest('details').open = true; status('Read recent observations.'); });
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
  const result = await api('connect', {profile: formProfile(), token: $('profile-token').value});
  $('profile-token').value = ''; snapshot = null; selected = null; render(); details();
  resetRecordingTransfer(); await loadWorkbench(); tab('inspect'); $('receive').checked = true;
  const expected = result.connection.platformVersion, actual = result.connection.actualPlatformVersion;
  status(`Forward connected. Start QaLens in the app, then refresh.${expected && expected !== actual ? ` Expected Android ${expected}; device is ${actual}.` : ''}`);
});
$('launch').onclick = () => perform(async () => { await api('launch', {}); status('Launch requested. App data preserved. Start its QaLens bridge if needed.'); });
$('disconnect').onclick = () => perform(async () => { await api('disconnect', {}); resetRecordingTransfer(); $('receive').checked = false; $('profile-token').value = ''; snapshot = null; selected = null; render(); details(); await loadWorkbench(); status('Disconnected; saved files and profiles remain.'); });
$('delete-profile').onclick = () => perform(async () => { if (!activeProfile) throw Error('Choose a saved profile first'); await api('profile/delete', {id: activeProfile.id}); activeProfile = null; await loadWorkbench(); status('Profile removed. App and saved components remain.'); });
$('import-profile').onclick = () => perform(async () => { const result = await api('profile', {profile: JSON.parse($('capabilities').value)}); fillProfile(result.profile); await loadWorkbench(); $('capabilities').value = ''; status(result.notice); });
$('reload-saved').onclick = () => perform(saved); $('reload-jobs').onclick = () => perform(loadWorkbench); $('pipeline').onchange = componentButtons;
$('run').onclick = () => perform(async () => {
  if (!savedHashes.has(component.hash)) throw Error('Save this component before running a pipeline');
  const result = await api('run', {hash: component.hash, pipeline: $('pipeline').value}); await loadWorkbench(); status(`Pipeline ${result.job.pipeline} started. Refresh results to check completion.`);
});
setInterval(async () => {
  if (!$('receive').checked || document.hidden || busy || polling) return;
  polling = true;
  try {
    const inbox = await api('inbox'); previews = inbox.documents; renderPreviews();
    if (inbox.received && previews.length) { showComponent(previews.at(-1)); status(`Received from phone · review attributes, then Save. PC preview omissions: ${inbox.omittedPreviews || 0}.${inbox.dropped ? ` ${inbox.dropped} older phone previews were dropped by the queue budget.` : ''}`); }
  } catch (error) { $('receive').checked = false; status(`${error.message} Receive paused; reconnect and enable it to retry.`); }
  finally { polling = false; }
}, 2500);
perform(async () => { session = (await api('bootstrap')).session; await loadWorkbench(); await loadPreviews(); await saved(); status('Ready. Choose what you want to do. Recordings transfer only when you choose.'); });

let recordingConnection = '', knownRecordings = new Set(), recordingPolling = false;
function resetRecordingTransfer() { $('auto-recordings').checked = false; recordingConnection = ''; knownRecordings.clear(); $('phone-recordings').replaceChildren(); }
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
  knownRecordings.add(name);
  status(`${result.duplicate ? 'Already saved' : 'Saved to PC'} · ${name} · ${(result.size / 1048576).toFixed(1)} MiB`);
  await localRecordings(); return result;
}
async function phoneRecordings(auto = false) {
  const connectionId = workbench.connectionId;
  const result = await api('recordings/device');
  if (result.connectionId !== connectionId || workbench.connectionId !== connectionId) throw Error('Device changed during recording discovery; refresh before copying');
  $('recording-state').textContent = result.recording ? 'Phone is recording. Bug clips save after the session stops.' : result.saving ? 'Phone is saving; waiting for completed files.' : 'Phone is ready. Completed archives appear below.';
  $('phone-recordings').replaceChildren();
  for (const entry of result.items) {
    const line = documentElement('div', 'toolbar'); $('phone-recordings').append(line);
    const label = documentElement('span', ''); label.textContent = `${entry.name} · ${(entry.size / 1048576).toFixed(1)} MiB`; line.append(label);
    button('Copy to PC', () => perform(() => copyRecording(entry.name, connectionId)), line);
  }
  if (auto) for (const entry of result.items) if (!knownRecordings.has(entry.name)) await copyRecording(entry.name, connectionId);
  return result;
}
$('auto-recordings').onchange = () => {
  if (busy || recordingPolling) { resetRecordingTransfer(); status('Wait for the current operation, then enable recording transfer.'); return; }
  perform(async () => {
  if (!$('auto-recordings').checked) { recordingConnection = ''; return; }
  try {
    await loadWorkbench(); if (!workbench.connected) throw Error('Connect a phone first');
    const current = await phoneRecordings(); knownRecordings = new Set(current.items.map(entry => entry.name));
    recordingConnection = workbench.connectionId; status('Automatic transfer enabled for new recordings on this connection. Existing recordings require Copy.');
  } catch (error) { resetRecordingTransfer(); throw error; }
});
};
$('refresh-recordings').onclick = () => perform(() => phoneRecordings());
$('refresh-local-recordings').onclick = () => perform(localRecordings);
setInterval(async () => {
  if (!$('auto-recordings').checked || !recordingConnection || busy || recordingPolling || document.hidden) return;
  recordingPolling = true;
  try { await loadWorkbench(); if ($('auto-recordings').checked) await phoneRecordings(true); }
  catch (error) { resetRecordingTransfer(); status(`${error.message} Recording transfer paused; choose it again to retry.`); }
  finally { recordingPolling = false; }
}, 5000);
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
