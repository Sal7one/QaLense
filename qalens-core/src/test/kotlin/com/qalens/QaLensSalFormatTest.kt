package com.qalens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QaLensSalFormatTest {

    private val config = QaLensConfig()

    @Test
    fun jsonEscapesQuotesAndControlChars() {
        assertEquals("\"a\\\"b\"", SalJson.str("a\"b"))
        assertEquals("\"line1\\nline2\"", SalJson.str("line1\nline2"))
    }

    @Test
    fun jsonEncodesNestedStructures() {
        val json = SalJson.encode(mapOf("a" to listOf(1, 2), "b" to true, "c" to null))
        assertEquals("{\"a\":[1,2],\"b\":true,\"c\":null}", json)
    }

    @Test
    fun manifestContainsCoreFields() {
        val m = SalManifest(
            createdAtMillis = 1000, appName = "App", appVersion = "1.0", buildVariant = "debug",
            environment = "staging", gitSha = "abc1234", device = "Pixel", androidVersion = "14",
            startMillis = 1000, endMillis = 6000, fps = 2,
            frameIndex = mapOf(1000L to "frames/000001.jpg"),
            files = listOf("manifest.json", "frames/000001.jpg"),
            counts = mapOf("frames" to 1, "network" to 0)
        )
        val json = SalTracks.manifest(m)
        assertTrue("\"formatVersion\":1" in json)
        assertTrue("\"durationMs\":5000" in json)
        assertTrue("\"fps\":2" in json)
        assertTrue("\"frames/000001.jpg\"" in json)
        assertTrue("\"staging\"" in json)
    }

    @Test
    fun networkTrackRedactsUrlQuery() {
        val events = listOf(NetworkEvent(method = "POST", url = "https://api.x/v1/pay?token=secret", status = 500, latencyMs = 800))
        val json = SalTracks.network(events, config)
        assertTrue("QUERY_REDACTED" in json)
        assertFalse("token=secret" in json)
        assertTrue("\"status\":500" in json)
    }

    @Test
    fun logsTrackRedactsPii() {
        val events = listOf(QaEvent(type = QaEventType.LOG, tag = "Auth", message = "login a@b.com"))
        val json = SalTracks.logs(events, config)
        assertTrue("EMAIL_REDACTED" in json)
        assertFalse("a@b.com" in json)
    }

    @Test
    fun summaryTrackEncodesScoreOwnerAndRepro() {
        val score = ReleaseReadinessScore(
            score = 65, penalties = listOf(ScorePenalty(ScoreDimension.NETWORK, 10, "1 failed API")),
            criticalIssues = 1, warnings = 3, missingTags = 0, duplicateTags = 0, failedApis = 1, slowApis = 0
        )
        val classification = BugClassification(BugCategory.BACKEND_API, Confidence.HIGH, listOf("POST /pay returned 500"))
        val repro = ReproSteps(listOf("1. Open Home", "2. Observe: 500"), "Should succeed", "Failure observed")
        val json = SalTracks.summary(score, classification, repro, config)
        assertTrue("\"score\":65" in json)
        assertTrue("\"Backend/API\"" in json)
        assertTrue("\"confidence\":\"HIGH\"" in json)
        assertTrue("\"Network Health\"" in json)
        assertTrue("Should succeed" in json)
    }

    @Test
    fun stateTrackEncodesFlagsAndDataSources() {
        val samples = listOf(
            StateSample(
                timestampMillis = 1000, screenName = "Home", route = "home",
                featureFlags = mapOf("new_home" to true),
                dataSources = mapOf("Prefs" to mapOf("theme" to "dark"))
            )
        )
        val json = SalTracks.state(samples, config)
        assertTrue("\"new_home\":true" in json)
        assertTrue("\"theme\":\"dark\"" in json)
        assertTrue("\"screen\":\"Home\"" in json)
    }
}
