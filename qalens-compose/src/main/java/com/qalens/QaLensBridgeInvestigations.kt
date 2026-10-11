package com.qalens

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Already-reviewed documents only. Validation never changes their values or captures new data. */
internal object QaLensInvestigationDocuments {
    const val SCHEMA = "qalens-investigation-transfer/1"
    const val BYTE_LIMIT = 256 * 1024
    private val itemId = Regex("(timeline|network|logs|state|crashes|performance|connectivity|memory|marks|anomalies):[0-9]{1,12}")

    fun validate(encoded: String): JSONObject {
        require(encoded.length <= BYTE_LIMIT && encoded.toByteArray(Charsets.UTF_8).size <= BYTE_LIMIT) { "Investigation exceeds 256 KiB" }
        // Bound parser recursion before JSONObject sees an untrusted same-process archive string.
        var depth = 0; var quoted = false; var escaped = false
        for (character in encoded) {
            if (quoted) {
                if (escaped) escaped = false else if (character == '\\') escaped = true else if (character == '"') quoted = false
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 16) { "Investigation nesting is too deep" } }
                '}', ']' -> depth--
            }
        }
        val document = JSONObject(encoded)
        keys(document, setOf("schema", "bundle", "report"))
        require(document.opt("schema") == SCHEMA)
        val bundle = document.getJSONObject("bundle")
        keys(bundle, setOf("schema", "recording", "question", "qaContext", "coverage", "items", "omissions", "investigation", "images"))
        require(bundle.opt("schema") == "qalens-insights-evidence/1")
        val recording = bundle.getJSONObject("recording")
        text(recording.opt("name"), 256)
        number(recording.opt("t0"), 0.0, Double.MAX_VALUE)
        val duration = number(recording.opt("durationMs"), 1.0, Double.MAX_VALUE)
        val focus = number(recording.opt("focusMs"), 0.0, duration)
        val from = number(recording.opt("windowStartMs"), 0.0, focus)
        number(recording.opt("windowEndMs"), focus.coerceAtLeast(from), duration)
        text(bundle.opt("question"), 2000)
        bundle.getJSONObject("coverage"); bundle.getJSONObject("omissions")
        bundle.opt("qaContext")?.let { value ->
            require(value is JSONObject)
            keys(value, setOf("expectedResult", "actualResult"))
            value.keys().forEach { text(value.opt(it), 1600) }
        }
        val selected = linkedMapOf<String, JSONObject>()
        val items = bundle.getJSONArray("items")
        require(items.length() <= 300)
        for (index in 0 until items.length()) {
            val item = items.getJSONObject(index)
            keys(item, setOf("id", "kind", "tMs", "summary", "details"))
            val id = text(item.opt("id"), 64)
            require(itemId.matches(id) && item.opt("kind") == id.substringBefore(':') && selected.put(id, item) == null)
            number(item.opt("tMs"), 0.0, duration)
            text(item.opt("summary"), 1200); item.getJSONObject("details")
        }
        val knownIds = selected.keys.toMutableSet()
        bundle.opt("investigation")?.let { value ->
            require(value is JSONObject); keys(value, setOf("target", "runtime"))
            require(value.opt("target") in setOf("recorded-app", "qalens-player"))
            value.opt("runtime")?.let { runtime ->
                require(runtime is JSONObject && value.opt("target") == "qalens-player")
                keys(runtime, setOf("id", "source", "client", "observedAtMillis", "recordingPositionMs", "details"))
                require(runtime.opt("id") == "player:runtime" && runtime.opt("source") == "current-player" && runtime.opt("client") in setOf("android", "web"))
                number(runtime.opt("observedAtMillis"), 0.0, Double.MAX_VALUE)
                number(runtime.opt("recordingPositionMs"), 0.0, duration)
                require(runtime.getJSONObject("details").toString().length <= 4000)
                knownIds += "player:runtime"
            }
        }
        bundle.opt("images")?.let { value ->
            require(value is JSONArray && value.length() <= 1)
            for (index in 0 until value.length()) {
                val image = value.getJSONObject(index)
                val data = text(image.opt("data"), 174764)
                require(data.isNotEmpty() && data.length % 4 == 0 && Regex("[A-Za-z0-9+/]*={0,2}").matches(data))
                val bytes = Base64.decode(data, Base64.NO_WRAP)
                validateImageMetadata(image, duration, bytes.size)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outWidth in 1..1600 && bounds.outHeight in 1..1600 && bounds.outMimeType == image.getString("mediaType"))
                knownIds += "images:0"
            }
        }
        // Base64 is transport, not text context; keep exactly the reviewed metadata in its budget.
        val textBundle = JSONObject(bundle.toString())
        textBundle.optJSONArray("images")?.let { images -> repeat(images.length()) { images.getJSONObject(it).remove("data") } }
        require(textBundle.toString().length <= 48_000)
        document.opt("report")?.let { value -> require(value is JSONObject); validateReport(value, knownIds, selected, bundle) }
        return document
    }

    /** Byte count is part of the Android preview; retain it and verify against the actual payload. */
    internal fun validateImageMetadata(image: JSONObject, duration: Double, decodedSize: Int) {
        keys(image, setOf("id", "tMs", "mediaType", "data", "source", "approximate", "bytes"))
        require(image.opt("id") == "images:0" && image.opt("mediaType") in setOf("image/jpeg", "image/png") &&
            image.opt("source") in setOf("recording-frame", "recording-video") && image.opt("approximate") is Boolean)
        number(image.opt("tMs"), 0.0, duration)
        val data = text(image.opt("data"), 174764)
        require(data.isNotEmpty() && data.length % 4 == 0 && Regex("[A-Za-z0-9+/]*={0,2}").matches(data) && decodedSize in 1..128 * 1024)
        if (image.has("bytes")) require(image.opt("bytes") is Number &&
            (image.opt("bytes") as Number).toDouble() == decodedSize.toDouble())
    }

    private fun validateReport(report: JSONObject, known: Set<String>, selected: Map<String, JSONObject>, bundle: JSONObject) {
        require(report.opt("schema") == "qalens-insights-report/1" && report.toString().length <= 48_000)
        text(report.opt("summary"), 4000, nonblank = true)
        fun citations(value: Any?): List<String> = strings(value, 20, 64).also { require(it.all { id -> id in known }) }
        objects(report.opt("observations"), 30).forEach { text(it.opt("text"), 2400, true); require(citations(it.opt("evidenceIds")).isNotEmpty()) }
        objects(report.opt("hypotheses"), 10).forEach {
            text(it.opt("title"), 400, true); text(it.opt("reasoning"), 2400, true)
            require(it.opt("confidence") in setOf("low", "medium", "high"))
            if (citations(it.opt("evidenceIds")).isEmpty()) require(it.opt("confidence") == "low")
            strings(it.opt("nextChecks"), 10, 1200)
        }
        strings(report.opt("missingEvidence"), 30, 1200); strings(report.opt("recommendedChecks"), 20, 1200)
        report.opt("qaReport")?.let { value ->
            require(value is JSONObject)
            keys(value, setOf("title", "steps", "expectedResult", "expectedSource", "actualResult", "actualSource"))
            text(value.opt("title"), 400); text(value.opt("expectedResult"), 1600); text(value.opt("actualResult"), 2400)
            require(value.opt("expectedSource") in setOf("tester", "not-provided") && value.opt("actualSource") in setOf("tester", "captured-evidence", "not-established"))
            val steps = objects(value.opt("steps"), 12)
            if (bundle.optJSONObject("investigation")?.optString("target") == "qalens-player") require(steps.isEmpty())
            steps.forEach { step ->
                keys(step, setOf("action", "evidenceIds")); text(step.opt("action"), 1200)
                val ids = citations(step.opt("evidenceIds")); require(ids.isNotEmpty())
                require(ids.all { id -> selected[id]?.let { item -> item.optString("kind") == "timeline" &&
                    item.getJSONObject("details").optString("kind").uppercase() in setOf("ACTION", "NAVIGATION", "SCREEN") } == true })
            }
        }
    }

    private fun keys(value: JSONObject, allowed: Set<String>) { require(value.keys().asSequence().all { it in allowed }) }
    private fun text(value: Any?, max: Int, nonblank: Boolean = false): String {
        require(value is String && value.length <= max && (!nonblank || value.isNotBlank()))
        return value
    }
    private fun number(value: Any?, from: Double, to: Double): Double {
        require(value is Number && value.toDouble().isFinite() && value.toDouble() in from..to)
        return value.toDouble()
    }
    private fun strings(value: Any?, count: Int, length: Int): List<String> {
        require(value is JSONArray && value.length() <= count)
        return (0 until value.length()).map { text(value.opt(it), length) }
    }
    private fun objects(value: Any?, count: Int): List<JSONObject> {
        require(value is JSONArray && value.length() <= count)
        return (0 until value.length()).map { value.getJSONObject(it) }
    }
}

/** Small access-expiring RAM queue; caller owns approval/generation locking. */
internal class InvestigationTransferQueue(private val now: () -> Long) {
    private data class Entry(val document: String, val bytes: Int, val expires: Long)
    private val pending = linkedMapOf<String, Entry>()
    private var bytes = 0
    private var sequence = 0L
    private var dropped = 0
    private var session = java.util.UUID.randomUUID().toString()
    private fun expire() {
        val time = now()
        pending.entries.toList().forEach { (id, entry) -> if (entry.expires <= time) { pending.remove(id); bytes -= entry.bytes; dropped++ } }
    }
    @Synchronized fun clear() {
        pending.clear(); bytes = 0; sequence = 0; dropped = 0; session = java.util.UUID.randomUUID().toString()
    }
    fun enqueue(encoded: String): String {
        val document = QaLensInvestigationDocuments.validate(encoded).toString()
        return enqueueValidated(document)
    }
    @Synchronized internal fun enqueueValidated(document: String): String {
        val size = document.toByteArray(Charsets.UTF_8).size
        expire()
        while (pending.size >= 4 || bytes + size > 1024 * 1024) {
            val first = pending.keys.first(); bytes -= pending.remove(first)!!.bytes; dropped++
        }
        val id = "$session:${++sequence}"
        pending[id] = Entry(document, size, now() + 5 * 60_000)
        bytes += size
        return id
    }
    fun inbox(): Map<String, Any?> {
        val (documents, omitted) = synchronized(this) {
            expire(); pending.map { (id, entry) -> id to entry.document } to dropped
        }
        // Parsing a response never holds the queue/listener lock used by main-thread Stop.
        return mapOf("ok" to true, "transfers" to documents.map { (id, document) -> mapOf("id" to id, "document" to JSONObject(document)) }, "dropped" to omitted)
    }
    @Synchronized fun acknowledge(ids: List<String>) {
        expire(); ids.forEach { id -> pending.remove(id)?.let { bytes -= it.bytes } }
    }
}

internal object QaLensBridgeInvestigations {
    private val queue = InvestigationTransferQueue(SystemClock::elapsedRealtime)
    fun clear() = queue.clear()
    fun enqueueValidated(encoded: String) = queue.enqueueValidated(encoded)
    fun inbox(): Map<String, Any?> = queue.inbox()
    fun acknowledge(ids: List<String>) = queue.acknowledge(ids)
}
