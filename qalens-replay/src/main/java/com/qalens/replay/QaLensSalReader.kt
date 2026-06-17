package com.qalens.replay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

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
    val videoStartMs: Long? = null
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
        val dir = File(context.cacheDir, "qalens_player/${System.currentTimeMillis()}").apply { mkdirs() }
        unzip(input, dir)

        val manifest = runCatching { JSONObject(textOf(dir, "manifest.json")) }.getOrDefault(JSONObject())
        val app = manifest.optJSONObject("app")
        val appLabel = buildString {
            append(app?.optString("name") ?: "App")
            app?.optString("version")?.takeIf { it.isNotBlank() }?.let { append(" $it") }
            manifest.optString("environment").takeIf { it.isNotBlank() }?.let { append(" · $it") }
        }

        val frames = parseFrames(manifest, dir)
        val start = manifest.optLong("startMillis", frames.firstOrNull()?.ts ?: 0L)
        val end = manifest.optLong("endMillis", frames.lastOrNull()?.ts ?: (start + 1))
        val videoName = manifest.optString("video").ifBlank { null }
        val videoFile = videoName?.let { File(dir, it) }?.takeIf { it.exists() }

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
            videoStartMs = manifest.optLong("videoStartMillis", 0L).takeIf { it > 0L }
        )
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
            val f = File(dir, rel)
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
        return runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(map) }
        }.getOrDefault(emptyList())
    }

    private fun textOf(dir: File, name: String): String =
        File(dir, name).let { if (it.exists()) it.readText() else "" }

    private fun unzip(input: InputStream, dir: File) {
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val out = File(dir, entry.name)
                // Guard against zip path traversal.
                if (!out.canonicalPath.startsWith(dir.canonicalPath)) { entry = zip.nextEntry; continue }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
                entry = zip.nextEntry
            }
        }
    }
}
