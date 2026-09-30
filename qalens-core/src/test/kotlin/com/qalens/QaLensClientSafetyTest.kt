package com.qalens

import kotlin.test.*

class QaLensClientSafetyTest {
    @Test fun authorizationConsumesSchemeAndCredentialAndSurvivesRepeatedRedaction() {
        val cfg = QaLensConfig()
        for (scheme in listOf("Basic", "Bearer", "Custom")) {
            val input = "Authorization: $scheme opaqueCredentialAbc\nContent-Type: text/plain"
            val redacted = cfg.redact(input)
            assertFalse(redacted.contains("opaqueCredentialAbc"))
            assertFalse(cfg.redact(redacted).contains("opaqueCredentialAbc"))
            assertTrue(redacted.contains("Content-Type: text/plain"))
        }
    }
    @Test fun coroutineDelegationPreservesExceptionIdentityAndHostThread() {
        val thread = Thread("host-fixture")
        val failure = IllegalStateException("original")
        var calls = 0
        thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { actualThread, actualFailure ->
            assertSame(thread, actualThread); assertSame(failure, actualFailure); calls++
        }
        QaLensExceptionDelegation.forward(failure, thread)
        assertEquals(1, calls)
    }
    @Test fun credentialOriginsRequireSameProtocolHostAndEffectivePort() {
        assertTrue(QaLensUploadPolicy.sameOrigin("https://example.test/a", "https://EXAMPLE.test:443/b"))
        for (other in listOf("http://example.test", "https://other.test", "https://example.test:8443", "file:///tmp/a", "https://user:password@example.test"))
            assertFalse(QaLensUploadPolicy.sameOrigin("https://example.test", other))
        assertFalse(QaLensUploadPolicy.sameOrigin("bad", "bad"))
    }
    @Test fun retriesDistinguishTemporaryFailuresFromPermanentRejection() {
        for (code in listOf(408, 429, 500, 503, 599)) assertTrue(QaLensUploadPolicy.retryable(code))
        for (code in listOf(200, 301, 400, 401, 403, 404)) assertFalse(QaLensUploadPolicy.retryable(code))
    }
    @Test fun screenshotGalleryAndUnmaskedVideoRequireSeparateOptIns() {
        val defaults = QaLensConfig()
        assertFalse(defaults.allowUnmaskedVideo)
        assertFalse(defaults.saveScreenshotsToGallery)
        val seeded = QaLensConfig.Builder(defaults.copy(allowUnmaskedVideo = true, saveScreenshotsToGallery = true)).build()
        assertTrue(seeded.allowUnmaskedVideo); assertTrue(seeded.saveScreenshotsToGallery)
    }
    @Test fun exportedScoreIncludesTheSameFramePenaltyAsLiveScoring() {
        val frames = listOf(FrameMetricsSample.fromNanoseconds(900_000_000, 0, 0, 0))
        val snapshot = InspectionSnapshot(screen = ScreenSnapshot(screenName = "Checkout"), device = DeviceSnapshot(),
            nodes = emptyList(), warnings = emptyList(), testTags = emptyList(), events = emptyList())
        val bundle = EvidenceBuilder.build(snapshot, emptyList(), QaLensConfig(), emptyMap(), frameMetrics = frames)
        val live = ReleaseReadinessEngine.score(snapshot.warnings, snapshot.screen, emptyList(), bundle.buildSafety.issues, frameMetrics = frames)
        assertEquals(live, bundle.score)
        assertTrue(bundle.score.penalties.any { it.reason.contains("frozen") })
    }
}
