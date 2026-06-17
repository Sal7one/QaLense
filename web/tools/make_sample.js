/*
 * make_sample.js — regenerates web/sample.sal, the bundled demo session.
 *
 * Renders a fake banking app's screens with headless Chromium (playwright-core) into JPEG frames,
 * builds realistic timeline/network/logs/state tracks for a 32s failing-transfer story, and packs
 * everything into a .sal (ZIP, STORE entries — no compression lib needed; readers inflate only
 * DEFLATE entries, STORE passes through).
 *
 * Run:  NODE_PATH=<dir with playwright-core> node web/tools/make_sample.js
 * The playwright-core browser is the cached ms-playwright chrome-headless-shell.
 */
"use strict";
const fs = require("fs");
const path = require("path");
const os = require("os");
const zlib = require("zlib");

const OUT = path.join(__dirname, "..", "sample.sal");
const EXE = path.join(os.homedir(),
  "Library/Caches/ms-playwright/chromium_headless_shell-1223/chrome-headless-shell-mac-arm64/chrome-headless-shell");

// ── Minimal ZIP writer (STORE only) ──────────────────────────────────────────
function crc32(buf) {
  if (zlib.crc32) return zlib.crc32(buf) >>> 0;
  // Fallback table-based CRC32
  if (!crc32.t) {
    crc32.t = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      crc32.t[n] = c;
    }
  }
  let c = ~0;
  for (let i = 0; i < buf.length; i++) c = crc32.t[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return ~c >>> 0;
}

function zipStore(entries) {
  const locals = [], centrals = [];
  let offset = 0;
  for (const [name, data] of entries) {
    const nameB = Buffer.from(name, "utf8");
    const crc = crc32(data);
    const lh = Buffer.alloc(30);
    lh.writeUInt32LE(0x04034b50, 0);
    lh.writeUInt16LE(20, 4);          // version
    lh.writeUInt16LE(0, 6);           // flags
    lh.writeUInt16LE(0, 8);           // method STORE
    lh.writeUInt32LE(0, 10);          // time/date
    lh.writeUInt32LE(crc, 14);
    lh.writeUInt32LE(data.length, 18);
    lh.writeUInt32LE(data.length, 22);
    lh.writeUInt16LE(nameB.length, 26);
    lh.writeUInt16LE(0, 28);
    locals.push(lh, nameB, data);

    const ch = Buffer.alloc(46);
    ch.writeUInt32LE(0x02014b50, 0);
    ch.writeUInt16LE(20, 4);
    ch.writeUInt16LE(20, 6);
    ch.writeUInt16LE(0, 8);
    ch.writeUInt16LE(0, 10);
    ch.writeUInt32LE(0, 12);
    ch.writeUInt32LE(crc, 16);
    ch.writeUInt32LE(data.length, 20);
    ch.writeUInt32LE(data.length, 24);
    ch.writeUInt16LE(nameB.length, 28);
    // extra/comment/disk/attrs = 0
    ch.writeUInt32LE(offset, 42);
    centrals.push(Buffer.concat([ch, nameB]));
    offset += 30 + nameB.length + data.length;
  }
  const centralBuf = Buffer.concat(centrals);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(entries.length, 8);
  eocd.writeUInt16LE(entries.length, 10);
  eocd.writeUInt32LE(centralBuf.length, 12);
  eocd.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, centralBuf, eocd]);
}

// ── Fake app screens (HTML → JPEG via headless Chromium) ─────────────────────
const BASE_CSS = `
  * { margin:0; padding:0; box-sizing:border-box; font-family:-apple-system,'Segoe UI',Roboto,sans-serif; }
  body { width:360px; height:800px; background:#f6f5f2; color:#191919; overflow:hidden; }
  .status { display:flex; justify-content:space-between; padding:10px 18px 0; font-size:12px; font-weight:600; }
  .pad { padding:0 18px; }
  .muted { color:#8a8a86; } .small { font-size:11px; }
  .hero { font-size:30px; font-weight:800; letter-spacing:-0.5px; }
  .card { border-radius:16px; padding:16px; color:#fff; margin-top:10px; }
  .row { display:flex; align-items:center; justify-content:space-between; padding:13px 0; border-bottom:1px solid #ecebe7; }
  .tx-ic { width:38px; height:38px; border-radius:10px; background:#e8e6e0; display:flex; align-items:center; justify-content:center; margin-right:10px; }
  .nav { position:absolute; bottom:0; left:0; right:0; display:flex; background:#fbfaf8; border-top:1px solid #e8e6e0; padding:10px 0 22px; }
  .nav div { flex:1; text-align:center; font-size:11px; color:#8a8a86; }
  .nav .on { color:#1a1a1a; font-weight:700; }
  .btn { background:#191919; color:#fff; border-radius:14px; text-align:center; padding:16px; font-weight:700; font-size:15px; margin-top:14px; }
  .field { background:#fff; border:1px solid #e4e2dc; border-radius:14px; padding:14px 16px; margin-top:10px; font-size:14px; }
  .chip { display:inline-block; background:#edecdf; border-radius:20px; padding:5px 12px; font-size:12px; font-weight:600; margin:4px 4px 0 0; }
  .errbox { background:#fdecec; border:1px solid #f5c2c0; color:#b3261e; border-radius:14px; padding:14px; margin-top:14px; font-size:13px; line-height:1.5; }
  .spin { width:26px; height:26px; border:3px solid #e4e2dc; border-top-color:#191919; border-radius:50%; margin:0 auto; }
  h2 { font-size:20px; font-weight:800; letter-spacing:-0.3px; }
`;
const status = `<div class="status"><span>9:41</span><span>▮▮▮ ⬤</span></div>`;
const nav = (on) => `<div class="nav">
  <div class="${on === 0 ? "on" : ""}">⌂<br/>Home</div>
  <div class="${on === 1 ? "on" : ""}">▣<br/>Cards</div>
  <div class="${on === 2 ? "on" : ""}">≡<br/>More</div></div>`;

const tx = (ic, name, sub, amt, neg) => `<div class="row">
  <div style="display:flex;align-items:center"><div class="tx-ic">${ic}</div>
  <div><div style="font-size:14px;font-weight:600">${name}</div><div class="muted small">${sub}</div></div></div>
  <div style="font-weight:700;font-size:14px;color:${neg ? "#191919" : "#1c7c43"}">${amt}</div></div>`;

const SCREENS = {
  home: `${status}<div class="pad">
    <div class="muted" style="margin-top:18px;font-size:13px">Good morning</div>
    <div style="font-size:22px;font-weight:800">Abdullah</div>
    <div class="muted small" style="margin-top:16px">Total balance</div>
    <div class="hero">257,930.50 SAR</div>
    <div style="display:flex;gap:10px">
      <div class="card" style="flex:1.3;background:linear-gradient(135deg,#143a75,#1d59b8)">
        <div style="display:flex;justify-content:space-between;font-size:12px"><b>Main Current</b><span style="opacity:.8">Current</span></div>
        <div style="font-size:21px;font-weight:800;margin-top:22px">24,580.00 SAR</div>
        <div class="small" style="opacity:.75;margin-top:4px">•••• 8901</div></div>
      <div class="card" style="flex:1;background:linear-gradient(135deg,#0e5e35,#1d8a52)">
        <div style="font-size:12px"><b>Savings Goal</b></div>
        <div style="font-size:19px;font-weight:800;margin-top:25px">87,200.00</div>
        <div class="small" style="opacity:.75;margin-top:4px">•••• 2555</div></div></div>
    <div style="margin-top:18px;font-weight:700">Transactions <span class="muted small" style="float:right;margin-top:4px">See all</span></div>
    ${tx("☕", "Starbucks", "Café", "−45.00", 1)}${tx("🛒", "Carrefour", "Groceries", "−380.00", 1)}
    ${tx("▶", "Netflix", "Entertainment", "−55.00", 1)}${tx("↘", "Transfer In", "Transfer", "+2,000.00", 0)}
  </div>${nav(0)}`,

  accounts: `${status}<div class="pad">
    <h2 style="margin-top:20px">My Cards</h2>
    <div class="card" style="background:linear-gradient(135deg,#143a75,#1d59b8)">
      <b style="font-size:13px">Main Current</b><div style="font-size:20px;font-weight:800;margin-top:18px">24,580.00 SAR</div>
      <div class="small" style="opacity:.75">•••• 8901</div></div>
    <div class="card" style="background:linear-gradient(135deg,#0e5e35,#1d8a52)">
      <b style="font-size:13px">Savings Goal</b><div style="font-size:20px;font-weight:800;margin-top:18px">87,200.00 SAR</div>
      <div class="small" style="opacity:.75">•••• 2555</div></div>
    <div class="card" style="background:linear-gradient(135deg,#6d1320,#a02436)">
      <b style="font-size:13px">Investment</b><div style="font-size:20px;font-weight:800;margin-top:18px">142,900.00 SAR</div>
      <div class="small" style="opacity:.75">•••• 0000</div></div>
  </div>${nav(1)}`,

  detail: `${status}<div class="pad">
    <div class="muted" style="margin-top:18px;font-size:13px">‹ Back</div>
    <h2 style="margin-top:8px">Savings Goal</h2>
    <div class="muted small">•••• 2555 · Savings</div>
    <div class="hero" style="margin-top:14px">87,200.00 SAR</div>
    <div><span class="chip">⇄ Transfer</span><span class="chip">＋ Top up</span><span class="chip">⛭ Manage</span></div>
    <div style="margin-top:20px;font-weight:700">Activity</div>
    ${tx("↘", "Auto-save", "Rule · weekly", "+500.00", 0)}${tx("↘", "Transfer In", "From Main", "+2,000.00", 0)}
    ${tx("％", "Profit share", "Quarterly", "+312.40", 0)}
  </div>${nav(1)}`,

  transfer: `${status}<div class="pad">
    <div class="muted" style="margin-top:18px;font-size:13px">‹ Back</div>
    <h2 style="margin-top:8px">Transfer</h2>
    <div class="muted small" style="margin-top:14px">From</div>
    <div class="field"><b>Savings Goal</b> · 87,200.00 SAR</div>
    <div class="muted small" style="margin-top:12px">To</div>
    <div class="field"><b>Main Current</b> · •••• 8901</div>
    <div class="muted small" style="margin-top:12px">Amount</div>
    <div class="field" style="font-size:24px;font-weight:800">1,500.00 <span class="muted" style="font-size:14px">SAR</span></div>
    <div class="btn">Confirm Transfer</div>
  </div>${nav(1)}`,

  spinner: `${status}<div class="pad">
    <div class="muted" style="margin-top:18px;font-size:13px">‹ Back</div>
    <h2 style="margin-top:8px">Transfer</h2>
    <div style="margin-top:170px"><div class="spin"></div>
    <div class="muted" style="text-align:center;margin-top:14px;font-size:13px">Processing transfer…</div></div>
  </div>${nav(1)}`,

  failure: `${status}<div class="pad">
    <div class="muted" style="margin-top:18px;font-size:13px">‹ Back</div>
    <h2 style="margin-top:8px">Transfer</h2>
    <div class="errbox"><b>Something went wrong</b><br/>
      We couldn't complete this transfer. Your money was not moved. (Error 500 — TRANSFER_FAILED)</div>
    <div class="btn">Try again</div>
    <div class="btn" style="background:#fff;color:#191919;border:1px solid #e4e2dc">Contact support</div>
  </div>${nav(1)}`,
};

// ── Session story (32s) ──────────────────────────────────────────────────────
const T0 = 1781200000000;                     // epoch base
const at = (s) => T0 + Math.round(s * 1000);
const DUR = 32;

// screen name per second (frame schedule + state track)
const screenAt = (s) =>
  s < 7 ? ["Home", "home"] :
  s < 13 ? ["Accounts", "accounts"] :
  s < 18 ? ["Account Detail", "detail"] :
  s < 25 ? ["Transfer", "transfer"] :
  s < 27 ? ["Transfer", "spinner"] :
  ["Payment Failure", "failure"];

const network = [
  { ts: at(1.2), method: "GET", url: "https://api.staging.qalens.dev/v1/accounts", status: 200, latencyMs: 184, requestBytes: 0, responseBytes: 2810, error: null },
  { ts: at(7.4), method: "GET", url: "https://api.staging.qalens.dev/v1/cards", status: 200, latencyMs: 236, requestBytes: 0, responseBytes: 1904, error: null },
  { ts: at(13.6), method: "GET", url: "https://api.staging.qalens.dev/v1/account/2/activity", status: 200, latencyMs: 311, requestBytes: 0, responseBytes: 5230, error: null },
  { ts: at(18.9), method: "GET", url: "https://api.staging.qalens.dev/v1/fx/rates?base=SAR", status: 200, latencyMs: 2340, requestBytes: 0, responseBytes: 1422, error: null },
  { ts: at(25.1), method: "POST", url: "https://api.staging.qalens.dev/v1/transfer", status: 500, latencyMs: 861, requestBytes: 412, responseBytes: 96, error: null },
  { ts: at(28.4), method: "POST", url: "https://api.staging.qalens.dev/v1/transfer", status: 500, latencyMs: 923, requestBytes: 412, responseBytes: 96, error: null },
];

const timeline = [
  { ts: at(0.3), kind: "NAVIGATION", title: "Navigated to home", detail: null, isError: false },
  { ts: at(1.2), kind: "NETWORK", title: "GET /v1/accounts 200 184ms", detail: null, isError: false },
  { ts: at(6.9), kind: "NAVIGATION", title: "Navigated to accounts", detail: null, isError: false },
  { ts: at(7.4), kind: "NETWORK", title: "GET /v1/cards 200 236ms", detail: null, isError: false },
  { ts: at(12.8), kind: "NAVIGATION", title: "Navigated to account/2", detail: "Savings Goal", isError: false },
  { ts: at(13.6), kind: "NETWORK", title: "GET /v1/account/2/activity 200 311ms", detail: null, isError: false },
  { ts: at(17.7), kind: "NAVIGATION", title: "Navigated to transfer/2", detail: null, isError: false },
  { ts: at(18.9), kind: "NETWORK", title: "GET /v1/fx/rates 200 2340ms", detail: "slow: above 1500ms threshold", isError: false },
  { ts: at(24.6), kind: "ACTION", title: "Confirm Transfer", detail: "User tapped Confirm Transfer (1,500.00 SAR)", isError: false },
  { ts: at(25.1), kind: "ERROR", title: "POST /v1/transfer 500 861ms", detail: "server", isError: true },
  { ts: at(27.9), kind: "ACTION", title: "Try again", detail: "User retried the transfer", isError: false },
  { ts: at(28.4), kind: "ERROR", title: "POST /v1/transfer 500 923ms", detail: "server", isError: true },
  { ts: at(29.0), kind: "SCREEN", title: "Payment Failure shown", detail: null, isError: false },
];

const logs = [
  { ts: at(0.4), type: "BREADCRUMB", tag: null, message: "Navigation → home" },
  { ts: at(1.3), type: "LOG", tag: "AccountsRepo", message: "[INFO] Loaded 4 accounts from staging" },
  { ts: at(6.9), type: "BREADCRUMB", tag: null, message: "Navigation → accounts" },
  { ts: at(12.8), type: "BREADCRUMB", tag: null, message: "Navigation → account/2" },
  { ts: at(17.7), type: "BREADCRUMB", tag: null, message: "Navigation → transfer/2" },
  { ts: at(19.0), type: "LOG", tag: "FxRates", message: "[WARN] rates call took 2340ms (threshold 1500ms)" },
  { ts: at(24.6), type: "EVENT", tag: "Tap", message: "Confirm Transfer" },
  { ts: at(25.2), type: "LOG", tag: "TransferApi", message: "[ERROR] POST /v1/transfer failed: HTTP 500 TRANSFER_FAILED" },
  { ts: at(27.9), type: "EVENT", tag: "Tap", message: "Try again" },
  { ts: at(28.5), type: "LOG", tag: "TransferApi", message: "[ERROR] POST /v1/transfer failed: HTTP 500 TRANSFER_FAILED (retry)" },
  { ts: at(29.1), type: "BREADCRUMB", tag: null, message: "Navigation → payment-failure" },
];

const state = [];
for (let s = 0; s <= DUR; s += 2) {
  const [name] = screenAt(s);
  state.push({
    ts: at(s),
    screen: name,
    route: name === "Home" ? "home" : name === "Accounts" ? "accounts" :
      name === "Account Detail" ? "account/2" : name === "Transfer" ? "transfer/2" : "payment-failure",
    featureFlags: { new_home: true, checkout_v2: true, wallet_refactor: s >= 20 },
    dataSources: {
      Preferences: { theme: "light", onboarded: "true" },
      Database: { accounts: "4", transactions: "152", pending_sync: s >= 25 ? "1" : "0" },
    },
  });
}

const summary = {
  score: 58, band: "Needs attention", criticalIssues: 1, failedApis: 2, slowApis: 1,
  penalties: [
    { dimension: "Network Health", points: 22 },
    { dimension: "Stability", points: 12 },
    { dimension: "Accessibility Health", points: 8 },
  ],
  category: "Backend/API", confidence: "HIGH",
  reasons: [
    "POST /v1/transfer returned 500 twice (server error, reproduced on retry)",
    "Error logs correlate with the failed responses",
    "FX rates call exceeded the slow threshold (2340ms)",
  ],
  repro: {
    steps: [
      "1. Navigated to home",
      "2. Navigated to accounts",
      "3. Opened account/2 (Savings Goal)",
      "4. Opened transfer/2 and entered 1,500.00 SAR",
      "5. Tap: Confirm Transfer",
      "6. Observe: POST /v1/transfer 500",
      "7. Tap: Try again → POST /v1/transfer 500",
    ],
    expected: "Transfer completes and a confirmation is shown.",
    actual: "Transfer failed twice with HTTP 500 TRANSFER_FAILED; Payment Failure screen shown.",
  },
};

// Precomputed on-device digest (schema qalens-analysis/1) — what QaLensAnalysis.digest() writes.
const analysis = {
  schema: "qalens-analysis/1",
  coverage: {
    frames: true, video: false, network: true, networkInterceptorInstalled: true,
    logs: true, state: true,
    notes: ["Visual track is low-fps frames (~2fps), not video — fast UI glitches can fall between frames."],
  },
  stats: {
    durationMs: DUR * 1000, screensVisited: 5, requests: network.length,
    failedRequests: 2, slowRequests: 1, slowThresholdMs: 1500,
    logEvents: logs.length, errorLogs: 2, avgLatencyMs: 809, p95LatencyMs: 2340,
  },
  endpoints: [
    { endpoint: "POST api.staging.qalens.dev/v1/transfer", count: 2, failures: 2, avgMs: 892, maxMs: 923, bytesDown: 192 },
    { endpoint: "GET api.staging.qalens.dev/v1/fx/rates", count: 1, failures: 0, avgMs: 2340, maxMs: 2340, bytesDown: 1422 },
    { endpoint: "GET api.staging.qalens.dev/v1/account/2/activity", count: 1, failures: 0, avgMs: 311, maxMs: 311, bytesDown: 5230 },
    { endpoint: "GET api.staging.qalens.dev/v1/cards", count: 1, failures: 0, avgMs: 236, maxMs: 236, bytesDown: 1904 },
    { endpoint: "GET api.staging.qalens.dev/v1/accounts", count: 1, failures: 0, avgMs: 184, maxMs: 184, bytesDown: 2810 },
  ],
  screens: [
    { screen: "Home", enterMs: 0, durationMs: 7000 },
    { screen: "Accounts", enterMs: 7000, durationMs: 6000 },
    { screen: "Account Detail", enterMs: 13000, durationMs: 5000 },
    { screen: "Transfer", enterMs: 18000, durationMs: 11000 },
    { screen: "Payment Failure", enterMs: 29000, durationMs: 3000 },
  ],
  anomalies: [
    { tMs: 18900, kind: "slow_request", title: "GET api.staging.qalens.dev/v1/fx/rates took 2340ms", detail: "threshold 1500ms" },
    { tMs: 20000, kind: "flag_flip", title: "Feature flag 'wallet_refactor' flipped OFF→ON mid-session", detail: "behavior before/after this point may differ by design" },
    { tMs: 25100, kind: "failed_request", title: "POST api.staging.qalens.dev/v1/transfer → 500", detail: "latency 861ms" },
    { tMs: 25100, kind: "error_burst", title: "3+ errors within 4s", detail: "clustered failures often share one root cause" },
    { tMs: 28400, kind: "failed_request", title: "POST api.staging.qalens.dev/v1/transfer → 500", detail: "latency 923ms" },
  ],
  likelyOwner: { category: "Backend/API", confidence: "HIGH", reasons: summaryReasons() },
};
function summaryReasons() {
  return [
    "POST /v1/transfer returned 500 twice (server error, reproduced on retry)",
    "Error logs correlate with the failed responses",
    "FX rates call exceeded the slow threshold (2340ms)",
  ];
}

const forAi = `# How to analyze this QaLens \`.sal\` session recording

You are looking inside a \`.sal\` file: a ZIP captured on-device during a manual QA session of an
Android app. Everything is pre-redacted. All timestamps are epoch milliseconds;
\`manifest.json.startMillis\` is t0 — join ANY two tracks by comparing \`ts\`.
\`analysis.json.anomalies[].tMs\` are relative to t0.

## Files
- \`manifest.json\` — session/app/device/build context; \`frameIndex\` maps epoch-ms → frame image.
- \`analysis.json\` — PRECOMPUTED digest, read this FIRST: coverage, stats, endpoints, screens, anomalies, likely owner.
- \`timeline.json\` — {ts, kind, title, detail, isError}
- \`network.json\` — {ts, method, url, status, latencyMs, requestBytes, responseBytes, error} (no bodies)
- \`logs.json\` — {ts, type, tag, message}
- \`state.json\` — {ts, screen, route, featureFlags, dataSources}
- \`summary.json\` / \`report.txt\` — capture-time verdict and full human report.
- \`frames/*.jpg\` — ~2fps screen captures (fast glitches can fall between frames).

## Rules
1. Respect \`analysis.json.coverage\` — never infer health from missing tracks.
2. Anchor every claim to evidence (ts/tMs, endpoint, screen, log line).
3. Correlate across tracks; flag flips mid-session change expected behavior.

## Produce three sections
- **For developers** — root-cause hypothesis with the evidence chain + exact repro steps.
- **For QA** — covered vs not covered, flaky signals worth a re-run.
- **For management** — 3 bullets max: user impact, release risk, recommended action.
`;

const report = `QaLens Full Report
==================
App        : QaLens Sample 1.4.2 (debug) · staging
Device     : Google Pixel 8 Pro · Android 16
Git        : 4f2a9c1 · build 8421
Window     : ${new Date(T0).toISOString()} +${DUR}s

Release readiness: 58/100 (Needs attention)
Likely owner     : Backend/API (HIGH)

Failures
--------
- POST /v1/transfer → 500 (861ms), retried → 500 (923ms). TRANSFER_FAILED.
- GET /v1/fx/rates → 200 but slow (2340ms > 1500ms threshold).

Reproduction
------------
${summary.repro.steps.join("\n")}

Expected: ${summary.repro.expected}
Actual  : ${summary.repro.actual}

Feature flags at failure: new_home=ON checkout_v2=ON wallet_refactor=ON (flipped mid-session at +20s)
`;

// ── Frame schedule: 2fps, one JPEG per distinct screen, index repeats it ─────
async function renderFrames() {
  const { chromium } = require("playwright-core");
  const browser = await chromium.launch({ executablePath: EXE, headless: true });
  const page = await browser.newPage({ viewport: { width: 360, height: 800 }, deviceScaleFactor: 2 });
  const shots = {};
  for (const [key, html] of Object.entries(SCREENS)) {
    await page.setContent(`<!doctype html><html><head><style>${BASE_CSS}</style></head><body>${html}</body></html>`);
    await page.waitForTimeout(80);
    shots[key] = await page.screenshot({ type: "jpeg", quality: 72 });
  }
  await browser.close();
  return shots;
}

(async () => {
  const shots = await renderFrames();

  const files = [];               // [name, Buffer]
  const frameIndex = {};
  const seen = {};
  let n = 0;
  for (let s = 0; s <= DUR - 0.5; s += 0.5) {
    const key = screenAt(s)[1];
    if (!seen[key]) {
      n++;
      seen[key] = `frames/${String(n).padStart(6, "0")}.jpg`;
      files.push([seen[key], shots[key]]);
    }
    frameIndex[String(at(s))] = seen[key];
  }

  const manifest = {
    formatVersion: 1,
    createdAtMillis: at(DUR),
    app: { name: "QaLens Sample", version: "1.4.2", variant: "debug" },
    environment: "staging",
    gitSha: "4f2a9c1",
    device: "Google Pixel 8 Pro",
    androidVersion: "16",
    startMillis: T0,
    endMillis: at(DUR),
    durationMs: DUR * 1000,
    fps: 2,
    frameIndex,
    files: ["manifest.json", "summary.json", "timeline.json", "network.json", "logs.json", "state.json", "analysis.json", "for_ai.md", "report.txt", ...files.map((f) => f[0])],
    counts: { frames: Object.keys(frameIndex).length, network: network.length, logs: logs.length, timeline: timeline.length },
    video: null,
    sessionId: "b7f3a1e2-demo-4c5d-9e8f-qalens-sample",
    platform: "android",
    sdkInt: 36,
    locale: "en_US",
    timezone: "Asia/Riyadh",
    screenWidthDp: 412, screenHeightDp: 915, density: 3.5, fontScale: 1.0,
  };

  const enc = (o) => Buffer.from(JSON.stringify(o), "utf8");
  const entries = [
    ["manifest.json", enc(manifest)],
    ["summary.json", enc(summary)],
    ["timeline.json", enc(timeline)],
    ["network.json", enc(network)],
    ["logs.json", enc(logs)],
    ["state.json", enc(state)],
    ["analysis.json", enc(analysis)],
    ["for_ai.md", Buffer.from(forAi, "utf8")],
    ["report.txt", Buffer.from(report, "utf8")],
    ...files,
  ];

  fs.writeFileSync(OUT, zipStore(entries));
  const kb = Math.round(fs.statSync(OUT).size / 1024);
  console.log(`Wrote ${OUT} (${kb} KB, ${files.length} unique frames, ${Object.keys(frameIndex).length} indexed)`);
})().catch((e) => { console.error(e); process.exit(1); });
