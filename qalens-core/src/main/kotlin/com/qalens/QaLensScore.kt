package com.qalens

enum class ScoreDimension(val display: String) {
    ACCESSIBILITY("Accessibility Health"),
    AUTOMATION_TAGS("Automation Tag Coverage"),
    NETWORK("Network Health"),
    NAVIGATION("Navigation Health"),
    SCREEN_METADATA("Screen Metadata"),
    BUILD_SAFETY("Build/Environment Safety"),
    PERFORMANCE("Performance"),
    EVIDENCE("Evidence Completeness")
}

/** One explainable deduction from the perfect score of 100. */
data class ScorePenalty(
    val dimension: ScoreDimension,
    val points: Int,
    val reason: String
)

data class ReleaseReadinessScore(
    val score: Int,
    val penalties: List<ScorePenalty>,
    val criticalIssues: Int,
    val warnings: Int,
    val missingTags: Int,
    val duplicateTags: Int,
    val failedApis: Int,
    val slowApis: Int
) {
    val band: String get() = when {
        score >= 85 -> "Ready"
        score >= 70 -> "Minor risk"
        score >= 50 -> "Needs attention"
        else        -> "High risk"
    }

    /** Penalties grouped by dimension, largest impact first — used for the "Why this score?" view. */
    fun byDimension(): List<Pair<ScoreDimension, Int>> =
        penalties.groupBy { it.dimension }
            .map { (dim, ps) -> dim to ps.sumOf { it.points } }
            .sortedByDescending { it.second }
}

/**
 * Deterministic, transparent scoring. Simple subtractive rules — no fake precision. Every
 * deduction carries a human-readable reason so a manager can see exactly why a screen lost points.
 */
object ReleaseReadinessEngine {

    // Penalty weights (documented in docs/v2_plan.md and surfaced in the UI).
    private const val P_CRITICAL_A11Y = 25
    private const val P_FAILED_API = 10
    private const val P_SLOW_API = 5
    private const val P_MISSING_SCREEN_META = 5
    private const val P_DUPLICATE_TAG = 3
    private const val P_MISSING_TAG = 2
    private const val P_WARNING = 1
    private const val P_BUILD_ISSUE = 5

    fun score(
        warnings: List<QaWarning>,
        screen: ScreenSnapshot,
        network: List<NetworkEvent>,
        buildSafetyIssues: List<String> = emptyList(),
        slowThresholdMs: Long = 2000L
    ): ReleaseReadinessScore {
        val penalties = mutableListOf<ScorePenalty>()

        val critical = warnings.filter { it.severity == WarningSeverity.CRITICAL }
        critical.forEach {
            penalties += ScorePenalty(ScoreDimension.ACCESSIBILITY, P_CRITICAL_A11Y, "Critical: ${it.title}")
        }

        val missingTags = warnings.filter { it.id == "QA_CLICKABLE_MISSING_TEST_TAG" }
        if (missingTags.isNotEmpty()) {
            penalties += ScorePenalty(
                ScoreDimension.AUTOMATION_TAGS, P_MISSING_TAG * missingTags.size,
                "${missingTags.size} clickable element(s) missing a test tag."
            )
        }
        val dupTags = warnings.filter { it.id == "QA_DUPLICATE_TEST_TAG" }.distinctBy { it.description }
        if (dupTags.isNotEmpty()) {
            penalties += ScorePenalty(
                ScoreDimension.AUTOMATION_TAGS, P_DUPLICATE_TAG * dupTags.size,
                "${dupTags.size} duplicate test tag(s)."
            )
        }

        val failed = network.filter { it.isError }
        if (failed.isNotEmpty()) {
            penalties += ScorePenalty(
                ScoreDimension.NETWORK, P_FAILED_API * failed.size,
                "${failed.size} failed API call(s)."
            )
        }
        val slow = network.filter { !it.isError && it.latencyMs >= slowThresholdMs }
        if (slow.isNotEmpty()) {
            penalties += ScorePenalty(
                ScoreDimension.PERFORMANCE, P_SLOW_API * slow.size,
                "${slow.size} slow API call(s) (≥ ${slowThresholdMs}ms)."
            )
        }

        if (screen.screenName.isNullOrBlank() && screen.route.isNullOrBlank()) {
            penalties += ScorePenalty(
                ScoreDimension.SCREEN_METADATA, P_MISSING_SCREEN_META,
                "Screen has no name or route. Use QaLensNavHost or QaLens.setScreen()."
            )
        }

        buildSafetyIssues.forEach {
            penalties += ScorePenalty(ScoreDimension.BUILD_SAFETY, P_BUILD_ISSUE, it)
        }

        // Non-critical warnings each cost a little (capped so they can't dominate).
        val minorWarnings = warnings.count { it.severity != WarningSeverity.CRITICAL }
        val minorPenalty = (minorWarnings * P_WARNING).coerceAtMost(15)
        if (minorPenalty > 0) {
            penalties += ScorePenalty(
                ScoreDimension.ACCESSIBILITY, minorPenalty,
                "$minorWarnings non-critical warning(s)."
            )
        }

        val total = (100 - penalties.sumOf { it.points }).coerceIn(0, 100)

        return ReleaseReadinessScore(
            score = total,
            penalties = penalties.sortedByDescending { it.points },
            criticalIssues = critical.size,
            warnings = warnings.size,
            missingTags = missingTags.size,
            duplicateTags = dupTags.size,
            failedApis = failed.size,
            slowApis = slow.size
        )
    }
}
