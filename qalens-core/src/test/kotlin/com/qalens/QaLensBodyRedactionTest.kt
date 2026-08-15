package com.qalens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QaLensBodyRedactionTest {

    private val config = QaLensConfig()

    @Test
    fun networkBodyPreviewsRedactSecretsAtEncodeTime() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dGVzdHNpZ25hdHVyZQ"
        val event = NetworkEvent(
            method = "POST",
            url = "https://api.example.com/login",
            status = 200,
            requestBodyPreview = "Bearer abc.def-123_xyz payload for john@example.com $jwt",
            responseBodyPreview = "{\"email\":\"jane@example.com\",\"token\":\"Bearer xyz.abc\"}"
        )
        val json = SalTracks.network(listOf(event), config)
        assertTrue("[JWT_REDACTED]" in json)
        assertTrue("[EMAIL_REDACTED]" in json)
        assertTrue("[TOKEN_REDACTED]" in json)
        assertFalse("eyJhbGci" in json)
        assertFalse("john@example.com" in json)
        assertFalse("jane@example.com" in json)
    }

    @Test
    fun truncationHelperAppendsMarkerOnlyWhenOverCap() {
        assertEquals("hello", truncateCapturedBody("hello", cap = 100))
        assertEquals("aaaaa…[truncated]", truncateCapturedBody("a".repeat(10), cap = 5))
    }
}
