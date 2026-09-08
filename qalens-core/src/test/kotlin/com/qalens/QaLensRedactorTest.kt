package com.qalens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QaLensRedactorTest {

    private val rules = RedactionRule.defaultRules()
    private fun redact(s: String) = QaLensRedactor.redact(s, rules)

    @Test
    fun redactsEmail() {
        assertEquals("Contact [EMAIL_REDACTED] now", redact("Contact john.doe@example.com now"))
    }

    @Test
    fun redactsJwt() {
        assertEquals("[JWT_REDACTED]", redact("eyJhbG.eyJzdWIi.SflKxwRJ"))
        assertTrue("[JWT_REDACTED]" in redact("Authheader eyJhbG.eyJzdWIi.SflKxwRJ end"))
    }

    @Test
    fun redactsBearerToken() {
        assertEquals("Bearer [TOKEN_REDACTED]", redact("Bearer abc.def-123_xyz"))
    }

    @Test
    fun redactsAuthorizationHeader() {
        assertTrue(redact("Authorization: Basic dXNlcjpwYXNz").startsWith("Authorization: [REDACTED]"))
    }

    @Test
    fun redactsCookieHeader() {
        assertTrue(redact("Cookie: session=abc123; theme=dark").startsWith("Cookie: [REDACTED]"))
    }

    @Test
    fun redactsKeyValueSecrets() {
        assertEquals("api_key=[REDACTED]", redact("api_key=supersecretvalue"))
        assertEquals("\"password\":\"[REDACTED]\"", redact("\"password\":\"hunter2\""))
    }

    @Test
    fun redactsCreditCard() {
        assertEquals("[CARD_REDACTED]", redact("4111 1111 1111 1111"))
        assertEquals("[CARD_REDACTED]", redact("4111-1111-1111-1111"))
    }

    @Test
    fun redactsSaudiPhone() {
        assertEquals("[SA_PHONE_REDACTED]", redact("0512345678"))
        assertEquals("[SA_PHONE_REDACTED]", redact("+966512345678"))
    }

    @Test
    fun redactsLongNumericId() {
        assertEquals("order [NUMBER_REDACTED]", redact("order 1234567890"))
    }

    @Test
    fun leavesSafeTextAlone() {
        val safe = "Tapped Recharge on Home screen"
        assertEquals(safe, redact(safe))
    }

    @Test
    fun nullAndEmptyAreSafe() {
        assertEquals("", QaLensRedactor.redact(null, rules))
        assertEquals("", QaLensRedactor.redact("", rules))
    }

    @Test
    fun redactUrlStripsQuery() {
        assertEquals("https://api.example.com/v1/recharge?[QUERY_REDACTED]",
            QaLensRedactor.redactUrl("https://api.example.com/v1/recharge?token=abc&id=42", rules))
        assertEquals("https://api.example.com/v1/balance",
            QaLensRedactor.redactUrl("https://api.example.com/v1/balance", rules))
    }

    @Test
    fun customRuleCanBeAdded() {
        val custom = rules + RedactionRule(Regex("(?i)projectx"), "[INTERNAL]")
        assertEquals("Build for [INTERNAL]", QaLensRedactor.redact("Build for ProjectX", custom))
    }

    @Test
    fun noReportPathLeaksEmailEvenMidSentence() {
        val out = redact("user a@b.co and b@c.io both failed")
        assertFalse("@" in out)
    }
    @Test fun emailRuleDoesLinearWorkOnLongNonEmailTokens() {
        // Count matcher reads instead of asserting elapsed time (stable on slow CI/emulators).
        val raw = "x".repeat(20_000)
        var reads = 0
        val counted = object : CharSequence {
            override val length: Int get() = raw.length
            override fun get(index: Int): Char {
                check(++reads <= raw.length * 30) { "Email matcher repeatedly rescanned a long token" }
                return raw[index]
            }
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = raw.subSequence(startIndex, endIndex)
            override fun toString(): String = raw
        }
        val email = rules.single { it.replacement == "[EMAIL_REDACTED]" }
        assertEquals(raw, email.pattern.replace(counted, email.replacement))
    }

    @Test fun emailBoundaryStillRedactsLongAddressesAndPunctuation() {
        val local = "x".repeat(20_000)
        assertEquals("Contact <[EMAIL_REDACTED]>, [EMAIL_REDACTED]!", redact("Contact <$local@example.com>, a+b@sub.example.org!"))
    }
}
