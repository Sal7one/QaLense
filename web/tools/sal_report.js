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

// sal.js logs "QaLens: reading .sal formatVersion N" via console.info; route it to stderr so
// `--json` stdout stays pure, machine-parseable JSON.
console.info = (...args) => console.error(...args);

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
  const forAi = process.argv.includes("--for-ai");
  const compareIdx = process.argv.indexOf("--compare");
  const baselineFile = compareIdx >= 0 ? process.argv[compareIdx + 1] : null;
  if (!file) { console.error("usage: node web/tools/sal_report.js <session.sal> [--json] [--for-ai] [--compare baseline.sal]"); process.exit(2); }
  if (compareIdx >= 0 && (!baselineFile || baselineFile.startsWith("--"))) {
    console.error("--compare requires a baseline .sal file path after it"); process.exit(2);
  }
  if (typeof DecompressionStream === "undefined") { console.error("Node 18+ required."); process.exit(2); }

  const buf = fs.readFileSync(file);
  const s = await SAL.read(buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength));

  const m = s.manifest, a = m.app || {}, sum = s.summary;
  const failed = s.network.filter((e) => e.error || e.status >= 400);
  const errors = s.timeline.filter((e) => e.isError);
  const ins = insights(s);

  // ── B11/C13: compare mode — diff against a baseline .sal ────────────────
  if (baselineFile) {
    const baseBuf = fs.readFileSync(baselineFile);
    const base = await SAL.read(baseBuf.buffer.slice(baseBuf.byteOffset, baseBuf.byteOffset + baseBuf.byteLength));
    const baseFailed = base.network.filter((e) => e.error || e.status >= 400).map((e) => `${e.method} ${shortPath(e.url)}`);
    const currFailed = failed.map((e) => `${e.method} ${shortPath(e.url)}`);
    const baseAnomalies = (base.analysis?.anomalies || []).map((x) => x.title || x.kind);
    const currAnomalies = (s.analysis?.anomalies || ins).map((x) => x.title || x.kind);
    const baseScreens = [...new Set(base.state.map((st) => st.screen || "Unknown"))];
    const currScreens = [...new Set(s.state.map((st) => st.screen || "Unknown"))];
    const baseScore = base.summary?.score ?? null;
    const currScore = sum?.score ?? null;
    const baseOwner = base.summary?.category ?? null;
    const currOwner = sum?.category ?? null;
    const baseCrashes = (base.analysis?.stats?.crashes) ?? 0;
    const currCrashes = (s.analysis?.stats?.crashes) ?? 0;
    const scoreDelta = (currScore ?? 0) - (baseScore ?? 0);
    const addedFailures = currFailed.filter((f) => !baseFailed.includes(f));
    const isRegression = scoreDelta < 0 || addedFailures.length > 0 || currCrashes > baseCrashes;

    const lines = [
      `## Session Diff — ${a.name || "app"} ${a.version || ""}`,
      ``,
      `| | Baseline | Current |`,
      `|---|---|---|`,
      `| Score | ${baseScore ?? "—"} | ${currScore ?? "—"} (${scoreDelta >= 0 ? "+" : ""}${scoreDelta}) |`,
      `| Duration | ${fmt(base.duration)} | ${fmt(s.duration)} |`,
      `| Failed requests | ${baseFailed.length} | ${currFailed.length} |`,
      `| Crashes | ${baseCrashes} | ${currCrashes} |`,
      `| Likely owner | ${baseOwner ?? "—"} | ${currOwner ?? "—"} |`,
    ];
    if (addedFailures.length) {
      lines.push("", "**New failures (not in baseline)**");
      addedFailures.forEach((f) => lines.push(`- \`${f}\``));
    }
    const resolved = baseFailed.filter((f) => !currFailed.includes(f));
    if (resolved.length) {
      lines.push("", "**Resolved (fixed since baseline)**");
      resolved.forEach((f) => lines.push(`- \`${f}\``));
    }
    lines.push("", `**Summary:** score ${scoreDelta >= 0 ? "+" : ""}${scoreDelta}, +${addedFailures.length} failed, -${resolved.length} resolved`);
    if (isRegression) lines.push("> ⚠ **Regression detected** — score dropped, new failures, or new crashes.");
    console.log(lines.join("\n"));
    process.exit(isRegression ? 1 : 0);
  }

  if (asJson) {
    console.log(JSON.stringify({
      app: a, environment: m.environment, device: m.device, android: m.androidVersion,
      git: m.gitSha, sessionId: m.sessionId || null, durationMs: s.duration, counts: m.counts,
      failedRequests: failed.length, errorEvents: errors.length,
      likelyOwner: sum ? { category: sum.category, confidence: sum.confidence } : null,
      anomalies: ins.map((i) => ({ tSec: Number(sec(i.ts - s.start)), title: i.title, detail: i.detail })),
      deviceAnalysis: s.analysis || null,
    }, null, 2));
  } else if (forAi) {
    // C12: AI-optimized output — structured markdown for LLM context windows.
    // Concise, no rendering, no emoji — pure signal for automated analysis.
    const lines = [
      `# QA Session Analysis`,
      ``,
      `## Meta`,
      `- App: ${a.name || "?"} ${a.version || ""}`,
      `- Environment: ${m.environment || "?"}`,
      `- Device: ${m.device || "?"} (Android ${m.androidVersion || "?"})`,
      `- Duration: ${fmt(s.duration)}`,
      `- Build: ${a.variant || "?"} · git ${m.gitSha || "?"}`,
      ``,
      `## Summary`,
      `- Score: ${sum ? sum.score : "N/A"} (${sum ? `${sum.category} / ${sum.confidence}` : "no classification"})`,
      `- Screens: ${[...new Set(s.state.map((x) => x.screen || "?"))].join(", ") || "none"}`,
      `- Requests: ${s.network.length} total, ${failed.length} failed`,
      `- Errors: ${errors.length} timeline events`,
      `- Crashes: ${s.analysis?.stats?.crashes || 0}`,
      ``,
    ];
    if (failed.length) {
      lines.push(`## Failed Requests`);
      failed.forEach((e) => lines.push(`- \`${e.method} ${shortPath(e.url)}\` → ${e.error || e.status} (${e.latencyMs}ms)`));
      lines.push(``);
    }
    if (ins.length) {
      lines.push(`## Anomalies`);
      ins.forEach((i) => lines.push(`- [${sec(i.ts - s.start)}s] ${i.title}: ${i.detail}`));
      lines.push(``);
    }
    if (sum && sum.repro && (sum.repro.steps || []).length) {
      lines.push(`## Reproduction`);
      sum.repro.steps.forEach((x) => lines.push(`1. ${x}`));
      lines.push(``, `- Expected: ${sum.repro.expected}`, `- Actual: ${sum.repro.actual}`);
    }
    if (s.analysis) {
      lines.push(`## Device Analysis`);
      if (s.analysis.anomalies?.length) {
        lines.push(`- Anomalies: ${s.analysis.anomalies.length}`);
        s.analysis.anomalies.forEach((a) => lines.push(`  - ${a.title}: ${a.detail}`));
      }
      if (s.analysis.stats) {
        const st = s.analysis.stats;
        lines.push(`- Stats: ${st.requests || 0} requests, ${st.failedRequests || 0} failed, ${st.slowRequests || 0} slow`);
      }
    }
    // C12: include the recorder's own AI brief when present — it explains every schema + join rule.
    if (s.forAi) {
      lines.push(``, `## for_ai.md (recorder's self-describing brief)`, ``, s.forAi);
    }
    console.log(lines.join("\n"));
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
    // C14: on-device anomalies the web heuristic didn't already flag (dedup by |Δt| < 1.5s).
    const onDevice = (s.analysis?.anomalies || []).filter((an) =>
      !ins.some((i) => Math.abs((i.ts - s.start) - (an.tMs || 0)) < 1500));
    if (onDevice.length) {
      lines.push("", "**On-device anomalies**");
      onDevice.forEach((an) => lines.push(`- \`t=${sec(an.tMs)}\` [${an.kind}] ${an.title} — ${an.detail}`));
    }
    if ((s.marks || []).length) {
      lines.push("", "**Bookmarks**");
      s.marks.forEach((mk) => lines.push(`- \`t=${sec(mk.ts - s.start)}\` ★ ${mk.label || "bookmark"}${mk.severity ? " (" + mk.severity + ")" : ""}`));
    }
    console.log(lines.join("\n"));
  }

  // C14: also gate on the device-precomputed anomalies (analysis.json) — the device may flag
  // things the web heuristic misses (memory spikes, jank bursts, crashes, connectivity failures).
  const deviceFailures = (s.analysis?.anomalies || []).filter((an) =>
    ["failed_request","error_burst","crash","anr","coroutine_exception","connectivity_failure","assertion_failed","memory_spike"].includes(an.kind));

  process.exit(failed.length || errors.length || deviceFailures.length ? 1 : 0);
})().catch((e) => { console.error("ERROR:", e.message || e); process.exit(2); });
