package com.qalens

import kotlin.test.*

class QaLensNetworkCaptureTest {
    private val raw = NetworkEvent(method = "GET", url = "https://example.test/profile?token=top-secret",
        error = "Contact jane@example.test", requestBodyPreview = "token=secret", responseBodyPreview = "private body")

    @Test fun disabledCaptureRejectsManualAndAdapterEvents() {
        assertNull(QaLensNetworkCapture.sanitize(raw, QaLensConfig(enabled = false)))
        assertNull(QaLensNetworkCapture.sanitize(raw, QaLensConfig(captureNetwork = false)))
    }
    @Test fun defaultsRemoveBodiesAndRedactMetadataBeforeStorage() {
        val safe = QaLensNetworkCapture.sanitize(raw, QaLensConfig())!!
        assertEquals("https://example.test/profile?[QUERY_REDACTED]", safe.url)
        assertEquals("Contact [EMAIL_REDACTED]", safe.error)
        assertNull(safe.requestBodyPreview)
        assertNull(safe.responseBodyPreview)
    }
    @Test fun explicitBodyOptInIsBoundedAndRedacted() {
        val safe = QaLensNetworkCapture.sanitize(raw.copy(responseBodyPreview = "x".repeat(100_000)),
            QaLensConfig(captureNetworkBodies = true))!!
        assertEquals("token=[REDACTED]", safe.requestBodyPreview)
        assertTrue(safe.responseBodyPreview!!.length < 66_000)
        assertTrue(safe.responseBodyPreview!!.contains("truncated", ignoreCase = true))
    }
    @Test fun legacyChuckerFlagDoesNotDisableFallbackCapture() {
        assertNotNull(QaLensNetworkCapture.sanitize(raw, QaLensConfig(networkFromChucker = true)))
    }
    @Test fun unavailableSizesAndNegativeDurationAreNormalized() {
        val safe = QaLensNetworkCapture.sanitize(raw.copy(latencyMs = -1, requestBodyBytes = -1, responseBodyBytes = -1), QaLensConfig())!!
        assertEquals(0L, safe.latencyMs)
        assertEquals(0L, safe.requestBodyBytes)
        assertEquals(0L, safe.responseBodyBytes)
    }
    @Test fun sourceNamesCannotBecomeCredentialOrUnboundedIdentifierStorage() {
        assertEquals("Ktor Client", QaLensNetworkCapture.sourceName(" Ktor Client "))
        for (name in listOf("", "a\nb", "https://example.test?token=secret", "x".repeat(65)))
            assertFailsWith<IllegalArgumentException> { QaLensNetworkCapture.sourceName(name) }
    }
    @Test fun diagnosticsExplainMissingWiringAndUnsupportedChuckerMode() {
        val report = QaLensIntegrationDiagnostics.report(QaLensConfig(networkFromChucker = true), QaLensUiState())
        assertTrue(report.contains("ACTION: attach"))
        assertTrue(report.contains("networkFromChucker is unsupported"))
        assertTrue(report.contains("not proof of traffic"))
        assertTrue(report.contains("not masked"))
    }
    @Test fun diagnosticsContainNoNetworkContents() {
        val report = QaLensIntegrationDiagnostics.report(QaLensConfig(), QaLensUiState(
            networkSources = setOf("Ktor"), networkEvents = listOf(raw)))
        assertTrue(report.contains("Declared network sources: Ktor"))
        assertFalse(report.contains("top-secret"))
        assertFalse(report.contains("jane@"))
    }
    @Test fun exportedNetworkErrorsAreRedactedEvenForDirectTrackEncoders() {
        val json = SalTracks.network(listOf(raw), QaLensConfig())
        assertFalse(json.contains("jane@example.test"))
        assertTrue(json.contains("[EMAIL_REDACTED]"))
    }
}
