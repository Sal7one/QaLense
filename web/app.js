/* app.js — QaLens Mission Control: .sal player UI. Vanilla JS, no build step.
 *
 * Adds on top of the original player: instant-replay recents (sessions cached in IndexedDB),
 * playback speed, filmstrip, error markers on the scrubber, Screens & Insights tracks, a network
 * waterfall with stats, a settings drawer, markdown export — everything persisted locally.
 */
(function () {
  "use strict";

  const SLOW_MS = 1500;            // network "slow" threshold (matches lib default)
  const CACHE_CAP_BYTES = 120 * 1024 * 1024;
  const SPEEDS = [0.5, 1, 2, 4];

  // ── DOM ──────────────────────────────────────────────────────────────────
  const $ = (id) => document.getElementById(id);
  const els = {
    landing: $("landing"), session: $("session"), dropzone: $("dropzone"),
    fileInput: $("fileInput"), banner: $("banner"), statbar: $("statbar"),
    video: $("video"), frame: $("frame"), noMedia: $("noMedia"), viewportScreen: $("viewportScreen"),
    filmstrip: $("filmstrip"),
    playPause: $("playPause"), stepPrev: $("stepPrev"), stepNext: $("stepNext"),
    jumpErr: $("jumpErr"), speedBtn: $("speedBtn"), time: $("time"),
    theaterBtn: $("theaterBtn"), fsBtn: $("fsBtn"),
    scrubber: $("scrubber"), scrubMarks: $("scrubMarks"),
    summaryBody: $("summaryBody"), stateBody: $("stateBody"), stateTime: $("stateTime"),
    deviceBody: $("deviceBody"), trackTabs: $("trackTabs"), trackList: $("trackList"),
    trackSubstats: $("trackSubstats"),
    reportView: $("reportView"), followToggle: $("followToggle"), search: $("search"),
    logChips: $("logChips"), recents: $("recents"), recentsGrid: $("recentsGrid"),
    cacheUsage: $("cacheUsage"),
    toast: $("toast"), compatNote: $("compatNote"),
    cTimeline: $("cTimeline"), cNetwork: $("cNetwork"), cLogs: $("cLogs"),
    cScreens: $("cScreens"), cInsights: $("cInsights"),
    topbarSession: $("topbarSession"), tbApp: $("tbApp"), tbEnv: $("tbEnv"), tbScore: $("tbScore"),
    exportBtn: $("exportBtn"), exportSalBtn: $("exportSalBtn"), closeSessionBtn: $("closeSessionBtn"),
    compareBtn: $("compareBtn"), compareInput: $("compareInput"),
    sendBackendBtn: $("sendBackendBtn"), markBtn: $("markBtn"),
    drawer: $("drawer"), drawerClose: $("drawerClose"), settingsBtn: $("settingsBtn"),
    setTheme: $("setTheme"), setDensity: $("setDensity"), setSpeed: $("setSpeed"),
    setAutoplay: $("setAutoplay"), setFollow: $("setFollow"),
    setCache: $("setCache"), setCacheUsage: $("setCacheUsage"), setCacheClear: $("setCacheClear"),
    setBackend: $("setBackend"), backendOpen: $("backendOpen"),
    aiBriefBar: $("aiBriefBar"), aiBriefCopy: $("aiBriefCopy"),
  };

  // ── Prefs (localStorage) ───────────────────────────────────────────────────
  const LS = {
    get(k, d) { try { const v = localStorage.getItem("qalens." + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
    set(k, v) { try { localStorage.setItem("qalens." + k, JSON.stringify(v)); } catch {} },
  };
  const prefs = {
    theme: LS.get("theme", "dark"),
    follow: LS.get("follow", true),
    track: LS.get("track", "timeline"),
    logLevel: LS.get("logLevel", "all"),
    expanded: LS.get("expanded", { summary: true, state: true, device: false }),
    speed: LS.get("speed", 1),
    autoplay: LS.get("autoplay", false),
    compact: LS.get("compact", false),
    cache: LS.get("cache", true),
    theater: LS.get("theater", false),
    backend: LS.get("backend", ""),
  };

  // ── IndexedDB session cache (instant-replay recents) ───────────────────────
  const IDB = {
    open() {
      if (this._p) return this._p;
      this._p = new Promise((resolve) => {
        if (!window.indexedDB) return resolve(null);
        const req = indexedDB.open("qalens-player", 1);
        req.onupgradeneeded = () => {
          const db = req.result;
          if (!db.objectStoreNames.contains("sessions")) db.createObjectStore("sessions", { keyPath: "key" });
        };
        req.onsuccess = () => resolve(req.result);
        req.onerror = () => resolve(null);
      });
      return this._p;
    },
    async put(record) {
      const db = await this.open(); if (!db) return false;
      return new Promise((resolve) => {
        const tx = db.transaction("sessions", "readwrite");
        tx.objectStore("sessions").put(record);
        tx.oncomplete = () => resolve(true);
        tx.onerror = () => resolve(false);
        tx.onabort = () => resolve(false);
      });
    },
    async get(key) {
      const db = await this.open(); if (!db) return null;
      return new Promise((resolve) => {
        const req = db.transaction("sessions").objectStore("sessions").get(key);
        req.onsuccess = () => resolve(req.result || null);
        req.onerror = () => resolve(null);
      });
    },
    async all() {
      const db = await this.open(); if (!db) return [];
      return new Promise((resolve) => {
        const req = db.transaction("sessions").objectStore("sessions").getAll();
        req.onsuccess = () => resolve(req.result || []);
        req.onerror = () => resolve([]);
      });
    },
    async del(key) {
      const db = await this.open(); if (!db) return;
      return new Promise((resolve) => {
        const tx = db.transaction("sessions", "readwrite");
        tx.objectStore("sessions").delete(key);
        tx.oncomplete = resolve; tx.onerror = resolve;
      });
    },
    async clear() {
      const db = await this.open(); if (!db) return;
      return new Promise((resolve) => {
        const tx = db.transaction("sessions", "readwrite");
        tx.objectStore("sessions").clear();
        tx.oncomplete = resolve; tx.onerror = resolve;
      });
    },
  };

  async function cacheSession(key, name, size, buf) {
    if (!prefs.cache) return;
    // Evict oldest until the new blob fits under the cap.
    const all = (await IDB.all()).sort((a, b) => a.date - b.date);
    let total = all.reduce((n, r) => n + (r.size || 0), 0);
    for (const r of all) {
      if (total + size <= CACHE_CAP_BYTES) break;
      await IDB.del(r.key); total -= r.size || 0;
    }
    if (size > CACHE_CAP_BYTES) return;
    await IDB.put({ key, name, size, date: Date.now(), blob: new Blob([buf]) });
    updateCacheUsage();
  }

  async function updateCacheUsage() {
    const all = await IDB.all();
    const total = all.reduce((n, r) => n + (r.size || 0), 0);
    const label = all.length ? `${all.length} cached · ${fmtBytes(total)}` : "cache empty";
    if (els.cacheUsage) els.cacheUsage.textContent = all.length ? label : "";
    if (els.setCacheUsage) els.setCacheUsage.textContent = label;
    return new Set(all.map((r) => r.key));
  }

  // ── State ──────────────────────────────────────────────────────────────────
  let S = null;            // session
  let S2 = null;           // C13: second session for compare mode (null = single-session view)
  let playhead = 0;        // absolute ms
  let playing = false;
  let activeTrack = prefs.track;
  let speed = SPEEDS.includes(prefs.speed) ? prefs.speed : 1;
  let rafId = null, lastReal = 0, lastHeavy = 0;
  let objectUrls = [];
  let pendingSeekSec = null;   // from ?t=<seconds>; applied to the next loaded session
  let lastRenderSig = "";      // C2: signature of the last renderTrack() rebuild — skip rebuilds when unchanged

  // ── Utils ────────────────────────────────────────────────────────────────
  const clampHead = (v) => Math.max(S.start, Math.min(S.end, v));
  const esc = (s) => String(s == null ? "" : s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
  function fmt(msRel) {
    const t = Math.max(0, Math.floor(msRel / 1000));
    return String(Math.floor(t / 60)).padStart(2, "0") + ":" + String(t % 60).padStart(2, "0");
  }
  // Row timestamps include tenths — short sessions otherwise read 00:00 everywhere.
  function fmtFine(msRel) {
    const ms = Math.max(0, msRel);
    return fmt(ms) + "." + Math.floor((ms % 1000) / 100);
  }
  function fmtBytes(n) {
    if (n >= 1e6) return (n / 1e6).toFixed(1) + " MB";
    if (n >= 1e3) return (n / 1e3).toFixed(0) + " KB";
    return n + " B";
  }
  function toast(msg, ms = 2600) {
    els.toast.textContent = msg; els.toast.hidden = false;
    clearTimeout(toast._t); toast._t = setTimeout(() => (els.toast.hidden = true), ms);
  }
  function percentile(sorted, p) {
    if (!sorted.length) return 0;
    return sorted[Math.min(sorted.length - 1, Math.floor((p / 100) * sorted.length))];
  }
  const ICON_PLAY = '<svg width="17" height="17"><use href="#i-play"/></svg>';
  const ICON_PAUSE = '<svg width="17" height="17"><use href="#i-pause"/></svg>';

  // ── Loading ────────────────────────────────────────────────────────────────
  async function loadFile(file) {
    try {
      // .appsal app configs get their own viewer (plain JSON, not a ZIP).
      if (/\.appsal$/i.test(file.name)) {
        const cfg = JSON.parse(await file.text());
        if (cfg.format !== "qalens-appsal/1") throw new Error("Not a qalens-appsal/1 file");
        renderAppSal(cfg, file.name);
        return;
      }
      toast("Reading " + file.name + "…", 1500);
      const buf = await file.arrayBuffer();
      const session = await SAL.read(buf);
      onLoaded(session, file.name, file.size || buf.byteLength, buf);
      cacheSession(file.name + ":" + (file.size || buf.byteLength), file.name, file.size || buf.byteLength, buf);
    } catch (e) {
      console.error(e);
      toast("⚠ " + (e.message || "Failed to read file"), 5000);
    }
  }

  // ── .appsal App Config EDITOR ───────────────────────────────────────────────
  // Full round-trip: open (or start blank) → edit everything → download <package>.appsal,
  // then import it on-device from the Control Room.
  let AS = null;   // the config being edited

  function blankAppSal() {
    return {
      format: "qalens-appsal/1", package: "", name: "", exportedAt: 0,
      ui: { panelMode: "minimal", overlayAlpha: 1, dockBottom: false },
      webhook: { url: "", headerName: "Authorization", headerValue: "", params: "", includeMeta: true },
      queries: [], macros: [], data: { watchPrefs: [] },
    };
  }

  function normalizeAppSal(cfg) {
    const b = blankAppSal();
    return {
      ...b, ...cfg,
      ui: { ...b.ui, ...(cfg.ui || {}) },
      webhook: { ...b.webhook, ...(cfg.webhook || {}) },
      queries: (cfg.queries || []).map((q) => ({ name: q.name || "", db: q.db || "", sql: q.sql || "" })),
      macros: (cfg.macros || []).map((m) => ({ name: m.name || "", steps: m.steps || [] })),
      data: { watchPrefs: (cfg.data && cfg.data.watchPrefs) || [] },
    };
  }

  const escAttr = (s) => esc(s).replace(/\n/g, "&#10;");

  function downloadText(name, text) {
    const a = document.createElement("a");
    a.href = URL.createObjectURL(new Blob([text], { type: "application/json" }));
    a.download = name;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 2000);
  }

  function renderAppSal(cfg, fileName) {
    pause();
    AS = normalizeAppSal(cfg || {});
    drawAppSal(fileName || "config.appsal");
    els.landing.hidden = true;
    els.session.hidden = true;
    $("appsalView").hidden = false;
    document.title = "QaLens · " + (AS.package || "app config editor");
  }

  function drawAppSal(fileName) {
    const v = $("appsalView");
    const field = (label, path, val, ph) =>
      `<label class="as-field"><span>${label}</span>
        <input type="text" data-as="${path}" value="${escAttr(val || "")}" placeholder="${escAttr(ph || "")}" /></label>`;

    v.innerHTML = `
      <div class="banner">
        <div class="banner-bits"><b>APP CONFIG EDITOR</b><span class="sep">·</span>${esc(AS.package || "new config")}</div>
        <div class="banner-meta">${AS.exportedAt ? "exported " + new Date(AS.exportedAt).toLocaleString() : "unsaved"} · ${esc(fileName)}</div>
      </div>

      <div class="appsal-grid">
        <section class="card"><header class="card-head"><h3>Identity</h3></header>
          <div class="card-body">
            ${field("App package (must match the APK)", "package", AS.package, "com.yourcompany.app")}
            ${field("Config name", "name", AS.name, "Payments team QA config")}
          </div>
        </section>

        <section class="card"><header class="card-head"><h3>QA Experience</h3></header>
          <div class="card-body">
            <label class="as-field"><span>Panel style</span>
              <select data-as="ui.panelMode">
                <option value="minimal" ${AS.ui.panelMode === "minimal" ? "selected" : ""}>QA Minimal</option>
                <option value="full" ${AS.ui.panelMode !== "minimal" ? "selected" : ""}>Full developer</option>
              </select></label>
            <label class="as-field"><span>Overlay opacity — <b id="asAlphaVal">${Math.round(AS.ui.overlayAlpha * 100)}%</b></span>
              <input type="range" min="10" max="100" value="${Math.round(AS.ui.overlayAlpha * 100)}" data-as="ui.overlayAlphaPct" /></label>
            <label class="as-check"><input type="checkbox" data-as="ui.dockBottom" ${AS.ui.dockBottom ? "checked" : ""} /> Dock panel at the bottom</label>
            ${field("Watched prefs files (comma separated)", "data.watchPrefsCsv", AS.data.watchPrefs.join(", "), "sample_settings, qalens_prefs")}
          </div>
        </section>

        <section class="card"><header class="card-head"><h3>Webhook</h3></header>
          <div class="card-body">
            ${field("Endpoint URL", "webhook.url", AS.webhook.url, "https://qa.example.com/api/sal-intake")}
            <div class="as-row2">
              ${field("Auth header", "webhook.headerName", AS.webhook.headerName, "Authorization")}
              ${field("Header value (••• = masked)", "webhook.headerValue", AS.webhook.headerValue, "Bearer …")}
            </div>
            ${field("Extra query params", "webhook.params", AS.webhook.params, "team=payments&pipeline=nightly")}
            <label class="as-check"><input type="checkbox" data-as="webhook.includeMeta" ${AS.webhook.includeMeta !== false ? "checked" : ""} /> Attach session metadata (app/version/env/device)</label>
          </div>
        </section>

        <section class="card"><header class="card-head"><h3>Saved Queries (${AS.queries.length})
          <button class="btn btn-ghost small" id="asAddQuery">＋ Add</button></h3></header>
          <div class="card-body">
            ${AS.queries.length ? "" : `<p class="muted small">No queries — add one. Room DBs are plain SQLite files.</p>`}
            ${AS.queries.map((q, i) => `
              <div class="appsal-item">
                <div class="as-row2">
                  ${field("Name", `queries.${i}.name`, q.name, "Pending transactions")}
                  ${field("Database file", `queries.${i}.db`, q.db, "sample.db")}
                </div>
                <textarea class="sql-edit" data-as="queries.${i}.sql" rows="3"
                  placeholder="SELECT * FROM …">${esc(q.sql)}</textarea>
                <button class="btn btn-ghost small as-del" data-del="query" data-i="${i}">✕ Remove query</button>
              </div>`).join("")}
          </div>
        </section>

        <section class="card"><header class="card-head"><h3>Macros (${AS.macros.length})
          <button class="btn btn-ghost small" id="asAddMacro">＋ Add</button></h3></header>
          <div class="card-body">
            <p class="muted small">Steps, one per line: <code>deeplink &lt;uri&gt;</code> · <code>wait &lt;ms&gt;</code> ·
              <code>tap &lt;tag|text&gt;</code> · <code>type &lt;tag&gt; &lt;text&gt;</code> ·
              <code>record [video]</code> · <code>stop</code> · <code>screenshot</code> · <code>mark &lt;text&gt;</code>.
              tap/type wait up to 5s for the target — macros can complete a full login alone.</p>
            ${AS.macros.map((m, i) => `
              <div class="appsal-item">
                ${field("Name", `macros.${i}.name`, m.name, "Smoke: accounts + transfer")}
                <textarea class="sql-edit" data-as="macros.${i}.steps" rows="4"
                  placeholder="deeplink qalenssample://accounts&#10;wait 1000&#10;screenshot">${esc(m.steps.join("\n"))}</textarea>
                <button class="btn btn-ghost small as-del" data-del="macro" data-i="${i}">✕ Remove macro</button>
              </div>`).join("")}
          </div>
        </section>
      </div>

      <div class="as-actions">
        <button class="btn btn-ghost" id="appsalBack">‹ Back</button>
        <button class="btn btn-accent" id="appsalDownload">⬇ Download .appsal</button>
        <button class="btn btn-ghost" id="appsalCopy">⧉ Copy JSON</button>
        <span class="muted small">Import it on-device: Control Room → App Config → ⤓ Import .appsal</span>
      </div>`;

    wireAppSal(fileName);
  }

  function appSalJson() {
    const out = JSON.parse(JSON.stringify(AS));
    out.exportedAt = Date.now();
    out.queries = out.queries.filter((q) => q.name.trim() || q.sql.trim());
    out.macros = out.macros
      .map((m) => ({ name: m.name, steps: m.steps.filter((s) => s.trim()) }))
      .filter((m) => m.name.trim() || m.steps.length);
    return JSON.stringify(out, null, 2);
  }

  function wireAppSal(fileName) {
    const v = $("appsalView");
    // Scalar bindings — write into AS on input so re-renders never lose values.
    v.querySelectorAll("[data-as]").forEach((el) => {
      el.oninput = () => {
        const path = el.dataset.as;
        const val = el.type === "checkbox" ? el.checked : el.value;
        if (path === "ui.overlayAlphaPct") {
          AS.ui.overlayAlpha = Number(val) / 100;
          $("asAlphaVal").textContent = val + "%";
        } else if (path === "data.watchPrefsCsv") {
          AS.data.watchPrefs = String(val).split(",").map((s) => s.trim()).filter(Boolean);
        } else if (path.startsWith("queries.")) {
          const [, i, key] = path.split(".");
          AS.queries[Number(i)][key] = val;
        } else if (path.startsWith("macros.")) {
          const [, i, key] = path.split(".");
          if (key === "steps") AS.macros[Number(i)].steps = String(val).split("\n");
          else AS.macros[Number(i)][key] = val;
        } else {
          const [a, b] = path.split(".");
          if (b === undefined) AS[a] = val; else AS[a][b] = val;
        }
      };
    });
    v.querySelectorAll(".as-del").forEach((btn) => {
      btn.onclick = () => {
        const i = Number(btn.dataset.i);
        if (btn.dataset.del === "query") AS.queries.splice(i, 1); else AS.macros.splice(i, 1);
        drawAppSal(fileName);
      };
    });
    $("asAddQuery").onclick = () => { AS.queries.push({ name: "", db: "", sql: "" }); drawAppSal(fileName); };
    $("asAddMacro").onclick = () => { AS.macros.push({ name: "", steps: [] }); drawAppSal(fileName); };
    $("appsalBack").onclick = () => { $("appsalView").hidden = true; els.landing.hidden = false; renderRecents(); };
    $("appsalDownload").onclick = () => {
      if (!AS.package.trim()) { toast("Set the app package first — the device refuses mismatched configs."); return; }
      downloadText(`${AS.package.trim()}.appsal`, appSalJson());
      toast("Downloaded — import it from the Control Room ✓");
    };
    $("appsalCopy").onclick = () => {
      (navigator.clipboard ? navigator.clipboard.writeText(appSalJson()) : Promise.reject())
        .then(() => toast(".appsal JSON copied ✓"))
        .catch(() => toast("Clipboard blocked"));
    };
  }

  async function loadCached(key) {
    const rec = await IDB.get(key);
    if (!rec || !rec.blob) { toast("Not in cache anymore — drop the file again."); return; }
    try {
      const buf = await rec.blob.arrayBuffer();
      onLoaded(await SAL.read(buf), rec.name, rec.size, buf);
    } catch (e) { toast("⚠ " + (e.message || "Failed to replay cached session"), 5000); }
  }

  function onLoaded(session, name, size, rawBuf) {
    revokeObjectUrls();
    objectUrls = session.frames.map((f) => f.url).concat(session.videoUrl ? [session.videoUrl] : []);
    S = session;
    S._rawBuf = rawBuf || null;  // store for Export .sal / send-to-backend
    S._screens = null; S._insights = null; S._events = null;
    S._localMarks = [];           // C9: bookmarks added in this viewer (not in the file)
    playhead = S.start;
    playing = false;

    saveRecent(session, name, size);
    els.landing.hidden = true;
    els.session.hidden = false;
    SAL.showRecordingCoverage(session, els.session);
    els.exportBtn.hidden = false;
    els.exportSalBtn.hidden = false;
    els.closeSessionBtn.hidden = false;
    els.compareBtn.hidden = false;
    els.sendBackendBtn.hidden = false;

    setupMedia();
    renderTopbar();
    renderBanner();
    renderStatbar();
    renderSummary();
    renderDevice();
    renderCounts();
    renderFilmstrip();
    renderScrubMarks();
    setTrack(activeTrack);
    syncMedia(true);
    updatePlayheadUI(true);
    applySpeed();
    document.title = "QaLens · " + (session.manifest.app ? (session.manifest.app.name || "session") : "session");
    if (pendingSeekSec != null) {
      // C1: for video mode the seek is deferred to onloadedmetadata (set above in setupMedia);
      // for frame mode there's no async metadata to wait for, so seek right away.
      if (!S.videoUrl) {
        seek(S.start + pendingSeekSec * 1000, true);
        toast("Opened at " + fmtFine(playhead - S.start) + " (from link)");
        pendingSeekSec = null;
      }
    } else if (prefs.autoplay) play();
  }

  function revokeObjectUrls() {
    objectUrls.forEach(URL.revokeObjectURL);
    objectUrls = [];
  }

  // Revoke object URLs held by the comparison session S2 to prevent memory leaks.
  function revokeS2Urls() {
    if (!S2) return;
    (S2.frames || []).forEach((f) => { if (f.url) URL.revokeObjectURL(f.url); });
    if (S2.videoUrl) URL.revokeObjectURL(S2.videoUrl);
  }

  function closeSession() {
    pause();
    revokeObjectUrls();
    revokeS2Urls();
    S = null; S2 = null;
    els.session.hidden = true;
    els.landing.hidden = false;
    els.exportBtn.hidden = true;
    els.exportSalBtn.hidden = true;
    els.closeSessionBtn.hidden = true;
    els.compareBtn.hidden = true;
    els.sendBackendBtn.hidden = true;
    els.topbarSession.hidden = true;
    document.title = "QaLens Mission Control — .sal session viewer";
    renderRecents();
  }

  // ── C13: Compare mode — drop a second .sal to diff against the current session ──────────
  async function loadCompare(file) {
    try {
      revokeS2Urls();
      const buf = await file.arrayBuffer();
      S2 = await SAL.read(buf);
      renderDiff();
    } catch (e) {
      toast("⚠ " + (e.message || "Failed to read comparison file"), 5000);
    }
  }

  function sessionSummary(s) {
    const failed = s.network.filter((e) => e.error || e.status >= 400).map((e) => `${e.method} ${shortPath(e.url)}`);
    const anomalies = (s.analysis?.anomalies || []).map((a) => a.title || a.kind);
    const screens = [...new Set(s.state.map((st) => st.screen || "Unknown"))];
    return {
      appName: (s.manifest.app && s.manifest.app.name) || "app",
      appVersion: (s.manifest.app && s.manifest.app.version) || "",
      environment: s.manifest.environment || "",
      durationMs: s.duration,
      score: s.summary ? s.summary.score : null,
      likelyOwner: s.summary ? s.summary.category : null,
      screensVisited: screens,
      failedRequests: failed,
      errorCount: s.timeline.filter((e) => e.isError).length,
      crashCount: (s.analysis?.stats?.crashes) ?? 0,
      anomalyTitles: anomalies,
    };
  }

  function renderDiff() {
    if (!S || !S2) return;
    const a = sessionSummary(S), b = sessionSummary(S2);
    const partialComparison = SAL.recordingCoverage(S).partial || SAL.recordingCoverage(S2).partial;
    const scoreDelta = (b.score ?? 0) - (a.score ?? 0);
    const addedFail = b.failedRequests.filter((f) => !a.failedRequests.includes(f));
    const resolved = a.failedRequests.filter((f) => !b.failedRequests.includes(f));
    const addedScreens = b.screensVisited.filter((s) => !a.screensVisited.includes(s));
    const removedScreens = a.screensVisited.filter((s) => !b.screensVisited.includes(s));
    const newAnom = b.anomalyTitles.filter((an) => !a.anomalyTitles.includes(an));
    const isRegression = scoreDelta < 0 || addedFail.length > 0 || b.crashCount > a.crashCount;
    const ownerChanged = a.likelyOwner !== b.likelyOwner;

    const regBanner = partialComparison
      ? `<div class="diff-banner err">Partial evidence — fixes and regressions cannot be confirmed from this comparison.</div>`
      : isRegression
      ? `<div class="diff-banner err">⚠ Regression — score ${scoreDelta >= 0 ? "+" : ""}${scoreDelta}, +${addedFail.length} new failures, ${b.crashCount - a.crashCount >= 0 ? "+" : ""}${b.crashCount - a.crashCount} crashes</div>`
      : `<div class="diff-banner ok">✓ No regression — score ${scoreDelta >= 0 ? "+" : ""}${scoreDelta}, ${resolved.length} resolved</div>`;

    const rows = [
      ["Score", a.score ?? "—", b.score ?? "—", `${scoreDelta >= 0 ? "+" : ""}${scoreDelta}`, scoreDelta < 0],
      ["Duration", fmt(a.durationMs), fmt(b.durationMs), "", false],
      ["Failed requests", a.failedRequests.length, b.failedRequests.length, `+${addedFail.length} / -${resolved.length}`, addedFail.length > 0],
      ["Crashes", a.crashCount, b.crashCount, `+${b.crashCount - a.crashCount}`, b.crashCount > a.crashCount],
      ["Likely owner", a.likelyOwner ?? "—", b.likelyOwner ?? "—", ownerChanged ? "changed" : "", ownerChanged],
    ];
    const table = `<table class="diff-table"><thead><tr><th></th><th>Baseline</th><th>Current</th><th>Δ</th></tr></thead><tbody>` +
      rows.map(([label, base, curr, delta, bad]) =>
        `<tr><td>${label}</td><td>${esc(String(base))}</td><td>${esc(String(curr))}</td><td class="${bad ? "err" : ""}">${esc(delta)}</td></tr>`
      ).join("") + `</tbody></table>`;

    const lists = [];
    if (addedFail.length) {
      lists.push(`<div class="diff-section"><h4>New failures (not in baseline)</h4><ul>${addedFail.map((f) => `<li class="err">${esc(f)}</li>`).join("")}</ul></div>`);
    }
    if (resolved.length) {
      lists.push(`<div class="diff-section"><h4>${partialComparison ? "Absent from retained evidence (fix unverified)" : "Resolved (fixed since baseline)"}</h4><ul>${resolved.map((f) => `<li class="ok">${esc(f)}</li>`).join("")}</ul></div>`);
    }
    if (addedScreens.length) {
      lists.push(`<div class="diff-section"><h4>New screens</h4><ul>${addedScreens.map((s) => `<li>${esc(s)}</li>`).join("")}</ul></div>`);
    }
    if (removedScreens.length) {
      lists.push(`<div class="diff-section"><h4>Screens no longer visited</h4><ul>${removedScreens.map((s) => `<li>${esc(s)}</li>`).join("")}</ul></div>`);
    }
    if (newAnom.length) {
      lists.push(`<div class="diff-section"><h4>New anomalies</h4><ul>${newAnom.map((an) => `<li class="warn">${esc(an)}</li>`).join("")}</ul></div>`);
    }

    const html = `
      <div class="diff-view">
        <div class="diff-header"><h3>Session Diff</h3><button class="btn btn-ghost small" id="diffClose">✕ Close compare</button></div>
        ${regBanner}
        <div class="diff-apps"><b>${esc(a.appName)} ${esc(a.appVersion)}</b> (${esc(a.environment || "—")}) vs <b>${esc(b.appName)} ${esc(b.appVersion)}</b> (${esc(b.environment || "—")})</div>
        ${table}
        ${lists.join("")}
      </div>`;
    els.trackList.innerHTML = html;
    els.trackList.hidden = false;
    els.reportView.hidden = true;
    els.trackSubstats.hidden = true;
    $("diffClose").onclick = () => { revokeS2Urls(); S2 = null; setTrack(activeTrack); };
  }

  // ── Media ────────────────────────────────────────────────────────────────
  function setupMedia() {
    els.scrubber.max = S.duration;
    els.scrubber.value = 0;
    if (S.videoUrl) {
      els.video.src = S.videoUrl; els.video.hidden = false;
      els.frame.hidden = true; els.noMedia.hidden = true;
      els.video.onended = pause;
      // C1: a ?t= deep-link seek (or any syncMedia(true)) issued before <video> has metadata
      // silently lands at 0. Defer until the element is actually ready, then apply the pending seek.
      els.video.onloadedmetadata = () => {
        if (pendingSeekSec != null) {
          seek(S.start + pendingSeekSec * 1000, true);
          toast("Opened at " + fmtFine(playhead - S.start) + " (from link)");
          pendingSeekSec = null;
        } else {
          syncMedia(true);
        }
      };
    } else if (S.frames.length) {
      els.frame.hidden = false; els.video.hidden = true; els.noMedia.hidden = true;
    } else {
      els.noMedia.hidden = false; els.frame.hidden = true; els.video.hidden = true;
    }
  }

  function frameAt(ts) {
    const f = S.frames;
    if (!f.length) return null;
    let lo = 0, hi = f.length - 1, ans = 0;
    while (lo <= hi) { const m = (lo + hi) >> 1; if (f[m].ts <= ts) { ans = m; lo = m + 1; } else hi = m - 1; }
    return f[ans];
  }
  function stateAt(ts) {
    let cur = S.state[0] || null;
    for (const s of S.state) { if (s.ts <= ts) cur = s; else break; }
    return cur;
  }
  function allEvents() {
    if (S._events) return S._events;
    const tag = (arr, isErr) => arr.map((e) => ({ ts: e.ts, isError: isErr(e) }));
    S._events = []
      .concat(tag(S.timeline, (e) => !!e.isError))
      .concat(tag(S.network, (e) => (e.status >= 400) || !!e.error))
      .concat(tag(S.logs, (e) => /error|\[ERROR\]|fail|exception/i.test(e.message || "")))
      .sort((a, b) => a.ts - b.ts);
    return S._events;
  }

  // Screen visits derived from the state track (consecutive samples merged into spans).
  function screenVisits() {
    if (S._screens) return S._screens;
    const visits = [];
    for (const s of S.state) {
      const name = s.screen || "Unknown";
      const last = visits[visits.length - 1];
      if (last && last.name === name) last.end = s.ts;
      else { if (last) last.end = s.ts; visits.push({ name, start: s.ts, end: s.ts }); }
    }
    // C5: the first loop already set each visit's end to the next sample's ts. Only the last visit
    // never got its end advanced — cap it at S.end. (Previously a second loop overwrote every
    // visit's end with the next *visit's* start, inflating merged-visit durations.)
    const last = visits[visits.length - 1];
    if (last) last.end = S.end;
    S._screens = visits;
    return visits;
  }

  // Auto-detected anomalies: failures, slowness, error bursts, flag flips, screen churn.
  // Recordings made by current QaLens carry analysis.json (computed on-device, pre-redaction
  // aware) — prefer it; older files fall back to computing the same things here.
  function insights() {
    if (S._insights) return S._insights;
    if (S.analysis && Array.isArray(S.analysis.anomalies) && S.analysis.anomalies.length) {
      const sevOf = (k) => (k === "failed_request" || k === "error_burst") ? "err" : "warn";
      S._insights = S.analysis.anomalies.map((a) => ({
        sev: sevOf(a.kind), ts: S.start + (a.tMs || 0),
        title: a.title || a.kind, detail: (a.detail || "") + "  ·  recorded on device",
      }));
      return S._insights;
    }
    const out = [];

    const failed = S.network.filter((e) => e.error || e.status >= 400);
    if (failed.length) {
      out.push({
        sev: "err", ts: failed[0].ts,
        title: `${failed.length} failed request${failed.length > 1 ? "s" : ""}`,
        detail: failed.slice(0, 3).map((e) => `${e.method} ${shortPath(e.url)} → ${e.error || e.status}`).join("  ·  "),
      });
    }
    const slow = S.network.filter((e) => !e.error && e.status < 400 && e.latencyMs >= SLOW_MS);
    if (slow.length) {
      const worst = slow.reduce((a, b) => (a.latencyMs > b.latencyMs ? a : b));
      out.push({
        sev: "warn", ts: worst.ts,
        title: `${slow.length} slow request${slow.length > 1 ? "s" : ""} (≥${SLOW_MS}ms)`,
        detail: `worst: ${worst.method} ${shortPath(worst.url)} took ${worst.latencyMs}ms`,
      });
    }
    // Error bursts: ≥3 error events inside 10s.
    const errs = allEvents().filter((e) => e.isError);
    for (let i = 0; i + 2 < errs.length; i++) {
      if (errs[i + 2].ts - errs[i].ts <= 10000) {
        out.push({
          sev: "err", ts: errs[i].ts,
          title: "Error burst",
          detail: `3+ errors within ${Math.round((errs[i + 2].ts - errs[i].ts) / 1000)}s starting at ${fmt(errs[i].ts - S.start)}`,
        });
        break;
      }
    }
    // Feature-flag flips mid-session.
    for (let i = 1; i < S.state.length; i++) {
      const prev = S.state[i - 1].featureFlags || {}, cur = S.state[i].featureFlags || {};
      for (const k of Object.keys(cur)) {
        if (k in prev && prev[k] !== cur[k]) {
          out.push({
            sev: "warn", ts: S.state[i].ts,
            title: `Feature flag flipped: ${k}`,
            detail: `${prev[k] ? "ON" : "OFF"} → ${cur[k] ? "ON" : "OFF"} at ${fmt(S.state[i].ts - S.start)}`,
          });
        }
      }
    }
    // Screen churn: ≥3 screen changes inside 4s.
    const visits = screenVisits();
    for (let i = 0; i + 2 < visits.length; i++) {
      if (visits[i + 2].start - visits[i].start <= 4000) {
        out.push({
          sev: "warn", ts: visits[i].start,
          title: "Rapid screen churn",
          detail: `${visits[i].name} → ${visits[i + 1].name} → ${visits[i + 2].name} within ${Math.round((visits[i + 2].start - visits[i].start) / 1000)}s`,
        });
        break;
      }
    }
    // Heavy payloads.
    const heavy = S.network.filter((e) => (e.responseBytes || 0) > 1e6);
    if (heavy.length) {
      const worst = heavy.reduce((a, b) => ((a.responseBytes || 0) > (b.responseBytes || 0) ? a : b));
      out.push({
        sev: "warn", ts: worst.ts,
        title: `${heavy.length} heavy response${heavy.length > 1 ? "s" : ""} (>1MB)`,
        detail: `largest: ${shortPath(worst.url)} · ${fmtBytes(worst.responseBytes || 0)}`,
      });
    }
    if (!out.length) out.push({ sev: "ok", ts: S.start, title: "No anomalies detected", detail: "No failures, slow calls, error bursts, flag flips, or screen churn in this session." });
    out.sort((a, b) => a.ts - b.ts);
    S._insights = out;
    return out;
  }

  // The video track starts at consent-grant time, NOT at session start — map through it, or
  // seeks land early by the consent delay. Old files fall back to end-aligning the video.
  function videoBase() {
    if (S.manifest.videoStartMillis) return S.manifest.videoStartMillis;
    if (els.video.duration > 0) return S.end - els.video.duration * 1000;
    return S.start;
  }

  function syncMedia(force) {
    if (S.videoUrl) {
      // C1/C6: only set currentTime once the element has metadata; otherwise the seek is lost
      // (readyState < 1) or videoBase() divides by a non-finite duration.
      if (force && els.video.readyState >= 1 && Number.isFinite(els.video.duration)) {
        try { els.video.currentTime = Math.max(0, (playhead - videoBase()) / 1000); } catch {}
      }
    } else if (S.frames.length) {
      const f = frameAt(playhead);
      if (f && els.frame.src !== f.url) els.frame.src = f.url;
    }
    const st = stateAt(playhead);
    els.viewportScreen.textContent = st && st.screen ? st.screen : "";
  }

  // ── Playback ───────────────────────────────────────────────────────────────
  function applySpeed() {
    els.speedBtn.textContent = (speed % 1 ? speed.toFixed(1) : speed) + "×";
    if (S && S.videoUrl) els.video.playbackRate = speed;
  }
  function cycleSpeed() {
    speed = SPEEDS[(SPEEDS.indexOf(speed) + 1) % SPEEDS.length];
    prefs.speed = speed; LS.set("speed", speed);
    els.setSpeed.value = String(speed);
    applySpeed();
    toast("Speed " + speed + "×", 1200);
  }
  function play() {
    if (playing || !S) return;
    playing = true; els.playPause.innerHTML = ICON_PAUSE;
    lastReal = performance.now(); lastHeavy = 0;
    if (S.videoUrl) { els.video.playbackRate = speed; els.video.play().catch(() => {}); }
    rafId = requestAnimationFrame(loop);
  }
  function pause() {
    playing = false; els.playPause.innerHTML = ICON_PLAY;
    if (S && S.videoUrl) els.video.pause();
    if (rafId) cancelAnimationFrame(rafId);
  }
  function loop(now) {
    if (!playing) return;
    if (S.videoUrl) {
      playhead = clampHead(videoBase() + els.video.currentTime * 1000);
      if (els.video.ended) { pause(); }
    } else {
      playhead = clampHead(playhead + (now - lastReal) * speed);
      lastReal = now;
      if (playhead >= S.end) { pause(); }
    }
    syncMedia(false);
    updatePlayheadUI(now - lastHeavy > 200);
    if (now - lastHeavy > 200) lastHeavy = now;
    if (playing) rafId = requestAnimationFrame(loop);
  }
  function seek(ts, fromUser) {
    playhead = clampHead(ts);
    if (fromUser) pause();
    syncMedia(true);
    updatePlayheadUI(true);
  }
  function stepEvent(dir) {
    const ev = allEvents();
    if (!ev.length) return;
    if (dir > 0) {
      // C7: binary search for the first event strictly after the playhead.
      let lo = 0, hi = ev.length, ans = ev.length;
      while (lo < hi) { const m = (lo + hi) >> 1; if (ev[m].ts > playhead) { ans = m; hi = m; } else lo = m + 1; }
      if (ans < ev.length) seek(ev[ans].ts, true);
    } else {
      // C7: binary search for the rightmost event strictly before the playhead. If it shares the
      // playhead's ts (clustered events), keep stepping back until the ts changes.
      let lo = 0, hi = ev.length, ans = -1;
      while (lo < hi) { const m = (lo + hi) >> 1; if (ev[m].ts < playhead) { ans = m; lo = m + 1; } else hi = m; }
      if (ans < 0) return;
      const target = ev[ans].ts;
      while (ans > 0 && ev[ans - 1].ts === target) ans--;
      seek(ev[ans].ts, true);
    }
  }
  function jumpError() { const e = allEvents().find((x) => x.isError); if (e) seek(e.ts, true); else toast("No errors in this session"); }

  function updatePlayheadUI(heavy) {
    els.scrubber.value = playhead - S.start;
    els.scrubber.style.setProperty("--prog", (((playhead - S.start) / S.duration) * 100).toFixed(2) + "%");
    els.time.textContent = fmtFine(playhead - S.start) + " / " + fmt(S.duration);
    if (heavy) {
      renderState();
      highlightFilmstrip();
      // BUGFIX: while the compare/diff view is open, the track list is the diff — playback
      // must not rebuild it with track rows (which used to silently destroy the diff).
      if (prefs.follow && activeTrack !== "report" && activeTrack !== "aibrief" && !S2) renderTrack();
    }
  }

  // ── Rendering ──────────────────────────────────────────────────────────────
  function scoreClass(n) { return n >= 85 ? "ok" : n >= 70 ? "warn" : n >= 50 ? "warn2" : "err"; }

  function renderTopbar() {
    const m = S.manifest, a = m.app || {};
    els.topbarSession.hidden = false;
    els.tbApp.textContent = (a.name || "session") + (a.version ? " v" + a.version : "");
    els.tbEnv.textContent = m.environment || "";
    els.tbEnv.hidden = !m.environment;
    const sc = S.summary ? S.summary.score : null;
    els.tbScore.textContent = sc == null ? "" : sc + "/100";
    els.tbScore.className = "tb-score " + (sc == null ? "" : scoreClass(sc));
  }

  function renderBanner() {
    const m = S.manifest, a = m.app || {};
    const bits = [];
    if (m.environment) bits.push(`<b>${esc(m.environment.toUpperCase())}</b>`);
    if (a.version) bits.push("v" + esc(a.version));
    if (m.device) bits.push(esc(m.device));
    if (m.androidVersion) bits.push("Android " + esc(m.androidVersion));
    if (m.gitSha) bits.push("git " + esc(String(m.gitSha).slice(0, 7)));
    const media = S.videoUrl ? "🎬 video" : (S.frames.length + " frames");
    els.banner.innerHTML = `<div class="banner-bits">${bits.join('<span class="sep">·</span>')}</div>
      <div class="banner-meta">${media} · ${fmt(S.duration)}</div>`;
  }

  function renderStatbar() {
    const failed = S.network.filter((e) => e.error || e.status >= 400).length;
    const slow = S.network.filter((e) => !e.error && e.status < 400 && e.latencyMs >= SLOW_MS).length;
    const errLogs = S.logs.filter((e) => /error|\[ERROR\]|fail|exception/i.test(e.message || "")).length;
    const sc = S.summary ? S.summary.score : null;
    const crashes = S.analysis?.stats?.crashes ?? 0;
    const jankRate = S.analysis?.jank?.jankRate ?? null;
    const cells = [
      [fmt(S.duration), "duration"],
      [sc == null ? "—" : sc, "score", sc == null ? "" : scoreClass(sc)],
      [S.network.length, "requests"],
      [failed, "failed", failed ? "err" : "ok"],
      [slow, "slow", slow ? "warn" : "ok"],
      [S.timeline.length, "events"],
      [errLogs, "error logs", errLogs ? "err" : "ok"],
      [screenVisits().length, "screens"],
    ];
    if (crashes > 0) cells.push([crashes, "crashes", "err"]);
    if (jankRate != null && jankRate > 0) cells.push([jankRate + "%", "jank", jankRate > 30 ? "err" : "warn"]);
    els.statbar.innerHTML = cells.map(([v, l, cls]) =>
      `<div class="stat"><b class="${cls || ""}">${esc(v)}</b><span>${esc(l)}</span></div>`).join("");
  }

  function renderSummary() {
    const s = S.summary;
    if (!s) { els.summaryBody.innerHTML = `<p class="muted">No summary in this recording.</p>`; return; }
    const sc = s.score || 0, cls = scoreClass(sc);
    const penalties = (s.penalties || []).map((p) =>
      `<div class="kv"><span>${esc(p.dimension)}</span><b class="err">-${p.points}</b></div>`).join("");
    const reasons = (s.reasons || []).map((r) => `<li>${esc(r)}</li>`).join("");
    const steps = (s.repro && s.repro.steps || []).map((x) => `<li>${esc(x)}</li>`).join("");
    els.summaryBody.innerHTML = `
      <div class="summary-top">
        <div class="donut ${cls}" style="--p:${sc}">
          <div class="donut-hole"><span class="donut-num">${sc}</span><span class="donut-cap">/100</span></div>
        </div>
        <div class="summary-meta">
          <div class="band band-${cls}">${esc(s.band || "")}</div>
          <div class="owner">Likely owner: <b>${esc(s.category || "Unknown")}</b>
            <span class="conf conf-${(s.confidence||'LOW').toLowerCase()}">${esc(s.confidence || "")}</span></div>
          <ul class="reasons">${reasons}</ul>
        </div>
      </div>
      ${penalties ? `<div class="subhead">Why this score</div><div class="kvs">${penalties}</div>` : ""}
      ${steps ? `<div class="subhead">Reproduction</div><ol class="steps">${steps}</ol>
        <div class="ea"><div><span class="ea-l ok">Expected</span> ${esc(s.repro.expected)}</div>
        <div><span class="ea-l err">Actual</span> ${esc(s.repro.actual)}</div></div>` : ""}`;
  }

  function renderState() {
    const st = stateAt(playhead);
    els.stateTime.textContent = fmt(playhead - S.start);
    if (!st) { els.stateBody.innerHTML = `<p class="muted">No state captured.</p>`; return; }
    const flags = Object.entries(st.featureFlags || {}).map(([k, v]) =>
      `<div class="kv"><span>${esc(k)}</span><b class="${v ? "ok" : "muted"}">${v ? "ON" : "OFF"}</b></div>`).join("");
    let data = "";
    for (const [src, kv] of Object.entries(st.dataSources || {})) {
      const rows = Object.entries(kv).map(([k, v]) => `<div class="kv"><span>${esc(k)}</span><b>${esc(v)}</b></div>`).join("");
      data += `<div class="subhead">${esc(src)}</div><div class="kvs">${rows}</div>`;
    }
    els.stateBody.innerHTML =
      `<div class="kv big"><span>Screen</span><b>${esc(st.screen || "—")}</b></div>
       ${st.route ? `<div class="kv"><span>Route</span><b>${esc(st.route)}</b></div>` : ""}
       ${flags ? `<div class="subhead">Feature flags</div><div class="kvs">${flags}</div>` : ""}
       ${data}`;
  }

  function renderDevice() {
    // Wide, not tall: label-over-value tiles that wrap into columns (the card sits next to the
    // banner in the top row, so horizontal space is the cheap dimension).
    const m = S.manifest, a = m.app || {}, c = m.counts || {};
    const tiles = [
      ["App", `${esc(a.name || "")} ${esc(a.version || "")}`],
      ["Env · variant", `${esc(m.environment || "—")} · ${esc(a.variant || "—")}`],
      ["Git", esc(m.gitSha || "—")],
      ["Device", esc(m.device || "—")],
      ["Android", esc(m.androidVersion || "—")],
      ["Recorded", new Date(m.createdAtMillis || Date.now()).toLocaleString()],
      ["Duration", fmt(S.duration)],
      ["Captured", `${c.frames || 0} frames · ${c.network || 0} net · ${c.logs || 0} logs`],
    ].map(([k, v]) => `<div class="kvt"><span>${k}</span><b>${v}</b></div>`).join("");
    els.deviceBody.innerHTML = `<div class="kv-grid">${tiles}</div>`;
  }

  function renderCounts() {
    els.cTimeline.textContent = S.timeline.length;
    els.cNetwork.textContent = S.network.length;
    els.cLogs.textContent = S.logs.length;
    els.cScreens.textContent = screenVisits().length;
    els.cInsights.textContent = insights().filter((i) => i.sev !== "ok").length || "";
  }

  function renderFilmstrip() {
    if (!S.frames.length) { els.filmstrip.hidden = true; els.filmstrip.innerHTML = ""; return; }
    els.filmstrip.hidden = false;
    const max = 14;
    const step = Math.max(1, Math.floor(S.frames.length / max));
    const picks = [];
    for (let i = 0; i < S.frames.length; i += step) picks.push(S.frames[i]);
    els.filmstrip.innerHTML = picks.map((f) =>
      `<img src="${f.url}" data-ts="${f.ts}" alt="" title="${fmt(f.ts - S.start)}" />`).join("");
  }
  function highlightFilmstrip() {
    if (els.filmstrip.hidden) return;
    let cur = null;
    for (const img of els.filmstrip.children) {
      if (Number(img.dataset.ts) <= playhead) cur = img;
      img.classList.remove("cur");
    }
    if (cur) cur.classList.add("cur");
  }
  // C16: half the mean inter-frame interval, so a filmstrip click lands mid-frame.
  function filmstripHalfStep() {
    const f = S.frames;
    if (f.length < 2) return 250;
    const sorted = f.map((x) => x.ts).sort((a, b) => a - b);
    let sum = 0;
    for (let i = 1; i < sorted.length; i++) sum += sorted[i] - sorted[i - 1];
    return Math.min(750, Math.round(sum / (sorted.length - 1) / 2));
  }

  function renderScrubMarks() {
    // Activity ticks (accent) under the scrubber, error ticks (red) on top,
    // star bookmarks (C9) above — the three layers share the same timeline.
    const evs = allEvents();
    const plain = evs.filter((e) => !e.isError).slice(0,300);
    const errs = evs.filter((e) => e.isError).slice(0, 200);
    const tick = (ts, cls) =>
      `<i class="${cls}" style="left:${(((ts - S.start) / S.duration) * 100).toFixed(2)}%"></i>`;
    const stars = (S.marks || []).concat(S._localMarks || []).map((m) =>
      `<i class="star" title="${esc(m.label || "bookmark")}" style="left:${(((m.ts - S.start) / S.duration) * 100).toFixed(2)}%"></i>`).join("");
    els.scrubMarks.innerHTML = plain.map((e) => tick(e.ts, "")).join("") + errs.map((e) => tick(e.ts, "err")).join("") + stars;
  }

  function statusPill(e) {
    const code = e.error ? "ERR" : (e.status === 0 ? "…" : e.status);
    const cls = e.error || e.status >= 400 ? "err" : e.status >= 300 ? "warn" : "ok";
    return `<span class="pill ${cls}">${esc(code)}</span>`;
  }

  function shortPath(url) {
    try { const u = new URL(url); return u.pathname || url; } catch { return url; }
  }
  function hostOf(url) { try { return new URL(url).host; } catch { return ""; } }

  function networkSubstats() {
    const lat = S.network.filter((e) => !e.error).map((e) => e.latencyMs).sort((a, b) => a - b);
    const failed = S.network.filter((e) => e.error || e.status >= 400).length;
    const slow = S.network.filter((e) => !e.error && e.status < 400 && e.latencyMs >= SLOW_MS).length;
    const avg = lat.length ? Math.round(lat.reduce((a, b) => a + b, 0) / lat.length) : 0;
    const hosts = [...new Set(S.network.map((e) => hostOf(e.url)).filter(Boolean))];
    const bytes = S.network.reduce((n, e) => n + (e.responseBytes || 0), 0);
    let html = `<span><b>${S.network.length}</b> requests</span>
      <span class="${failed ? "err" : ""}"><b>${failed}</b> failed</span>
      <span class="${slow ? "warn" : ""}"><b>${slow}</b> slow</span>
      <span>avg <b>${avg}ms</b></span>
      <span>p95 <b>${percentile(lat, 95)}ms</b></span>
      <span><b>${fmtBytes(bytes)}</b> down</span>
      <span><b>${hosts.length}</b> host${hosts.length === 1 ? "" : "s"}</span>`;
    // C11: Surface per-endpoint breakdown from analysis.json when available.
    const endpoints = S.analysis?.endpoints;
    if (endpoints && endpoints.length) {
      html += `<div class="sub-endpoints">`;
      endpoints.slice(0, 8).forEach((ep) => {
        const cls = ep.failedRequests > 0 ? "err" : ep.slowRequests > 0 ? "warn" : "";
        html += `<span class="${cls}" title="${esc(ep.endpoint)}">${esc(ep.endpoint.split(" ").pop()?.slice(0, 30) || ep.endpoint)}</span>`;
      });
      html += `</div>`;
    }
    return html;
  }

  function rowsFor(track) {
    const q = els.search.value.trim().toLowerCase();
    const follow = prefs.follow;
    let items;
    if (track === "timeline") {
      const markItems = (S.marks || []).concat(S._localMarks || []).map((m) => ({
        ts: m.ts, isError: false, main: "★ " + (m.label || "bookmark"),
        sub: (m.local ? "" : "from the recording · ") + (m.severity || "info"),
        tag: "bookmark", text: ("★ " + (m.label || "") + " " + (m.severity || "")).toLowerCase(),
        _bookmark: true, _severity: m.severity || "info", _markId: m.id || null, _local: !!m.local,
      }));
      items = S.timeline.map((e) => ({
        ts: e.ts, isError: !!e.isError, main: e.title || "", sub: e.detail || "", tag: e.kind || "",
        text: (e.title + " " + (e.detail || "")).toLowerCase(),
      })).concat(markItems).sort((a, b) => a.ts - b.ts);
    } else if (track === "network") {
      const maxLat = Math.max(1, ...S.network.map((e) => e.latencyMs || 0));
      items = S.network.map((e) => {
        const cls = e.error || e.status >= 400 ? "err" : e.latencyMs >= SLOW_MS ? "warn" : "";
        const w = Math.max(2, Math.round(((e.latencyMs || 0) / maxLat) * 100));
        const off = Math.round(((e.ts - S.start) / S.duration) * (100 - w));
        return {
          ts: e.ts, isError: e.error || e.status >= 400,
          main: `${e.method} ${shortPath(e.url)}`,
          sub: `${e.latencyMs}ms · ${fmtBytes(e.responseBytes || 0)}${e.error ? " · " + e.error : ""} · ${hostOf(e.url)}`,
          pill: statusPill(e), detail: e.url,
          extra: `<div class="waterfall"><i class="${cls}" style="left:${off}%;width:${w}%"></i></div>`,
          text: (e.method + " " + e.url).toLowerCase(),
        };
      });
    } else {
      const lvl = prefs.logLevel;
      const raw = S.logs.filter((e) => lvl === "all" || e.type === lvl);
      // Collapse consecutive duplicates — log-heavy apps repeat the same line dozens of times.
      const collapsed = [];
      for (const e of raw) {
        const last = collapsed[collapsed.length - 1];
        if (last && last.message === e.message && last.type === e.type && last.tag === e.tag) last._n++;
        else collapsed.push({ ...e, _n: 1 });
      }
      items = collapsed.map((e) => ({
        ts: e.ts, isError: /error|\[ERROR\]|fail|exception/i.test(e.message || ""),
        main: (e.message || "") + (e._n > 1 ? `   ×${e._n}` : ""),
        sub: `[${e.type}]${e.tag ? " " + e.tag : ""}`, tag: e.type,
        text: ((e.message || "") + " " + (e.tag || "")).toLowerCase(),
      }));
    }
    if (q) items = items.filter((i) => i.text.includes(q));
    items.sort((a, b) => a.ts - b.ts);
    // All rows stay visible (so you can click-to-seek FORWARD too); with follow on, rows after
    // the playhead are dimmed and the row at the playhead is highlighted.
    return { visible: items.slice().reverse(), follow, total: items.length };
  }

  function renderTrack() {
    if (activeTrack === "report") return;
    if (activeTrack === "screens") return renderScreens();
    if (activeTrack === "insights") return renderInsights();
    const { visible, follow, total } = rowsFor(activeTrack);
    if (!visible.length) {
      els.trackList.innerHTML = `<div class="empty">No entries.</div>`;
      lastRenderSig = "";
      return;
    }
    // C2: only rebuild the DOM when the filter/track/content actually changed. During playback
    // updatePlayheadUI calls this every ~200ms with follow on — rebuilding 600 rows 5×/s janks.
    // When the signature is unchanged, keep the existing rows and just reapply highlight classes.
    const sig = `${activeTrack}|${els.search.value.trim().toLowerCase()}|${prefs.logLevel}|${total}|${follow}`;
    if (sig === lastRenderSig && els.trackList.children.length === visible.length) {
      updateRowHighlighting(visible, follow);
      return;
    }
    lastRenderSig = sig;
    // visible is newest-first; the "current" row is the first one at/behind the playhead.
    const curIdx = follow ? visible.findIndex((i) => i.ts <= playhead) : -1;
    const html = visible.map((i, idx) => {
      const cur = idx === curIdx;
      const future = follow && i.ts > playhead;
      const bkmrk = i._bookmark ? " row-bookmark sev-" + (i._severity || "info") : "";
      const sevChip = i._bookmark ? '<span class="sev-chip ' + (i._severity || "info") + '">' + esc(i._severity || "info") + '</span>' : "";
      const del = i._bookmark && i._local
        ? '<button class="mark-del" data-mark-id="' + esc(i._markId) + '" title="Delete this mark">✕</button>'
        : "";
      return `<div class="row ${i.isError ? "row-err" : ""} ${bkmrk} ${cur ? "row-cur" : ""} ${future ? "row-future" : ""}" data-ts="${i.ts}" data-detail="${i.detail ? esc(i.detail) : ""}" title="Click to jump the player to ${fmtFine(i.ts - S.start)}">
        <div class="row-time">${fmtFine(i.ts - S.start)}</div>
        <div class="row-body">
          <div class="row-main">${i.pill || ""}${i.tag ? `<span class="kind">${esc(i.tag)}</span>` : ""}<span>${esc(i.main)}</span>${sevChip}</div>
          ${i.sub ? `<div class="row-sub">${esc(i.sub)}</div>` : ""}
          ${i.extra || ""}
        </div>
        ${del}
        <button class="row-jump" title="Jump here">↧</button>
      </div>`;
    }).join("");
    els.trackList.innerHTML = html;
  }

  // C2: cheap highlight-only update for the follow-the-playhead case — toggles row-cur/row-future
  // classes on existing DOM nodes instead of rebuilding innerHTML. O(rows) with no layout thrash.
  function updateRowHighlighting(visible, follow) {
    const kids = els.trackList.children;
    let curIdx = follow ? -1 : -2;  // -2 means "no current row highlight" (follow off)
    if (follow) {
      for (let i = 0; i < visible.length; i++) {
        if (visible[i].ts <= playhead) curIdx = i; else break;
      }
    }
    for (let i = 0; i < kids.length; i++) {
      const want = i === curIdx;
      const future = follow && visible[i].ts > playhead;
      kids[i].classList.toggle("row-cur", want);
      kids[i].classList.toggle("row-future", future);
    }
  }

  function renderScreens() {
    const visits = screenVisits();
    if (!visits.length) { els.trackList.innerHTML = `<div class="empty">No state samples in this recording.</div>`; return; }
    const maxDur = Math.max(1, ...visits.map((v) => v.end - v.start));
    els.trackList.innerHTML = visits.map((v) => {
      const cur = playhead >= v.start && playhead < v.end;
      return `<div class="screen-visit ${cur ? "row-cur" : ""}" data-ts="${v.start}">
        <div class="sv-name">${esc(v.name)}</div>
        <div class="sv-bar"><i style="width:${Math.max(3, Math.round(((v.end - v.start) / maxDur) * 100))}%"></i></div>
        <div class="sv-dur">${fmt(v.end - v.start)}</div>
        <button class="row-jump" title="Jump here">↧</button>
      </div>`;
    }).join("");
  }

  function renderInsights() {
    els.trackList.innerHTML = insights().map((i) => `
      <div class="insight ${i.sev}">
        <h5>${esc(i.title)}</h5>
        <p>${esc(i.detail)}</p>
        ${i.sev === "ok" ? "" : `<button class="row-jump" data-ts="${i.ts}">↧ jump to ${fmt(i.ts - S.start)}</button>`}
      </div>`).join("");
  }

  function setTrack(track) {
    // BUGFIX: tab switching while the compare view is open used to silently replace the diff
    // DOM with track rows. Close the compare first — the user asked for a track.
    if (S2) { revokeS2Urls(); S2 = null; }
    activeTrack = track; prefs.track = track; LS.set("track", track);
    lastRenderSig = "";  // C2: force a rebuild on tab switch
    [...els.trackTabs.children].forEach((b) => b.classList.toggle("active", b.dataset.track === track));
    els.logChips.hidden = track !== "logs";
    const isReport = track === "report";
    const isAiBrief = track === "aibrief";
    els.reportView.hidden = !(isReport || isAiBrief);
    els.trackList.hidden = isReport || isAiBrief;
    els.aiBriefBar.hidden = !isAiBrief;
    els.search.parentElement.style.display = (isReport || isAiBrief || track === "screens" || track === "insights") ? "none" : "";
    els.trackSubstats.hidden = track !== "network";
    if (track === "network") els.trackSubstats.innerHTML = networkSubstats();
    if (isReport) {
      els.reportView.textContent = S.report || "(no report)";
    } else if (isAiBrief) {
      // C12: prefer the recorder's own for_ai.md; synthesize a brief for older files.
      els.reportView.textContent = S.forAi ||
        "This recording predates for_ai.md. Quick brief:\n\n" +
        "Session: " + ((S.manifest.app && S.manifest.app.name) || "app") +
        " · " + fmt(S.duration) + " · score " + (S.summary ? S.summary.score : "?") + "/100\n" +
        "Likely owner: " + (S.summary ? S.summary.category : "?") + "\n" +
        "Failed requests: " + S.network.filter((e) => e.error || e.status >= 400).length + "\n" +
        "Screens: " + [...new Set(S.state.map((st) => st.screen || "?"))].join(", ");
    } else renderTrack();
  }

  // ── Export (markdown to clipboard) ─────────────────────────────────────────
  function exportSummary() {
    const m = S.manifest, a = m.app || {}, s = S.summary;
    const failed = S.network.filter((e) => e.error || e.status >= 400);
    const lines = [
      `## QA Session — ${a.name || "app"} ${a.version || ""}`,
      ``,
      `| | |`, `|---|---|`,
      `| Environment | ${m.environment || "—"} |`,
      `| Device | ${m.device || "—"} (Android ${m.androidVersion || "—"}) |`,
      `| Build | ${a.variant || "—"} · git ${m.gitSha || "—"} |`,
      `| Recorded | ${new Date(m.createdAtMillis || Date.now()).toLocaleString()} · ${fmt(S.duration)} |`,
      `| Score | ${s ? s.score + "/100 (" + (s.band || "") + ")" : "—"} |`,
      `| Likely owner | ${s ? (s.category || "—") + " · " + (s.confidence || "") : "—"} |`,
    ];
    if (s && s.repro && (s.repro.steps || []).length) {
      lines.push("", "**Repro steps**");
      s.repro.steps.forEach((x) => lines.push(`- ${x}`));
      lines.push("", `**Expected:** ${s.repro.expected}`, `**Actual:** ${s.repro.actual}`);
    }
    if (failed.length) {
      lines.push("", "**Failed requests**");
      failed.slice(0, 10).forEach((e) =>
        lines.push(`- \`${e.method} ${shortPath(e.url)}\` → ${e.error || e.status} (${e.latencyMs}ms)`));
    }
    const ins = insights().filter((i) => i.sev !== "ok");
    if (ins.length) {
      lines.push("", "**Auto-detected anomalies** (open the .sal with `?t=<sec>` to land on the moment)");
      ins.forEach((i) => lines.push(`- \`t=${((i.ts - S.start) / 1000).toFixed(1)}\` ${i.title} — ${i.detail}`));
    }
    const marks = (S.marks || []).concat(S._localMarks || []);
    if (marks.length) {
      lines.push("", "**Bookmarks**");
      marks.forEach((mk) => lines.push(`- \`t=${((mk.ts - S.start) / 1000).toFixed(1)}\` ★ ${mk.label || "bookmark"}${mk.severity ? " (" + mk.severity + ")" : ""}`));
    }
    if (S.forAi) lines.push("", "**AI brief** (`for_ai.md`) is embedded in the .sal — paste it into your AI of choice.");
    const coverage = SAL.recordingCoverage(S);
    if (coverage.partial) lines.unshift(
      "> **Partial recording — conclusions cover retained evidence only.**", ...coverage.warnings.map((w) => "> " + w), "");
    const text = lines.join("\n");
    (navigator.clipboard ? navigator.clipboard.writeText(text) : Promise.reject())
      .then(() => toast("Session summary copied as markdown ✓"))
      .catch(() => { console.log(text); toast("Clipboard blocked — summary printed to console"); });
  }

  // ── C9: in-viewer bookmarks ("⭐ Mark moment") ─────────────────────────────
  function markNow() {
    if (!S) return;
    const all = (S.marks || []).concat(S._localMarks || []);
    if (all.some((m) => Math.abs(m.ts - playhead) < 300)) {
      toast("This moment is already marked — delete the existing mark first", 3000);
      return;
    }
    const label = (window.prompt("What happened at this moment?", "Marked by QA") || "").trim() || "Marked by QA";
    const severity = (window.prompt("Severity: info / warning / bug", "info") || "info").trim().toLowerCase();
    S._localMarks.push({ id: "m" + Date.now(), ts: playhead, label: label, severity: ["info", "warning", "bug"].includes(severity) ? severity : "info", local: true });
    renderScrubMarks();
    if (activeTrack === "timeline") renderTrack();
    toast("★ Marked " + fmtFine(playhead - S.start) + " (" + (["info", "warning", "bug"].includes(severity) ? severity : "info") + ")");
  }
  function deleteMark(id) {
    S._localMarks = (S._localMarks || []).filter((m) => m.id !== id);
    renderScrubMarks();
    if (activeTrack === "timeline") renderTrack();
    toast("Mark removed");
  }

  // ── Backend hook: send the open session to the configured Mission Control backend ──
  // Mirrors the mobile webhook contract: multipart 'file' (.sal) + X-QaLens-* headers to
  // <base>/webhook, or a JSON summary to <base>/api/ingest when no raw file is available.
  function buildWebSummary() {
    const m = S.manifest, a = m.app || {}, s = S.summary;
    return {
      app: a.name || "web session", version: a.version || "",
      environment: m.environment || "", device: m.device || "web player",
      platform: "web", name: (a.name || "session") + ".sal",
      sessionId: m.sessionId || null,
      score: s ? s.score : null, likelyOwner: s ? s.category : null,
      failedRequests: S.network.filter((e) => e.error || e.status >= 400).length,
      crashes: S.analysis?.stats?.crashes ?? 0,
      durationMs: S.duration,
      marks: (S.marks || []).concat(S._localMarks || []).map((mk) => ({ tSec: ((mk.ts - S.start) / 1000).toFixed(1), label: mk.label })),
      anomalies: insights().filter((i) => i.sev !== "ok").map((i) => ({ tSec: ((i.ts - S.start) / 1000).toFixed(1), title: i.title, detail: i.detail })),
    };
  }

  async function sendToBackend() {
    if (!S) return;
    const base = String(prefs.backend || "").trim().replace(/\/+$/, "");
    if (!base) { openDrawer(); toast("Set a Backend URL in Settings first", 3500); return; }
    els.sendBackendBtn.disabled = true;
    els.sendBackendBtn.textContent = "⇪ sending…";
    try {
      const m = S.manifest, a = m.app || {};
      const headers = {
        "X-QaLens-App": a.name || "web session",
        "X-QaLens-Version": a.version || "",
        "X-QaLens-Env": m.environment || "",
        "X-QaLens-Device": m.device || "web player",
        "X-QaLens-Platform": "web",
      };
      Object.keys(headers).forEach((k) => { if (!headers[k]) delete headers[k]; });
      let res;
      if (S._rawBuf) {
        // Full-fidelity: ship the actual .sal (multipart 'file'), same as the mobile Control Room.
        const fd = new FormData();
        fd.append("file", new Blob([S._rawBuf], { type: "application/zip" }), (a.name || "session") + ".sal");
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
      toast("Backend unreachable (" + base + "): " + (e.message || e), 6000);
    } finally {
      els.sendBackendBtn.disabled = false;
      els.sendBackendBtn.textContent = "⇪ Send to backend";
    }
  }

  // ── Recents (metadata in localStorage, payloads in IndexedDB) ─────────────
  function saveRecent(s, name, size) {
    const rec = {
      key: name + ":" + size,
      name, size, date: Date.now(),
      score: s.summary ? s.summary.score : null,
      env: s.manifest.environment || "",
      app: (s.manifest.app && s.manifest.app.name) || "",
      events: (s.timeline.length + s.network.length + s.logs.length),
      dur: s.duration,
    };
    let list = LS.get("recent", []);
    list = list.filter((r) => !(r.name === name && r.size === size));
    list.unshift(rec);
    LS.set("recent", list.slice(0, 12));
  }
  async function renderRecents() {
    const list = LS.get("recent", []);
    els.recents.hidden = list.length === 0;
    const cached = await updateCacheUsage();
    els.recentsGrid.innerHTML = list.map((r) => {
      const key = r.key || (r.name + ":" + r.size);
      const isCached = cached.has(key);
      const sc = r.score == null ? "" : `<span class="rc-score ${scoreClass(r.score)}">${r.score}</span>`;
      return `<div class="recent ${isCached ? "cached" : ""}" data-key="${esc(key)}">
        <div class="rc-top">${sc}<div class="rc-name">${esc(r.name)}</div></div>
        <div class="rc-meta">${esc(r.app || "")} ${r.env ? "· " + esc(r.env) : ""}</div>
        <div class="rc-meta muted">${r.events} events · ${fmt(r.dur)} · ${new Date(r.date).toLocaleDateString()}</div>
        ${isCached ? `<div class="rc-replay">▶ Replay instantly</div>` : `<div class="rc-meta muted">drop file to replay</div>`}
      </div>`;
    }).join("");
  }

  // ── Theatre / fullscreen ───────────────────────────────────────────────────
  function setTheater(on) {
    prefs.theater = on; LS.set("theater", on);
    document.body.classList.toggle("theater", on);
    els.theaterBtn.classList.toggle("tbtn-on", on);
  }
  function toggleFullscreen() {
    const frame = document.querySelector(".device-frame");
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    else if (frame) frame.requestFullscreen().catch(() => toast("Fullscreen blocked by the browser"));
  }

  // ── C8: Keyboard help overlay (press ? to toggle) ─────────────────────────
  function toggleHelpOverlay() {
    let overlay = document.getElementById("helpOverlay");
    if (overlay) { overlay.remove(); return; }
    overlay = document.createElement("div");
    overlay.id = "helpOverlay";
    overlay.className = "help-overlay";
    overlay.innerHTML = `
      <div class="help-card">
        <div class="help-title">Keyboard Shortcuts</div>
        <div class="help-grid">
          <div class="help-row"><kbd>Space</kbd><span>Play / Pause</span></div>
          <div class="help-row"><kbd>←</kbd><span>Previous event</span></div>
          <div class="help-row"><kbd>→</kbd><span>Next event</span></div>
          <div class="help-row"><kbd>e</kbd><span>Jump to next error</span></div>
          <div class="help-row"><kbd>s</kbd><span>Cycle playback speed</span></div>
          <div class="help-row"><kbd>f</kbd><span>Toggle follow mode</span></div>
          <div class="help-row"><kbd>t</kbd><span>Toggle theater mode</span></div>
          <div class="help-row"><kbd>x</kbd><span>Toggle fullscreen</span></div>
          <div class="help-row"><kbd>1</kbd>–<kbd>7</kbd><span>Switch track (7 = AI brief)</span></div>
          <div class="help-row"><kbd>m</kbd><span>⭐ Mark moment</span></div>
          <div class="help-row"><kbd>?</kbd><span>Toggle this help</span></div>
        </div>
        <div class="help-dismiss">Press <kbd>?</kbd> or <kbd>Esc</kbd> to close</div>
      </div>`;
    overlay.addEventListener("click", (e) => { if (e.target === overlay) toggleHelpOverlay(); });
    document.body.appendChild(overlay);
  }

  // ── Settings drawer ────────────────────────────────────────────────────────
  function setTheme(t) { document.documentElement.dataset.theme = t; prefs.theme = t; LS.set("theme", t); els.setTheme.value = t; }
  function setCompact(on) { document.body.classList.toggle("compact", on); prefs.compact = on; LS.set("compact", on); }
  function openDrawer() { els.drawer.hidden = false; updateCacheUsage(); }
  function closeDrawer() { els.drawer.hidden = true; }

  function applyExpanded() {
    document.querySelectorAll("[data-card]").forEach((card) => {
      const open = prefs.expanded[card.dataset.card] !== false;
      card.classList.toggle("collapsed", !open);
    });
  }

  // ── Wire up ──────────────────────────────────────────────────────────────
  function init() {
    setTheme(prefs.theme);
    setCompact(prefs.compact);
    els.followToggle.checked = prefs.follow;
    els.setFollow.checked = prefs.follow;
    els.setSpeed.value = String(speed);
    els.setAutoplay.checked = prefs.autoplay;
    els.setDensity.checked = prefs.compact;
    els.setCache.checked = prefs.cache;
    applySpeed();
    applyExpanded();
    renderRecents();
    if (typeof DecompressionStream === "undefined")
      els.compatNote.textContent = "⚠ Your browser lacks DecompressionStream — use a recent Chrome, Edge, Safari, or Firefox.";

    // open
    const pick = () => els.fileInput.click();
    $("openBtn").onclick = pick; $("openBtn2").onclick = pick;
    els.fileInput.onchange = (e) => { if (e.target.files[0]) loadFile(e.target.files[0]); };

    // Load bundled sample (works when served over http; file:// blocks fetch).
    const sampleBtn = $("sampleBtn");
    if (sampleBtn) sampleBtn.onclick = async () => {
      try {
        const res = await fetch("sample.sal");
        if (!res.ok) throw new Error("not found");
        const buf = await res.arrayBuffer();
        onLoaded(await SAL.read(buf), "sample.sal", buf.byteLength, buf);
      } catch (_) {
        toast("Serve the folder (python3 -m http.server) or drag web/sample.sal in.", 4500);
      }
    };

    // drag & drop (whole window)
    ["dragenter", "dragover"].forEach((ev) => window.addEventListener(ev, (e) => {
      e.preventDefault(); els.dropzone && els.dropzone.classList.add("hot");
    }));
    ["dragleave", "drop"].forEach((ev) => window.addEventListener(ev, (e) => {
      e.preventDefault(); els.dropzone && els.dropzone.classList.remove("hot");
    }));
    window.addEventListener("drop", (e) => { const f = e.dataTransfer.files[0]; if (f) loadFile(f); });

    // theme + drawer
    $("themeBtn").onclick = () => setTheme(prefs.theme === "dark" ? "light" : "dark");
    els.settingsBtn.onclick = () => (els.drawer.hidden ? openDrawer() : closeDrawer());
    els.drawerClose.onclick = closeDrawer;
    els.setTheme.onchange = () => setTheme(els.setTheme.value);
    els.setDensity.onchange = () => setCompact(els.setDensity.checked);
    els.setSpeed.onchange = () => {
      speed = Number(els.setSpeed.value) || 1;
      prefs.speed = speed; LS.set("speed", speed); applySpeed();
    };
    els.setAutoplay.onchange = () => { prefs.autoplay = els.setAutoplay.checked; LS.set("autoplay", prefs.autoplay); };
    els.setFollow.onchange = () => {
      prefs.follow = els.setFollow.checked; LS.set("follow", prefs.follow);
      els.followToggle.checked = prefs.follow;
      if (S) renderTrack();
    };
    els.setCache.onchange = () => { prefs.cache = els.setCache.checked; LS.set("cache", prefs.cache); };
    els.setCacheClear.onclick = async () => { await IDB.clear(); updateCacheUsage(); renderRecents(); toast("Session cache cleared"); };

    // Backend hook (frontend): Mission Control → mock/real QaLens backend.
    els.setBackend.value = prefs.backend;
    els.setBackend.onchange = () => { prefs.backend = els.setBackend.value.trim(); LS.set("backend", prefs.backend); toast(prefs.backend ? "Backend URL saved — use ⇪ Send to backend" : "Backend URL cleared"); };
    els.backendOpen.onclick = () => {
      const b = String(prefs.backend || "").trim();
      if (!b) { toast("Set a Backend URL first"); return; }
      window.open(b, "_blank", "noopener");
    };
    els.sendBackendBtn.onclick = sendToBackend;
    els.markBtn.onclick = markNow;
    els.aiBriefCopy.onclick = () => {
      const brief = S && S.forAi ? S.forAi : els.reportView.textContent;
      (navigator.clipboard ? navigator.clipboard.writeText(brief) : Promise.reject())
        .then(() => toast("AI brief copied ✓"))
        .catch(() => toast("Clipboard blocked"));
    };

    // export + close
    els.exportBtn.onclick = exportSummary;
    els.exportSalBtn.onclick = () => {
      if (!S || !S._rawBuf) { toast("No raw .sal buffer to export", 3000); return; }
      const a = document.createElement("a");
      a.href = URL.createObjectURL(new Blob([S._rawBuf], { type: "application/zip" }));
      a.download = (S.manifest?.app?.name || "session") + ".sal";
      document.body.appendChild(a); a.click(); document.body.removeChild(a);
      setTimeout(() => URL.revokeObjectURL(a.href), 5000);
      toast("Exported " + a.download, 2000);
    };
    els.closeSessionBtn.onclick = closeSession;
    // C13: compare — open a second .sal to diff against the current session.
    els.compareBtn.onclick = () => els.compareInput.click();
    els.compareInput.onchange = (e) => { if (e.target.files[0]) loadCompare(e.target.files[0]); e.target.value = ""; };

    // transport
    els.playPause.onclick = () => (playing ? pause() : play());
    els.stepPrev.onclick = () => stepEvent(-1);
    els.stepNext.onclick = () => stepEvent(1);
    els.jumpErr.onclick = jumpError;
    els.speedBtn.onclick = cycleSpeed;
    els.theaterBtn.onclick = () => setTheater(!prefs.theater);
    els.fsBtn.onclick = toggleFullscreen;
    setTheater(prefs.theater);
    els.scrubber.oninput = () => seek(S.start + Number(els.scrubber.value), true);
    els.filmstrip.onclick = (e) => { const img = e.target.closest("img"); if (img) seek(Number(img.dataset.ts) + filmstripHalfStep(), true); };

    // tabs
    els.trackTabs.onclick = (e) => { const b = e.target.closest(".tab"); if (b) setTrack(b.dataset.track); };
    els.followToggle.onchange = () => {
      prefs.follow = els.followToggle.checked; LS.set("follow", prefs.follow);
      els.setFollow.checked = prefs.follow;
      renderTrack();
    };
    els.search.oninput = () => renderTrack();
    els.logChips.onclick = (e) => {
      const c = e.target.closest(".chip"); if (!c) return;
      prefs.logLevel = c.dataset.level; LS.set("logLevel", prefs.logLevel);
      [...els.logChips.children].forEach((x) => x.classList.toggle("active", x === c));
      renderTrack();
    };

    // Row interactions: clicking ANY row (or insight/screen-visit) seeks the player to that exact
    // moment — forward or backward. Rows with a detail payload also expand it.
    els.trackList.onclick = (e) => {
      const del = e.target.closest(".mark-del");
      if (del) { deleteMark(del.dataset.markId); return; }
      const jump = e.target.closest(".row-jump");
      if (jump) {
        const holder = jump.dataset.ts ? jump : jump.closest("[data-ts]");
        if (holder) seek(Number(holder.dataset.ts || jump.dataset.ts), true);
        return;
      }
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

    // recents: replay cached sessions on click
    els.recentsGrid.onclick = (e) => {
      const card = e.target.closest(".recent.cached");
      if (card) loadCached(card.dataset.key);
    };

    // expandable cards
    document.querySelectorAll("[data-toggle]").forEach((h) => h.onclick = () => {
      const card = h.closest("[data-card]");
      card.classList.toggle("collapsed");
      prefs.expanded[card.dataset.card] = !card.classList.contains("collapsed");
      LS.set("expanded", prefs.expanded);
    });

    $("clearRecents").onclick = async () => { LS.set("recent", []); await IDB.clear(); renderRecents(); };

    // keyboard
    const TRACK_KEYS = ["timeline", "network", "logs", "screens", "insights", "report", "aibrief"];
    window.addEventListener("keydown", (e) => {
      if (e.target.tagName === "INPUT" || e.target.tagName === "SELECT" ||
          e.target.tagName === "TEXTAREA" || e.target.isContentEditable) return;
      if (e.key === "Escape") { closeDrawer(); const h = document.getElementById("helpOverlay"); if (h) h.remove(); return; }
      if (els.session.hidden) return;
      if (e.code === "Space") { e.preventDefault(); playing ? pause() : play(); }
      else if (e.code === "ArrowRight") stepEvent(1);
      else if (e.code === "ArrowLeft") stepEvent(-1);
      else if (e.key === "e") jumpError();
      else if (e.key === "s") cycleSpeed();
      else if (e.key === "f") { els.followToggle.checked = !els.followToggle.checked; els.followToggle.onchange(); }
      else if (e.key === "t") setTheater(!prefs.theater);
      else if (e.key === "x") toggleFullscreen();
      else if (e.key === "?") toggleHelpOverlay();
      else if (e.key === "m") markNow();
      else if (/^[1-7]$/.test(e.key)) setTrack(TRACK_KEYS[Number(e.key) - 1]);
    });

    updateCacheUsage();

    // Deep-linkable demo: ?sample auto-loads the bundled session (handy for demos/screenshots),
    // and ?t=<seconds> opens AT that moment (QA pastes "?sample&t=24.6" in a ticket; the dev
    // lands exactly on the failure). ?t also applies to the next manually loaded session.
    const params = new URLSearchParams(location.search);
    if (params.has("t")) {
      const t = Number(params.get("t"));
      if (!Number.isNaN(t)) pendingSeekSec = t;
    }
    // Blank .appsal editor from the landing page.
    const appsalNewBtn = $("appsalNewBtn");
    if (appsalNewBtn) appsalNewBtn.onclick = () => renderAppSal(null, "new.appsal");

    if (params.has("sample") && sampleBtn) sampleBtn.onclick();
    else if (params.has("appsal")) {
      // ?appsal opens the bundled app-config demo in the .appsal viewer.
      fetch("sample.appsal").then((r) => r.json())
        .then((cfg) => renderAppSal(cfg, "sample.appsal"))
        .catch(() => toast("Serve the folder over http to load sample.appsal", 4000));
    }

    // C13: ?compare=a.sal&b=b.sal — load both sessions and show the diff (needs http serving).
    if (params.has("compare") && params.get("a") && params.get("b")) {
      Promise.all([fetch(params.get("a")).then((r) => r.arrayBuffer()), fetch(params.get("b")).then((r) => r.arrayBuffer())])
        .then(async ([bufA, bufB]) => {
          onLoaded(await SAL.read(bufA), params.get("a").split("/").pop(), bufA.byteLength, bufA);
          S2 = await SAL.read(bufB);
          renderDiff();
        })
        .catch(() => toast("Serve the folder over http to use ?compare=a&b", 4000));
    }
  }

  document.addEventListener("DOMContentLoaded", init);
})();
