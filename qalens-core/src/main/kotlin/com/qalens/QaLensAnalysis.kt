package com.qalens

/**
 * AI-readiness layer of the `.sal` format (schema `qalens-analysis/1`).
 *
 * [digest] precomputes the conclusions an analyst (human or AI) would otherwise re-derive from the
 * raw tracks: stats, per-endpoint aggregates, screen spans, timestamped anomalies, likely owner —
 * plus a `coverage` section that says which tracks are missing/empty and why that matters, so a
 * model knows what it CANNOT see instead of hallucinating about it.
 *
 * [aiGuide] is the self-describing `for_ai.md` embedded in every `.sal`: schemas, join rules, and
 * a tasked analysis brief. Everything here is pure and redaction-aware.
 */
object QaLensAnalysis {

    const val SCHEMA = "qalens-analysis/1"

    data class Coverage(
        val hasFrames: Boolean,
        val hasVideo: Boolean,
        val networkInterceptorInstalled: Boolean,
        val networkCount: Int,
        val logCount: Int,
        val stateCount: Int
    )

    fun digest(
        coverage: Coverage,
        startMillis: Long,
        endMillis: Long,
        network: List<NetworkEvent>,
        events: List<QaEvent>,
        timeline: List<TimelineEvent>,
        stateSamples: List<StateSample>,
        classification: BugClassification?,
        config: QaLensConfig
    ): String {
        val durationMs = (endMillis - startMillis).coerceAtLeast(1)
        val slowMs = config.slowNetworkThresholdMs

        // ── Coverage notes: what is missing and why it matters ──────────────
        val notes = mutableListOf<String>()
        if (!coverage.networkInterceptorInstalled)
            notes += "Network capture NOT installed (QaLensOkHttpInterceptor missing) — network.json is blind, do not infer 'no traffic'."
        else if (coverage.networkCount == 0)
            notes += "Interceptor installed but no requests in the window — screens may be cached/offline."
        if (coverage.logCount == 0)
            notes += "No log events captured — host app may not forward logs (Timber tree not planted?). Absence of errors in logs.json is NOT evidence of health."
        if (coverage.stateCount == 0)
            notes += "No state samples — screen/flag context unavailable."
        if (!coverage.hasVideo && !coverage.hasFrames)
            notes += "No visual track — reason about behavior from tracks only."
        if (!coverage.hasVideo && coverage.hasFrames)
            notes += "Visual track is low-fps frames (~2fps), not video — fast UI glitches can fall between frames."

        // ── Stats ────────────────────────────────────────────────────────────
        val failed = network.filter { it.isError }
        val slow = network.filter { !it.isError && it.latencyMs >= slowMs }
        val errorLogs = events.filter {
            it.message.contains("error", true) || it.message.contains("[ERROR]") || it.message.contains("exception", true)
        }
        val latencies = network.filter { it.error == null }.map { it.latencyMs }.sorted()
        fun p(pct: Int): Long =
            if (latencies.isEmpty()) 0 else latencies[((pct / 100.0) * latencies.size).toInt().coerceAtMost(latencies.size - 1)]

        // ── Per-endpoint aggregates (redacted method+path, query stripped) ──
        fun endpointKey(e: NetworkEvent): String {
            val red = QaLensRedactor.redactUrl(e.url, config.redactionRules)
            val noQuery = red.substringBefore('?')
            val path = runCatching { java.net.URL(noQuery).let { "${it.host}${it.path}" } }.getOrDefault(noQuery)
            return "${e.method} $path"
        }
        val endpoints = network.groupBy(::endpointKey).map { (key, list) ->
            mapOf(
                "endpoint" to key,
                "count" to list.size,
                "failures" to list.count { it.isError },
                "avgMs" to list.map { it.latencyMs }.average().toLong(),
                "maxMs" to (list.maxOfOrNull { it.latencyMs } ?: 0L),
                "bytesDown" to list.sumOf { it.responseBodyBytes }
            )
        }.sortedByDescending { it["failures"] as Int }

        // ── Screen visit spans from the state track ─────────────────────────
        data class Visit(val screen: String, var startTs: Long, var endTs: Long)
        val visits = mutableListOf<Visit>()
        stateSamples.forEach { s ->
            val name = s.screenName ?: "Unknown"
            val last = visits.lastOrNull()
            if (last != null && last.screen == name) last.endTs = s.timestampMillis
            else visits += Visit(name, s.timestampMillis, s.timestampMillis)
        }
        visits.forEachIndexed { i, v -> v.endTs = if (i + 1 < visits.size) visits[i + 1].startTs else endMillis }
        val screens = visits.map {
            mapOf("screen" to config.redact(it.screen), "enterMs" to (it.startTs - startMillis), "durationMs" to (it.endTs - it.startTs))
        }

        // ── Anomalies (timestamped, relative ms — join any track on these) ──
        val anomalies = mutableListOf<Map<String, Any?>>()
        failed.forEach {
            anomalies += mapOf(
                "tMs" to (it.timestampMillis - startMillis), "kind" to "failed_request",
                "title" to "${it.method} ${endpointKey(it).substringAfter(' ')} → ${it.error ?: it.status}",
                "detail" to "latency ${it.latencyMs}ms"
            )
        }
        slow.forEach {
            anomalies += mapOf(
                "tMs" to (it.timestampMillis - startMillis), "kind" to "slow_request",
                "title" to "${endpointKey(it)} took ${it.latencyMs}ms",
                "detail" to "threshold ${slowMs}ms"
            )
        }
        // Error burst: 3+ error-ish moments within 10s (timeline errors + failed requests + error logs).
        val errTs = (timeline.filter { it.isError }.map { it.timestampMillis } +
            failed.map { it.timestampMillis } + errorLogs.map { it.timestampMillis }).sorted()
        for (i in 0..errTs.size - 3) {
            if (errTs[i + 2] - errTs[i] <= 10_000) {
                anomalies += mapOf(
                    "tMs" to (errTs[i] - startMillis), "kind" to "error_burst",
                    "title" to "3+ errors within ${(errTs[i + 2] - errTs[i]) / 1000}s",
                    "detail" to "clustered failures often share one root cause"
                )
                break
            }
        }
        // Feature-flag flips mid-session.
        for (i in 1 until stateSamples.size) {
            val prev = stateSamples[i - 1].featureFlags
            stateSamples[i].featureFlags.forEach { (k, v) ->
                if (prev.containsKey(k) && prev[k] != v) {
                    anomalies += mapOf(
                        "tMs" to (stateSamples[i].timestampMillis - startMillis), "kind" to "flag_flip",
                        "title" to "Feature flag '$k' flipped ${if (v) "OFF→ON" else "ON→OFF"} mid-session",
                        "detail" to "behavior before/after this point may differ by design"
                    )
                }
            }
        }
        anomalies.sortBy { it["tMs"] as Long }

        return SalJson.obj(
            "schema" to SCHEMA,
            "coverage" to mapOf(
                "frames" to coverage.hasFrames,
                "video" to coverage.hasVideo,
                "network" to (coverage.networkCount > 0),
                "networkInterceptorInstalled" to coverage.networkInterceptorInstalled,
                "logs" to (coverage.logCount > 0),
                "state" to (coverage.stateCount > 0),
                "notes" to notes
            ),
            "stats" to mapOf(
                "durationMs" to durationMs,
                "screensVisited" to visits.size,
                "requests" to network.size,
                "failedRequests" to failed.size,
                "slowRequests" to slow.size,
                "slowThresholdMs" to slowMs,
                "logEvents" to events.size,
                "errorLogs" to errorLogs.size,
                "avgLatencyMs" to (latencies.takeIf { it.isNotEmpty() }?.average()?.toLong() ?: 0L),
                "p95LatencyMs" to p(95)
            ),
            "endpoints" to endpoints,
            "screens" to screens,
            "anomalies" to anomalies,
            "likelyOwner" to classification?.let {
                mapOf(
                    "category" to it.category.display,
                    "confidence" to it.confidence.name,
                    "reasons" to it.reasons.map { r -> config.redact(r) }
                )
            }
        )
    }

    /** `for_ai.md` — every .sal explains itself to whatever AI ingests it. */
    fun aiGuide(): String = """
# How to analyze this QaLens `.sal` session recording

You are looking inside a `.sal` file: a ZIP captured on-device during a manual QA session of an
Android app. Everything is pre-redacted (tokens, emails, card/phone numbers are masked). All
timestamps are epoch milliseconds; `manifest.json.startMillis` is t0 — join ANY two tracks by
comparing `ts`. `analysis.json.anomalies[].tMs` are relative to t0.

## Files
| File | What it is |
|---|---|
| `manifest.json` | Session/app/device/build context. `frameIndex` maps epoch-ms → frame image. `videoStartMillis` (if video) is when `video.mp4` t=0 occurred. |
| `analysis.json` | PRECOMPUTED digest — read this FIRST: coverage, stats, per-endpoint aggregates, screen spans, timestamped anomalies, likely owner. |
| `timeline.json` | Merged user-visible events: `{ts, kind: NAVIGATION|SCREEN|NETWORK|ACTION|ERROR|LOG, title, detail, isError}` |
| `network.json` | Requests: `{ts, method, url, status, latencyMs, requestBytes, responseBytes, error}` (no bodies — privacy) |
| `logs.json` | App logs: `{ts, type: LOG|EVENT|BREADCRUMB, tag, message}` |
| `state.json` | Sampled app state: `{ts, screen, route, featureFlags, dataSources}` |
| `summary.json` | Capture-time verdict: likely owner, reasons, repro steps, expected vs actual |
| `report.txt` | Human-readable full report |
| `frames/*.jpg` or `video.mp4` | What the screen showed. Frames are ~2fps — fast glitches can fall between frames. |

## Rules
1. **Respect `analysis.json.coverage`.** If a track is missing/empty, say so — do NOT infer health
   from absent data (e.g. empty network.json with `networkInterceptorInstalled=false` means blind,
   not "no traffic").
2. Anchor every claim to evidence: quote `ts`/`tMs`, endpoint, screen, or log line.
3. Correlate across tracks: a failed request + error log + screen change within ~2s is one story.
4. `featureFlags` flips mid-session change expected behavior — check before calling something a bug.

## Produce three sections
- **For developers** — root cause hypothesis with the evidence chain (timestamps, endpoint,
  preceding actions), exact repro steps, and what to instrument next if inconclusive.
- **For QA** — what was covered (screens/duration), what was NOT (coverage gaps, untested flows),
  flaky/suspicious signals worth a re-run.
- **For management** — 3 bullets max: user impact, release risk, recommended action. No jargon.

## Webhook contract (if this file arrived via the QaLens webhook)
The upload was a multipart POST (`file` part, application/zip) with `X-QaLens-App/-Version/-Env/
-Device/-Platform/-Sal-Name/-Sal-Size` headers, `X-QaLens-User` (which tester uploaded — shared
test phones carry per-person QA profiles) and `X-QaLens-Digest` — the `stats` object from
`analysis.json` as one-line JSON, for triage without unzipping. Respond with any 2xx and a short
body; the body is shown to the QA engineer in the device's Control Room.
""".trimIndent()
}
