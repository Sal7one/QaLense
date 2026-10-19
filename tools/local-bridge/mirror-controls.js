'use strict';
// Geometry is shared by pointer, wheel and inspection; letterbox space is never a phone target.
(function (root) {
  const clamp = (n, low, high) => Math.max(low, Math.min(high, n));
  function point(box, width, height, clientX, clientY, outside = false) {
    if (![box.width, box.height, width, height].every(n => Number.isFinite(n) && n > 0)) return null;
    const scale = Math.min(box.width / width, box.height / height);
    const x = (clientX - box.left - (box.width - width * scale) / 2) / (width * scale);
    const y = (clientY - box.top - (box.height - height * scale) / 2) / (height * scale);
    if (!Number.isFinite(x) || !Number.isFinite(y) || (!outside && (x < 0 || x > 1 || y < 0 || y > 1))) return null;
    return {x: clamp(x, 0, 1), y: clamp(y, 0, 1)};
  }
  function gesture(start, end, elapsed, width, height) {
    const distance = Math.hypot((end.x - start.x) * width, (end.y - start.y) * height);
    if (distance < 12) return {action: elapsed >= 550 ? 'long-press' : 'tap', ...start, duration: clamp(Math.round(elapsed), 600, 1500)};
    return {action: 'swipe', ...start, endX: end.x, endY: end.y, duration: clamp(Math.round(elapsed), 150, 1500)};
  }
  function wheel(at, dx, dy, width, height) {
    if (!Number.isFinite(dx) || !Number.isFinite(dy) || Math.max(Math.abs(dx), Math.abs(dy)) < 1) return null;
    const horizontal = Math.abs(dx) > Math.abs(dy), delta = horizontal ? dx : dy;
    const travel = clamp(Math.abs(delta) / (horizontal ? width : height), .12, .4) * Math.sign(delta);
    const axis = horizontal ? 'x' : 'y', start = {...at}, end = {...at};
    start[axis] = clamp(at[axis] + travel / 2, .12, .88);
    end[axis] = clamp(start[axis] - travel, .08, .92);
    return {action: 'swipe', ...start, endX: end.x, endY: end.y, duration: 300};
  }
  function split(shares, index, delta, width) {
    if (!(width > 0)) return shares;
    const sizes = shares.map(n => n * width), minima = [250, 310, 180];
    delta = clamp(delta, minima[index] - sizes[index], sizes[index + 1] - minima[index + 1]);
    sizes[index] += delta; sizes[index + 1] -= delta;
    return sizes.map(n => n / width);
  }
  function hit(nodes, windows, x, y) {
    const contains = b => b && x >= b.left && x <= b.right && y >= b.top && y <= b.bottom;
    // New SDKs identify each window: empty dialog space must not hit the Activity underneath.
    // Older SDKs retain their existing smallest-element selection contract.
    const foreground = Array.isArray(windows) && windows.length
      ? windows.filter(w => contains(w.bounds)).sort((a, b) => b.order - a.order)[0] : null;
    if (Array.isArray(windows) && windows.length && !foreground) return null;
    const rank = n => (n.tag ? 4 : 0) + (n.actions?.length ? 2 : 0) + (n.text?.length || n.description?.length ? 1 : 0);
    return nodes.filter(n => contains(n.bounds) && (!foreground || n.windowId === foreground.id))
      .sort((a, b) => (a.bounds.right-a.bounds.left)*(a.bounds.bottom-a.bounds.top) - (b.bounds.right-b.bounds.left)*(b.bounds.bottom-b.bounds.top) || rank(b) - rank(a))[0] || null;
  }
  function installLayout(document, storage) {
    const workspace = document.getElementById('workspace');
    if (!workspace?.getBoundingClientRect) return; // Non-browser regression harnesses.
    const key = 'qalens.desktop.layout.v1', defaults = {shares: [.38, .40, .22], height: 700};
    let layout = {...defaults, shares: [...defaults.shares]};
    try {
      const saved = JSON.parse(storage.getItem(key));
      if (saved && Array.isArray(saved.shares) && saved.shares.length === 3 && saved.shares.every(n => Number.isFinite(n) && n > .1 && n < .8) && Math.abs(saved.shares.reduce((a, b) => a + b) - 1) < .01 && Number.isFinite(saved.height))
        layout = {shares: saved.shares, height: clamp(saved.height, 480, 1400)};
    } catch (_) { /* Browser storage can be disabled. Layout still works. */ }
    const handles = [document.getElementById('resize-mirror'), document.getElementById('resize-tree')];
    const heightHandle = document.getElementById('resize-height');
    function measuredShares() {
      // CSS can enforce a pane minimum after the window shrinks. Begin the next drag at the
      // actual rendered widths so a saved large-window layout never jumps under the pointer.
      const width = workspace.getBoundingClientRect().width - 24;
      const sizes = ['.mirror-panel', '.selection-panel', '.tree-panel'].map(selector => workspace.querySelector(selector)?.getBoundingClientRect().width || 0);
      return width > 0 && Math.abs(sizes.reduce((a, b) => a + b) - width) < 2 ? sizes.map(n => n / width) : [...layout.shares];
    }
    function apply(save = false) {
      workspace.style.setProperty('--mirror-width', `${layout.shares[0]}fr`);
      workspace.style.setProperty('--selection-width', `${layout.shares[1]}fr`);
      workspace.style.setProperty('--tree-width', `${layout.shares[2]}fr`);
      workspace.style.setProperty('--workspace-height', `${layout.height}px`);
      handles.forEach((h, i) => h.setAttribute('aria-valuenow', String(Math.round(layout.shares[i] * 100))));
      heightHandle.setAttribute('aria-valuenow', String(Math.round(layout.height)));
      if (save) try { storage.setItem(key, JSON.stringify(layout)); } catch (_) { /* Nonessential preference. */ }
    }
    [...handles, heightHandle].forEach((handle, index) => {
      let drag = null;
      handle.addEventListener('pointerdown', e => {
        if (e.button !== 0) return;
        e.preventDefault(); handle.setPointerCapture(e.pointerId);
        drag = {x: e.clientX, y: e.clientY, shares: measuredShares(), height: layout.height};
        document.body.classList.add('resizing');
      });
      handle.addEventListener('pointermove', e => {
        if (!drag) return;
        if (index === 2) layout.height = clamp(drag.height + e.clientY - drag.y, 480, 1400);
        else layout.shares = split(drag.shares, index, e.clientX - drag.x, workspace.getBoundingClientRect().width - 24);
        apply();
      });
      const finish = () => { if (!drag) return; drag = null; document.body.classList.remove('resizing'); apply(true); };
      handle.addEventListener('pointerup', finish); handle.addEventListener('lostpointercapture', finish);
      handle.addEventListener('pointercancel', finish);
      handle.addEventListener('keydown', e => {
        const delta = e.key === 'ArrowLeft' || e.key === 'ArrowUp' ? -30 : e.key === 'ArrowRight' || e.key === 'ArrowDown' ? 30 : 0;
        if (!delta && e.key !== 'Home') return;
        e.preventDefault();
        if (e.key === 'Home') layout = {...defaults, shares: [...defaults.shares]};
        else if (index === 2) layout.height = clamp(layout.height + delta, 480, 1400);
        else layout.shares = split(measuredShares(), index, delta, workspace.getBoundingClientRect().width - 24);
        apply(true);
      });
      handle.addEventListener('dblclick', () => { layout = {...defaults, shares: [...defaults.shares]}; apply(true); });
    });
    document.getElementById('reset-layout').onclick = () => { layout = {...defaults, shares: [...defaults.shares]}; apply(true); };
    apply();
  }
  const api = {point, gesture, wheel, split, hit, installLayout};
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.QaLensMirror = api;
})(typeof globalThis === 'undefined' ? this : globalThis);
