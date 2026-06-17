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

    fun manifest(m: SalManifest): String = SalJson.obj(
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
        "files" to m.files,
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
                "error" to it.error
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
}
