package com.qalens

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A7: Compile-time API parity test.
 *
 * This file references every public method that the noop [QaLens] object must expose.
 * If any method is missing or has the wrong signature, this file won't compile — giving
 * an immediate, precise error at the exact missing method.
 *
 * This is more reliable than reflection (which fails at runtime with missing Room/Android
 * classes) and catches both missing methods and signature mismatches.
 */
object NoopParityCheck {
    private val state = MutableStateFlow(QaLensUiState())
    private val config = MutableStateFlow(QaLensConfig())

    fun exerciseAll() {
        val q = com.qalens.QaLens

        // Core lifecycle — install requires non-null Application, skip in test (it's a noop anyway)
        q.configure {}

        // State
        q.setScreen("screen", "route")
        q.setFeatureFlagProvider { emptyMap() }
        q.log("msg")
        q.event("name")
        q.breadcrumb("msg")
        q.timberLog(0, "tag", "msg")

        // Panel
        q.openPanel()
        q.closePanel()
        q.togglePanel()
        q.toggleInspectMode()
        q.setInspectMode(true)
        q.toggleTagMode()
        q.setTagMode(true)
        q.setOverlayAlpha(1f)
        q.toggleDock()
        q.setOverlayEnabled(true)
        q.panicRestore()
        q.setPanelMinimal(false)
        q.setWatchMode(false)
        q.toggleWatchMode()
        q.markMoment()

        // Inspect
        q.selectNode(null)

        // Network
        q.markNetworkAvailable()
        q.logNetwork(NetworkEvent(method = "GET", url = "http://test"))
        q.clearNetworkLog()
        q.clearLogs()
        q.resetRecomposeCounters()

        // Navigation
        q.navigate("link")
        q.takeScreenshot()
        q.restartActivity()

        // Recording
        q.startRecording()
        q.stopRecording()
        q.toggleRecording()
        q.refreshRecordings()
        q.shareRecording(RecordingInfo("", "", 0, 0))
        q.deleteRecording(RecordingInfo("", "", 0, 0))
        q.deleteAllRecordings()

        // Reporting
        q.buildReport()
        q.buildFullReport()
        q.buildSessionSummary()
        q.buildJiraReport()
        q.buildSlackSummary()
        q.buildReproSteps()
        q.buildGitHubIssue()
        q.buildLinearIssue()
        q.buildMarkdownReport()
        q.evidenceBundle()

        // A4: errors
        q.pushError(ErrorKind.OTHER, "msg")
        q.dismissError("id")
        q.clearErrors()

        // C9: bookmarks
        q.addBookmark("label")
        q.addBookmark("label", BookmarkSeverity.BUG)
        q.removeBookmark("id")
        q.clearBookmarks()

        // B1+B13: crashes
        q.bridgeCrashes(object : QaLensCrashBridge {
            override fun enrich(crash: QaLensCrash, evidence: String) {}
            override fun onCrash(callback: (QaLensCrash) -> Unit) {}
        })
        q.lastCrash()

        // B5: connectivity
        q.currentConnectivity()

        // B7: coroutine
        q.coroutineExceptionHandler()

        // B3: tabs
        q.registerTab(object : QaLensTabProvider {
            override val title: String = "test"
            @androidx.compose.runtime.Composable
            override fun Content(state: QaLensUiState, config: QaLensConfig) {}
        })
        q.unregisterTab("test")

        // A6: data observers
        q.registerDataSourceObserver(object : DataSourceObserver {
            override fun onChanged(source: String, tableName: String, changeType: ChangeType) {}
            override fun onError(source: String, error: String) {}
        })
        q.unregisterDataSourceObserver(object : DataSourceObserver {
            override fun onChanged(source: String, tableName: String, changeType: ChangeType) {}
            override fun onError(source: String, error: String) {}
        })
        q.notifyDataChange("src", "table", ChangeType.INSERT)
        q.notifyDataError("src", "err")

        // B16: data sources
        q.registerDataSource("name") { emptyMap() }

        // B15: macros
        q.runMacro(emptyList())

        // Existing
        q.observeDataStore("name", kotlinx.coroutines.flow.flowOf("v"))
        q.registerDeepLinkScenario("name", "uri")
    }
}

/**
 * A7 executed (not only compiled): every no-op twin must actually RUN without throwing.
 * JUnit4 needs a plain class — the object above is the compile-time reference surface.
 */
class NoopParityCheckTest {
    @org.junit.Test
    fun parityHoldsAtRuntime() {
        NoopParityCheck.exerciseAll()
    }
}
