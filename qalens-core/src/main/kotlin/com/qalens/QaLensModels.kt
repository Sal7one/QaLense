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
    val error: String? = null
) {
    val isError: Boolean get() = error != null || status in 400..599
    val statusLabel: String get() = if (error != null) "ERR" else if (status == 0) "…" else "$status"
    val shortUrl: String get() = try {
        val u = java.net.URL(url); (u.path.takeIf { it.isNotBlank() } ?: url)
    } catch (_: Exception) { url }
    val latencyLabel: String get() = if (latencyMs < 1000) "${latencyMs}ms" else "${"%.1f".format(latencyMs / 1000.0)}s"
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
    val deepLinkScenarios: List<DeepLinkScenario> = emptyList(),
    val scenarioRuns: Map<String, ScenarioRun> = emptyMap(),
    val contractResult: ContractResult? = null,
    // App-provided read-only snapshots (DataStore prefs, Room counts, …), keyed by source name.
    val dataSources: Map<String, Map<String, String>> = emptyMap(),
    val recordings: List<RecordingInfo> = emptyList()
) {
    /** Total bytes of all saved recordings — for the storage line in the Recordings manager. */
    val recordingsBytes: Long get() = recordings.sumOf { it.sizeBytes }
    val testTags: List<String> get() = nodes.mapNotNull { it.testTag }.distinct().sorted()
}
