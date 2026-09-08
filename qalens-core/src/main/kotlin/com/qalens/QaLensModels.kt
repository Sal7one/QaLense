package com.qalens

/** Rectangle in window pixels. Kept pure Kotlin so reports do not depend on Android/Compose types. */
data class QaRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2

    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= right && y >= top && y <= bottom
}

data class InspectNode(
    val id: String,
    val testTag: String? = null,
    val qaName: String? = null,
    val contentDescription: List<String> = emptyList(),
    val text: List<String> = emptyList(),
    val role: String? = null,
    val stateDescription: String? = null,
    val isEnabled: Boolean = true,
    val isClickable: Boolean = false,
    val isFocusable: Boolean = false,
    val isSelected: Boolean = false,
    val isHeading: Boolean = false,
    val bounds: QaRect = QaRect(0, 0, 0, 0),
    val widthDp: Float = 0f,
    val heightDp: Float = 0f,
    val source: NodeSource = NodeSource.SEMANTICS,
    val hiddenFromReports: Boolean = false,
    val warnings: List<QaWarning> = emptyList()
) {
    val label: String
        get() = qaName
            ?: text.firstOrNull()
            ?: contentDescription.firstOrNull()
            ?: testTag
            ?: role
            ?: id

    val hasHumanLabel: Boolean
        get() = qaName != null || text.isNotEmpty() || contentDescription.isNotEmpty()
}

enum class NodeSource {
    SEMANTICS,
    MANUAL_QA_TAG,
    ACCESSIBILITY_FALLBACK
}

data class QaWarning(
    val id: String,
    val severity: WarningSeverity,
    val title: String,
    val description: String,
    val nodeId: String? = null
)

enum class WarningSeverity { CRITICAL, WARNING, INFO }

data class ScreenSnapshot(
    val activityName: String = "",
    val fragmentName: String? = null,
    val screenName: String? = null,
    val route: String? = null,
    val history: List<String> = emptyList(),
    val timestampMillis: Long = System.currentTimeMillis()
) {
    val displayName: String get() = screenName ?: route ?: activityName.ifBlank { "Unknown Screen" }
}

data class DeviceSnapshot(
    val appName: String = "",
    val appVersion: String = "",
    val versionCode: Long = 0,
    val buildVariant: String = "",
    val gitSha: String? = null,
    val buildNumber: String? = null,
    val environment: String? = null,
    val manufacturer: String = "",
    val deviceModel: String = "",
    val androidVersion: String = "",
    val sdkVersion: Int = 0,
    val screenWidthDp: Int = 0,
    val screenHeightDp: Int = 0,
    val density: Float = 1f,
    val fontScale: Float = 1f,
    val isRtl: Boolean = false,
    val userType: String? = null,
    val featureFlags: Map<String, Boolean> = emptyMap()
)

data class QaEvent(
    val timestampMillis: Long = System.currentTimeMillis(),
    val type: QaEventType,
    val message: String,
    val tag: String? = null
)

enum class QaEventType { LOG, EVENT, BREADCRUMB }

/**
 * A surfaced failure (recording, screenshot, export, webhook, navigation, data source, …).
 * Replaces the old pattern of swallowing exceptions into the event log where QA never saw them.
 * The panel renders a dismissible banner when [errors] is non-empty; each may carry a [retry] lambda.
 */
data class QaLensError(
    val id: String,
    val kind: ErrorKind,
    val message: String,
    val timestampMillis: Long = System.currentTimeMillis(),
    /** If non-null, the banner shows a ↻ Retry button that invokes this. */
    val retry: (() -> Unit)? = null
)

enum class ErrorKind {
    RECORDING,
    SCREENSHOT,
    EXPORT,
    WEBHOOK,
    NAVIGATION,
    DATA_SOURCE,
    OTHER
}

/**
 * A captured crash or ANR. Stored in `QaLensUiState.crashes` (capped, in-memory ring buffer) and
 * serialized as `crashes.json` inside the `.sal`. The stack trace is captured at throw time; the
 * screen/route/network summary is the *context that led to the crash* — QaLens's unique angle.
 */
data class QaLensCrash(
    val timestampMillis: Long = System.currentTimeMillis(),
    val type: CrashType,
    val thread: String,
    val throwable: String?,
    val stackTrace: String,
    val screen: String? = null,
    val route: String? = null,
    val lastNetworkSummary: String? = null
)

enum class CrashType { CRASH, ANR, COROUTINE_EXCEPTION }

/** Human label for reports and analysis.json anomalies. */
val CrashType.display: String get() = when (this) {
    CrashType.CRASH -> "Crash"
    CrashType.ANR -> "ANR"
    CrashType.COROUTINE_EXCEPTION -> "Coroutine exception"
}

/**
 * C9: A QA bookmark / annotation dropped during a session to flag a notable moment.
 * Stored in `QaLensUiState.bookmarks` and the `.sal` `marks.json` track.
 */
data class Bookmark(
    val id: String = java.util.UUID.randomUUID().toString().take(8),
    val timestampMillis: Long = System.currentTimeMillis(),
    val label: String,
    val severity: BookmarkSeverity = BookmarkSeverity.INFO
)

enum class BookmarkSeverity { INFO, WARNING, BUG }

/**
 * One frame's render timing, captured via `Window.OnFrameMetricsAvailableListener` (API 24+).
 * Jank = a frame slower than the platform's refresh deadline (~16ms on 60Hz); frozen = >700ms.
 * Stored in `QaLensUiState.frameMetrics` (capped ring buffer) and the `.sal` `performance.json` track.
 */
data class FrameMetricsSample(
    val timestampMillis: Long = System.currentTimeMillis(),
    val totalMs: Long,
    val layoutMs: Long = 0,
    val drawMs: Long = 0,
    val gpuMs: Long = 0,
    val jank: Boolean = false,
    val frozen: Boolean = false
) {
    companion object {
        /** A frame taking longer than this (ms) misses a 60Hz vsync → visible jank. */
        const val JANK_THRESHOLD_MS = 16L
        /** A frame taking longer than this (ms) means the app appeared frozen to the user. */
        const val FROZEN_THRESHOLD_MS = 700L

        fun fromNanoseconds(total: Long, layout: Long = 0, draw: Long = 0, gpu: Long = 0): FrameMetricsSample =
            FrameMetricsSample(
                totalMs = total.coerceAtLeast(0) / 1_000_000,
                layoutMs = layout.coerceAtLeast(0) / 1_000_000,
                drawMs = draw.coerceAtLeast(0) / 1_000_000,
                gpuMs = gpu.coerceAtLeast(0) / 1_000_000,
                jank = total > JANK_THRESHOLD_MS * 1_000_000,
                frozen = total > FROZEN_THRESHOLD_MS * 1_000_000
            )
    }
}

/**
 * A point-in-time snapshot of the device's network connectivity. Captured by
 * `QaLensConnectivity` (via `ConnectivityManager.NetworkCallback`) and stamped onto each
 * [NetworkEvent] at request time, so a failed request can be distinguished as "server 500" vs
 * "device lost connection mid-request" — feeding the [BugClassifier].
 */
data class ConnectivitySnapshot(
    val timestampMillis: Long = System.currentTimeMillis(),
    val type: ConnectivityType,
    val strengthBars: Int = 0,
    val hasVpn: Boolean = false,
    val isMetered: Boolean = false
)

enum class ConnectivityType { WIFI, CELLULAR, ETHERNET, OFFLINE, UNKNOWN }

/**
 * One memory-usage sample, captured during a recording (or on a trim event). Feeds the
 * `.sal` `memory.json` track and the "Memory" card in the Device tab. B8.
 */
data class MemorySample(
    val timestampMillis: Long = System.currentTimeMillis(),
    val totalKb: Long,
    val freeKb: Long,
    val nativeKb: Long = 0,
    val trimLevel: String? = null
)

data class InspectionSnapshot(
    val screen: ScreenSnapshot,
    val device: DeviceSnapshot,
    val nodes: List<InspectNode>,
    val warnings: List<QaWarning>,
    val testTags: List<String>,
    val events: List<QaEvent>,
    val selectedNode: InspectNode? = null,
    val generatedAtMillis: Long = System.currentTimeMillis()
)

/** A saved `.sal` recording on disk, for the in-app Recordings manager. */
data class RecordingInfo(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val createdAtMillis: Long
) {
    val formattedSize: String get() = humanSize(sizeBytes)
    companion object {
        fun humanSize(bytes: Long): String = when {
            bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000L     -> "%.0f KB".format(bytes / 1_000.0)
            else                -> "$bytes B"
        }
    }
}

data class NetworkEvent(
    val timestampMillis: Long = System.currentTimeMillis(),
    val method: String,
    val url: String,
    val status: Int = 0,
    val latencyMs: Long = 0,
    val requestBodyBytes: Long = 0,
    val responseBodyBytes: Long = 0,
    val error: String? = null,
    /** Connectivity at request time — distinguishes "server 500" from "device lost WiFi". B5. */
    val connectivity: ConnectivitySnapshot? = null,
    /**
     * R8: captured request-body preview. Non-null only when [QaLensConfig.captureNetworkBodies] is
     * on; truncated at 64&nbsp;KB, redacted, and only for text-ish content types (binary bodies get a
     * `<binary N bytes>` placeholder). Re-redacted again at `.sal` encode time.
     */
    val requestBodyPreview: String? = null,
    /** R8: captured response-body preview — same rules as [requestBodyPreview]. */
    val responseBodyPreview: String? = null
) {
    val isError: Boolean get() = error != null || status in 400..599
    val statusLabel: String get() = if (error != null) "ERR" else if (status == 0) "…" else "$status"
    val shortUrl: String get() = try {
        val u = java.net.URL(url); (u.path.takeIf { it.isNotBlank() } ?: url)
    } catch (_: Exception) { url }
    val latencyLabel: String get() = if (latencyMs < 1000) "${latencyMs}ms" else "${"%.1f".format(latencyMs / 1000.0)}s"
    /** True if this request failed while the device was offline/losing connectivity. */
    val failedDueToConnectivity: Boolean get() = isError && connectivity?.type == ConnectivityType.OFFLINE
}

data class QaLensUiState(
    val isInstalled: Boolean = false,
    val isPanelOpen: Boolean = false,
    /** QA-minimal panel (big colorful controls) instead of the full developer panel. */
    val minimalPanel: Boolean = false,
    val isInspectMode: Boolean = false,
    /** Tag mode: like inspect, but draws every visible automation/test tag on its component. */
    val isTagMode: Boolean = false,
    val isRecording: Boolean = false,
    val isSavingRecording: Boolean = false,
    /** Watch mode: translucent, non-interactive live overlay; touches pass to the app. */
    val isWatchMode: Boolean = false,
    /** Overlay opacity (0.1–1.0), driven by the transparency slider. */
    val overlayAlpha: Float = 1f,
    /** Dock the panel / watch HUD to the bottom instead of the top (frees the opposite edge). */
    val dockBottom: Boolean = false,
    /** Master switch — false fully detaches the QaLens overlay from the host app ("stop injecting"). */
    val overlayEnabled: Boolean = true,
    val screen: ScreenSnapshot = ScreenSnapshot(),
    val device: DeviceSnapshot = DeviceSnapshot(),
    val nodes: List<InspectNode> = emptyList(),
    val warnings: List<QaWarning> = emptyList(),
    val events: List<QaEvent> = emptyList(),
    val selectedNode: InspectNode? = null,
    val networkEvents: List<NetworkEvent> = emptyList(),
    val recomposeCounts: Map<String, Int> = emptyMap(),
    // Derived analysis (recomputed on each scan / network event)
    val score: ReleaseReadinessScore? = null,
    val classification: BugClassification? = null,
    val buildSafety: BuildSafetyStatus? = null,
    val featureFlags: Map<String, Boolean> = emptyMap(),
    val screenQuality: Map<String, ScreenQualitySnapshot> = emptyMap(),
    val networkAvailable: Boolean = false,
    /** Declared adapter names; not a guarantee of complete network coverage. */
    val networkSources: Set<String> = emptySet(),
    val deepLinkScenarios: List<DeepLinkScenario> = emptyList(),
    val scenarioRuns: Map<String, ScenarioRun> = emptyMap(),
    val contractResult: ContractResult? = null,
    // App-provided read-only snapshots (DataStore prefs, Room counts, …), keyed by source name.
    val dataSources: Map<String, Map<String, String>> = emptyMap(),
    /** Surfaced failures — shown as a dismissible banner in the panel header. Capped at 20. */
    val errors: List<QaLensError> = emptyList(),
    /** Captured crashes/ANRs (in-memory, capped at 10) — the latest is surfaced in the Overview tab. */
    val crashes: List<QaLensCrash> = emptyList(),
    /** Per-frame render timings (capped at 1000) — feeds the jank score + the Performance `.sal` track. */
    val frameMetrics: List<FrameMetricsSample> = emptyList(),
    /** Current connectivity snapshot (shown as a chip in the Network tab). B5. */
    val connectivity: ConnectivitySnapshot? = null,
    /** Connectivity transitions this session (capped at 100) — timeline events + `.sal` track. B5. */
    val connectivityTransitions: List<ConnectivitySnapshot> = emptyList(),
    /** Memory samples (capped at 200) — feeds the `.sal` `memory.json` track. B8. */
    val memorySamples: List<MemorySample> = emptyList(),
    /** C9: QA bookmarks / annotations dropped during a session. */
    val bookmarks: List<Bookmark> = emptyList(),
    val recordings: List<RecordingInfo> = emptyList()
) {
    /** Total bytes of all saved recordings — for the storage line in the Recordings manager. */
    val recordingsBytes: Long get() = recordings.sumOf { it.sizeBytes }
    val testTags: List<String> get() = nodes.mapNotNull { it.testTag }.distinct().sorted()
}
