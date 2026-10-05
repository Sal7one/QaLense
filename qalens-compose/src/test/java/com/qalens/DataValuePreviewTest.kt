package com.qalens

import org.junit.Assert.*
import org.junit.Test

class DataValuePreviewTest {
    @Test fun masksCredentialsAndSourceRulesBeforeBounding() {
        val result = DataValuePreview.sanitize(mapOf(
            "accessToken" to "opaque-value", "password" to "other-value", "theme" to "dark",
            "account" to "private-id", "description" to "prefix-${"x".repeat(4_000)}-private"
        ), QaLensConfig(), setOf("account"), listOf(Regex("private$")))
        assertEquals("dark", result["theme"])
        for (key in listOf("accessToken", "password", "account", "description"))
            assertEquals("[REDACTED]", result[key])
    }

    @Test fun appliesGlobalRedactionToKeysAndValues() {
        val config = QaLensConfig(redactionRules = listOf(RedactionRule(Regex("fixture-private"), "masked")))
        assertEquals(mapOf("masked" to "masked"), DataValuePreview.sanitize(
            mapOf("fixture-private" to "fixture-private"), config))
    }

    @Test fun boundsPreviewAndDisclosesTruncation() {
        val result = DataValuePreview.sanitize((0..150).associate { "field$it" to "v".repeat(3_000) }, QaLensConfig())
        assertEquals(100, result.size)
        assertTrue(result.values.all { it.length < 2_100 && it.endsWith("… [truncated]") })
        assertEquals(mapOf("theme" to "[REDACTED]"), DataValuePreview.sanitize(mapOf("theme" to "dark"), QaLensConfig(), redactAll = true))
    }

    @Test fun currentRulesRedactCachedFieldNamesForRecordingState() {
        val engine = AnalysisEngine(emptyMap(), mapOf("Settings" to DataSourceEntry(
            provider = { mapOf("fixture-private" to "fixture-private") }, redactFieldNames = true
        )), null) { _, _ -> fail("Unexpected provider failure") }
        val config = QaLensConfig(redactionRules = listOf(RedactionRule(Regex("fixture-private"), "masked")))
        assertEquals(mapOf("masked" to "masked"), engine.analyze(QaLensUiState(), config, emptyMap()).dataSources["Settings"])
    }
}
