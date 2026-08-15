package com.qalens

/**
 * The `.sal` session format (v1). A `.sal` is a ZIP containing JSON tracks + frame images; see
 * docs/replay_backlog.md. This file owns the *pure* parts: the data models and a small,
 * dependency-free JSON encoder used to write the manifest and track files. Decoding happens on the
 * Android side (the player) with `org.json`, so core stays dependency-free.
 *
 * Every track encoder is redaction-aware: nothing is written without folding text through
 * [QaLensConfig.redact] first.
 */

/** A point-in-time snapshot of app state, sampled during a recording. */
data class StateSample(
    val timestampMillis: Long,
    val screenName: String?,
    val route: String?,
    val featureFlags: Map<String, Boolean> = emptyMap(),
    val dataSources: Map<String, Map<String, String>> = emptyMap()
)

/** Manifest written as `manifest.json` at the root of a `.sal`. */
data class SalManifest(
    val formatVersion: Int = 1,
    val createdAtMillis: Long,
    val appName: String,
    val appVersion: String,
    val buildVariant: String,
    val environment: String?,
    val gitSha: String?,
    val device: String,
    val androidVersion: String,
    val startMillis: Long,
    val endMillis: Long,
    val fps: Int,
    /** epoch-ms -> frame file path, e.g. 169..L -> "frames/000001.jpg". */
    val frameIndex: Map<Long, String>,
    val files: List<String>,
    val counts: Map<String, Int>,
    /** When set, the recording used a MediaProjection H.264 track (e.g. "video.mp4") instead of frames. */
    val videoFile: String? = null,
    /**
     * Epoch-ms when the video track actually began (MediaProjection starts only after the consent
     * dialog, seconds after [startMillis]). Players MUST map video position through this, or every
     * seek lands early by the consent delay. Null for frame recordings / old files.
     */
    val videoStartMillis: Long? = null,
    // ── AI/analysis context (qalens-analysis/1) ────────────────────────────
    /** Random UUID — lets backends dedupe and reference sessions. */
    val sessionId: String? = null,
    val sdkInt: Int = 0,
    val locale: String? = null,
    val timezone: String? = null,
    val screenWidthDp: Int = 0,
    val screenHeightDp: Int = 0,
    val density: Float = 0f,
    val fontScale: Float = 0f
) {
    val durationMs: Long get() = (endMillis - startMillis).coerceAtLeast(0)
}

/**
 * R9: one entry in a v2 `manifest.files` array — `{name, crc32, compressed}`. `crc32` is the
 * lowercase-hex CRC-32 of the **uncompressed** content; `compressed` is true when the entry is
 * GZIP-compressed inside the ZIP (JSON tracks), false for frames/video/plain text.
 */
data class SalFileEntry(
    val name: String,
    val crc32: String,
    val compressed: Boolean
)

/** Minimal, allocation-light JSON writer. Supports the value types the `.sal` tracks need. */
object SalJson {
    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is String -> str(value)
        is Boolean -> value.toString()
        is Int, is Long, is Double, is Float -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) -> "${str(k.toString())}:${encode(v)}" }
        is Iterable<*> -> value.joinToString(",", "[", "]") { encode(it) }
        else -> str(value.toString())
    }

    fun obj(vararg pairs: Pair<String, Any?>): String =
        pairs.joinToString(",", "{", "}") { (k, v) -> "${str(k)}:${encode(v)}" }

    fun str(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}

/** Encoders that turn live tracks into the JSON arrays/objects stored inside a `.sal`. */
object SalTracks {

    /** v1 manifest: files is an array of strings. */
    fun manifest(m: SalManifest): String = manifestJson(m, m.files)

    /** v2 manifest (R9): files is an array of {name, crc32, compressed} objects. */
    fun manifest(m: SalManifest, entries: List<SalFileEntry>): String =
        manifestJson(
            m,
            entries.map { mapOf("name" to it.name, "crc32" to it.crc32, "compressed" to it.compressed) }
        )

    private fun manifestJson(m: SalManifest, files: Any): String = SalJson.obj(
        "formatVersion" to m.formatVersion,
        "createdAtMillis" to m.createdAtMillis,
        "app" to mapOf("name" to m.appName, "version" to m.appVersion, "variant" to m.buildVariant),
        "environment" to m.environment,
        "gitSha" to m.gitSha,
        "device" to m.device,
        "androidVersion" to m.androidVersion,
        "startMillis" to m.startMillis,
        "endMillis" to m.endMillis,
        "durationMs" to m.durationMs,
        "fps" to m.fps,
        "frameIndex" to m.frameIndex.entries.associate { it.key.toString() to it.value },
        "files" to files,
        "counts" to m.counts,
        "video" to m.videoFile,
        "videoStartMillis" to m.videoStartMillis,
        "sessionId" to m.sessionId,
        "platform" to "android",
        "sdkInt" to m.sdkInt,
        "locale" to m.locale,
        "timezone" to m.timezone,
        "screenWidthDp" to m.screenWidthDp,
        "screenHeightDp" to m.screenHeightDp,
        "density" to m.density,
        "fontScale" to m.fontScale
    )

    /** R9: GZIP-compress a track's JSON text (RFC 1952) for v2 .sal packaging. */
    fun gzip(text: String): ByteArray =
        java.io.ByteArrayOutputStream().use { bos ->
            java.util.zip.GZIPOutputStream(bos).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            bos.toByteArray()
        }

    /** R9: lowercase-hex CRC-32 of [bytes] (crc32Hex of empty bytes is "00000000"). */
    fun crc32Hex(bytes: ByteArray): String {
        val crc = java.util.zip.CRC32()
        crc.update(bytes)
        return "%08x".format(crc.value)
    }

    fun timeline(events: List<TimelineEvent>, config: QaLensConfig): String =
        SalJson.encode(events.map {
            mapOf(
                "ts" to it.timestampMillis,
                "kind" to it.kind.name,
                "title" to config.redact(it.title),
                "detail" to it.detail?.let(config::redact),
                "isError" to it.isError
            )
        })

    fun network(events: List<NetworkEvent>, config: QaLensConfig): String =
        SalJson.encode(events.map {
            mapOf(
                "ts" to it.timestampMillis,
                "method" to it.method,
                "url" to QaLensRedactor.redactUrl(it.url, config.redactionRules),
                "status" to it.status,
                "latencyMs" to it.latencyMs,
                "requestBytes" to it.requestBodyBytes,
                "responseBytes" to it.responseBodyBytes,
                "error" to it.error,
                // R8: re-redact body previews at encode time (belt-and-suspenders on top of the
                // capture-time redaction in the interceptor).
                "requestBodyPreview" to it.requestBodyPreview?.let(config::redact),
                "responseBodyPreview" to it.responseBodyPreview?.let(config::redact),
                "connectivity" to it.connectivity?.let { c ->
                    mapOf("type" to c.type.name, "strengthBars" to c.strengthBars, "hasVpn" to c.hasVpn, "isMetered" to c.isMetered)
                }
            )
        })

    fun logs(events: List<QaEvent>, config: QaLensConfig): String =
        SalJson.encode(events.map {
            mapOf(
                "ts" to it.timestampMillis,
                "type" to it.type.name,
                "tag" to it.tag?.let(config::redact),
                "message" to config.redact(it.message)
            )
        })

    /** Capture-time derived insight (score / likely-cause / repro) over the recorded window. */
    fun summary(
        score: ReleaseReadinessScore,
        classification: BugClassification,
        repro: ReproSteps,
        config: QaLensConfig
    ): String = SalJson.obj(
        "score" to score.score,
        "band" to score.band,
        "criticalIssues" to score.criticalIssues,
        "failedApis" to score.failedApis,
        "slowApis" to score.slowApis,
        "penalties" to score.byDimension().map { (dim, pts) -> mapOf("dimension" to dim.display, "points" to pts) },
        "category" to classification.category.display,
        "confidence" to classification.confidence.name,
        "reasons" to classification.reasons.map { config.redact(it) },
        "repro" to mapOf(
            "steps" to repro.steps.map { config.redact(it) },
            "expected" to config.redact(repro.expected),
            "actual" to config.redact(repro.actual)
        )
    )

    fun state(samples: List<StateSample>, config: QaLensConfig): String =
        SalJson.encode(samples.map { s ->
            mapOf(
                "ts" to s.timestampMillis,
                "screen" to s.screenName?.let(config::redact),
                "route" to s.route?.let(config::redact),
                "featureFlags" to s.featureFlags,
                "dataSources" to s.dataSources.mapValues { (_, kv) -> kv.mapValues { config.redact(it.value) } }
            )
        })

    fun crashes(crashes: List<QaLensCrash>, config: QaLensConfig): String =
        SalJson.encode(crashes.map { c ->
            mapOf(
                "ts" to c.timestampMillis,
                "type" to c.type.name,
                "thread" to config.redact(c.thread),
                "throwable" to c.throwable?.let(config::redact),
                "stackTrace" to c.stackTrace,
                "screen" to c.screen?.let(config::redact),
                "route" to c.route?.let(config::redact),
                "lastNetworkSummary" to c.lastNetworkSummary?.let(config::redact)
            )
        })

    fun performance(samples: List<FrameMetricsSample>, config: QaLensConfig): String =
        SalJson.encode(samples.map { s ->
            mapOf(
                "ts" to s.timestampMillis,
                "totalMs" to s.totalMs,
                "layoutMs" to s.layoutMs,
                "drawMs" to s.drawMs,
                "gpuMs" to s.gpuMs,
                "jank" to s.jank,
                "frozen" to s.frozen
            )
        })

    fun connectivity(transitions: List<ConnectivitySnapshot>): String =
        SalJson.encode(transitions.map { c ->
            mapOf(
                "ts" to c.timestampMillis,
                "type" to c.type.name,
                "strengthBars" to c.strengthBars,
                "hasVpn" to c.hasVpn,
                "isMetered" to c.isMetered
            )
        })

    fun memory(samples: List<MemorySample>): String =
        SalJson.encode(samples.map { m ->
            mapOf(
                "ts" to m.timestampMillis,
                "totalKb" to m.totalKb,
                "freeKb" to m.freeKb,
                "nativeKb" to m.nativeKb,
                "trimLevel" to m.trimLevel
            )
        })

    /** C9: QA bookmarks / annotations. */
    fun marks(bookmarks: List<Bookmark>): String =
        SalJson.encode(bookmarks.map { b ->
            mapOf(
                "id" to b.id,
                "ts" to b.timestampMillis,
                "label" to b.label,
                "severity" to b.severity.name.lowercase()
            )
        })
}
