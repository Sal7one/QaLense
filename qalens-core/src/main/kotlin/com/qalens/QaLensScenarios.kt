package com.qalens

/** A named, repeatable deep-link entry point QA can launch from the panel without Android Studio. */
data class DeepLinkScenario(
    val name: String,
    val uri: String,
    val expectedRoute: String? = null,
    val tags: List<String> = emptyList()
)

enum class ScenarioStatus { NOT_RUN, LAUNCHED, PENDING, PASS, FAIL }

/** Result of launching a scenario. [actualRoute] is the route observed after launch, if any. */
data class ScenarioRun(
    val scenarioName: String,
    val status: ScenarioStatus,
    val actualRoute: String? = null,
    val timestampMillis: Long = System.currentTimeMillis()
)

/**
 * Pure matching used to validate a scenario's expected route against the route observed after the
 * deep link launched. Accepts an exact match, a path-prefix match, or a screen-name match so a
 * scenario expecting "recharge" passes when the app lands on "recharge/42".
 */
object ScenarioMatcher {
    fun matches(expectedRoute: String, actualRoute: String?, actualScreenName: String?): Boolean {
        if (actualRoute == null && actualScreenName == null) return false
        val expected = expectedRoute.trim().trimEnd('/')
        val route = actualRoute?.trim()?.trimEnd('/')
        val name = actualScreenName?.trim()
        return route == expected ||
            route?.substringBefore("/")?.substringBefore("?")?.equals(expected, true) == true ||
            route?.startsWith("$expected/") == true ||
            route?.startsWith("$expected?") == true ||
            name.equals(expected, ignoreCase = true)
    }
}
