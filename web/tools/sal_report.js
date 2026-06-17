#!/usr/bin/env node
/*
 * sal_report.js — turn any .sal recording into a Jira/Slack-ready markdown report, no browser.
 *
 *   node web/tools/sal_report.js path/to/session.sal [--json]
 *
 * Reuses the same dependency-free reader the web player uses (web/sal.js). Built for CI: attach
 * .sal files as build artifacts and post this report to the PR / ticket automatically.
 * Exit code 1 when the session contains failures (failed requests or error timeline events),
 * so a pipeline step can gate on it. Needs Node 18+ (DecompressionStream).
 */
"use strict";
const fs = require("fs");
const path = require("path");

// Minimal browser shim for the sal.js IIFE (same approach as web/test/read.test.js).
global.window = global;
if (!global.URL.createObjectURL) global.URL.createObjectURL = () => "blob://stub";
require(path.join(__dirname, "..", "sal.js"));
const SAL = global.SAL;

const SLOW_MS = 1500;

const fmt = (ms) => {
  const t = Math.max(0, Math.floor(ms / 1000));
  return String(Math.floor(t / 60)).padStart(2, "0") + ":" + String(t % 60).padStart(2, "0");
};
const sec = (ms) => (ms / 1000).toFixed(1);
const shortPath = (url) => { try { return new URL(url).pathname || url; } catch { return url; } };

function insights(s) {
  const out = [];
  const failed = s.network.filter((e) => e.error || e.status >= 400);
  if (failed.length) out.push({
    sev: "err", ts: failed[0].ts,
    title: `${failed.length} failed request${failed.length > 1 ? "s" : ""}`,
    detail: failed.slice(0, 3).map((e) => `${e.method} ${shortPath(e.url)} → ${e.error || e.status}`).join(" · "),
  });
  const slow = s.network.filter((e) => !e.error && e.status < 400 && e.latencyMs >= SLOW_MS);
  if (slow.length) {
    const worst = slow.reduce((a, b) => (a.latencyMs > b.latencyMs ? a : b));
    out.push({
      sev: "warn", ts: worst.ts,
      title: `${slow.length} slow request${slow.length > 1 ? "s" : ""} (≥${SLOW_MS}ms)`,
      detail: `worst: ${worst.method} ${shortPath(worst.url)} took ${worst.latencyMs}ms`,
    });
  }
  for (let i = 1; i < s.state.length; i++) {
    const prev = s.state[i - 1].featureFlags || {}, cur = s.state[i].featureFlags || {};
    for (const k of Object.keys(cur)) {
      if (k in prev && prev[k] !== cur[k]) out.push({
        sev: "warn", ts: s.state[i].ts,
        title: `Feature flag flipped: ${k}`,
        detail: `${prev[k] ? "ON" : "OFF"} → ${cur[k] ? "ON" : "OFF"} at ${fmt(s.state[i].ts - s.start)}`,
      });
    }
  }
  return out.sort((a, b) => a.ts - b.ts);
}

(async () => {
  const file = process.argv[2];
  const asJson = process.argv.includes("--json");
  if (!file) { console.error("usage: node web/tools/sal_report.js <session.sal> [--json]"); process.exit(2); }
  if (typeof DecompressionStream === "undefined") { console.error("Node 18+ required."); process.exit(2); }

  const buf = fs.readFileSync(file);
  const s = await SAL.read(buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength));

  const m = s.manifest, a = m.app || {}, sum = s.summary;
  const failed = s.network.filter((e) => e.error || e.status >= 400);
  const errors = s.timeline.filter((e) => e.isError);
  const ins = insights(s);

  if (asJson) {
    console.log(JSON.stringify({
      app: a, environment: m.environment, device: m.device, android: m.androidVersion,
      git: m.gitSha, sessionId: m.sessionId || null, durationMs: s.duration, counts: m.counts,
      failedRequests: failed.length, errorEvents: errors.length,
      likelyOwner: sum ? { category: sum.category, confidence: sum.confidence } : null,
      anomalies: ins.map((i) => ({ tSec: Number(sec(i.ts - s.start)), title: i.title, detail: i.detail })),
      // On-device digest (qalens-analysis/1) passed through verbatim when present.
      deviceAnalysis: s.analysis || null,
    }, null, 2));
  } else {
    const lines = [
      `## QA Session Report — ${a.name || "app"} ${a.version || ""}`,
      ``,
      `| | |`, `|---|---|`,
      `| Environment | ${m.environment || "—"} |`,
      `| Device | ${m.device || "—"} (Android ${m.androidVersion || "—"}) |`,
      `| Build | ${a.variant || "—"} · git ${m.gitSha || "—"} |`,
      `| Recorded | ${new Date(m.createdAtMillis || 0).toISOString()} · ${fmt(s.duration)} |`,
      `| Captured | ${(m.counts && m.counts.frames) || s.frames.length} frames · ${s.network.length} requests · ${s.logs.length} logs |`,
      `| Failures | ${failed.length} failed requests · ${errors.length} error events |`,
      `| Likely owner | ${sum ? `${sum.category} (${sum.confidence})` : "—"} |`,
    ];
    if (sum && sum.repro && (sum.repro.steps || []).length) {
      lines.push("", "**Reproduction**");
      sum.repro.steps.forEach((x) => lines.push(`- ${x}`));
      lines.push("", `**Expected:** ${sum.repro.expected}`, `**Actual:** ${sum.repro.actual}`);
    }
    if (failed.length) {
      lines.push("", "**Failed requests**");
      failed.slice(0, 10).forEach((e) =>
        lines.push(`- \`t=${sec(e.ts - s.start)}\` \`${e.method} ${shortPath(e.url)}\` → ${e.error || e.status} (${e.latencyMs}ms)`));
    }
    if (ins.length) {
      lines.push("", "**Auto-detected anomalies** (open the .sal in the player with `?t=<sec>`)");
      ins.forEach((i) => lines.push(`- \`t=${sec(i.ts - s.start)}\` ${i.title} — ${i.detail}`));
    }
    console.log(lines.join("\n"));
  }

  process.exit(failed.length || errors.length ? 1 : 0);
})().catch((e) => { console.error("ERROR:", e.message || e); process.exit(2); });
