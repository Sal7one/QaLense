/*
 * app-v2.js — QaLens Mission Control v2: the .sal player, redesigned.
 * Vanilla JS, no build step, no dependencies beyond sal.js (the .sal reader).
 *
 * Structure (small modules inside one IIFE, in dependency order):
 *   util   — esc/fmt/toast/percentile helpers
 *   prefs  — persisted settings (localStorage)
 *   store  — the open session + playhead + ui state
 *   media  — frames/video sync to the playhead
 *   derive — pure projections: events, screens, insights, stats
 *   render — DOM writers (escaped innerHTML, render-signature caching)
 *   app    — wiring: loading, transport, tracks, compare, backend hook, keyboard
 */
(function () {
  "use strict";

  const SLOW_MS = 1500;
  const SPEEDS = [0.5, 1, 2, 4];
  const ICON_PLAY = '<svg width="16" height="16"><use href="#i-play"/></svg>';
  const ICON_PAUSE = '<svg width="16" height="16"><use href="#i-pause"/></svg>';

  // ── util ──────────────────────────────────────────────────────────────────
  const $ = (id) => document.getElementById(id);
  const clampHead = (v) => Math.max(store.S.start, Math.min(store.S.end, v));
  const esc = (s) => String(s == null ? "" : s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
  function fmt(msRel) {
    const t = Math.max(0, Math.floor(msRel / 1000));
    return String(Math.floor(t / 60)).padStart(2, "0") + ":" + String(t % 60).padStart(2, "0");
  }
  function fmtFine(msRel) {
    const ms = Math.max(0, msRel);
    return fmt(ms) + "." + Math.floor((ms % 1000) / 100);
  }
  function fmtBytes(n) {
    if (n >= 1e6) return (n / 1e6).toFixed(1) + " MB";
    if (n >= 1e3) return (n / 1e3).toFixed(0) + " KB";
    return n + " B";
  }
  function percentile(sorted, p) {
    if (!sorted.length) return 0;
    return sorted[Math.min(sorted.length - 1, Math.floor((p / 100) * sorted.length))];
  }
  let toastTimer = null;
  function toast(msg, ms = 2600) {
    const el = els.toast;
    el.textContent = msg; el.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { el.hidden = true; }, ms);
  }

  // ── prefs ─────────────────────────────────────────────────────────────────
  const LS = {
    get(k, d) { try { const v = localStorage.getItem("qalens2." + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
    set(k, v) { try { localStorage.setItem("qalens2." + k, JSON.stringify(v)); } catch {} },
  };
  const prefs = {
    theme: LS.get("theme", "dark"),
    follow: LS.get("follow", true),
    track: LS.get("track", "timeline"),
    logLevel: LS.get("logLevel", "all"),
    speed: LS.get("speed", 1),
    autoplay: LS.get("autoplay", false),
    compact: LS.get("compact", false),
    theater: LS.get("theater", false),
    backend: LS.get("backend", ""),
    expanded: LS.get("expanded", { summary: true, state: true, device: true }),
  };

  // ── elements ──────────────────────────────────────────────────────────────
  const els = {
    landing: $("landing"), dropzone: $("dropzone"), stage: $("stage"),
    fileInput: $("fileInput"), compareInput: $("compareInput"),
    statbar: $("statbar"), sessionChip: $("sessionChip"),
    chipApp: $("chipApp"), chipEnv: $("chipEnv"), chipScore: $("chipScore"),
    video: $("video"), frame: $("frame"), noMedia: $("noMedia"), screenName: $("screenName"),
    filmstrip: $("filmstrip"), scrubber: $("scrubber"), scrubTicks: $("scrubTicks"), time: $("time"),
    playPause: $("playPause"), stepPrev: $("stepPrev"), stepNext: $("stepNext"),
    jumpErr: $("jumpErr"), markBtn: $("markBtn"), speedBtn: $("speedBtn"),
    theaterBtn: $("theaterBtn"), fsBtn: $("fsBtn"),
    trackTabs: $("trackTabs"), trackList: $("trackList"), reportView: $("reportView"),
    tracksTools: $("tracksTools"), search: $("search"), logChips: $("logChips"),
    followToggle: $("followToggle"), substats: $("substats"), aiBar: $("aiBar"), aiCopy: $("aiCopy"),
    cTimeline: $("cTimeline"), cNetwork: $("cNetwork"), cLogs: $("cLogs"),
    cScreens: $("cScreens"), cInsights: $("cInsights"),
    summaryBody: $("summaryBody"), stateBody: $("stateBody"), stateTime: $("stateTime"), deviceBody: $("deviceBody"),
    exportBtn: $("exportBtn"), sendBackendBtn: $("sendBackendBtn"), compareBtn: $("compareBtn"),
    exportSalBtn: $("exportSalBtn"), closeSessionBtn: $("closeSessionBtn"),
    drawer: $("drawer"), drawerClose: $("drawerClose"), settingsBtn: $("settingsBtn"), themeBtn: $("themeBtn"),
    setTheme: $("setTheme"), setDensity: $("setDensity"), setSpeed: $("setSpeed"),
    setAutoplay: $("setAutoplay"), setFollow: $("setFollow"),
    setBackend: $("setBackend"), backendOpen: $("backendOpen"),
    toast: $("toast"), compatNote: $("compatNote"), deviceFrame: $("deviceFrame"),
    markOverlay: $("markOverlay"), markLabel: $("markLabel"), markTime: $("markTime"),
    markSave: $("markSave"), markCancel: $("markCancel"),
  };

  // ── store ─────────────────────────────────────────────────────────────────
  const store = {
    S: null,           // the parsed session (sal.js shape)
    S2: null,          // comparison session
    rawBuf: null,      // original .sal bytes (Export .sal / backend hook)
    name: "",
    playhead: 0,
    playing: false,
    speed: SPEEDS.includes(prefs.speed) ? prefs.speed : 1,
    track: prefs.track,
    logLevel: prefs.logLevel,
    localMarks: [],
    diffOpen: false,
    cache: { events: null, screens: null, insights: null },
    renderSig: "",
    objectUrls: [],
    pendingSeekSec: null,
    rafId: null, lastReal: 0, lastHeavy: 0,
  };

  // ── derive (pure projections over store.S) ───────────────────────────────
  function frameAt(ts) {
    const f = store.S.frames;
    if (!f.length) return null;
    let lo = 0, hi = f.length - 1, ans = 0;
    while (lo <= hi) { const m = (lo + hi) >> 1; if (f[m].ts <= ts) { ans = m; lo = m + 1; } else hi = m - 1; }
    return f[ans];
  }
  function stateAt(ts) {
    let cur = store.S.state[0] || null;
    for (const s of store.S.state) { if (s.ts <= ts) cur = s; else break; }
    return cur;
  }
  function allEvents() {
    if (store.cache.events) return store.cache.events;
    const tag = (arr, isErr) => arr.map((e) => ({ ts: e.ts, isError: isErr(e) }));
    store.cache.events = []
      .concat(tag(store.S.timeline, (e) => !!e.isError))
      .concat(tag(store.S.network, (e) => e.status >= 400 || !!e.error))
      .concat(tag(store.S.logs, (e) => /error|\[ERROR\]|fail|exception/i.test(e.message || "")))
      .sort((a, b) => a.ts - b.ts);
    return store.cache.events;
  }
  function screenVisits() {
    if (store.cache.screens) return store.cache.screens;
    const visits = [];
    for (const s of store.S.state) {
      const name = s.screen || "Unknown";
      const last = visits[visits.length - 1];
      if (last && last.name === name) last.end = s.ts;
      else { if (last) last.end = s.ts; visits.push({ name, start: s.ts, end: s.ts }); }
    }
    const last = visits[visits.length - 1];
    if (last) last.end = store.S.end;
    store.cache.screens = visits;
    return visits;
  }
  function insights() {
    if (store.cache.insights) return store.cache.insights;
    if (store.S.analysis && Array.isArray(store.S.analysis.anomalies) && store.S.analysis.anomalies.length) {
      store.cache.insights = store.S.analysis.anomalies.map((a) => ({
        sev: (a.kind === "failed_request" || a.kind === "error_burst") ? "err" : "warn",
        ts: store.S.start + (a.tMs || 0),
        title: a.title || a.kind,
        detail: (a.detail || "") + "  ·  recorded on device",
      }));
      return store.cache.insights;
    }
    const out = [];
    const failed = store.S.network.filter((e) => e.error || e.status >= 400);
    if (failed.length) out.push({ sev: "err", ts: failed[0].ts, title: failed.length + " failed request" + (failed.length > 1 ? "s" : ""), detail: failed.slice(0, 3).map((e) => e.method + " " + shortPath(e.url) + " → " + (e.error || e.status)).join("  ·  ") });
    const slow = store.S.network.filter((e) => !e.error && e.status < 400 && e.latencyMs >= SLOW_MS);
    if (slow.length) {
      const worst = slow.reduce((a, b) => (a.latencyMs > b.latencyMs ? a : b));
      out.push({ sev: "warn", ts: worst.ts, title: slow.length + " slow request" + (slow.length > 1 ? "s" : "") + " (≥" + SLOW_MS + "ms)", detail: "worst: " + worst.method + " " + shortPath(worst.url) + " took " + worst.latencyMs + "ms" });
    }
    const errs = allEvents().filter((e) => e.isError);
    for (let i = 0; i + 2 < errs.length; i++) {
      if (errs[i + 2].ts - errs[i].ts <= 10000) {
        out.push({ sev: "err", ts: errs[i].ts, title: "Error burst", detail: "3+ errors within " + Math.round((errs[i + 2].ts - errs[i].ts) / 1000) + "s starting at " + fmt(errs[i].ts - store.S.start) });
        break;
      }
    }
    for (let i = 1; i < store.S.state.length; i++) {
      const prev = store.S.state[i - 1].featureFlags || {}, cur = store.S.state[i].featureFlags || {};
      for (const k of Object.keys(cur)) {
        if (k in prev && prev[k] !== cur[k]) out.push({ sev: "warn", ts: store.S.state[i].ts, title: "Feature flag flipped: " + k, detail: (prev[k] ? "ON" : "OFF") + " → " + (cur[k] ? "ON" : "OFF") });
      }
    }
    const visits = screenVisits();
    for (let i = 0; i + 2 < visits.length; i++) {
      if (visits[i + 2].start - visits[i].start <= 4000) {
        out.push({ sev: "warn", ts: visits[i].start, title: "Rapid screen churn", detail: visits[i].name + " → " + visits[i + 1].name + " → " + visits[i + 2].name });
        break;
      }
    }
    const heavy = store.S.network.filter((e) => (e.responseBytes || 0) > 1e6);
    if (heavy.length) {
      const worst = heavy.reduce((a, b) => ((a.responseBytes || 0) > (b.responseBytes || 0) ? a : b));
      out.push({ sev: "warn", ts: worst.ts, title: heavy.length + " heavy response" + (heavy.length > 1 ? "s" : "") + " (>1MB)", detail: "largest: " + shortPath(worst.url) + " · " + fmtBytes(worst.responseBytes || 0) });
    }
    if (!out.length) out.push({ sev: "ok", ts: store.S.start, title: "No anomalies detected", detail: "No failures, slow calls, error bursts, flag flips, or screen churn in this session." });
    out.sort((a, b) => a.ts - b.ts);
    store.cache.insights = out;
    return out;
  }
  function shortPath(url) { try { const u = new URL(url); return u.pathname || url; } catch { return url; } }
  function hostOf(url) { try { return new URL(url).host; } catch { return ""; } }
  function scoreClass(n) { return n >= 85 ? "ok" : n >= 70 ? "warn" : n >= 50 ? "warn2" : "err"; }
  function allMarks() { return (store.S.marks || []).concat(store.localMarks); }
  function filmstripHalfStep() {
    const f = store.S.frames;
    if (f.length < 2) return 250;
    const sorted = f.map((x) => x.ts).sort((a, b) => a - b);
    let sum = 0;
    for (let i = 1; i < sorted.length; i++) sum += sorted[i] - sorted[i - 1];
    return Math.min(750, Math.round(sum / (sorted.length - 1) / 2));
  }

  // ── media ─────────────────────────────────────────────────────────────────
  function setupMedia() {
    els.scrubber.max = store.S.duration;
    els.scrubber.value = 0;
    if (store.S.videoUrl) {
      els.video.src = store.S.videoUrl; els.video.hidden = false;
      els.frame.hidden = true; els.noMedia.hidden = true;
      els.video.onended = pause;
      els.video.onloadedmetadata = () => {
        if (store.pendingSeekSec != null) {
          seek(store.S.start + store.pendingSeekSec * 1000, true);
          toast("Opened at " + fmtFine(store.playhead - store.S.start) + " (from link)");
          store.pendingSeekSec = null;
        } else syncMedia(true);
      };
    } else if (store.S.frames.length) {
      els.frame.hidden = false; els.video.hidden = true; els.noMedia.hidden = true;
    } else {
      els.noMedia.hidden = false; els.frame.hidden = true; els.video.hidden = true;
    }
  }
  function videoBase() {
    if (store.S.manifest.videoStartMillis) return store.S.manifest.videoStartMillis;
    if (els.video.duration > 0) return store.S.end - els.video.duration * 1000;
    return store.S.start;
  }
  function syncMedia(force) {
    if (store.S.videoUrl) {
      if (force && els.video.readyState >= 1 && Number.isFinite(els.video.duration)) {
        try { els.video.currentTime = Math.max(0, (store.playhead - videoBase()) / 1000); } catch {}
      }
    } else if (store.S.frames.length) {
      const f = frameAt(store.playhead);
      if (f && els.frame.src !== f.url) els.frame.src = f.url;
    }
    const st = stateAt(store.playhead);
    els.screenName.textContent = st && st.screen ? st.screen : "";
  }

  // ── playback ──────────────────────────────────────────────────────────────
  function applySpeed() {
    els.speedBtn.textContent = (store.speed % 1 ? store.speed.toFixed(1) : store.speed) + "×";
    if (store.S && store.S.videoUrl) els.video.playbackRate = store.speed;
  }
  function cycleSpeed() {
    store.speed = SPEEDS[(SPEEDS.indexOf(store.speed) + 1) % SPEEDS.length];
    prefs.speed = store.speed; LS.set("speed", store.speed);
    els.setSpeed.value = String(store.speed);
    applySpeed(); toast("Speed " + store.speed + "×", 1200);
  }
  function play() {
    if (store.playing || !store.S) return;
    store.playing = true; els.playPause.innerHTML = ICON_PAUSE;
    store.lastReal = performance.now(); store.lastHeavy = 0;
    if (store.S.videoUrl) { els.video.playbackRate = store.speed; els.video.play().catch(() => {}); }
    store.rafId = requestAnimationFrame(loop);
  }
  function pause() {
    store.playing = false; els.playPause.innerHTML = ICON_PLAY;
    if (store.S && store.S.videoUrl) els.video.pause();
    if (store.rafId) cancelAnimationFrame(store.rafId);
  }
  function loop(now) {
    if (!store.playing) return;
    if (store.S.videoUrl) {
      store.playhead = clampHead(videoBase() + els.video.currentTime * 1000);
      if (els.video.ended) pause();
    } else {
      store.playhead = clampHead(store.playhead + (now - store.lastReal) * store.speed);
      store.lastReal = now;
      if (store.playhead >= store.S.end) pause();
    }
    syncMedia(false);
    const heavy = now - store.lastHeavy > 200;
    updatePlayheadUI(heavy);
    if (heavy) store.lastHeavy = now;
    if (store.playing) store.rafId = requestAnimationFrame(loop);
  }
  function seek(ts, fromUser) {
    store.playhead = clampHead(ts);
    if (fromUser) pause();
    syncMedia(true);
    updatePlayheadUI(true);
  }
  function stepEvent(dir) {
    const ev = allEvents();
    if (!ev.length) return;
    if (dir > 0) {
      let lo = 0, hi = ev.length, ans = ev.length;
      while (lo < hi) { const m = (lo + hi) >> 1; if (ev[m].ts > store.playhead) { ans = m; hi = m; } else lo = m + 1; }
      if (ans < ev.length) seek(ev[ans].ts, true);
    } else {
      let lo = 0, hi = ev.length, ans = -1;
      while (lo < hi) { const m = (lo + hi) >> 1; if (ev[m].ts < store.playhead) { ans = m; lo = m + 1; } else hi = m; }
      if (ans < 0) return;
      const target = ev[ans].ts;
      while (ans > 0 && ev[ans - 1].ts === target) ans--;
      seek(ev[ans].ts, true);
    }
  }
  function jumpError() {
    const e = allEvents().find((x) => x.isError);
    if (e) seek(e.ts, true); else toast("No errors in this session");
  }
  function updatePlayheadUI(heavy) {
    els.scrubber.value = store.playhead - store.S.start;
    els.scrubber.style.setProperty("--prog", (((store.playhead - store.S.start) / store.S.duration) * 100).toFixed(2) + "%");
    els.time.textContent = fmtFine(store.playhead - store.S.start) + " / " + fmt(store.S.duration);
    if (heavy) {
      renderState();
      highlightFilmstrip();
      // While the compare/diff view is open, the track list IS the diff — never rebuild it
      // with track rows during playback (the classic player had this bug).
      if (prefs.follow && store.track !== "report" && store.track !== "aibrief" && !store.diffOpen) renderTrack();
    }
  }

  // ── render: session chrome ───────────────────────────────────────────────
  function renderChip() {
    const m = store.S.manifest, a = m.app || {};
    els.sessionChip.hidden = false;
    els.chipApp.textContent = (a.name || "session") + (a.version ? " v" + a.version : "");
    els.chipEnv.textContent = m.environment || "no env";
    const sc = store.S.summary ? store.S.summary.score : null;
    els.chipScore.textContent = sc == null ? "—" : sc + "/100";
    els.chipScore.className = "score " + (sc == null ? "" : scoreClass(sc));
  }
  function renderStats() {
    const failed = store.S.network.filter((e) => e.error || e.status >= 400).length;
    const slow = store.S.network.filter((e) => !e.error && e.status < 400 && e.latencyMs >= SLOW_MS).length;
    const errLogs = store.S.logs.filter((e) => /error|\[ERROR\]|fail|exception/i.test(e.message || "")).length;
    const sc = store.S.summary ? store.S.summary.score : null;
    const crashes = store.S.analysis?.stats?.crashes ?? 0;
    const jankRate = store.S.analysis?.jank?.jankRate ?? null;
    const cells = [
      [fmt(store.S.duration), "duration", ""],
      [sc == null ? "—" : sc, "score", sc == null ? "" : scoreClass(sc)],
      [store.S.network.length, "requests", ""],
      [failed, "failed", failed ? "err" : "ok"],
      [slow, "slow", slow ? "warn" : "ok"],
      [store.S.timeline.length, "events", ""],
      [errLogs, "error logs", errLogs ? "err" : "ok"],
      [screenVisits().length, "screens", ""],
    ];
    if (crashes > 0) cells.push([crashes, "crashes", "err"]);
    if (jankRate != null && jankRate > 0) cells.push([jankRate + "%", "jank", jankRate > 30 ? "err" : "warn"]);
    els.statbar.innerHTML = cells.map(([v, l, cls]) =>
      '<div class="stat"><b class="' + cls + '">' + esc(v) + '</b><span>' + esc(l) + '</span></div>').join("");
  }
  function renderSummary() {
    const s = store.S.summary;
    if (!s) { els.summaryBody.innerHTML = '<p class="muted">No summary in this recording.</p>'; return; }
    const sc = s.score || 0, cls = scoreClass(sc);
    const penalties = (s.penalties || []).map((p) =>
      '<div class="kv"><span>' + esc(p.dimension) + '</span><b class="err">-' + p.points + '</b></div>').join("");
    const reasons = (s.reasons || []).map((r) => '<li>' + esc(r) + '</li>').join("");
    const steps = ((s.repro && s.repro.steps) || []).map((x) => '<li>' + esc(x) + '</li>').join("");
    els.summaryBody.innerHTML =
      '<div class="summary-top">' +
        '<div class="donut ' + cls + '" style="--p:' + sc + '"><div class="donut-hole"><span class="donut-num">' + sc + '</span><span class="donut-cap">/100</span></div></div>' +
        '<div>' +
          '<div class="band band-' + cls + '">' + esc(s.band || "") + '</div>' +
          '<div class="kv"><span>Likely owner</span><b>' + esc(s.category || "Unknown") + ' · ' + esc(s.confidence || "") + '</b></div>' +
          '<ul class="reasons">' + reasons + '</ul>' +
        '</div>' +
      '</div>' +
      (penalties ? '<div class="subhead">Why this score</div>' + penalties : "") +
      (steps ? '<div class="subhead">Reproduction</div><ol class="steps">' + steps + '</ol>' +
        '<div class="ea"><div><span class="ea-l ok">Expected</span> ' + esc(s.repro.expected) + '</div>' +
        '<div><span class="ea-l err">Actual</span> ' + esc(s.repro.actual) + '</div></div>' : "");
  }
  function renderState() {
    const st = stateAt(store.playhead);
    els.stateTime.textContent = fmt(store.playhead - store.S.start);
    if (!st) { els.stateBody.innerHTML = '<p class="muted">No state captured.</p>'; return; }
    const flags = Object.entries(st.featureFlags || {}).map(([k, v]) =>
      '<div class="kv"><span>' + esc(k) + '</span><b class="' + (v ? "ok" : "muted") + '">' + (v ? "ON" : "OFF") + '</b></div>').join("");
    let data = "";
    for (const [src, kv] of Object.entries(st.dataSources || {})) {
      const rows = Object.entries(kv).map(([k, v]) => '<div class="kv"><span>' + esc(k) + '</span><b>' + esc(v) + '</b></div>').join("");
      data += '<div class="subhead">' + esc(src) + '</div>' + rows;
    }
    els.stateBody.innerHTML =
      '<div class="kv"><span>Screen</span><b>' + esc(st.screen || "—") + '</b></div>' +
      (st.route ? '<div class="kv"><span>Route</span><b>' + esc(st.route) + '</b></div>' : "") +
      (flags ? '<div class="subhead">Feature flags</div>' + flags : "") +
      data;
  }
  function renderDevice() {
    const m = store.S.manifest, a = m.app || {}, c = m.counts || {};
    const tiles = [
      ["App", esc((a.name || "") + " " + (a.version || ""))],
      ["Env · variant", esc((m.environment || "—") + " · " + (a.variant || "—"))],
      ["Git", esc(m.gitSha || "—")],
      ["Device", esc(m.device || "—")],
      ["Android", esc(m.androidVersion || "—")],
      ["Recorded", new Date(m.createdAtMillis || Date.now()).toLocaleString()],
      ["Duration", fmt(store.S.duration)],
      ["Captured", (c.frames || 0) + " frames · " + (c.network || 0) + " net · " + (c.logs || 0) + " logs"],
    ].map(([k, v]) => '<div class="kvt"><span>' + k + '</span><b>' + v + '</b></div>').join("");
    els.deviceBody.innerHTML = '<div class="kv-grid">' + tiles + '</div>';
  }
  function renderCounts() {
    els.cTimeline.textContent = store.S.timeline.length;
    els.cNetwork.textContent = store.S.network.length;
    els.cLogs.textContent = store.S.logs.length;
    els.cScreens.textContent = screenVisits().length;
    els.cInsights.textContent = insights().filter((i) => i.sev !== "ok").length || "";
  }
  function renderFilmstrip() {
    if (!store.S.frames.length) { els.filmstrip.hidden = true; els.filmstrip.innerHTML = ""; return; }
    els.filmstrip.hidden = false;
    const max = 14, step = Math.max(1, Math.floor(store.S.frames.length / max));
    const picks = [];
    for (let i = 0; i < store.S.frames.length; i += step) picks.push(store.S.frames[i]);
    els.filmstrip.innerHTML = picks.map((f) =>
      '<img src="' + f.url + '" data-ts="' + f.ts + '" alt="" title="' + fmt(f.ts - store.S.start) + '" />').join("");
  }
  function highlightFilmstrip() {
    if (els.filmstrip.hidden) return;
    let cur = null;
    for (const img of els.filmstrip.children) {
      if (Number(img.dataset.ts) <= store.playhead) cur = img;
      img.classList.remove("cur");
    }
    if (cur) cur.classList.add("cur");
  }
  function renderTicks() {
    const evs = allEvents();
    const plain = evs.filter((e) => !e.isError).slice(0, 300);
    const errs = evs.filter((e) => e.isError).slice(0, 200);
    const tick = (ts, cls, title) =>
      '<i class="' + cls + '" style="left:' + (((ts - store.S.start) / store.S.duration) * 100).toFixed(2) + '%"' + (title ? ' title="' + esc(title) + '"' : "") + '></i>';
    const stars = allMarks().map((m) => tick(m.ts, "star", (m.label || "bookmark") + " · " + (m.severity || "info"))).join("");
    els.scrubTicks.innerHTML = plain.map((e) => tick(e.ts, "", "")).join("") + errs.map((e) => tick(e.ts, "err", "")).join("") + stars;
  }

  // ── render: tracks ───────────────────────────────────────────────────────
  function statusPill(e) {
    const code = e.error ? "ERR" : (e.status === 0 ? "…" : e.status);
    const cls = e.error || e.status >= 400 ? "err" : e.status >= 300 ? "warn" : "ok";
    return '<span class="pill ' + cls + '">' + esc(code) + '</span>';
  }
  function networkSubstats() {
    const lat = store.S.network.filter((e) => !e.error).map((e) => e.latencyMs).sort((a, b) => a - b);
    const failed = store.S.network.filter((e) => e.error || e.status >= 400).length;
    const slow = store.S.network.filter((e) => !e.error && e.status < 400 && e.latencyMs >= SLOW_MS).length;
    const avg = lat.length ? Math.round(lat.reduce((a, b) => a + b, 0) / lat.length) : 0;
    const hosts = [...new Set(store.S.network.map((e) => hostOf(e.url)).filter(Boolean))];
    const bytes = store.S.network.reduce((n, e) => n + (e.responseBytes || 0), 0);
    return '<span><b>' + store.S.network.length + '</b> requests</span>' +
      '<span class="' + (failed ? "err" : "") + '"><b>' + failed + '</b> failed</span>' +
      '<span class="' + (slow ? "warn" : "") + '"><b>' + slow + '</b> slow</span>' +
      '<span>avg <b>' + avg + 'ms</b></span>' +
      '<span>p95 <b>' + percentile(lat, 95) + 'ms</b></span>' +
      '<span><b>' + fmtBytes(bytes) + '</b> down</span>' +
      '<span><b>' + hosts.length + '</b> host' + (hosts.length === 1 ? "" : "s") + '</span>';
  }
  function rowsFor(track) {
    const q = els.search.value.trim().toLowerCase();
    let items;
    if (track === "timeline") {
      const markItems = allMarks().map((m) => ({
        ts: m.ts, isError: false,
        main: "★ " + (m.label || "bookmark"),
        sub: (m.local ? "" : "from the recording · ") + (m.severity || "info"),
        tag: "bookmark", text: ("★ " + (m.label || "") + " " + (m.severity || "")).toLowerCase(),
        _bookmark: true, _sev: m.severity || "info", _markId: m.id || null, _local: !!m.local,
      }));
      items = store.S.timeline.map((e) => ({
        ts: e.ts, isError: !!e.isError, main: e.title || "", sub: e.detail || "", tag: e.kind || "",
        text: (e.title + " " + (e.detail || "")).toLowerCase(),
      })).concat(markItems).sort((a, b) => a.ts - b.ts);
    } else if (track === "network") {
      const maxLat = Math.max(1, ...store.S.network.map((e) => e.latencyMs || 0));
      items = store.S.network.map((e) => {
        const cls = e.error || e.status >= 400 ? "err" : e.latencyMs >= SLOW_MS ? "warn" : "";
        const w = Math.max(2, Math.round(((e.latencyMs || 0) / maxLat) * 100));
        const off = Math.round(((e.ts - store.S.start) / store.S.duration) * (100 - w));
        return {
          ts: e.ts, isError: e.error || e.status >= 400,
          main: e.method + " " + shortPath(e.url),
          sub: e.latencyMs + "ms · " + fmtBytes(e.responseBytes || 0) + (e.error ? " · " + e.error : "") + " · " + hostOf(e.url),
          pill: statusPill(e), detail: e.url,
          extra: '<div class="waterfall"><i class="' + cls + '" style="left:' + off + '%;width:' + w + '%"></i></div>',
          text: (e.method + " " + e.url).toLowerCase(),
        };
      });
    } else {
      const raw = store.S.logs.filter((e) => store.logLevel === "all" || e.type === store.logLevel);
      const collapsed = [];
      for (const e of raw) {
        const last = collapsed[collapsed.length - 1];
        if (last && last.message === e.message && last.type === e.type && last.tag === e.tag) last._n++;
        else collapsed.push({ ...e, _n: 1 });
      }
      items = collapsed.map((e) => ({
        ts: e.ts, isError: /error|\[ERROR\]|fail|exception/i.test(e.message || ""),
        main: (e.message || "") + (e._n > 1 ? "   ×" + e._n : ""),
        sub: "[" + e.type + "]" + (e.tag ? " " + e.tag : ""), tag: e.type,
        text: ((e.message || "") + " " + (e.tag || "")).toLowerCase(),
      }));
    }
    if (q) items = items.filter((i) => i.text.includes(q));
    items.sort((a, b) => a.ts - b.ts);
    return items.slice().reverse();
  }
  function renderTrack() {
    if (store.track === "report" || store.track === "aibrief") return;
    if (store.track === "screens") return renderScreens();
    if (store.track === "insights") return renderInsights();
    const visible = rowsFor(store.track);
    if (!visible.length) { els.trackList.innerHTML = '<div class="empty">No entries.</div>'; store.renderSig = ""; return; }
    const sig = store.track + "|" + els.search.value.trim().toLowerCase() + "|" + store.logLevel + "|" + visible.length + "|" + prefs.follow;
    if (sig === store.renderSig && els.trackList.children.length === visible.length) {
      updateRowHighlighting(visible);
      return;
    }
    store.renderSig = sig;
    const curIdx = prefs.follow ? visible.findIndex((i) => i.ts <= store.playhead) : -1;
    els.trackList.innerHTML = visible.map((i, idx) => {
      const cur = idx === curIdx;
      const future = prefs.follow && i.ts > store.playhead;
      const bkmrk = i._bookmark ? " row-bookmark sev-" + (i._sev || "info") : "";
      const sevChip = i._bookmark ? '<span class="sev-chip ' + (i._sev || "info") + '">' + esc(i._sev || "info") + '</span>' : "";
      const del = i._bookmark && i._local
        ? '<button class="mark-del" data-mark-id="' + esc(i._markId) + '" title="Delete this mark">✕</button>'
        : "";
      return '<div class="row ' + (i.isError ? "row-err" : "") + bkmrk + (cur ? " row-cur" : "") + (future ? " row-future" : "") + '" data-ts="' + i.ts + '" data-detail="' + (i.detail ? esc(i.detail) : "") + '">' +
        '<div class="row-time">' + fmtFine(i.ts - store.S.start) + '</div>' +
        '<div class="row-body">' +
          '<div class="row-main">' + (i.pill || "") + (i.tag ? '<span class="kind">' + esc(i.tag) + '</span>' : "") + '<span>' + esc(i.main) + '</span>' + sevChip + '</div>' +
          (i.sub ? '<div class="row-sub">' + esc(i.sub) + '</div>' : "") +
          (i.extra || "") +
        '</div>' +
        del +
        '<button class="row-jump">↧</button>' +
      '</div>';
    }).join("");
  }
  function updateRowHighlighting(visible) {
    const kids = els.trackList.children;
    let curIdx = prefs.follow ? -1 : -2;
    if (prefs.follow) {
      for (let i = 0; i < visible.length; i++) {
        if (visible[i].ts <= store.playhead) curIdx = i; else break;
      }
    }
    for (let i = 0; i < kids.length; i++) {
      kids[i].classList.toggle("row-cur", i === curIdx);
      kids[i].classList.toggle("row-future", prefs.follow && visible[i].ts > store.playhead);
    }
  }
  function renderScreens() {
    const visits = screenVisits();
    if (!visits.length) { els.trackList.innerHTML = '<div class="empty">No state samples in this recording.</div>'; return; }
    const maxDur = Math.max(1, ...visits.map((v) => v.end - v.start));
    els.trackList.innerHTML = visits.map((v) => {
      const cur = store.playhead >= v.start && store.playhead < v.end;
      return '<div class="screen-visit ' + (cur ? "row-cur" : "") + '" data-ts="' + v.start + '">' +
        '<div class="sv-name">' + esc(v.name) + '</div>' +
        '<div class="sv-bar"><i style="width:' + Math.max(3, Math.round(((v.end - v.start) / maxDur) * 100)) + '%"></i></div>' +
        '<div class="sv-dur">' + fmt(v.end - v.start) + '</div>' +
        '<button class="row-jump">↧</button>' +
      '</div>';
    }).join("");
  }
  function renderInsights() {
    els.trackList.innerHTML = insights().map((i) =>
      '<div class="insight ' + i.sev + '">' +
        '<h5>' + esc(i.title) + '</h5>' +
        '<p>' + esc(i.detail) + '</p>' +
        (i.sev === "ok" ? "" : '<button class="row-jump" data-ts="' + i.ts + '">↧ jump to ' + fmt(i.ts - store.S.start) + '</button>') +
      '</div>').join("");
  }
  function setTrack(track) {
    // Switching tracks while the diff is open closes the compare first (the user asked for a track).
    if (store.diffOpen) { revokeS2(); store.S2 = null; store.diffOpen = false; }
    store.track = track; prefs.track = track; LS.set("track", track);
    store.renderSig = "";
    [...els.trackTabs.children].forEach((b) => b.classList.toggle("active", b.dataset.track === track));
    els.logChips.hidden = track !== "logs";
    const isReport = track === "report", isAi = track === "aibrief";
    els.reportView.hidden = !(isReport || isAi);
    els.trackList.hidden = isReport || isAi;
    els.aiBar.hidden = !isAi;
    els.search.hidden = isReport || isAi || track === "screens" || track === "insights";
    els.substats.hidden = track !== "network";
    if (track === "network") els.substats.innerHTML = networkSubstats();
    if (isReport) els.reportView.textContent = store.S.report || "(no report)";
    else if (isAi) els.reportView.textContent = store.S.forAi || "(this recording predates for_ai.md)";
    else renderTrack();
  }

  // ── session lifecycle ────────────────────────────────────────────────────
  function revokeUrls() { store.objectUrls.forEach(URL.revokeObjectURL); store.objectUrls = []; }
  function revokeS2() {
    if (!store.S2) return;
    (store.S2.frames || []).forEach((f) => { if (f.url) URL.revokeObjectURL(f.url); });
    if (store.S2.videoUrl) URL.revokeObjectURL(store.S2.videoUrl);
  }
  async function loadFile(file) {
    try {
      toast("Reading " + file.name + "…", 1500);
      const buf = await file.arrayBuffer();
      const session = await SAL.read(buf);
      onLoaded(session, file.name, buf);
    } catch (e) {
      console.error(e);
      toast("⚠ " + (e.message || "Failed to read file"), 5000);
    }
  }
  function onLoaded(session, name, rawBuf) {
    revokeUrls();
    store.S = session;
    store.S2 = null;
    store.rawBuf = rawBuf || null;
    store.name = name;
    store.localMarks = [];
    store.cache = { events: null, screens: null, insights: null };
    store.objectUrls = session.frames.map((f) => f.url).concat(session.videoUrl ? [session.videoUrl] : []);
    store.playhead = session.start;
    store.playing = false;

    els.landing.hidden = true;
    els.stage.hidden = false;
    els.exportBtn.hidden = false;
    els.sendBackendBtn.hidden = false;
    els.compareBtn.hidden = false;
    els.exportSalBtn.hidden = false;
    els.closeSessionBtn.hidden = false;

    setupMedia();
    renderChip();
    renderStats();
    renderSummary();
    renderDevice();
    renderCounts();
    renderFilmstrip();
    renderTicks();
    setTrack(store.track);
    syncMedia(true);
    updatePlayheadUI(true);
    applySpeed();
    document.title = "QaLens · " + ((session.manifest.app && session.manifest.app.name) || "session");
    if (store.pendingSeekSec != null && !session.videoUrl) {
      seek(session.start + store.pendingSeekSec * 1000, true);
      toast("Opened at " + fmtFine(store.playhead - session.start) + " (from link)");
      store.pendingSeekSec = null;
    } else if (prefs.autoplay) play();
  }
  function closeSession() {
    pause();
    revokeUrls(); revokeS2();
    store.S = null; store.S2 = null; store.rawBuf = null;
    els.stage.hidden = true;
    els.landing.hidden = false;
    els.exportBtn.hidden = true; els.sendBackendBtn.hidden = true; els.compareBtn.hidden = true;
    els.exportSalBtn.hidden = true; els.closeSessionBtn.hidden = true;
    els.sessionChip.hidden = true;
    document.title = "QaLens Mission Control v2 — .sal session viewer";
  }

  // ── compare ───────────────────────────────────────────────────────────────
  async function loadCompare(file) {
    try {
      revokeS2();
      store.S2 = await SAL.read(await file.arrayBuffer());
      renderDiff();
    } catch (e) { toast("⚠ " + (e.message || "Failed to read comparison file"), 5000); }
  }
  function sessionModel(s) {
    const failed = s.network.filter((e) => e.error || e.status >= 400).map((e) => e.method + " " + shortPath(e.url));
    const anomalies = (s.analysis?.anomalies || []).map((a) => a.title || a.kind);
    const screens = [...new Set(s.state.map((st) => st.screen || "Unknown"))];
    return {
      appName: (s.manifest.app && s.manifest.app.name) || "app",
      appVersion: (s.manifest.app && s.manifest.app.version) || "",
      environment: s.manifest.environment || "",
      score: s.summary ? s.summary.score : null,
      likelyOwner: s.summary ? s.summary.category : null,
      screensVisited: screens,
      failedRequests: failed,
      crashCount: s.analysis?.stats?.crashes ?? 0,
      anomalyTitles: anomalies,
    };
  }
  function renderDiff() {
    if (!store.S || !store.S2) return;
    const a = sessionModel(store.S), b = sessionModel(store.S2);
    const scoreDelta = (b.score ?? 0) - (a.score ?? 0);
    const addedFail = b.failedRequests.filter((f) => !a.failedRequests.includes(f));
    const resolved = a.failedRequests.filter((f) => !b.failedRequests.includes(f));
    const addedScreens = b.screensVisited.filter((s) => !a.screensVisited.includes(s));
    const removedScreens = a.screensVisited.filter((s) => !b.screensVisited.includes(s));
    const newAnom = b.anomalyTitles.filter((an) => !a.anomalyTitles.includes(an));
    const isRegression = scoreDelta < 0 || addedFail.length > 0 || b.crashCount > a.crashCount;
    const rows = [
      ["Score", a.score ?? "—", b.score ?? "—", (scoreDelta >= 0 ? "+" : "") + scoreDelta, scoreDelta < 0],
      ["Failed requests", a.failedRequests.length, b.failedRequests.length, "+" + addedFail.length + " / -" + resolved.length, addedFail.length > 0],
      ["Crashes", a.crashCount, b.crashCount, "+" + (b.crashCount - a.crashCount), b.crashCount > a.crashCount],
      ["Likely owner", a.likelyOwner ?? "—", b.likelyOwner ?? "—", a.likelyOwner !== b.likelyOwner ? "changed" : "", a.likelyOwner !== b.likelyOwner],
    ];
    const table = '<table class="diff-table"><thead><tr><th></th><th>Baseline</th><th>Current</th><th>Δ</th></tr></thead><tbody>' +
      rows.map(([label, base, curr, delta, bad]) =>
        '<tr><td>' + label + '</td><td>' + esc(String(base)) + '</td><td>' + esc(String(curr)) + '</td><td class="' + (bad ? "err" : "") + '">' + esc(delta) + '</td></tr>').join("") +
      '</tbody></table>';
    const lists = [];
    if (addedFail.length) lists.push('<div class="diff-section"><h4>New failures (not in baseline)</h4><ul>' + addedFail.map((f) => '<li class="err">' + esc(f) + '</li>').join("") + '</ul></div>');
    if (resolved.length) lists.push('<div class="diff-section"><h4>Resolved (fixed since baseline)</h4><ul>' + resolved.map((f) => '<li class="ok">' + esc(f) + '</li>').join("") + '</ul></div>');
    if (addedScreens.length) lists.push('<div class="diff-section"><h4>New screens</h4><ul>' + addedScreens.map((s) => '<li>' + esc(s) + '</li>').join("") + '</ul></div>');
    if (removedScreens.length) lists.push('<div class="diff-section"><h4>Screens no longer visited</h4><ul>' + removedScreens.map((s) => '<li>' + esc(s) + '</li>').join("") + '</ul></div>');
    if (newAnom.length) lists.push('<div class="diff-section"><h4>New anomalies</h4><ul>' + newAnom.map((an) => '<li class="warn">' + esc(an) + '</li>').join("") + '</ul></div>');
    els.trackList.innerHTML =
      '<div class="diff-view">' +
        '<div style="display:flex;justify-content:space-between;align-items:center"><h3 style="margin:0 0 10px">Session Diff</h3>' +
        '<button class="btn small" id="diffClose">✕ Close compare</button></div>' +
        (isRegression ? '<div class="diff-banner err">⚠ Regression — score ' + (scoreDelta >= 0 ? "+" : "") + scoreDelta + ', +' + addedFail.length + ' new failures</div>' : '<div class="diff-banner ok">✓ No regression — score ' + (scoreDelta >= 0 ? "+" : "") + scoreDelta + ', ' + resolved.length + ' resolved</div>') +
        table + lists.join("") +
      '</div>';
    els.trackList.hidden = false;
    els.reportView.hidden = true;
    els.substats.hidden = true; els.aiBar.hidden = true; els.search.hidden = true;
    store.diffOpen = true;
    $("diffClose").onclick = () => { revokeS2(); store.S2 = null; store.diffOpen = false; setTrack(store.track); };
  }

  // ── export + backend hook ─────────────────────────────────────────────────
  function exportSummary() {
    const m = store.S.manifest, a = m.app || {}, s = store.S.summary;
    const failed = store.S.network.filter((e) => e.error || e.status >= 400);
    const lines = [
      "## QA Session — " + (a.name || "app") + " " + (a.version || ""), "",
      "| | |", "|---|---|",
      "| Environment | " + (m.environment || "—") + " |",
      "| Device | " + (m.device || "—") + " (Android " + (m.androidVersion || "—") + ") |",
      "| Score | " + (s ? s.score + "/100 (" + (s.band || "") + ")" : "—") + " |",
      "| Likely owner | " + (s ? (s.category || "—") + " · " + (s.confidence || "") : "—") + " |",
    ];
    if (s && s.repro && (s.repro.steps || []).length) {
      lines.push("", "**Repro steps**");
      s.repro.steps.forEach((x) => lines.push("- " + x));
      lines.push("", "**Expected:** " + s.repro.expected, "**Actual:** " + s.repro.actual);
    }
    if (failed.length) {
      lines.push("", "**Failed requests**");
      failed.slice(0, 10).forEach((e) => lines.push("- `" + e.method + " " + shortPath(e.url) + "` → " + (e.error || e.status) + " (" + e.latencyMs + "ms)"));
    }
    const marks = allMarks();
    if (marks.length) {
      lines.push("", "**Bookmarks**");
      marks.forEach((mk) => lines.push("- `t=" + ((mk.ts - store.S.start) / 1000).toFixed(1) + "` ★ " + (mk.label || "bookmark") + " (" + (mk.severity || "info") + ")"));
    }
    const text = lines.join("\n");
    (navigator.clipboard ? navigator.clipboard.writeText(text) : Promise.reject())
      .then(() => toast("Session summary copied as markdown ✓"))
      .catch(() => { console.log(text); toast("Clipboard blocked — summary printed to console"); });
  }
  // ── marks: label + severity, dedup, delete ──────────────────────────────
  function markExistsNear(ts) {
    return allMarks().some((m) => Math.abs(m.ts - ts) < 300);
  }
  function openMarkDialog() {
    if (!store.S) return;
    els.markTime.textContent = "at " + fmtFine(store.playhead - store.S.start);
    els.markLabel.value = "";
    const info = els.markOverlay.querySelector('.mark-sev input[value="info"]');
    if (info) info.checked = true;
    els.markOverlay.hidden = false;
    els.markLabel.focus();
  }
  function closeMarkDialog() {
    els.markOverlay.hidden = true;
  }
  function saveMark() {
    if (!store.S) return;
    if (markExistsNear(store.playhead)) {
      closeMarkDialog();
      toast("This moment is already marked — delete the existing mark first", 3000);
      return;
    }
    const sev = (els.markOverlay.querySelector('input[name="markSev"]:checked') || {}).value || "info";
    const label = els.markLabel.value.trim() || "Marked by QA";
    store.localMarks.push({ id: "m" + Date.now(), ts: store.playhead, label: label, severity: sev, local: true });
    closeMarkDialog();
    renderTicks();
    if (store.track === "timeline") renderTrack();
    toast("★ Marked at " + fmtFine(store.playhead - store.S.start) + " (" + sev + ")");
  }
  function deleteMark(id) {
    store.localMarks = store.localMarks.filter((m) => m.id !== id);
    renderTicks();
    if (store.track === "timeline") renderTrack();
    toast("Mark removed");
  }
  function markNow() {
    openMarkDialog();
  }
  function buildWebSummary() {
    const m = store.S.manifest, a = m.app || {}, s = store.S.summary;
    return {
      app: a.name || "web session", version: a.version || "",
      environment: m.environment || "", device: m.device || "web player", platform: "web",
      name: store.name, sessionId: m.sessionId || null,
      score: s ? s.score : null, likelyOwner: s ? s.category : null,
      failedRequests: store.S.network.filter((e) => e.error || e.status >= 400).length,
      crashes: store.S.analysis?.stats?.crashes ?? 0, durationMs: store.S.duration,
      marks: allMarks().map((mk) => ({ tSec: ((mk.ts - store.S.start) / 1000).toFixed(1), label: mk.label })),
    };
  }
  async function sendToBackend() {
    if (!store.S) return;
    const base = String(prefs.backend || "").trim().replace(/\/+$/, "");
    if (!base) { openDrawer(); toast("Set a Backend URL in Settings first", 3500); return; }
    els.sendBackendBtn.disabled = true;
    try {
      const m = store.S.manifest, a = m.app || {};
      const headers = {
        "X-QaLens-App": a.name || "web session", "X-QaLens-Version": a.version || "",
        "X-QaLens-Env": m.environment || "", "X-QaLens-Device": m.device || "web player",
        "X-QaLens-Platform": "web",
      };
      Object.keys(headers).forEach((k) => { if (!headers[k]) delete headers[k]; });
      let res;
      if (store.rawBuf) {
        const fd = new FormData();
        fd.append("file", new Blob([store.rawBuf], { type: "application/zip" }), (a.name || "session") + ".sal");
        res = await fetch(base + "/webhook", { method: "POST", headers, body: fd });
      } else {
        headers["Content-Type"] = "application/json";
        res = await fetch(base + "/api/ingest", { method: "POST", headers, body: JSON.stringify(buildWebSummary()) });
      }
      const text = await res.text();
      let verdict = "";
      try { const j = JSON.parse(text); verdict = j.summary || j.message || j.verdict || ""; } catch { verdict = text.slice(0, 160); }
      if (res.ok) toast("Backend ✓ " + (verdict || "accepted"), 5000);
      else toast("Backend rejected (" + res.status + ") " + (verdict || text.slice(0, 120)), 6000);
    } catch (e) {
      toast("Backend unreachable: " + (e.message || e), 6000);
    } finally { els.sendBackendBtn.disabled = false; }
  }

  // ── settings + help ───────────────────────────────────────────────────────
  function setTheme(t) { document.documentElement.dataset.theme = t; prefs.theme = t; LS.set("theme", t); els.setTheme.value = t; }
  function setCompact(on) { document.body.classList.toggle("compact", on); prefs.compact = on; LS.set("compact", on); }
  function openDrawer() { els.drawer.hidden = false; }
  function closeDrawer() { els.drawer.hidden = true; }
  function applyExpanded() {
    document.querySelectorAll("[data-card]").forEach((card) => {
      card.classList.toggle("collapsed", prefs.expanded[card.dataset.card] === false);
    });
  }
  function toggleHelp() {
    let overlay = $("helpOverlay");
    if (overlay) { overlay.remove(); return; }
    overlay = document.createElement("div");
    overlay.id = "helpOverlay"; overlay.className = "help-overlay";
    overlay.innerHTML = '<div class="help-card"><div class="help-title">Keyboard Shortcuts</div><div class="help-grid">' +
      '<div class="help-row"><kbd>space</kbd><span>Play / Pause</span></div>' +
      '<div class="help-row"><kbd>←</kbd><span>Previous event</span></div>' +
      '<div class="help-row"><kbd>→</kbd><span>Next event</span></div>' +
      '<div class="help-row"><kbd>e</kbd><span>Jump to first error</span></div>' +
      '<div class="help-row"><kbd>s</kbd><span>Cycle speed</span></div>' +
      '<div class="help-row"><kbd>f</kbd><span>Toggle follow</span></div>' +
      '<div class="help-row"><kbd>t</kbd><span>Theatre mode</span></div>' +
      '<div class="help-row"><kbd>x</kbd><span>Fullscreen</span></div>' +
      '<div class="help-row"><kbd>m</kbd><span>⭐ Mark moment</span></div>' +
      '<div class="help-row"><kbd>1</kbd>–<kbd>7</kbd><span>Switch track (7 = AI brief)</span></div>' +
      '<div class="help-row"><kbd>?</kbd><span>Toggle this help</span></div>' +
      '</div><div class="help-dismiss">Press <kbd>?</kbd> or <kbd>Esc</kbd> to close</div></div>';
    overlay.addEventListener("click", (e) => { if (e.target === overlay) toggleHelp(); });
    document.body.appendChild(overlay);
  }
  function setTheater(on) { prefs.theater = on; LS.set("theater", on); document.body.classList.toggle("theater", on); els.theaterBtn.classList.toggle("tbtn-on", on); }
  function toggleFullscreen() {
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    else els.deviceFrame.requestFullscreen().catch(() => toast("Fullscreen blocked by the browser"));
  }

  // ── wiring ────────────────────────────────────────────────────────────────
  function init() {
    setTheme(prefs.theme);
    setCompact(prefs.compact);
    els.followToggle.checked = prefs.follow;
    els.setFollow.checked = prefs.follow;
    els.setSpeed.value = String(store.speed);
    els.setAutoplay.checked = prefs.autoplay;
    els.setDensity.checked = prefs.compact;
    els.setBackend.value = prefs.backend;
    applySpeed(); applyExpanded();
    if (typeof DecompressionStream === "undefined")
      els.compatNote.textContent = "⚠ Your browser lacks DecompressionStream — use a recent Chrome, Edge, Safari, or Firefox.";

    // open / load
    const pick = () => els.fileInput.click();
    $("openBtn").onclick = pick;
    els.fileInput.onchange = (e) => { if (e.target.files[0]) loadFile(e.target.files[0]); };
    $("sampleBtn").onclick = async () => {
      try {
        const res = await fetch("sample.sal");
        if (!res.ok) throw new Error("not found");
        // BUGFIX: read the Response body ONCE — a second arrayBuffer() on a consumed body
        // throws and the session never loaded (parse succeeded, onLoaded never ran).
        const buf = await res.arrayBuffer();
        onLoaded(await SAL.read(buf), "sample.sal", buf);
      } catch (e) {
        console.error(e);
        toast("Demo failed to load" + (e && e.message ? ": " + e.message : "") + " — serve the folder (./demo.sh) or drag web/sample.sal in.", 5000);
      }
    };
    ["dragenter", "dragover"].forEach((ev) => window.addEventListener(ev, (e) => { e.preventDefault(); els.dropzone.classList.add("hot"); }));
    ["dragleave", "drop"].forEach((ev) => window.addEventListener(ev, (e) => { e.preventDefault(); els.dropzone.classList.remove("hot"); }));
    window.addEventListener("drop", (e) => { const f = e.dataTransfer.files[0]; if (f) loadFile(f); });

    // transport
    els.playPause.onclick = () => (store.playing ? pause() : play());
    els.stepPrev.onclick = () => stepEvent(-1);
    els.stepNext.onclick = () => stepEvent(1);
    els.jumpErr.onclick = jumpError;
    els.speedBtn.onclick = cycleSpeed;
    els.markBtn.onclick = markNow;
    els.markSave.onclick = saveMark;
    els.markCancel.onclick = closeMarkDialog;
    els.markOverlay.addEventListener("click", (e) => { if (e.target === els.markOverlay) closeMarkDialog(); });
    els.markLabel.addEventListener("keydown", (e) => {
      if (e.key === "Enter") { e.preventDefault(); saveMark(); }
      if (e.key === "Escape") { e.stopPropagation(); closeMarkDialog(); }
    });
    els.theaterBtn.onclick = () => setTheater(!prefs.theater);
    els.fsBtn.onclick = toggleFullscreen;
    setTheater(prefs.theater);
    els.scrubber.oninput = () => seek(store.S.start + Number(els.scrubber.value), true);
    els.filmstrip.onclick = (e) => { const img = e.target.closest("img"); if (img) seek(Number(img.dataset.ts) + filmstripHalfStep(), true); };

    // tracks
    els.trackTabs.onclick = (e) => { const b = e.target.closest(".tab"); if (b) setTrack(b.dataset.track); };
    els.followToggle.onchange = () => { prefs.follow = els.followToggle.checked; LS.set("follow", prefs.follow); els.setFollow.checked = prefs.follow; renderTrack(); };
    els.search.oninput = () => renderTrack();
    els.logChips.onclick = (e) => {
      const c = e.target.closest(".chip"); if (!c) return;
      store.logLevel = c.dataset.level; prefs.logLevel = store.logLevel; LS.set("logLevel", store.logLevel);
      [...els.logChips.children].forEach((x) => x.classList.toggle("active", x === c));
      renderTrack();
    };
    els.trackList.onclick = (e) => {
      const del = e.target.closest(".mark-del");
      if (del) { deleteMark(del.dataset.markId); return; }
      const jump = e.target.closest(".row-jump");
      if (jump) { const holder = jump.dataset.ts ? jump : jump.closest("[data-ts]"); if (holder) seek(Number(holder.dataset.ts), true); return; }
      const holder = e.target.closest("[data-ts]");
      if (!holder) return;
      seek(Number(holder.dataset.ts), true);
      const d = holder.dataset.detail;
      if (d) {
        let det = holder.querySelector(".row-detail");
        if (det) det.remove();
        else { det = document.createElement("div"); det.className = "row-detail"; det.textContent = d; holder.appendChild(det); }
      }
    };

    // chrome
    els.exportBtn.onclick = exportSummary;
    els.sendBackendBtn.onclick = sendToBackend;
    els.compareBtn.onclick = () => els.compareInput.click();
    els.compareInput.onchange = (e) => { if (e.target.files[0]) loadCompare(e.target.files[0]); e.target.value = ""; };
    els.exportSalBtn.onclick = () => {
      if (!store.rawBuf) { toast("No raw .sal buffer to export", 3000); return; }
      const a = document.createElement("a");
      a.href = URL.createObjectURL(new Blob([store.rawBuf], { type: "application/zip" }));
      a.download = ((store.S.manifest?.app?.name) || "session") + ".sal";
      document.body.appendChild(a); a.click(); document.body.removeChild(a);
      setTimeout(() => URL.revokeObjectURL(a.href), 5000);
    };
    els.closeSessionBtn.onclick = closeSession;
    els.aiCopy.onclick = () => {
      const brief = store.S && store.S.forAi ? store.S.forAi : els.reportView.textContent;
      (navigator.clipboard ? navigator.clipboard.writeText(brief) : Promise.reject())
        .then(() => toast("AI brief copied ✓")).catch(() => toast("Clipboard blocked"));
    };

    // drawer / theme
    els.themeBtn.onclick = () => setTheme(prefs.theme === "dark" ? "light" : "dark");
    els.settingsBtn.onclick = () => (els.drawer.hidden ? openDrawer() : closeDrawer());
    els.drawerClose.onclick = closeDrawer;
    els.setTheme.onchange = () => setTheme(els.setTheme.value);
    els.setDensity.onchange = () => setCompact(els.setDensity.checked);
    els.setSpeed.onchange = () => { store.speed = Number(els.setSpeed.value) || 1; prefs.speed = store.speed; LS.set("speed", store.speed); applySpeed(); };
    els.setAutoplay.onchange = () => { prefs.autoplay = els.setAutoplay.checked; LS.set("autoplay", prefs.autoplay); };
    els.setFollow.onchange = () => { prefs.follow = els.setFollow.checked; LS.set("follow", prefs.follow); els.followToggle.checked = prefs.follow; renderTrack(); };
    els.setBackend.onchange = () => { prefs.backend = els.setBackend.value.trim(); LS.set("backend", prefs.backend); toast(prefs.backend ? "Backend URL saved — use ⇪ Send to backend" : "Backend URL cleared"); };
    els.backendOpen.onclick = () => { const b = String(prefs.backend || "").trim(); if (!b) { toast("Set a Backend URL first"); return; } window.open(b, "_blank", "noopener"); };
    document.querySelectorAll("[data-toggle]").forEach((h) => h.onclick = () => {
      const card = h.closest("[data-card]");
      card.classList.toggle("collapsed");
      prefs.expanded[card.dataset.card] = !card.classList.contains("collapsed");
      LS.set("expanded", prefs.expanded);
    });

    // keyboard
    const TRACK_KEYS = ["timeline", "network", "logs", "screens", "insights", "report", "aibrief"];
    window.addEventListener("keydown", (e) => {
      if (e.target.tagName === "INPUT" || e.target.tagName === "SELECT" || e.target.tagName === "TEXTAREA" || e.target.isContentEditable) return;
      if (e.key === "Escape") { closeDrawer(); const h = $("helpOverlay"); if (h) h.remove(); return; }
      if (els.stage.hidden) return;
      if (e.code === "Space") { e.preventDefault(); store.playing ? pause() : play(); }
      else if (e.code === "ArrowRight") stepEvent(1);
      else if (e.code === "ArrowLeft") stepEvent(-1);
      else if (e.key === "e") jumpError();
      else if (e.key === "s") cycleSpeed();
      else if (e.key === "f") { els.followToggle.checked = !els.followToggle.checked; els.followToggle.onchange(); }
      else if (e.key === "t") setTheater(!prefs.theater);
      else if (e.key === "x") toggleFullscreen();
      else if (e.key === "m") markNow();
      else if (e.key === "?") toggleHelp();
      else if (/^[1-7]$/.test(e.key)) setTrack(TRACK_KEYS[Number(e.key) - 1]);
    });

    // deep links
    const params = new URLSearchParams(location.search);
    if (params.has("t")) { const t = Number(params.get("t")); if (!Number.isNaN(t)) store.pendingSeekSec = t; }
    if (params.has("sample") && $("sampleBtn")) $("sampleBtn").onclick();
    else if (params.has("compare") && params.get("a") && params.get("b")) {
      Promise.all([fetch(params.get("a")).then((r) => r.arrayBuffer()), fetch(params.get("b")).then((r) => r.arrayBuffer())])
        .then(async ([bufA, bufB]) => {
          onLoaded(await SAL.read(bufA), params.get("a").split("/").pop(), bufA);
          store.S2 = await SAL.read(bufB);
          renderDiff();
        })
        .catch(() => toast("Serve the folder over http to use ?compare=a&b", 4000));
    }
  }

  document.addEventListener("DOMContentLoaded", init);
})();
