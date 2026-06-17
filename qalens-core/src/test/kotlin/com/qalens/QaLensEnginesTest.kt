package com.qalens

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
