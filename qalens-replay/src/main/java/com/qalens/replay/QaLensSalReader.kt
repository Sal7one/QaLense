package com.qalens.replay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream

// ── Player-side models (decoded from a .sal) ───────────────────────────────────

data class PItem(val ts: Long, val label: String, val sub: String?, val isError: Boolean)
data class FrameRef(val ts: Long, val file: File)
data class PStateSample(
    val ts: Long,
    val screen: String?,
    val flags: Map<String, Boolean>,
    val data: Map<String, Map<String, String>>
)

data class PSummary(
    val score: Int,
    val band: String,
    val category: String,
    val confidence: String,
    val reasons: List<String>,
    val penalties: List<Pair<String, Int>>,
    val steps: List<String>,
    val expected: String,
    val actual: String
)

data class PlayerSession(
    val rootDir: File,
    val appLabel: String,
    val startMs: Long,
    val endMs: Long,
    val fps: Int,
    val frames: List<FrameRef>,
    val timeline: List<PItem>,
    val network: List<PItem>,
    val logs: List<PItem>,
    val state: List<PStateSample>,
    val summary: PSummary?,
    val report: String,
    val videoFile: File?,
    /** Epoch-ms when the video track began (consent granted) — video position maps through this. */
    val videoStartMs: Long? = null,
    val recordingWarnings: List<String> = emptyList()
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(1)

    /** All event items merged + sorted, for step-to-next/prev navigation. */
    val allEvents: List<PItem> get() = (timeline + network + logs).sortedBy { it.ts }

    fun frameAt(ts: Long): FrameRef? =
        frames.lastOrNull { it.ts <= ts } ?: frames.firstOrNull()

    fun stateAt(ts: Long): PStateSample? =
        state.lastOrNull { it.ts <= ts } ?: state.firstOrNull()
}

/**
 * Decodes a `.sal` (ZIP) into a [PlayerSession]. Uses Android's built-in `org.json`, so the player
 * module needs no JSON dependency. Unzips into the app cache and guards against path traversal.
 */
object QaLensSalReader {

    fun read(context: Context, input: InputStream): PlayerSession {
        val root = File(context.cacheDir, "qalens_player").apply { mkdirs() }
        // Clean abandoned imports from earlier processes; active/recent sessions are released by UI.
        root.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.deleteRecursively() }
        val dir = File(root, java.util.UUID.randomUUID().toString()).apply { check(mkdirs()) }
        try {
            BoundedArchive.extract(input, dir)

            val manifest = JSONObject(BoundedArchive.text(BoundedArchive.file(dir, "manifest.json"), 1024 * 1024L))
            val formatVersion = manifest.optInt("formatVersion", 1)
            if (formatVersion !in 1..2) {
                throw IllegalArgumentException(
                    "Unsupported .sal formatVersion $formatVersion — this player supports versions 1 and 2."
                )
            }
            verifyChecksums(manifest, dir)
            val app = manifest.optJSONObject("app")
            val appLabel = buildString {
                append(app?.optString("name") ?: "App")
                app?.optString("version")?.takeIf { it.isNotBlank() }?.let { append(" $it") }
                manifest.optString("environment").takeIf { it.isNotBlank() }?.let { append(" · $it") }
            }

            val frames = parseFrames(manifest, dir)
            val start = manifest.optLong("startMillis", frames.firstOrNull()?.ts ?: 0L)
            val end = manifest.optLong("endMillis", frames.lastOrNull()?.ts ?: (start + 1))
            require(start >= 0 && end >= start && end - start <= 86_400_000L) { "Invalid recording time range" }
            val videoName = manifest.optString("video").ifBlank { null }
            val videoFile = videoName?.let { BoundedArchive.file(dir, it) }?.takeIf { it.exists() }

            return PlayerSession(
                rootDir = dir,
                appLabel = appLabel,
                startMs = start,
                endMs = end,
                fps = manifest.optInt("fps", 2),
                frames = frames,
                timeline = parseTimeline(arrayOf(dir, "timeline.json")),
                network = parseNetwork(arrayOf(dir, "network.json")),
                logs = parseLogs(arrayOf(dir, "logs.json")),
                state = parseState(arrayOf(dir, "state.json")),
                summary = parseSummary(dir),
                report = textOf(dir, "report.txt"),
                videoFile = videoFile,
                videoStartMs = manifest.optLong("videoStartMillis", 0L).takeIf { it > 0L },
                recordingWarnings = parseRecordingWarnings(dir)
            )
        } catch (failure: Throwable) {
            dir.deleteRecursively()
            throw failure
        }
    }

    private val cleanup = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "qalens-replay-cleanup").apply { isDaemon = true }
    }
    fun release(session: PlayerSession) { cleanup.execute { session.rootDir.deleteRecursively() } }


    private fun parseRecordingWarnings(dir: File): List<String> {
        val text = textOf(dir, "analysis.json").ifBlank { return emptyList() }
        val recording = JSONObject(text).optJSONObject("coverage")?.optJSONObject("recording")
            ?: return emptyList() // Older files did not measure retention coverage.
        val warnings = mutableListOf<String>()
        val tracks = recording.optJSONObject("tracks")
        tracks?.keys()?.forEach { name ->
            val track = tracks.optJSONObject(name)
            val dropped = track?.optLong("dropped", 0) ?: 0
            if (dropped > 0) warnings += "$name: $dropped observations omitted."
        }
        if (recording.optBoolean("truncated") && warnings.isEmpty()) warnings += "Recording evidence was truncated."
        val callbacks = recording.optLong("droppedFrameCallbacks", 0)
        if (callbacks > 0) warnings += "Android dropped $callbacks frame-metrics callbacks."
        return warnings
    }

    private fun parseSummary(dir: File): PSummary? {
        val text = textOf(dir, "summary.json").ifBlank { return null }
        return runCatching {
            val o = JSONObject(text)
            val reasons = o.optJSONArray("reasons").toStringList()
            val penalties = o.optJSONArray("penalties")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { it.optString("dimension") to it.optInt("points") }
                }
            } ?: emptyList()
            val repro = o.optJSONObject("repro") ?: JSONObject()
            PSummary(
                score = o.optInt("score"),
                band = o.optString("band"),
                category = o.optString("category"),
                confidence = o.optString("confidence"),
                reasons = reasons,
                penalties = penalties,
                steps = repro.optJSONArray("steps").toStringList(),
                expected = repro.optString("expected"),
                actual = repro.optString("actual")
            )
        }.getOrNull()
    }

    private fun JSONArray?.toStringList(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { optString(it) }

    /** org.json's optString turns JSON null into the literal "null" — never surface that. */
    private fun JSONObject.strOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).ifBlank { null }

    private fun parseFrames(manifest: JSONObject, dir: File): List<FrameRef> {
        val index = manifest.optJSONObject("frameIndex") ?: return emptyList()
        return index.keys().asSequence().mapNotNull { key ->
            val ts = key.toLongOrNull() ?: return@mapNotNull null
            val rel = index.optString(key)
            val f = BoundedArchive.file(dir, rel)
            if (f.exists()) FrameRef(ts, f) else null
        }.sortedBy { it.ts }.toList()
    }

    private fun parseTimeline(loc: Array<Any>): List<PItem> = parseArray(loc) { o ->
        PItem(o.optLong("ts"), o.optString("title"), o.strOrNull("detail"), o.optBoolean("isError"))
    }

    private fun parseNetwork(loc: Array<Any>): List<PItem> = parseArray(loc) { o ->
        val status = o.optInt("status")
        val err = o.strOrNull("error")
        PItem(
            ts = o.optLong("ts"),
            label = "${o.optString("method")} ${o.optString("url")}",
            sub = "${if (status == 0) "…" else status} · ${o.optLong("latencyMs")}ms" + (err?.let { " · $it" } ?: ""),
            isError = err != null || status >= 400
        )
    }

    private fun parseLogs(loc: Array<Any>): List<PItem> = parseArray(loc) { o ->
        val tag = o.strOrNull("tag")
        PItem(
            ts = o.optLong("ts"),
            label = o.optString("message"),
            sub = "[${o.optString("type")}]" + (tag?.let { " $it" } ?: ""),
            isError = o.optString("message").contains("error", true) || o.optString("message").contains("[ERROR]")
        )
    }

    private fun parseState(loc: Array<Any>): List<PStateSample> = parseArray(loc) { o ->
        val flags = mutableMapOf<String, Boolean>()
        o.optJSONObject("featureFlags")?.let { ff -> ff.keys().forEach { flags[it] = ff.optBoolean(it) } }
        val data = mutableMapOf<String, Map<String, String>>()
        o.optJSONObject("dataSources")?.let { ds ->
            ds.keys().forEach { src ->
                val inner = ds.optJSONObject(src) ?: JSONObject()
                val kv = mutableMapOf<String, String>()
                inner.keys().forEach { kv[it] = inner.optString(it) }
                data[src] = kv
            }
        }
        PStateSample(o.optLong("ts"), o.strOrNull("screen"), flags, data)
    }

    private fun <T> parseArray(loc: Array<Any>, map: (JSONObject) -> T): List<T> {
        val dir = loc[0] as File
        val name = loc[1] as String
        val text = textOf(dir, name).ifBlank { return emptyList() }
        val arr = JSONArray(text)
        require(arr.length() <= 40_000) { "Too many track observations" }
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(map) }
    }

    /**
     * Read a track as UTF-8, transparently decompressing v2 entries (gzip detected by magic bytes:
     * 0x1f 0x8b). v1 entries (plain text) pass through unchanged.
     */
    private fun textOf(dir: File, name: String): String {
        val file = BoundedArchive.file(dir, name)
        return if (file.exists()) BoundedArchive.text(file) else ""
    }

    private fun verifyChecksums(manifest: JSONObject, dir: File) {
        val files = manifest.optJSONArray("files") ?: return
        require(files.length() <= 4096) { "Excessive manifest entries" }
        for (i in 0 until files.length()) {
            val item = files.opt(i) ?: continue
            val name = if (item is JSONObject) item.getString("name") else item.toString()
            val file = BoundedArchive.file(dir, name)
            require(file.isFile) { "Missing archive entry: $name" }
            if (item is JSONObject && item.has("crc32")) {
                val expected = item.getString("crc32")
                val actual = BoundedArchive.checksum(file, decode = name.endsWith(".json"))
                require(actual.equals(expected, ignoreCase = true)) { "Archive checksum mismatch: $name" }
            }
        }
    }
}
