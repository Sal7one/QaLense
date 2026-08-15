package com.qalens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QaLensEnginesTest {

    private val config = QaLensConfig()

    private fun net(status: Int = 200, latency: Long = 100, error: String? = null, url: String = "https://api.x.com/v1/thing") =
        NetworkEvent(method = "POST", url = url, status = status, latencyMs = latency, error = error)

    private fun warn(id: String, sev: WarningSeverity, title: String = id) =
        QaWarning(id = id, severity = sev, title = title, description = "desc")

    // ── Scoring ─────────────────────────────────────────────────────────────

    @Test
    fun perfectScreenScores100() {
        val score = ReleaseReadinessEngine.score(
            warnings = emptyList(),
            screen = ScreenSnapshot(screenName = "Home", route = "home"),
            network = emptyList()
        )
        assertEquals(100, score.score)
        assertEquals("Ready", score.band)
    }

    @Test
    fun criticalA11yAndFailedApiDeduct() {
        val score = ReleaseReadinessEngine.score(
            warnings = listOf(warn("A11Y_CLICKABLE_MISSING_LABEL", WarningSeverity.CRITICAL)),
            screen = ScreenSnapshot(screenName = "Checkout", route = "checkout"),
            network = listOf(net(status = 500, latency = 800))
        )
        // 100 - 25 (critical) - 10 (failed api) = 65
        assertEquals(65, score.score)
        assertEquals(1, score.criticalIssues)
        assertEquals(1, score.failedApis)
    }

    @Test
    fun scoreNeverGoesNegative() {
        val many = (1..20).map { warn("A11Y_CLICKABLE_MISSING_LABEL", WarningSeverity.CRITICAL, "c$it") }
        val score = ReleaseReadinessEngine.score(many, ScreenSnapshot(), emptyList())
        assertEquals(0, score.score)
    }

    @Test
    fun missingScreenMetadataPenalized() {
        val score = ReleaseReadinessEngine.score(emptyList(), ScreenSnapshot(), emptyList())
        assertTrue(score.penalties.any { it.dimension == ScoreDimension.SCREEN_METADATA })
    }

    // ── Classifier ────────────────────────────────────────────────────────────

    @Test
    fun server5xxIsBackendHigh() {
        val c = BugClassifier.classify(
            network = listOf(net(status = 500)),
            warnings = emptyList(),
            screenHistory = listOf("home", "checkout")
        )
        assertEquals(BugCategory.BACKEND_API, c.category)
        assertEquals(Confidence.HIGH, c.confidence)
    }

    @Test
    fun client4xxIsBackendMedium() {
        val c = BugClassifier.classify(listOf(net(status = 401)), emptyList(), listOf("home"))
        assertEquals(BugCategory.BACKEND_API, c.category)
        assertEquals(Confidence.MEDIUM, c.confidence)
    }

    @Test
    fun navigationLoopIsNavigationCategory() {
        val c = BugClassifier.classify(
            network = emptyList(),
            warnings = emptyList(),
            screenHistory = listOf("home", "a", "home", "a")
        )
        assertEquals(BugCategory.ANDROID_NAVIGATION, c.category)
    }

    @Test
    fun noSignalIsUnknownOrClientSide() {
        val c = BugClassifier.classify(emptyList(), emptyList(), emptyList())
        assertTrue(c.category == BugCategory.UNKNOWN || c.category == BugCategory.ANDROID_UI)
    }

    // ── B5: Connectivity-aware classifier ─────────────────────────────────────

    @Test
    fun offlineFailureIsConfigurationNotBackend() {
        val offlineSnap = ConnectivitySnapshot(type = ConnectivityType.OFFLINE)
        val failedOffline = NetworkEvent(method = "GET", url = "https://api.x.com/v1/balance",
            status = 0, error = "SocketTimeoutException", connectivity = offlineSnap)
        val c = BugClassifier.classify(listOf(failedOffline), emptyList(), listOf("home"))
        assertEquals(BugCategory.CONFIGURATION_ENVIRONMENT, c.category)
        assertEquals(Confidence.HIGH, c.confidence)
        assertTrue(c.reasons.any { it.contains("offline") })
    }

    @Test
    fun transportFailureWithoutConnectivityIsBackend() {
        // A transport error with NO connectivity snapshot (older recording / interceptor didn't stamp)
        // should fall back to Backend/API, not Configuration.
        val failed = NetworkEvent(method = "GET", url = "https://api.x.com/v1/balance",
            status = 0, error = "SocketTimeoutException", connectivity = null)
        val c = BugClassifier.classify(listOf(failed), emptyList(), listOf("home"))
        assertEquals(BugCategory.BACKEND_API, c.category)
    }

    @Test
    fun server5xxWithWifiStaysBackend() {
        // A 500 while on WiFi is definitely the server, not the network.
        val wifiSnap = ConnectivitySnapshot(type = ConnectivityType.WIFI, strengthBars = 4)
        val serverErr = NetworkEvent(method = "POST", url = "https://api.x.com/v1/transfer",
            status = 500, connectivity = wifiSnap)
        val c = BugClassifier.classify(listOf(serverErr), emptyList(), listOf("home", "transfer"))
        assertEquals(BugCategory.BACKEND_API, c.category)
        assertEquals(Confidence.HIGH, c.confidence)
    }

    // ── Timeline + repro ──────────────────────────────────────────────────────

    @Test
    fun timelineIsSortedByTime() {
        val events = listOf(
            QaEvent(timestampMillis = 30, type = QaEventType.EVENT, message = "Tap Submit", tag = "Submit"),
            QaEvent(timestampMillis = 10, type = QaEventType.BREADCRUMB, message = "Navigation → home")
        )
        val network = listOf(net(status = 500).copy(timestampMillis = 20))
        val tl = TimelineMerger.merge(events, network, config)
        assertEquals(listOf(10L, 20L, 30L), tl.map { it.timestampMillis })
    }

    @Test
    fun reproGeneratesStepsAndFlagsError() {
        val events = listOf(
            QaEvent(timestampMillis = 10, type = QaEventType.BREADCRUMB, message = "Navigation → home"),
            QaEvent(timestampMillis = 20, type = QaEventType.EVENT, message = "Tapped Recharge", tag = "Recharge")
        )
        val network = listOf(net(status = 500).copy(timestampMillis = 30))
        val repro = ReproStepGenerator.generate(TimelineMerger.merge(events, network, config))
        assertTrue(repro.hasData)
        assertTrue(repro.steps.first().startsWith("1."))
        assertTrue(repro.actual.contains("Failure", ignoreCase = true))
    }

    @Test
    fun reproHonestWhenEmpty() {
        val repro = ReproStepGenerator.generate(emptyList())
        assertTrue(!repro.hasData)
    }

    // ── Build safety ────────────────────────────────────────────────────────

    @Test
    fun missingMetadataFlagged() {
        val status = BuildSafetyCheck.check(DeviceSnapshot())
        assertTrue(status.issues.isNotEmpty())
        assertTrue(!status.isSafe)
    }

    @Test
    fun devHostInStagingFlagged() {
        val device = DeviceSnapshot(environment = "staging", buildVariant = "staging", gitSha = "abc1234", appVersion = "1.0")
        val status = BuildSafetyCheck.check(device, observedHost = "api-dev.internal")
        assertTrue(status.issues.any { it.contains("DEV", ignoreCase = true) })
    }

    // ── Screen quality store ──────────────────────────────────────────────────

    @Test
    fun screenStoreTracksBestWorstAndVisits() {
        val screen = ScreenSnapshot(screenName = "Checkout", route = "checkout")
        var map = emptyMap<String, ScreenQualitySnapshot>()
        map = ScreenQualityStore.record(map, screen, score(40), newVisit = true)
        map = ScreenQualityStore.record(map, screen, score(80), newVisit = false)
        map = ScreenQualityStore.record(map, screen, score(60), newVisit = true)
        val snap = map.values.single()
        assertEquals(80, snap.bestScore)
        assertEquals(40, snap.worstScore)
        assertEquals(60, snap.latestScore)
        assertEquals(2, snap.visitCount)
    }

    private fun score(value: Int) = ReleaseReadinessScore(
        score = value, penalties = emptyList(), criticalIssues = 0, warnings = 0,
        missingTags = 0, duplicateTags = 0, failedApis = 0, slowApis = 0
    )

    // ── Jank analyzer (B2) ───────────────────────────────────────────────────

    @Test
    fun jankAnalyzer_emptyListIsAllZeros() {
        val d = JankAnalyzer.analyze(emptyList())
        assertEquals(0, d.sampleCount)
        assertEquals(0, d.jankCount)
        assertEquals(0.0f, d.jankRate)
    }

    @Test
    fun jankAnalyzer_countsJankAndFrozen() {
        val samples = listOf(
            FrameMetricsSample(totalMs = 10, jank = false, frozen = false),
            FrameMetricsSample(totalMs = 20, jank = true, frozen = false),
            FrameMetricsSample(totalMs = 800, jank = true, frozen = true),
            FrameMetricsSample(totalMs = 15, jank = false, frozen = false),
        )
        val d = JankAnalyzer.analyze(samples)
        assertEquals(4, d.sampleCount)
        assertEquals(2, d.jankCount)
        assertEquals(1, d.frozenCount)
        assertEquals(800, d.worstFrameMs)
    }

    @Test
    fun jarkAnalyzer_highJankRateTriggersScorePenalty() {
        val samples = (1..10).map { FrameMetricsSample(totalMs = if (it <= 7) 30L else 10L, jank = it <= 7) }
        val score = ReleaseReadinessEngine.score(
            warnings = emptyList(),
            screen = ScreenSnapshot(screenName = "List"),
            network = emptyList(),
            frameMetrics = samples
        )
        // 70% jank rate → P_HIGH_JANK_RATE (10) deducted
        assertTrue(score.score <= 90, "Expected jank penalty, got ${score.score}")
        assertTrue(score.penalties.any { it.dimension == ScoreDimension.PERFORMANCE })
    }

    @Test
    fun jankAnalyzer_frozenFrameTriggersScorePenalty() {
        val samples = listOf(FrameMetricsSample(totalMs = 800, jank = true, frozen = true))
        val score = ReleaseReadinessEngine.score(
            warnings = emptyList(),
            screen = ScreenSnapshot(screenName = "Detail"),
            network = emptyList(),
            frameMetrics = samples
        )
        assertTrue(score.score < 100, "Expected frozen-frame penalty, got ${score.score}")
        assertTrue(score.penalties.any { it.reason.contains("frozen") })
    }

    // ── Session diff (B11) ───────────────────────────────────────────────────

    private fun summary(score: Int?, failed: List<String>, owner: String?, crashes: Int = 0) =
        QaLensDiff.SalSessionSummary(
            appName = "Test", appVersion = "1.0", environment = "staging",
            durationMs = 30000, score = score, likelyOwner = owner,
            screensVisited = listOf("Home"), failedRequests = failed,
            errorCount = failed.size, crashCount = crashes, anomalyTitles = emptyList()
        )

    @Test
    fun diffDetectsScoreDrop() {
        val d = QaLensDiff.diff(summary(80, emptyList(), "Android/UI"), summary(60, emptyList(), "Backend/API"))
        assertEquals(-20, d.scoreDelta)
        assertTrue(d.isRegression)
        assertTrue(d.likelyOwnerChanged)
    }

    @Test
    fun diffDetectsNewFailures() {
        val base = summary(80, emptyList(), "Android/UI")
        val curr = summary(80, listOf("POST /transfer"), "Backend/API")
        val d = QaLensDiff.diff(base, curr)
        assertEquals(1, d.addedFailedRequests.size)
        assertTrue(d.isRegression)
    }

    @Test
    fun diffDetectsResolvedFailures() {
        val base = summary(50, listOf("POST /transfer"), "Backend/API")
        val curr = summary(80, emptyList(), "Android/UI")
        val d = QaLensDiff.diff(base, curr)
        assertEquals(1, d.resolvedFailedRequests.size)
        assertEquals(30, d.scoreDelta)
        assertFalse(d.isRegression)
    }

    @Test
    fun diffNoChangeIsNotRegression() {
        val s = summary(80, emptyList(), "Android/UI")
        val d = QaLensDiff.diff(s, s)
        assertEquals(0, d.scoreDelta)
        assertFalse(d.isRegression)
    }

    @Test
    fun macroAssertExistsFindsNode() {
        val state = QaLensUiState(nodes = listOf(
            InspectNode(id = "1", qaName = "Submit")
        ))
        val result = MacroEngine.execute(listOf(MacroStep.AssertExists("Submit")), state)
        assertTrue(result.allPassed)
        assertEquals(1, result.steps.size)
        assertTrue(result.steps[0].passed)
    }

    @Test
    fun macroAssertExistsMissingNode() {
        val state = QaLensUiState(nodes = emptyList())
        val result = MacroEngine.execute(listOf(MacroStep.AssertExists("Submit")), state)
        assertFalse(result.allPassed)
        assertFalse(result.steps[0].passed)
    }

    @Test
    fun macroAssertRoutePasses() {
        val state = QaLensUiState(screen = ScreenSnapshot(route = "/home"))
        val result = MacroEngine.execute(listOf(MacroStep.AssertRoute("/home")), state)
        assertTrue(result.allPassed)
    }

    @Test
    fun macroAssertRouteFails() {
        val state = QaLensUiState(screen = ScreenSnapshot(route = "/login"))
        val result = MacroEngine.execute(listOf(MacroStep.AssertRoute("/home")), state)
        assertFalse(result.allPassed)
    }

    @Test
    fun macroIfConditionBranchesCorrectly() {
        val state = QaLensUiState(nodes = listOf(InspectNode(id = "1", qaName = "Button")))
        val ifStep = MacroStep.If(
            condition = MacroCondition.Exists("Button"),
            thenStep = MacroStep.AssertRoute("/expected"),
            elseStep = MacroStep.AssertRoute("/fallback")
        )
        val result = MacroEngine.execute(listOf(ifStep), state)
        // thenStep should have run — and failed because route is empty
        assertFalse(result.allPassed)
        // The error message should contain the expected route, not fallback
        assertTrue(result.steps[0].message.contains("/expected"))
    }

    @Test
    fun macroIfElseBranchesWhenConditionFalse() {
        val state = QaLensUiState(nodes = emptyList())
        val ifStep = MacroStep.If(
            condition = MacroCondition.Exists("Button"),
            thenStep = MacroStep.AssertRoute("/expected"),
            elseStep = MacroStep.AssertRoute("/fallback")
        )
        val result = MacroEngine.execute(listOf(ifStep), state)
        assertFalse(result.allPassed)
        assertTrue(result.steps[0].message.contains("/fallback"))
    }

    @Test
    fun macroAssertNetworkPasses() {
        val state = QaLensUiState(networkEvents = listOf(
            NetworkEvent(method = "GET", url = "https://api.example.com/users", status = 200)
        ))
        val result = MacroEngine.execute(
            listOf(MacroStep.AssertNetwork("api.example.com", 200)), state
        )
        assertTrue(result.allPassed)
    }

    @Test
    fun macroAssertNetworkFailsOnMismatch() {
        val state = QaLensUiState(networkEvents = listOf(
            NetworkEvent(method = "GET", url = "https://api.example.com/users", status = 500)
        ))
        val result = MacroEngine.execute(
            listOf(MacroStep.AssertNetwork("api.example.com", 200)), state
        )
        assertFalse(result.allPassed)
    }

    @Test
    fun macroConditionNoErrorsPasses() {
        val state = QaLensUiState(errors = emptyList())
        val result = MacroEngine.execute(
            listOf(MacroStep.If(
                condition = MacroCondition.NoErrors(),
                thenStep = MacroStep.AssertRoute("/ok"),
                elseStep = MacroStep.AssertRoute("/err")
            )),
            state
        )
        // Then branch: route is empty, so assert fails
        assertFalse(result.allPassed)
        assertTrue(result.steps[0].message.contains("/ok"))
    }
}
