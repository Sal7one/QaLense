/* Shared investigation panel. The desktop embeds this file through the web player;
 * it does not carry a second player, model form or report renderer.
 */
(function (root, factory) {
  const api = factory(typeof module === 'object' && module.exports ? require('./insights.js') : root.QaLensInsights);
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.QaLensInsightsUI = api;
})(typeof globalThis === 'object' ? globalThis : this, function (I) {
  'use strict';
  const delay = (ms, signal) => new Promise((resolve, reject) => {
    const abort = () => { clearTimeout(timer); signal?.removeEventListener('abort', abort); const e = Error('Cancelled'); e.name = 'AbortError'; reject(e); };
    const timer = setTimeout(() => { signal?.removeEventListener('abort', abort); resolve(); }, ms);
    if (signal?.aborted) abort(); else signal?.addEventListener('abort', abort, {once: true});
  });
  const fmt = ms => `${Math.floor(Math.max(0, ms) / 60000)}:${(Math.max(0, ms) / 1000 % 60).toFixed(1).padStart(4, '0')}`;
  function install(options) {
    const document = options.document || window.document, host = options.container;
    if (!host || !I) throw Error('Insights panel is unavailable.');
    const node = (tag, value, cls) => { const el = document.createElement(tag); if (value != null) el.textContent = value; if (cls) el.className = cls; return el; };
    function field(label, control, parent) { const el = node('label', null, 'lens-field'); el.append(node('span', label), control); parent.append(el); return control; }
    function button(label, parent, action, cls = '') { const el = node('button', label, `btn ${cls}`); el.type = 'button'; el.onclick = action; parent.append(el); return el; }
    function select(items) { const el = node('select'); for (const [value, label] of items) { const o = node('option', label); o.value = value; el.append(o); } return el; }
    const title = node('div', null, 'lens-title'); title.append(node('h3', 'Investigate a bug'), node('span', 'Lens 2.0', 'lens-badge')); host.append(title);
    host.append(node('p', 'Pause at the symptom, review the captured context, then ask your local model what happened and what to check next.', 'muted'));
    const importNotice = node('p', '', 'lens-disclosure'); importNotice.hidden = true; host.append(importNotice);
    const target = field('What are you investigating?', select([['recorded-app', 'The app originally recorded'], ['qalens-player', 'QaLens’s current replay player']]), host);
    const focusRow = node('div', null, 'lens-grid'); host.append(focusRow);
    const focusInput = field('Bug moment · seconds', node('input'), focusRow); focusInput.type = 'number'; focusInput.min = '0'; focusInput.step = '0.001'; focusInput.value = '0';
    const windowInput = field('Evidence window', select([['60000', '30s before + 30s after'], ['20000', '10s before + 10s after'], ['120000', '1 min before + 1 min after'], ['whole', 'Whole recording · bounded selection']]), focusRow);
    const focusAction = button('Use paused moment', focusRow, () => focus(options.playhead?.() || 0));
    const question = field('What went wrong? (optional)', node('textarea'), host); question.rows = 3; question.maxLength = 1600; question.placeholder = 'Expected video to resume after seeking; it stayed loading. What might explain it?';
    const qaInputs = node('div', null, 'lens-qa-inputs'); host.append(qaInputs);
    const expectedResult = field('Expected result · tester reported (optional)', node('textarea'), qaInputs); expectedResult.rows = 2; expectedResult.maxLength = 1600; expectedResult.placeholder = 'After clicking Play, the video resumes.';
    const actualResult = field('Actual result · tester reported (optional)', node('textarea'), qaInputs); actualResult.rows = 2; actualResult.maxLength = 1600; actualResult.placeholder = 'After clicking Play, the spinner stays visible.';
    host.append(node('p', 'These are your expectations and observations. They are labeled as tester reports and do not become captured proof.', 'muted small'));
    const review = node('details', null, 'lens-review'); review.open = true; host.append(review);
    const reviewTitle = node('summary', 'Review the evidence that will be sent'); review.append(reviewTitle);
    const evidenceSummary = node('p', '', 'muted'); review.append(evidenceSummary);
    const coverage = node('p', '', 'lens-coverage'); review.append(coverage);
    const evidenceList = node('div', null, 'lens-evidence-list'); review.append(evidenceList);
    const reviewActions = node('div', null, 'lens-actions'); review.append(reviewActions);
    const downloadEvidence = button('Export selected evidence JSON', reviewActions, () => download('qalens-insights-evidence.json', bundle));
    const refreshRuntime = button('Refresh current player facts', reviewActions, () => { currentRuntime = null; discardImage(); report = null; results.replaceChildren(); refresh(); }); refreshRuntime.hidden = true;
    const disclosure = node('p', 'Only the reviewed text evidence goes to the local URL when you press Analyze. Video, screenshots and audio are not sent. Captured values may still be sensitive; review them first.', 'lens-disclosure'); host.append(disclosure);
    const imageBox = node('details', null, 'lens-review'); imageBox.append(node('summary', 'Optional visual evidence · one reviewed still')); host.append(imageBox);
    const imageLabel = node('label', null, 'lens-image-choice'), includeImage = node('input'); includeImage.type = 'checkbox'; imageLabel.append(includeImage, node('span', 'Include one selected recording image · vision model required')); imageBox.append(imageLabel);
    imageBox.append(node('p', 'No full video or live phone screenshot is sent. HD recording pixels are unmasked; a selected still inherits that. Review its contents. A single still cannot establish motion or prove a freeze.', 'muted small'));
    const prepareImage = button('Prepare still at bug moment', imageBox, prepareStill); prepareImage.disabled = true;
    const imagePreview = node('img', null, 'lens-image-preview'); imagePreview.alt = 'Reviewed optional recording still'; imagePreview.hidden = true; imageBox.append(imagePreview);
    const imageStatus = node('p', 'Off by default. Prepare and review a saved recording still before Analyze.', 'muted small'); imageBox.append(imageStatus);
    const modelBox = node('div', null, 'lens-model-box'); host.append(modelBox);
    const modelRow = node('div', null, 'lens-grid'); modelBox.append(modelRow);
    const url = field('Local model URL', node('input'), modelRow); url.type = 'url'; url.value = 'http://127.0.0.1:1234'; url.placeholder = 'http://127.0.0.1:1234'; url.autocomplete = 'off';
    const connect = button('Connect model', modelRow, discover, 'btn-primary');
    const model = field('Installed chat model', select([['', 'Connect to discover models']]), modelBox); model.disabled = true;
    const advanced = node('details', null, 'lens-advanced'); advanced.append(node('summary', 'Advanced connection')); modelBox.append(advanced);
    const protocol = field('Protocol', select([['auto', 'Detect automatically'], ['openai', 'OpenAI compatible'], ['ollama', 'Ollama']]), advanced);
    protocol.value = 'openai';
    const apiKey = field('Optional API key · memory only', node('input'), advanced); apiKey.type = 'password'; apiKey.autocomplete = 'off'; apiKey.maxLength = 4096;
    modelBox.append(node('p', options.transport ? 'The Python GUI connects to the model on this PC; phone pairing credentials are never forwarded.' : 'The browser connects directly. If CORS blocks it, allow this player’s origin in your local model server or use the Python GUI.', 'muted small'));
    const actions = node('div', null, 'lens-actions'); host.append(actions);
    const analyze = button('Analyze with local model', actions, run, 'btn-primary'); analyze.disabled = true;
    const offline = button('Review offline signals', actions, () => { refresh(); report = I.offlineReport(bundle); renderReport(report, 'Offline · captured signals only'); });
    const cancelButton = button('Cancel', actions, cancel); cancelButton.hidden = true;
    const status = node('p', 'Offline signals are available without a model.', 'lens-status'); status.setAttribute('role', 'status'); status.setAttribute('aria-live', 'polite'); host.append(status);
    const results = node('div', null, 'lens-results'); host.append(results);
    let bundle = null, report = null, reportEvidence = null, importedBundle = null, connected = null, active = null, generation = 0, jobId = null, destroyed = false, reviewedImage = null, runtimeDetails = null, currentRuntime = null, prepareTimer = null, reviewDirty = false;
    const evidenceRows = new Map();
    const canPrepareImage = () => importedBundle ? !!importedBundle.images?.length : !!options.captureStill;
    const config = () => I.localConfig({baseUrl: url.value, protocol: connected?.protocol || protocol.value, model: model.value, apiKey: apiKey.value});
    const busy = () => !!active;
    function busyUi(on) {
      connect.disabled = on; focusInput.disabled = on || !!importedBundle; windowInput.disabled = on || !!importedBundle; question.disabled = on; expectedResult.disabled = on; actualResult.disabled = on; focusAction.disabled = on || !!importedBundle; target.disabled = on || !!importedBundle;
      url.disabled = on; protocol.disabled = on; apiKey.disabled = on; model.disabled = on || !connected?.models.length;
      offline.disabled = on || reviewDirty || !bundle; analyze.disabled = on || reviewDirty || !connected?.models.length || !model.value || !bundle;
      cancelButton.hidden = !on; downloadEvidence.disabled = reviewDirty || !bundle;
      includeImage.disabled = on || reviewDirty || !canPrepareImage(); prepareImage.disabled = on || reviewDirty || !includeImage.checked || !canPrepareImage() || !bundle;
      refreshRuntime.disabled = on || !bundle || !!importedBundle;
    }
    function disconnected() {
      if (active) cancel(); discardImage(); report = null; results.replaceChildren(); connected = null; model.replaceChildren(); const o = node('option', 'Connect to discover models'); o.value = ''; model.append(o); busyUi(false);
      status.textContent = 'Connection changed. Connect again before analyzing.';
    }
    url.oninput = disconnected; protocol.onchange = disconnected; apiKey.oninput = disconnected;
    model.onchange = () => { discardImage(); report = null; results.replaceChildren(); refresh(); };
    focusInput.onchange = windowInput.onchange = () => { discardImage(); currentRuntime = null; report = null; results.replaceChildren(); refresh(); };
    question.oninput = expectedResult.oninput = actualResult.oninput = () => {
      discardImage(); currentRuntime = null; report = null; results.replaceChildren(); clearTimeout(prepareTimer);
      reviewDirty = true;
      analyze.disabled = true; offline.disabled = true; downloadEvidence.disabled = true; status.textContent = 'Preparing the evidence for your updated question or QA results…';
      // Do not scan an hour of heavy logs and rebuild hundreds of details rows on
      // every key. Consent/results invalidate immediately; preparation waits for
      // a typing pause. Analyze never silently rebuilds the reviewed selection.
      prepareTimer = setTimeout(() => { prepareTimer = null; if (!destroyed) refresh(); }, 200);
    };
    target.onchange = () => { if (busy()) cancel(); discardImage(); currentRuntime = null; report = null; results.replaceChildren(); refresh(); status.textContent = target.value === 'qalens-player' ? 'Current QaLens replay facts are reviewed separately from the recorded app’s events.' : 'Investigating historical captured behavior of the recorded app.'; };
    includeImage.onchange = () => { discardImage(false); report = null; results.replaceChildren(); refresh(); };
    function discardImage(uncheck = true) {
      reviewedImage = null; imagePreview.removeAttribute('src'); imagePreview.hidden = true; if (uncheck) includeImage.checked = false;
      imageStatus.textContent = 'Off by default. Prepare and review a saved recording still before Analyze.';
      disclosure.textContent = 'Only the reviewed text evidence goes to the local URL when you press Analyze. Video, screenshots and audio are not sent. Captured values may still be sensitive; review them first.';
    }
    async function prepareStill() {
      if (busy() || reviewDirty || !includeImage.checked || !canPrepareImage() || !bundle) return;
      const controller = new AbortController(), mine = ++generation; active = controller; report = null; results.replaceChildren(); busyUi(true); status.textContent = 'Preparing one still from the saved recording for your review…';
      try {
        const image = importedBundle ? importedBundle.images[0] : await options.captureStill(bundle.recording.focusMs, controller.signal);
        if (mine !== generation || destroyed) return;
        const checked = I.withImage(bundle, image, true); reviewedImage = checked.images[0]; bundle = checked;
        imagePreview.src = `data:${reviewedImage.mediaType};base64,${reviewedImage.data}`; imagePreview.hidden = false;
        imageStatus.textContent = `Ready for review · ${fmt(reviewedImage.tMs)}${reviewedImage.approximate ? ' · approximate moment' : ''} · ${reviewedImage.width}×${reviewedImage.height}. Your selected model must support images; QaLens cannot certify that it interpreted the pixels.`;
        disclosure.textContent = 'Analyze will send the reviewed text and this one saved recording still to your local model. No full video or audio is sent. Check the still for sensitive unmasked content before proceeding.';
        renderEvidence(); status.textContent = 'Still prepared. Review the image before Analyze.';
      } catch (error) { if (mine === generation && !destroyed) { discardImage(); status.textContent = error.name === 'AbortError' ? 'Cancelled.' : error.message; } }
      finally { if (mine === generation && !destroyed) { active = null; busyUi(false); } }
    }
    function evidenceLink(id, parent) {
      if (id === 'player:runtime' && bundle?.investigation?.runtime) {
        const link = button(`player:runtime · ${importedBundle ? 'transferred' : 'current'} replay snapshot`, parent, () => { if (runtimeDetails) { runtimeDetails.open = true; runtimeDetails.scrollIntoView?.({block: 'nearest'}); } }, 'lens-citation');
        link.title = 'Show the player snapshot and its real observation clock; this is not an archived host event.'; return;
      }
      const item = bundle?.items.find(row => row.id === id) || bundle?.images?.find(image => image.id === id) || importedBundle?.images?.find(image => image.id === id); if (!item) return;
      const link = button(`${id} · ${fmt(item.tMs)}`, parent, () => {
        if (!importedBundle) { options.seek?.(item.tMs); return; }
        const row = evidenceRows.get(id); if (row) { row.open = true; row.scrollIntoView?.({block: 'nearest'}); }
      }, 'lens-citation'); link.title = importedBundle ? 'Review the copied evidence. The original recording video is not loaded.' : item.summary;
    }
    function refresh() {
      clearTimeout(prepareTimer); prepareTimer = null;
      if (importedBundle) {
        try {
          bundle = I.reviewImportedBundle(importedBundle, {question: question.value, qaContext: {expectedResult: expectedResult.value, actualResult: actualResult.value}});
          if (reviewedImage && includeImage.checked) bundle = I.withImage(bundle, reviewedImage, true);
          refreshRuntime.hidden = true; reviewDirty = false; renderEvidence();
        } catch (error) { bundle = null; reviewDirty = false; status.textContent = error.message; busyUi(busy()); }
        return;
      }
      const session = options.session?.();
      if (!session) { bundle = null; reviewDirty = false; evidenceList.replaceChildren(); evidenceSummary.textContent = 'Open a recording to investigate.'; coverage.textContent = ''; busyUi(busy()); return; }
      focusInput.max = String(session.duration / 1000);
      const focusMs = Math.max(0, Number(focusInput.value) * 1000 || 0);
      refreshRuntime.hidden = target.value !== 'qalens-player';
      if (target.value === 'qalens-player' && !currentRuntime) { const sampled = options.runtime?.(); currentRuntime = sampled ? I.runtimeEvidence(sampled, session.duration) : null; }
      bundle = I.buildBundle(session, {name: options.name?.(), focusMs, windowMs: windowInput.value === 'whole' ? null : Number(windowInput.value), question: question.value,
        qaContext: {expectedResult: expectedResult.value, actualResult: actualResult.value},
        target: target.value, runtime: target.value === 'qalens-player' ? currentRuntime : null});
      if (reviewedImage && includeImage.checked) bundle = I.withImage(bundle, reviewedImage, true);
      focusInput.value = String(bundle.recording.focusMs / 1000);
      reviewDirty = false;
      renderEvidence();
    }
    function renderEvidence() {
      if (!bundle) return;
      const omitted = bundle.omissions;
      evidenceSummary.textContent = `${bundle.items.length} selected observations${bundle.images?.length ? ' + one reviewed still' : ''} · ${fmt(bundle.recording.windowStartMs)}–${fmt(bundle.recording.windowEndMs)} · ${JSON.stringify(I.textBundle(bundle)).length.toLocaleString()} / 48,000 context characters. ${omitted.outsideWindow || 0} outside window; ${(omitted.itemLimit || 0) + (omitted.contextLimit || 0)} omitted by analysis limits; ${omitted.detailsTruncated || 0} details shortened.${omitted.invalidTimestamp || omitted.invalidTracks?.length ? ` Warning: ${omitted.invalidTimestamp || 0} invalid timestamps / ${omitted.invalidTracks?.length || 0} malformed tracks omitted.` : ''}`;
      const retention = options.recordingCoverage?.(options.session?.());
      coverage.textContent = importedBundle ? 'Phone handoff: only the supplied selected evidence is available. Load the original .sal to change the moment/window, review full tracks or play video. Missing events are not proof of healthy behavior.' : retention?.partial ? `Partial recording: ${retention.warnings.join(' ')}` : !bundle.coverage.retentionKnown ? 'Recording retention is unknown. A missing event cannot prove the app was healthy.' : 'Only retained captured evidence is available; missing signals do not establish absence of a bug.';
      evidenceList.replaceChildren();
      runtimeDetails = null; evidenceRows.clear();
      if (bundle.investigation?.runtime) {
        const runtime = bundle.investigation.runtime; runtimeDetails = node('details', null, 'lens-evidence lens-runtime');
        runtimeDetails.append(node('summary', `player:runtime · ${importedBundle ? 'transferred' : 'current'} ${runtime.client} replay snapshot; not a recorded host event`));
        runtimeDetails.append(node('p', `Observation clock: ${runtime.observedAtMillis} epoch ms. Referenced recording position: ${fmt(runtime.recordingPositionMs)}. ${importedBundle ? 'This supplied phone replay snapshot is historical, not the current web player state.' : 'This snapshot cannot explain earlier player behavior without a reproduction.'}`, 'muted small'));
        runtimeDetails.append(node('pre', JSON.stringify(runtime.details, null, 2))); evidenceList.append(runtimeDetails);
      } else if (target.value === 'qalens-player') evidenceList.append(node('p', 'Current player runtime evidence is unavailable; historical host events alone cannot diagnose the replay client.', 'lens-coverage'));
      for (const item of bundle.items) {
        const row = node('details', null, 'lens-evidence'); const head = node('summary'); const label = node('span', `${item.id} · ${fmt(item.tMs)} · ${item.summary}`); head.append(label); row.append(head);
        const jump = button(importedBundle ? 'Review copied evidence' : 'Seek to evidence', row, () => { if (importedBundle) row.open = true; else options.seek?.(item.tMs); }, 'small'); jump.title = importedBundle ? 'No full recording was transferred. Read this original captured observation.' : 'Seek the player without changing this investigation window';
        evidenceRows.set(item.id, row);
        row.append(node('pre', JSON.stringify(item.details, null, 2))); evidenceList.append(row);
      }
      if (importedBundle?.images?.length) {
        const image = importedBundle.images[0], row = node('details', null, 'lens-evidence'); row.append(node('summary', `images:0 · ${fmt(image.tMs)} · transferred saved still`));
        const preview = node('img', null, 'lens-image-preview'); preview.src = `data:${image.mediaType};base64,${image.data}`; preview.alt = 'Transferred saved recording still'; row.append(preview, node('p', 'Viewing this transferred image does not authorize a PC model request. Select Include image and Review transferred still to include it in a new analysis.', 'muted small'));
        evidenceRows.set(image.id, row); evidenceList.append(row);
      }
      if (!bundle.items.length) evidenceList.append(node('p', 'No timestamped observations in this window. Change the moment/window or add host capture integrations.', 'muted'));
      busyUi(busy());
    }
    function focus(ms) {
      if (importedBundle) { status.textContent = 'The phone’s selected moment and evidence window are fixed. Load the original .sal to choose another moment.'; return; }
      if (busy()) cancel(); discardImage(); currentRuntime = null; options.pause?.(); focusInput.value = String(Math.max(0, finite(ms)) / 1000); report = null; results.replaceChildren(); refresh();
      status.textContent = `Investigation at ${fmt(bundle?.recording.focusMs || 0)}. Review the context before Analyze.`;
    }
    function finite(value) { return typeof value === 'number' && Number.isFinite(value) ? value : 0; }
    function cancel() {
      clearTimeout(prepareTimer); prepareTimer = null;
      generation++; active?.abort(); active = null;
      if (jobId && options.transport) { const id = jobId; void options.transport.request('cancel', {id}).catch(() => {}); } jobId = null;
      if (reviewDirty) refresh();
      busyUi(false); status.textContent = 'Cancelled. No new result was accepted.';
    }
    async function discover() {
      if (busy()) return;
      const controller = new AbortController(), mine = ++generation; active = controller; discardImage(); report = null; results.replaceChildren(); busyUi(true); status.textContent = 'Connecting and discovering installed chat models…';
      try {
        const checked = I.localConfig({baseUrl: url.value, protocol: protocol.value, apiKey: apiKey.value});
        const result = options.transport ? await options.transport.request('models', {config: checked}, controller.signal) : await I.discover(checked, controller.signal, options.fetch);
        if (mine !== generation || destroyed) return;
        connected = {...result, models: (result.models || []).filter(entry => I.chatModel(entry.id))}; model.replaceChildren();
        for (const entry of connected.models) { const item = node('option', entry.name || entry.id); item.value = entry.id; model.append(item); }
        if (!connected.models.length) { const item = node('option', 'No installed chat models'); item.value = ''; model.append(item); }
        status.textContent = connected.models.length ? `Connected · ${result.protocol === 'ollama' ? 'Ollama' : 'OpenAI compatible'} · ${connected.models.length} installed chat model${connected.models.length === 1 ? '' : 's'}. Analyze sends only reviewed text.` : 'Connected, but no chat model is installed. Load an instruction/chat model in your local server, then Connect again. Embedding models cannot investigate bugs.';
      } catch (error) { if (mine === generation && !destroyed) { connected = null; status.textContent = error.name === 'AbortError' ? 'Cancelled.' : error.message; } }
      finally { if (mine === generation && !destroyed) { active = null; busyUi(false); } }
    }
    async function run() {
      if (busy() || !connected?.models.length) return;
      if (reviewDirty || prepareTimer) { status.textContent = 'Preparing updated evidence. Review the new selection before Analyze.'; return; }
      options.pause?.(); if (!bundle) return;
      const sentBundle = bundle, controller = new AbortController(), mine = ++generation; active = controller; report = null; results.replaceChildren(); busyUi(true);
      if (includeImage.checked && !reviewedImage) { active = null; busyUi(false); status.textContent = 'Prepare and review a still first, or uncheck Include image for text-only analysis.'; return; }
      status.textContent = sentBundle.images?.length ? 'Analyzing reviewed text and one still with your local model… No full video or audio is sent.' : 'Analyzing reviewed text with your local model… Video and pixels are not sent.';
      try {
        let answer;
        if (options.transport) {
          let response = await options.transport.request('analyze', {config: config(), bundle: sentBundle, includeImage: !!sentBundle.images?.length}, controller.signal);
          if (mine !== generation || destroyed) { if (response.job?.id) void options.transport.request('cancel', {id: response.job.id}).catch(() => {}); return; }
          jobId = response.job?.id;
          const deadline = Date.now() + 125000;
          while (['queued', 'running'].includes(response.job?.state)) {
            if (Date.now() > deadline) throw Error('The local model timed out. Narrow the window or use a smaller model.');
            await delay(600, controller.signal); response = await options.transport.request('jobs', {id: jobId}, controller.signal);
            if (mine !== generation || destroyed) return;
            status.textContent = `${response.job.state === 'queued' ? 'Queued' : 'Analyzing'} with ${response.job.model || model.value}…`;
          }
          if (response.job?.state === 'cancelled') { const error = Error('Cancelled'); error.name = 'AbortError'; throw error; }
          if (response.job?.state !== 'done') throw Error(response.job?.error || 'The local model could not complete this report.');
          answer = I.normalizeReport(response.job.report, sentBundle, {secrets: [apiKey.value]}); jobId = null;
        } else answer = await I.analyze(config(), sentBundle, controller.signal, options.fetch, {includeImage: !!sentBundle.images?.length});
        if (mine !== generation || destroyed) return;
        report = answer; renderReport(answer, `Local model · ${model.value}`); status.textContent = 'Analysis complete. Causes are hypotheses; use evidence links and next checks to verify them.';
      } catch (error) {
        if (mine === generation && !destroyed) {
          if (jobId && options.transport) { void options.transport.request('cancel', {id: jobId}).catch(() => {}); jobId = null; }
          status.textContent = error.name === 'AbortError' ? 'Cancelled.' : error.message;
        }
      } finally { if (mine === generation && !destroyed) { active = null; busyUi(false); } }
    }
    function renderReport(value, source, sourceBundle = bundle) {
      reportEvidence = sourceBundle;
      results.replaceChildren(); results.append(node('h3', 'What the evidence suggests'), node('p', `${source} · ${bundle?.investigation?.target === 'qalens-player' ? 'QaLens replay snapshot' : 'recorded app'} · ${value.pixelsProvided ? 'text + one supplied still; vision interpretation unverified' : 'text evidence'} · verify before treating a cause as established`, 'muted small'), node('p', value.summary, 'lens-report-summary'));
      if (value.groundingWarnings.length) { const warning = node('div', null, 'lens-warning'); for (const message of value.groundingWarnings) warning.append(node('p', message)); results.append(warning); }
      const qa = value.qaReport, qaBlock = node('div', null, 'lens-finding lens-qa-report'); qaBlock.append(node('h4', 'QA bug report'), node('h5', qa.title), node('h5', 'Captured steps'));
      if (qa.steps.length) {
        const steps = node('ol');
        for (const step of qa.steps) { const row = node('li'); row.append(node('p', step.action)); const links = node('div', null, 'lens-citations'); for (const id of step.evidenceIds) evidenceLink(id, links); row.append(links); steps.append(row); }
        qaBlock.append(steps, node('p', 'These selected recorded actions may be partial. Verify that they reproduce the issue.', 'muted small'));
      } else qaBlock.append(node('p', bundle?.investigation?.target === 'qalens-player' ? 'No replay-control action trace was supplied. Archived app actions describe the originally recorded app, so they are not presented as QaLens player reproduction steps.' : 'No captured actions were selected. Reproduce and document the steps; no clicks or actions have been invented.', 'muted small'));
      qaBlock.append(node('h5', `Expected result · ${qa.expectedSource === 'tester' ? 'tester reported' : 'not provided'}`), node('p', qa.expectedResult, 'lens-qa-value'));
      qaBlock.append(node('h5', `Actual result · ${qa.actualSource === 'tester' ? 'tester reported' : qa.actualSource === 'captured-evidence' ? 'Model interpretation of cited evidence' : 'not established'}`), node('p', qa.actualResult, 'lens-qa-value')); results.append(qaBlock);
      results.append(node('h4', 'Observed facts'));
      if (!value.observations.length) results.append(node('p', 'No cited captured facts were returned.', 'muted'));
      for (const observation of value.observations) { const row = node('div', null, 'lens-finding'); row.append(node('p', observation.text)); const links = node('div', null, 'lens-citations'); for (const id of observation.evidenceIds) evidenceLink(id, links); row.append(links); results.append(row); }
      results.append(node('h4', 'Possible causes · not a diagnosis'));
      if (!value.hypotheses.length) results.append(node('p', 'The selected evidence does not support a specific cause.', 'muted'));
      for (const hypothesis of value.hypotheses) {
        const row = node('div', null, 'lens-finding'); row.append(node('h5', hypothesis.title), node('span', `${hypothesis.confidence} confidence${hypothesis.evidenceBacked === false ? ' · unverified / no supporting evidence' : ''}`, 'lens-confidence'), node('p', hypothesis.reasoning));
        const links = node('div', null, 'lens-citations'); for (const id of hypothesis.evidenceIds) evidenceLink(id, links); row.append(links);
        if (hypothesis.nextChecks.length) { const list = node('ul'); for (const check of hypothesis.nextChecks) list.append(node('li', check)); row.append(list); } results.append(row);
      }
      for (const [heading, entries] of [['Missing evidence', value.missingEvidence], ['What to check next', value.recommendedChecks]]) {
        results.append(node('h4', heading)); const list = node('ul'); for (const entry of entries) list.append(node('li', entry)); results.append(list);
      }
      const exports = node('div', null, 'lens-actions');
      button('Copy QA report', exports, async () => {
        const markdown = I.qaMarkdown(value);
        try { if (options.copy) await options.copy(markdown); else { const clipboard = globalThis.navigator?.clipboard; if (!clipboard) throw Error('Clipboard unavailable'); await clipboard.writeText(markdown); } status.textContent = 'QA report copied as Markdown.'; }
        catch { const fallback = node('textarea'); fallback.value = markdown; fallback.readOnly = true; fallback.rows = 12; field('QA Markdown · select and copy', fallback, results); fallback.focus?.(); fallback.select?.(); status.textContent = 'Clipboard access is unavailable. The QA report is ready below to select and copy.'; }
      });
      button('Export investigation JSON', exports, () => download('qalens-investigation.json', {schema: 'qalens-investigation-transfer/1', bundle: reportEvidence, report: value})); results.append(exports);
    }
    function download(name, data) {
      if (!data) return;
      if (options.download) { options.download(name, data); return; }
      const blob = new Blob([JSON.stringify(data, null, 2)], {type: 'application/json'}), objectUrl = URL.createObjectURL(blob), link = node('a'); link.href = objectUrl; link.download = name; document.body.append(link); link.click(); link.remove(); setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
    }
    refresh();
    function importInvestigation(document) {
      const checked = I.validateInvestigation(document);
      cancel(); discardImage(); importedBundle = checked.bundle; currentRuntime = null; report = null; reportEvidence = null; results.replaceChildren();
      question.maxLength = 2000; question.value = checked.bundle.question; expectedResult.value = checked.bundle.qaContext?.expectedResult || ''; actualResult.value = checked.bundle.qaContext?.actualResult || '';
      target.value = checked.bundle.investigation?.target || 'recorded-app'; focusInput.value = String(checked.bundle.recording.focusMs / 1000); focusInput.max = String(checked.bundle.recording.durationMs / 1000);
      importNotice.hidden = false; importNotice.textContent = `Phone investigation · ${checked.bundle.recording.name}. Review selected evidence and the supplied interpretation. Analyze is a separate PC model request; model settings and credentials were not transferred.`;
      prepareImage.textContent = 'Review transferred still'; refresh();
      if (checked.report) { report = checked.report; renderReport(report, 'Phone report · supplied interpretation', checked.bundle); }
      status.textContent = 'Phone investigation opened. No model request or automatic save occurred. Review the evidence before any new analysis.';
      return checked;
    }
    return {focus, refresh, cancel, busy, importInvestigation, clear() { cancel(); discardImage(); importedBundle = null; importNotice.hidden = true; question.maxLength = 1600; prepareImage.textContent = 'Prepare still at bug moment'; currentRuntime = null; report = null; reportEvidence = null; question.value = ''; expectedResult.value = ''; actualResult.value = ''; results.replaceChildren(); focusInput.value = '0'; refresh(); },
      destroy() { cancel(); destroyed = true; options.transport?.close(); host.replaceChildren(); }};
  }
  return {install};
});
