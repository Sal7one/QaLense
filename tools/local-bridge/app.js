'use strict';
const $ = id => document.getElementById(id);
let snapshot = null, selected = null, busy = false;
const status = text => { $('status').textContent = text; };
async function api(path, command) {
  const headers = {Authorization: `Bearer ${$('token').value}`};
  if (command) headers['Content-Type'] = 'application/json';
  const response = await fetch(`/api/${path}`, {method: command ? 'POST' : 'GET', headers, body: command ? JSON.stringify(command) : undefined, cache: 'no-store'});
  const result = await response.json();
  if (!response.ok || result.ok === false) throw Error(result.error || `HTTP ${response.status}`);
  return result;
}
async function perform(work) {
  if (busy) return;
  busy = true; document.querySelectorAll('button').forEach(b => { b.disabled = true; });
  try { await work(); } catch (error) { status(error.message); }
  finally { busy = false; document.querySelectorAll('button').forEach(b => { b.disabled = false; }); }
}
async function refresh() {
  snapshot = await api('snapshot');
  selected = snapshot.nodes.find(n => n.id === selected?.id) || null;
  $('screen').textContent = `${snapshot.screen} · ${snapshot.nodes.length} visible nodes`;
  status(`Tree refreshed. ${snapshot.omittedNodes} nodes omitted by limit. Actions report acceptance; refresh to verify results.`);
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
  frame.setAttribute('width', snapshot.viewport.width); frame.setAttribute('height', snapshot.viewport.height);
  frame.setAttribute('class', 'viewport'); $('map').append(frame);
  const parents = new Map(snapshot.nodes.map(n => [n.id, n.parentId]));
  for (const node of filtered()) {
    let depth = 0, ancestor = node.parentId;
    const seen = new Set();
    while (ancestor && !seen.has(ancestor) && depth < 12) { seen.add(ancestor); depth++; ancestor = parents.get(ancestor); }
    const button = document.createElement('button'); button.className = `node${selected?.id === node.id ? ' selected' : ''}`;
    button.style.paddingInlineStart = `${10 + depth * 12}px`;
    button.textContent = `${node.tag ? `[${node.tag}] ` : ''}${node.label} · ${node.actions.join(', ') || node.role || 'node'}`;
    button.onclick = () => choose(node); $('tree').append(button);
    const rect = document.createElementNS('http://www.w3.org/2000/svg', 'rect'), b = node.bounds;
    rect.setAttribute('x', b.left); rect.setAttribute('y', b.top); rect.setAttribute('width', b.right-b.left); rect.setAttribute('height', b.bottom-b.top);
    rect.setAttribute('class', selected?.id === node.id ? 'selected' : '');
    const title = document.createElementNS('http://www.w3.org/2000/svg', 'title'); title.textContent = node.tag || node.label;
    rect.append(title); rect.onclick = () => choose(node); $('map').append(rect);
  }
}
function choose(node) { selected = node; render(); details(); }
function details() {
  $('summary').textContent = selected ? `${selected.label}\nTag: ${selected.tag || 'none'} · ${selected.role || 'Component'} · ${selected.enabled ? 'Enabled' : 'Disabled'}\n${selected.bounds.right-selected.bounds.left} × ${selected.bounds.bottom-selected.bounds.top} px` : 'Choose a tree node or a rectangle.';
  $('details').textContent = selected ? JSON.stringify(selected, null, 2) : 'Choose an element to see its raw semantics.';
  $('actions').replaceChildren();
  if (!selected) return;
  for (const action of ['select', ...selected.actions]) {
    const button = document.createElement('button'); button.textContent = action === 'select' ? 'Highlight on device' : action;
    button.onclick = () => perform(async () => {
      const command = {action, id: selected.id};
      if (action === 'type') { const text = prompt('Set field text (sent to the host, never saved by this tool)'); if (text === null) return; command.text = text; }
      if (action === 'scroll') { const dy = prompt('Vertical scroll in pixels (positive scrolls forward)', '400'); if (dy === null) return; command.dy = Number(dy); if (!Number.isFinite(command.dy)) throw Error('Enter a finite scroll delta'); }
      await api('command', command); await refresh();
    }); $('actions').append(button);
  }
}
$('connect').onsubmit = event => { event.preventDefault(); perform(refresh); };
$('refresh').onclick = () => perform(refresh);
$('search').oninput = render; $('tagged').onchange = render;
$('events').onclick = () => perform(async () => { $('observations').textContent = JSON.stringify(await api('events'), null, 2); status('Read recent observations. No complete-recording claim.'); });
