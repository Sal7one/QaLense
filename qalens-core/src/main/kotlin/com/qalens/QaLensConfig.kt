package com.qalens

data class QaLensConfig(
    /**
     * Master run-condition. When false, QaLens is fully inert even in debug builds: QaLensRoot
     * renders content() directly (no Box, no semantics, no effects), the installer attaches no
     * overlay/notification/shake. Gate it on whatever your team uses:
     * `enabled = BuildConfig.DEBUG && environment == "qa"`.
     */
    val enabled: Boolean = true,
    val appName: String = "",
    val appVersion: String = "",
    val buildVariant: String = "",
    val gitSha: String? = null,
    val buildNumber: String? = null,
    val environment: String? = null,
    val expectedEnvironment: String? = null,
    val featureFlags: Map<String, Boolean> = emptyMap(),
    val userType: String? = null,
    val redactionRules: List<RedactionRule> = RedactionRule.defaultRules(),
    val maxEventHistory: Int = 600,
    val touchTargetMinDp: Float = 48f,
    val slowNetworkThresholdMs: Long = 2000L,
    val requireTestTagsForClickable: Boolean = true,
    val nonDescriptiveLabels: Set<String> = defaultWeakLabels,
    val enableSemanticsReflection: Boolean = true,
    val enableAutoInstall: Boolean = true
) {
    class Builder(seed: QaLensConfig = QaLensConfig()) {
        var enabled: Boolean = seed.enabled
        var appName: String = seed.appName
        var appVersion: String = seed.appVersion
        var buildVariant: String = seed.buildVariant
        var gitSha: String? = seed.gitSha
        var buildNumber: String? = seed.buildNumber
        var environment: String? = seed.environment
        var expectedEnvironment: String? = seed.expectedEnvironment
        var featureFlags: Map<String, Boolean> = seed.featureFlags
        var userType: String? = seed.userType
        var redactionRules: List<RedactionRule> = seed.redactionRules
        var maxEventHistory: Int = seed.maxEventHistory
        var touchTargetMinDp: Float = seed.touchTargetMinDp
        var slowNetworkThresholdMs: Long = seed.slowNetworkThresholdMs
        var requireTestTagsForClickable: Boolean = seed.requireTestTagsForClickable
        var nonDescriptiveLabels: Set<String> = seed.nonDescriptiveLabels
        var enableSemanticsReflection: Boolean = seed.enableSemanticsReflection
        var enableAutoInstall: Boolean = seed.enableAutoInstall

        /** Add a custom redaction rule on top of the defaults. */
        fun addRedaction(pattern: String, replacement: String = "[REDACTED]") {
            redactionRules = redactionRules + RedactionRule(Regex(pattern), replacement)
        }

        fun build(): QaLensConfig = QaLensConfig(
            enabled = enabled,
            appName = appName,
            appVersion = appVersion,
            buildVariant = buildVariant,
            gitSha = gitSha,
            buildNumber = buildNumber,
            environment = environment,
            expectedEnvironment = expectedEnvironment,
            featureFlags = featureFlags,
            userType = userType,
            redactionRules = redactionRules,
            maxEventHistory = maxEventHistory.coerceAtLeast(20),
            touchTargetMinDp = touchTargetMinDp.coerceAtLeast(24f),
            slowNetworkThresholdMs = slowNetworkThresholdMs.coerceAtLeast(250L),
            requireTestTagsForClickable = requireTestTagsForClickable,
            nonDescriptiveLabels = nonDescriptiveLabels,
            enableSemanticsReflection = enableSemanticsReflection,
            enableAutoInstall = enableAutoInstall
        )
    }

    companion object {
        val defaultWeakLabels = setOf(
            "button", "image", "icon", "click", "tap", "here", "link", "item",
            "view", "element", "field", "label", "text", "more", "next", "previous"
        )
    }
}

data class RedactionRule(
    val pattern: Regex,
    val replacement: String
) {
    fun apply(value: String): String = value.replace(pattern, replacement)

    companion object {
        /**
         * Default redaction rules, ordered so the most specific patterns run first
         * (e.g. JWT before generic long numbers, credit cards before plain digit runs).
         * Every export path in QaLens folds text through these before it leaves the device.
         */
        fun defaultRules(): List<RedactionRule> = listOf(
            // JSON Web Tokens (header.payload.signature)
            RedactionRule(
                Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"),
                "[JWT_REDACTED]"
            ),
            // Authorization headers (any scheme)
            RedactionRule(
                Regex("(?i)authorization\\s*[:=]\\s*\\S+"),
                "Authorization: [REDACTED]"
            ),
            // Cookie / Set-Cookie headers (rest of line)
            RedactionRule(
                Regex("(?i)(set-)?cookie\\s*[:=]\\s*[^\\n]+"),
                "Cookie: [REDACTED]"
            ),
            // Bearer tokens
            RedactionRule(
                Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+"),
                "Bearer [TOKEN_REDACTED]"
            ),
            // Secret-looking key/value pairs: token=..., "api_key":"...", password: ...
            RedactionRule(
                Regex("(?i)(access[_-]?token|refresh[_-]?token|api[_-]?key|client[_-]?secret|password|passwd|secret|token)(\"?\\s*[:=]\\s*\"?)([^\"\\s,&}]+)"),
                "$1$2[REDACTED]"
            ),
            // Email addresses
            RedactionRule(
                Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
                "[EMAIL_REDACTED]"
            ),
            // Credit-card-like numbers (13–16 digits, optional spaces/dashes). Runs before
            // the generic long-number rule so cards are masked with their own label.
            RedactionRule(
                Regex("\\b\\d{4}[ -]?\\d{4}[ -]?\\d{4}[ -]?\\d{1,4}\\b"),
                "[CARD_REDACTED]"
            ),
            // Saudi mobile numbers (+9665…, 05…). Uses digit look-around (not \b) so the
            // leading "+" is consumed too.
            RedactionRule(
                Regex("(?<![\\d+])(?:\\+?966|0)?5\\d{8}(?!\\d)"),
                "[SA_PHONE_REDACTED]"
            ),
            // Long numeric IDs (account numbers, order ids, etc.)
            RedactionRule(
                Regex("\\b\\d{10,13}\\b"),
                "[NUMBER_REDACTED]"
            )
        )
    }
}

fun QaLensConfig.redact(value: String): String =
    QaLensRedactor.redact(value, redactionRules)
