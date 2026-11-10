'use strict';
const QaLensAgents = (() => {
  const text = (value, limit) => typeof value === 'string' ? [...value].slice(0, limit).join('') : '';
  function identity(node) {
    const result = {};
    for (const [key, limit] of Object.entries({id: 256, parentId: 256, windowId: 128, tag: 256, role: 80, label: 240})) {
      result[key] = text(node[key], limit);
    }
    result.actions = ['tap', 'type', 'scroll'].filter(action => node.actions?.includes(action));
    if ((node.value != null || result.actions.includes('type')) && result.label !== '[protected]') result.label = '[editable]';
    result.enabled = node.enabled !== false;
    result.bounds = {};
    for (const key of ['left', 'top', 'right', 'bottom']) {
      const value = node.bounds?.[key];
      if (Number.isFinite(value) && Math.abs(value) < 2 ** 53) result.bounds[key] = value;
    }
    return result;
  }

  function target(snapshot, node) {
    return {screen: text(snapshot.screen, 200), route: text(snapshot.route, 200), element: identity(node)};
  }

  function match(reference, snapshot) {
    if (!reference || !snapshot || reference.connectionId !== snapshot.connectionId) return null;
    const candidates = snapshot.nodes.filter(node => node.id === reference.node.id);
    if (candidates.length !== 1) return null;
    const [node] = candidates, fresh = target(snapshot, node);
    return fresh.screen === reference.screen && fresh.route === reference.route &&
      JSON.stringify(fresh.element) === JSON.stringify(identity(reference.node)) ? node : null;
  }

  function install({document, api, context, focusChanged, showElement}) {
    const $ = id => document.getElementById(`agent-${id}`);
    const requestDialog = $('request-dialog'), accessDialog = $('access-dialog');
    let access = null, anchor = null, draft = null, draftRevision = null, published = false, focus = null;
    let pending = false, polling = false, backToRequest = false, accessRevision = 0;

    function selection() {
      const {connectionId, ready, snapshot, selected} = context();
      if (!ready || !selected || snapshot?.connectionId !== connectionId) return null;
      return {connectionId, nodeId: selected.id, target: target(snapshot, selected)};
    }

    function sync() {
      const current = context(), connected = current.ready && !!current.connectionId;
      if (access && access.connectionId !== current.connectionId) access = null;
      if ((!connected || !access?.enabled) && focus) {
        focus = null;
        focusChanged(null);
      }
      $('access').textContent = access?.enabled ? 'Agent access on' : 'Agent access';
      $('access').disabled = current.busy;
      $('ask').disabled = current.busy || !selection();
      const selected = anchor && JSON.stringify(anchor) === JSON.stringify(selection());
      if (!selected && draft) {
        draft = null;
        if (requestDialog.open) $('request-status').textContent = 'The selection changed. Close this request and select the element again.';
      }
      const locked = pending || current.busy;
      $('intent').disabled = locked;
      $('task').disabled = locked;
      $('review').disabled = locked || !selected || !$('intent').value.trim();
      $('request-close').disabled = locked;
      $('request-access').disabled = locked;
      $('reviewed').hidden = !draft;
      $('prompt').value = draft?.prompt || '';
      $('copy').disabled = locked || !draft;
      $('share').disabled = locked || !draft || !access?.enabled || published;
      $('send').hidden = !access?.handoff;
      $('send').textContent = access?.handoff ? `Send to ${access.handoff}` : '';
      $('send').disabled = $('share').disabled;
      $('access-close').disabled = locked;
      $('enable').hidden = !!access?.enabled;
      $('enable').disabled = locked || !connected;
      $('disable').hidden = !access?.enabled;
      $('disable').disabled = locked;
      $('options').disabled = locked || !!access?.enabled;
      $('config-block').hidden = !access?.enabled;
      $('copy-config').disabled = locked;
    }

    function applyAccess(value) {
      if (value.connectionId !== context().connectionId) return;
      if (access?.connectionId === value.connectionId && value.revision < access.revision) return;
      const changed = access?.revision !== value.revision;
      access = value;
      if (changed) {
        $('allow-images').checked = value.screenshots;
        $('allow-diagnostics').checked = value.diagnostics;
        $('config').value = JSON.stringify(value.config, null, 2);
      }
      if (draft && value.revision > draftRevision) {
        draft = null;
        $('request-status').textContent = 'App inspection or MCP access changed. Review the prompt again.';
      }
      if (focus?.id !== value.focus?.id) {
        focus = value.focus;
        focusChanged(focus);
      }
      sync();
    }

    async function poll(force = false) {
      const {connectionId, ready} = context();
      const visibleDialog = requestDialog.open || accessDialog.open;
      if (polling || pending || (!force && (!ready || document.hidden || access && !access.enabled && !visibleDialog))) return;
      const revision = accessRevision;
      polling = true;
      try {
        const value = await api('agent/status');
        if (connectionId === context().connectionId && !pending && revision === accessRevision) applyAccess(value);
      } catch (error) {
        if (focus) { focus = null; focusChanged(null); }
        if (force) $('access-status').textContent = error.message;
      } finally { polling = false; }
    }

    async function run(message, action) {
      if (pending) return;
      pending = true; $(message).textContent = ''; sync();
      try { await action(); }
      catch (error) { $(message).textContent = error.message; }
      finally { pending = false; sync(); }
    }

    function openAccess(fromRequest) {
      backToRequest = fromRequest;
      if (fromRequest) requestDialog.close();
      $('access-status').textContent = context().ready ? '' : 'Connect and approve an app to enable MCP access.';
      sync();
      accessDialog.showModal();
      void poll(true);
    }

    $('access').onclick = () => openAccess(false);
    $('request-access').onclick = () => openAccess(true);
    $('access-close').onclick = () => accessDialog.close();
    accessDialog.addEventListener('close', () => {
      if (backToRequest) { backToRequest = false; requestDialog.showModal(); sync(); }
    });
    for (const dialog of [requestDialog, accessDialog]) {
      dialog.addEventListener('cancel', event => { if (pending) event.preventDefault(); });
    }

    async function setAccess(enabled) {
      const {connectionId} = context();
      ++accessRevision;
      const result = await api('agent/access', {enabled, screenshots: $('allow-images').checked,
        diagnostics: $('allow-diagnostics').checked}, connectionId);
      if (connectionId !== context().connectionId) return;
      draft = null;
      applyAccess(result);
      $('request-status').textContent = 'Review the prompt again after changing MCP access.';
      $('access-status').textContent = enabled ? 'Access is enabled for this phone session.' : 'MCP access revoked.';
    }
    $('enable').onclick = () => run('access-status', () => setAccess(true));
    $('disable').onclick = () => run('access-status', () => setAccess(false));

    $('ask').onclick = () => {
      anchor = selection();
      if (!anchor) return;
      draft = null;
      published = false;
      $('task').value = 'debug';
      $('intent').value = '';
      const {element, screen, route} = anchor.target;
      $('request-element').textContent = [element.tag || element.label || element.role || 'Selected element', route || screen].filter(Boolean).join(' · ');
      $('request-status').textContent = 'Review the exact prompt before copying or sharing it.';
      sync();
      requestDialog.showModal();
      $('intent').focus();
    };
    $('request-close').onclick = () => requestDialog.close();
    $('task').onchange = $('intent').oninput = () => {
      draft = null;
      $('request-status').textContent = '';
      sync();
    };

    $('review').onclick = () => run('request-status', async () => {
      if (!anchor || JSON.stringify(anchor) !== JSON.stringify(selection())) throw Error('Select the element again.');
      draft = null;
      sync();
      const {connectionId, ...selected} = anchor;
      const result = await api('agent/draft', {...selected, task: $('task').value, intent: $('intent').value}, connectionId);
      if (JSON.stringify(anchor) !== JSON.stringify(selection())) throw Error('The selection changed. Choose the element again.');
      draft = result.request;
      draftRevision = result.revision;
      published = false;
      $('request-status').textContent = access?.enabled ? 'Review the context, then copy or share this exact prompt.' :
        'You can copy this prompt now. To share through MCP, enable access and review again.';
    });

    async function copy(field, message) {
      const input = $(field);
      try { await navigator.clipboard.writeText(input.value); $(message).textContent = 'Copied.'; }
      catch (_) { input.focus(); input.select(); $(message).textContent = 'Text selected. Use your keyboard to copy it.'; }
    }
    $('copy').onclick = () => copy('prompt', 'request-status');
    $('copy-config').onclick = () => copy('config', 'access-status');

    async function share(deliver) {
      if (!draft || published || JSON.stringify(anchor) !== JSON.stringify(selection())) return;
      const result = await api('agent/send', {id: draft.id, deliver}, anchor.connectionId);
      if (anchor.connectionId !== context().connectionId) return;
      published = true;
      $('request-status').textContent = {
        accepted: 'The configured handoff accepted the request. It is also available through MCP.',
        unconfirmed: 'Direct delivery could not be confirmed. The request is available through MCP.',
        pending: 'Direct delivery is in progress. The request is available through MCP.',
        mcp: 'Shared with MCP. Ask your agent to read the request using its ID above.',
      }[result.delivery];
    }
    $('share').onclick = () => run('request-status', () => share(false));
    $('send').onclick = () => run('request-status', () => share(true));
    $('reference-open').onclick = () => {
      if (focus) void showElement(focus);
    };
    sync();
    return {sync, poll};
  }

  return {install, match};
})();
