package com.qalens

import kotlin.test.*

class QaLensCrashEvidenceTest {
    @Test fun everyBridgeTextFieldIsRedacted() {
        val raw = "contact person@example.test token=secret"
        val safe = QaLensCrashEvidence.sanitize(QaLensCrash(type = CrashType.CRASH, thread = raw,
            throwable = raw, stackTrace = raw, screen = raw, route = raw, lastNetworkSummary = raw), QaLensConfig())
        for (value in listOf(safe.thread, safe.throwable, safe.stackTrace, safe.screen, safe.route, safe.lastNetworkSummary)) {
            assertFalse(value!!.contains("person@example.test"))
            assertFalse(value.contains("token=secret"))
        }
    }
    @Test fun vendorStackSizeIsBoundedWithoutChangingCrashIdentity() {
        val crash = QaLensCrash(123, CrashType.ANR, "main", null, "x".repeat(100_000))
        val safe = QaLensCrashEvidence.sanitize(crash, QaLensConfig())
        assertEquals(123L, safe.timestampMillis)
        assertEquals(CrashType.ANR, safe.type)
        assertEquals(16_384, safe.stackTrace.length)
        assertNull(safe.throwable)
    }
}
