package com.qalens

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Release / no-op implementation. Mirrors the full public API of the debug artifacts so apps
 * compile unchanged, but does nothing: no overlay, no sensors, no notification, no interceptor,
 * no state retention, no background work.
 */
object QaLens {
    private const val DISABLED = "QaLens is disabled in release/noop builds."

    private val uiState = MutableStateFlow(QaLensUiState())
    private val configState = MutableStateFlow(QaLensConfig())

    val state: StateFlow<QaLensUiState> = uiState.asStateFlow()
    val config: StateFlow<QaLensConfig> = configState.asStateFlow()

    fun configure(block: QaLensConfig.Builder.() -> Unit) {
        // Keep config so reads are consistent, but never collect or act on it.
        configState.value = QaLensConfig.Builder(configState.value).apply(block).build()
    }

    fun install(application: Application) = Unit
    fun setScreen(name: String, route: String? = null) = Unit
    fun setFeatureFlagProvider(provider: () -> Map<String, Boolean>) = Unit
    fun log(message: String) = Unit
    fun event(name: String, message: String = name) = Unit
    fun breadcrumb(message: String) = Unit
    fun timberLog(priority: Int, tag: String?, message: String) = Unit
    fun openPanel() = Unit
    fun closePanel() = Unit
    fun togglePanel() = Unit
    fun toggleInspectMode() = Unit
    fun setInspectMode(enabled: Boolean) = Unit
    fun toggleTagMode() = Unit
    fun setTagMode(enabled: Boolean) = Unit
    fun setOverlayAlpha(alpha: Float) = Unit
    fun toggleDock() = Unit
    fun setOverlayEnabled(enabled: Boolean) = Unit
    fun panicRestore() = Unit
    fun setPanelMinimal(minimal: Boolean) = Unit
    fun markMoment(note: String = "Marked by QA") = Unit
    fun setWatchMode(on: Boolean) = Unit
    fun toggleWatchMode() = Unit
    fun selectNode(node: InspectNode?) = Unit

    fun registerDeepLinkScenario(
        name: String,
        uri: String,
        expectedRoute: String? = null,
        tags: List<String> = emptyList()
    ) = Unit
    fun runScenario(scenario: DeepLinkScenario) = Unit
    fun contract(screen: String, block: ScreenContractBuilder.() -> Unit) = Unit
    fun registerDataSource(name: String, provider: () -> Map<String, String>) = Unit
    fun observeRoom(db: RoomDatabase, vararg tables: String) = Unit
    fun <T> observeDataStore(name: String, flow: Flow<T>, describe: (T) -> String = { "updated" }) = Unit

    fun markNetworkAvailable() = Unit
    fun logNetwork(event: NetworkEvent) = Unit
    fun clearNetworkLog() = Unit
    fun clearLogs() = Unit
    fun resetRecomposeCounters() = Unit
    fun navigate(deepLink: String) = Unit
    fun takeScreenshot(share: Boolean = true) = Unit
    fun restartActivity() = Unit
    fun startRecording(video: Boolean = false) = Unit
    fun stopRecording() = Unit
    fun toggleRecording() = Unit
    fun refreshRecordings() = Unit
    fun shareRecording(info: RecordingInfo) = Unit
    fun deleteRecording(info: RecordingInfo) = Unit
    fun deleteAllRecordings() = Unit

    fun snapshot(): InspectionSnapshot = InspectionSnapshot(
        screen = ScreenSnapshot(),
        device = DeviceSnapshot(),
        nodes = emptyList(),
        warnings = emptyList(),
        testTags = emptyList(),
        events = emptyList()
    )

    fun evidenceBundle(attachments: List<EvidenceAttachment> = emptyList()): EvidenceBundle =
        EvidenceBuilder.build(
            snapshot = snapshot(),
            network = emptyList(),
            config = configState.value,
            featureFlags = emptyMap(),
            attachments = attachments,
            networkInterceptorInstalled = false
        )

    fun buildJiraReport(): String = DISABLED
    fun buildSlackSummary(): String = DISABLED
    fun buildReproSteps(): String = DISABLED
    fun buildFullReport(): String = DISABLED
    fun buildSessionSummary(): String = DISABLED
    fun buildReport(): String = DISABLED
}

@Composable
fun QaLensRoot(
    modifier: Modifier = Modifier,
    screenName: String? = null,
    route: String? = null,
    testTagsAsResourceId: Boolean = true,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    content()
}

fun Modifier.qaTag(tag: String, hiddenFromReports: Boolean = false): Modifier = this
fun Modifier.qaName(name: String): Modifier = this
fun Modifier.qaHiddenFromReports(): Modifier = this
fun Modifier.qaContentDescription(value: String): Modifier = this

@Composable
fun Modifier.qaLensRecompose(name: String): Modifier = this
