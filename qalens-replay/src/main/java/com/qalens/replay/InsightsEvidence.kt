package com.qalens.replay

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.PriorityQueue
import kotlin.math.abs

internal data class InsightsEvidence(
    val json: String,
    val itemTimes: Map<String, Long>,
    val counts: Map<String, Int>,
    val notes: List<String>,
    val focusMs: Long,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val omitted: Int,
    val image: RecordingInsightImage? = null,
    val runtime: InsightsRuntime? = null
)

/** Imports only captured archive tracks; it never queries the running host or decodes pixels. */
internal object InsightsEvidenceBuilder {
    const val SCHEMA = "qalens-insights-evidence/1"
    const val ITEM_LIMIT = 300
    const val TEXT_LIMIT = 48_000
    private const val SOURCE_RESERVE = 3
    private const val DETAILS_LIMIT = 1800
    private val tracks = listOf("timeline", "network", "logs", "state", "crashes", "performance", "connectivity", "memory", "marks")
    private val sensitive = Regex("password|passwd|secret|token|authorization|cookie|credential|api[-_]?key", RegexOption.IGNORE_CASE)
    private val credential = Regex("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9+/_.=-]+")
    private val essentialFields = listOf("status", "error", "method", "url", "latencyMs", "screen", "type", "throwable", "jank", "frozen", "totalMs", "kind", "isError", "label", "freeKb", "nativeKb")

    private data class Candidate(val kind: String, val index: Int, val tMs: Long, val raw: JSONObject,
                                 val rank: Int, val distance: Long) {
        val id: String get() = "$kind:$index"
    }
    private val priority = compareBy<Candidate>({ it.rank }, { it.distance }, { it.kind }, { it.index })
    private class ValueBudget(var nodes: Int = 100)

    fun build(session: PlayerSession, focusMs: Long, question: String, windowMs: Long = 30_000,
              target: String = "recorded-app", runtime: InsightsRuntime? = null,
              expectedResult: String = "", actualResult: String = ""): InsightsEvidence {
        require(target in setOf("recorded-app", "qalens-player")) { "Unknown investigation target." }
        val player = runtime?.takeIf { target == "qalens-player" }
        player?.let { require(it.recordingPositionMs in 0..session.durationMs && it.observedAtMillis >= 0 && it.json().getJSONObject("details").toString().length <= 4000) {
            "The reviewed player runtime snapshot exceeds its bounds."
        } }
        val focus = focusMs.coerceIn(0, session.durationMs)
        val radius = windowMs.coerceIn(5_000, 300_000)
        val from = (focus - radius).coerceAtLeast(0)
        val to = (focus + radius).coerceAtMost(session.durationMs)
        // Comparator.reversed() needs API 24; this module also supports Android 6.
        val reversePriority = Comparator<Candidate> { a, b -> priority.compare(b, a) }
        val keep = PriorityQueue(ITEM_LIMIT, reversePriority)
        val keptById = mutableMapOf<String, Candidate>()
        val reserves = linkedMapOf<String, PriorityQueue<Candidate>>()
        val totals = linkedMapOf<String, Int>()
        val notes = mutableListOf("Text telemetry only: the model does not receive video pixels, screenshots, audio or the live app.")
        var invalid = 0
        var outside = 0
        var considered = 0
        fun offer(candidate: Candidate, count: Boolean = true) {
            if (count) considered++
            // Continuous ERROR logs must not erase an independently captured failure or app value.
            val reserved = reserves.getOrPut(candidate.kind) { PriorityQueue(SOURCE_RESERVE, reversePriority) }
            val previousReserve = reserved.firstOrNull { it.id == candidate.id }
            if (previousReserve == null || priority.compare(candidate, previousReserve) < 0) {
                if (previousReserve != null) reserved.remove(previousReserve)
                if (reserved.size < SOURCE_RESERVE) reserved += candidate
                else if (priority.compare(candidate, reserved.peek()) < 0) { reserved.poll(); reserved += candidate }
            }
            val existing = keptById[candidate.id]
            if (existing != null) {
                if (priority.compare(candidate, existing) >= 0) return
                keep.remove(existing); keptById.remove(candidate.id)
            }
            if (keep.size < ITEM_LIMIT) { keep += candidate; keptById[candidate.id] = candidate }
            else if (priority.compare(candidate, keep.peek()) < 0) {
                keptById.remove(checkNotNull(keep.poll()).id); keep += candidate; keptById[candidate.id] = candidate
            }
        }
        fun consume(kind: String, array: JSONArray, relative: Boolean = false) {
            require(array.length() <= 40_000) { "Too many $kind observations for analysis." }
            totals[kind] = array.length()
            var before: Candidate? = null
            var after: Candidate? = null
            for (i in 0 until array.length()) {
                val raw = array.optJSONObject(i)
                val value = raw?.opt(if (relative) "tMs" else "ts")
                val numeric = (value as? Number)?.toString()?.toLongOrNull()
                val t = numeric?.let { if (relative) it else it - session.startMs }
                if (t == null || t < 0 || t > session.durationMs) { invalid++; continue }
                val inWindow = t in from..to
                if (!inWindow) outside++
                val failure = isFailure(kind, raw)
                val rank = when {
                    failure && inWindow -> 1
                    kind == "marks" && inWindow -> 2
                    inWindow -> 3
                    else -> 5
                }
                val candidate = Candidate(kind, i, t, raw, rank, abs(t - focus))
                if (kind == "state" || kind == "connectivity") {
                    if (t <= focus && (before == null || t > before.tMs)) before = candidate
                    if (t > focus && (after == null || t < after.tMs)) after = candidate
                }
                if (inWindow) offer(candidate)
            }
            // Boundary context is real observed state at its original time, never a fabricated value at focus.
            listOfNotNull(before, after).forEach { boundary ->
                if (boundary.tMs <= to) offer(boundary.copy(rank = 0), count = boundary.tMs !in from..to)
            }
        }
        for (kind in tracks) {
            val file = BoundedArchive.file(session.rootDir, "$kind.json")
            if (!file.exists()) { totals[kind] = 0; continue }
            consume(kind, JSONArray(BoundedArchive.text(file)))
        }
        val analysisFile = BoundedArchive.file(session.rootDir, "analysis.json")
        val analysis = if (analysisFile.exists()) JSONObject(BoundedArchive.text(analysisFile)) else JSONObject()
        analysis.optJSONArray("anomalies")?.let { consume("anomalies", it, relative = true) }
        val recorded = analysis.optJSONObject("coverage") ?: JSONObject()
        val retention = recorded.optJSONObject("recording")
        recorded.optJSONArray("notes")?.let { array ->
            for (i in 0 until minOf(array.length(), 20)) notes += clean(array.optString(i), 600)
        }
        if (retention == null) notes += "This archive does not report measured recording retention. Empty tracks do not prove the app is healthy."
        notes += session.recordingWarnings.take(20)
        if (totals["state"] == 0) notes += "No captured app state: player state, decoded preferences and data values may be unavailable."
        if (totals["network"] == 0) notes += "No captured network requests: the interceptor may be missing or disabled; this does not establish no traffic."
        if (totals["logs"] == 0) notes += "No captured host logs: logging hooks may be absent or disabled."
        var detailTruncations = 0
        fun bounded(value: Any?, depth: Int = 0, key: String = "", budget: ValueBudget = ValueBudget()): Any? {
            if (budget.nodes-- <= 0) { detailTruncations++; return "[NODE_LIMIT]" }
            if (sensitive.containsMatchIn(key)) { detailTruncations++; return "[REDACTED]" }
            if (depth > 4) { detailTruncations++; return "[DEPTH_LIMIT]" }
            return when (value) {
                is JSONObject -> JSONObject().also { out ->
                    val keys = value.keys().asSequence().take(24).toList().sorted()
                    if (value.length() > keys.size) detailTruncations += value.length() - keys.size
                    for ((index, child) in keys.withIndex()) {
                        if (budget.nodes <= 0) { detailTruncations += keys.size - index; out.put("_qalensTruncated", true); break }
                        out.put(clean(child, 96), bounded(value.opt(child), depth + 1, child, budget))
                    }
                }
                is JSONArray -> JSONArray().also { out ->
                    if (value.length() > 16) detailTruncations += value.length() - 16
                    for (i in 0 until minOf(value.length(), 16)) {
                        if (budget.nodes <= 0) { detailTruncations += minOf(value.length(), 16) - i; out.put("[NODE_LIMIT]"); break }
                        out.put(bounded(value.opt(i), depth + 1, budget = budget))
                    }
                }
                is String -> {
                    if (value.length > 600) detailTruncations++
                    clean(if (key.equals("url", true)) redactUrl(value) else value, 600)
                }
                is Number, is Boolean -> value
                else -> JSONObject.NULL
            }
        }
        fun boundedDetails(raw: JSONObject): JSONObject {
            val details = bounded(raw) as JSONObject
            // A wide nested value can exhaust nodes while its encoded text is still small.
            // Real scalar/null metadata must never become missing or a truncation marker.
            for (key in essentialFields) {
                val value = raw.opt(key)
                if (raw.has(key) && (value == JSONObject.NULL || value is String || value is Number || value is Boolean))
                    details.put(key, bounded(value, key = key, budget = ValueBudget(1)))
            }
            val encoded = details.toString()
            // Leave space for a historical-window context note below this strict per-item cap.
            if (encoded.length <= DETAILS_LIMIT - 300) return details
            detailTruncations++
            val compact = JSONObject().put("truncated", true)
                .put("detailNotice", "Captured details exceeded the per-item node/text budget; inspect the original track for omitted values.")
            // Preserve these actual fields even when a large headers/app-values object used the node budget first.
            for (key in essentialFields) {
                if (!raw.has(key)) continue
                compact.put(key, bounded(raw.opt(key), key = key, budget = ValueBudget(8)))
                if (compact.toString().length > DETAILS_LIMIT - 300) compact.remove(key)
            }
            var preview = encoded.take(700)
            while (preview.isNotEmpty()) {
                compact.put("preview", preview)
                if (compact.toString().length <= DETAILS_LIMIT - 300) break
                preview = preview.take(preview.length / 2)
            }
            if (preview.isEmpty()) compact.remove("preview")
            return compact
        }
        val sources = reserves.values.map { it.toList().sortedWith(priority) }
        // Admit the first reserve from each source before its second/third, even when text is tight.
        val reserved = (0 until SOURCE_RESERVE).flatMap { index -> sources.mapNotNull { it.getOrNull(index) } }
        val selected = (reserved + keep.toList().sortedWith(priority)).distinctBy { it.id }.take(ITEM_LIMIT).toMutableList()
        val items = mutableMapOf<String, JSONObject>()
        for (candidate in selected) {
            items[candidate.id] = JSONObject().put("id", candidate.id).put("tMs", candidate.tMs)
                .put("kind", candidate.kind).put("summary", clean(summary(candidate.kind, candidate.raw), 256))
                .put("details", boundedDetails(candidate.raw).apply {
                    if (candidate.tMs < from && candidate.kind in setOf("state", "connectivity")) put("windowContext",
                        "Latest preceding captured ${candidate.kind} sample outside the selected window. Its timestamp is real; it does not establish current state.")
                })
        }
        val coverage = JSONObject().put("recorded", bounded(recorded)).put("retentionKnown", retention != null)
            .put("selectionPolicy", "Up to three relevant observations per captured source are reserved before remaining slots are ranked by failure/context and proximity. Item/text limits may still omit evidence.")
            .put("media", JSONObject().put("videoPresent", session.videoFile != null).put("frameCount", session.frames.size)
                .put("pixelsSent", false).put("pixelsAnalyzed", false))
            .put("notes", JSONArray(notes.take(30)))
        val questionText = clean(question.trim(), 2_000)
        val qaExpected = clean(expectedResult.trim(), 1600)
        val qaActual = clean(actualResult.trim(), 1600)
        fun document(): JSONObject {
            val counts = selected.groupingBy { it.kind }.eachCount()
            val byKind = JSONObject()
            for ((kind, total) in totals) byKind.put(kind, JSONObject().put("total", total)
                .put("selected", counts[kind] ?: 0).put("omitted", (total - (counts[kind] ?: 0)).coerceAtLeast(0)))
            return JSONObject().put("schema", SCHEMA)
                .put("recording", JSONObject().put("name", clean(session.appLabel, 128)).put("t0", session.startMs)
                    .put("durationMs", session.durationMs).put("focusMs", focus).put("windowStartMs", from).put("windowEndMs", to))
                .put("question", questionText).put("coverage", coverage)
                .apply { if (qaExpected.isNotBlank() || qaActual.isNotBlank()) put("qaContext",
                    JSONObject().put("expectedResult", qaExpected).put("actualResult", qaActual)) }
                .put("investigation", JSONObject().put("target", target).apply { player?.let { put("runtime", it.json()) } })
                .put("items", JSONArray(selected.sortedWith(compareBy({ it.tMs }, { it.id })).map { items.getValue(it.id) }))
                .put("omissions", JSONObject().put("outsideWindow", (outside - selected.count { it.tMs !in from..to }).coerceAtLeast(0))
                    .put("itemLimit", (considered - minOf(considered, ITEM_LIMIT)).coerceAtLeast(0))
                    .put("contextLimit", (items.size - selected.size).coerceAtLeast(0)).put("invalidTimestamp", invalid)
                    .put("detailsTruncated", detailTruncations).put("byKind", byKind))
        }
        var encoded = document().toString()
        while (encoded.length > TEXT_LIMIT && selected.isNotEmpty()) { selected.removeAt(selected.lastIndex); encoded = document().toString() }
        require(encoded.length <= TEXT_LIMIT) { "Evidence coverage is too large for the model context. Open a smaller recording." }
        val counts = selected.groupingBy { it.kind }.eachCount()
        return InsightsEvidence(encoded, selected.associate { it.id to it.tMs } + if (player != null) mapOf("player:runtime" to player.recordingPositionMs) else emptyMap(),
            counts, notes.distinct().take(30), focus, from, to, (totals.values.sum() - selected.size).coerceAtLeast(0), runtime = player)
    }

    fun withImage(evidence: InsightsEvidence, image: RecordingInsightImage): InsightsEvidence {
        require(image.bytes.size in 1..RecordingInsightImagePolicy.BYTE_LIMIT) { "The selected image exceeds the local model limit." }
        val doc = JSONObject(evidence.json)
        require(image.tMs in 0..doc.getJSONObject("recording").getLong("durationMs")) { "The saved image is outside this recording's time range." }
        val coverage = doc.getJSONObject("coverage")
        coverage.getJSONObject("media").put("pixelsSent", true).put("pixelsAnalyzed", JSONObject.NULL)
        val note = "One reviewed saved still is provided to the model. It cannot establish video motion/audio; image support and actual pixel analysis are not verified."
        val notes = evidence.notes.filterNot { it.startsWith("Text telemetry only") } + note
        coverage.put("notes", JSONArray(notes))
        // Base64 is separate provider image content, never repeated inside the 48k text context.
        doc.put("images", JSONArray().put(image.metadata()))
        val array = doc.getJSONArray("items")
        val times = evidence.itemTimes.toMutableMap()
        var removed = 0
        val originalContextOmissions = doc.getJSONObject("omissions").optInt("contextLimit")
        fun updateOmissions() {
            val counts = (0 until array.length()).map { array.getJSONObject(it).getString("kind") }.groupingBy { it }.eachCount()
            val byKind = doc.getJSONObject("omissions").optJSONObject("byKind")
            byKind?.keys()?.forEach { kind ->
                val track = byKind.getJSONObject(kind)
                track.put("selected", counts[kind] ?: 0).put("omitted", (track.optInt("total") - (counts[kind] ?: 0)).coerceAtLeast(0))
            }
            doc.getJSONObject("omissions").put("contextLimit", originalContextOmissions + removed)
        }
        updateOmissions()
        while (doc.toString().length > TEXT_LIMIT && array.length() > 0) {
            val counts = (0 until array.length()).map { array.getJSONObject(it).getString("kind") }.groupingBy { it }.eachCount()
            val removeAt = (array.length() - 1 downTo 0).firstOrNull {
                (counts[array.getJSONObject(it).getString("kind")] ?: 0) > SOURCE_RESERVE
            } ?: array.length() - 1
            times.remove(array.getJSONObject(removeAt).getString("id")); array.remove(removeAt); removed++
            updateOmissions()
        }
        require(doc.toString().length <= TEXT_LIMIT) { "The image metadata cannot fit the selected context." }
        times[image.id] = image.tMs
        return evidence.copy(json = doc.toString(), itemTimes = times, notes = notes,
            counts = (0 until array.length()).map { array.getJSONObject(it).getString("kind") }.groupingBy { it }.eachCount(),
            omitted = evidence.omitted + removed, image = image)
    }

    private fun isFailure(kind: String, value: JSONObject): Boolean = when (kind) {
        "network" -> value.optInt("status") >= 400 || !value.isNull("error") && value.optString("error").isNotBlank()
        "crashes", "anomalies" -> true
        "performance" -> value.optBoolean("jank") || value.optBoolean("frozen")
        "logs" -> Regex("\\b(?:error|failed|failure|fatal|anr)\\b|\\b[A-Za-z_$][A-Za-z0-9_.$]*(?:Exception|Error)\\b", RegexOption.IGNORE_CASE).containsMatchIn(value.optString("message"))
        "timeline" -> value.optBoolean("isError")
        else -> false
    }

    private fun summary(kind: String, value: JSONObject): String = when (kind) {
        "network" -> "${value.optString("method")} ${redactUrl(value.optString("url"))} → ${value.optInt("status")} (${value.optLong("latencyMs")}ms)"
        "logs" -> "${value.optString("tag")}: ${value.optString("message")}"
        "state" -> "Observed state on ${value.optString("screen")}"
        "crashes" -> "${value.optString("type")}: ${value.optString("throwable")}"
        "performance" -> "Frame ${value.optDouble("totalMs")}ms; jank=${value.optBoolean("jank")}; frozen=${value.optBoolean("frozen")}"
        "connectivity" -> "Observed connectivity ${value.optString("type")}"
        "memory" -> "Memory free ${value.optLong("freeKb")} KiB, native ${value.optLong("nativeKb")} KiB"
        "marks" -> value.optString("label")
        else -> value.optString("title").ifBlank { kind }
    }

    private fun clean(value: String, limit: Int): String = credential.replace(value, "$1 [REDACTED]")
        .replace(Regex("[\\u0000-\\u0008\\u000b\\u000c\\u000e-\\u001f]"), "").take(limit)
    private fun redactUrl(value: String): String = Regex("^([A-Za-z][A-Za-z0-9+.-]*://)[^/]*@")
        .replace(value.substringBefore('?').substringBefore('#'), "$1") + if ('?' in value) "?[QUERY_REDACTED]" else ""
}
