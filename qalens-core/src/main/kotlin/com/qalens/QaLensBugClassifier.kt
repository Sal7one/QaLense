package com.qalens

enum class BugCategory(val display: String) {
    BACKEND_API("Backend/API"),
    ANDROID_UI("Android/UI"),
    ANDROID_NAVIGATION("Android/Navigation"),
    ANDROID_STATE("Android/State"),
    ACCESSIBILITY("Accessibility"),
    AUTOMATION_TESTABILITY("Automation/Testability"),
    CONFIGURATION_ENVIRONMENT("Configuration/Environment"),
    PERFORMANCE("Performance"),
    UNKNOWN("Unknown")
}

enum class Confidence { LOW, MEDIUM, HIGH }

data class BugClassification(
    val category: BugCategory,
    val confidence: Confidence,
    val reasons: List<String>
) {
    companion object {
        val UNKNOWN = BugClassification(
            BugCategory.UNKNOWN, Confidence.LOW,
            listOf("Not enough signal captured to suggest an owner.")
        )
    }
}

/**
 * Suggests a *likely* owner for a bug from observed signals. Deliberately uses "likely" language
 * and returns [BugCategory.UNKNOWN] when evidence is weak — it never blames aggressively.
 */
object BugClassifier {

    private data class Candidate(val category: BugCategory, val confidence: Confidence, val reason: String)

    fun classify(
        network: List<NetworkEvent>,
        warnings: List<QaWarning>,
        screenHistory: List<String>,
        slowThresholdMs: Long = 2000L,
        buildSafetyIssues: List<String> = emptyList()
    ): BugClassification {
        val candidates = mutableListOf<Candidate>()

        // ── Network-driven ──────────────────────────────────────────────
        val failed = network.filter { it.isError }
        val server5xx = failed.firstOrNull { it.status in 500..599 }
        val client4xx = failed.firstOrNull { it.status in 400..499 }
        val transport = failed.firstOrNull { it.error != null }
        val slow = network.firstOrNull { it.latencyMs >= slowThresholdMs && !it.isError }

        // B5: a transport failure while the device was offline/losing connectivity is an
        // environment issue, not a backend bug. Reclassify before the generic transport rule.
        val connectivityFailure = failed.firstOrNull { it.failedDueToConnectivity }
        if (connectivityFailure != null) {
            candidates += Candidate(
                BugCategory.CONFIGURATION_ENVIRONMENT, Confidence.HIGH,
                "${connectivityFailure.method} ${connectivityFailure.shortUrl} failed while the device was offline — likely a connectivity drop, not a server bug."
            )
        }

        if (server5xx != null) {
            candidates += Candidate(
                BugCategory.BACKEND_API, Confidence.HIGH,
                "${server5xx.method} ${server5xx.shortUrl} returned ${server5xx.status} (server error)."
            )
        }
        if (client4xx != null) {
            candidates += Candidate(
                BugCategory.BACKEND_API, Confidence.MEDIUM,
                "${client4xx.method} ${client4xx.shortUrl} returned ${client4xx.status} (auth/validation/contract)."
            )
        }
        // Transport failures NOT caused by offline connectivity stay Backend/API (could be DNS,
        // TLS, server unreachable). Connectivity-caused ones are already classified above.
        if (transport != null && connectivityFailure == null) {
            candidates += Candidate(
                BugCategory.BACKEND_API, Confidence.MEDIUM,
                "${transport.method} ${transport.shortUrl} failed with ${transport.error}."
            )
        }
        if (slow != null) {
            candidates += Candidate(
                BugCategory.PERFORMANCE, Confidence.MEDIUM,
                "${slow.method} ${slow.shortUrl} took ${slow.latencyLabel} (over ${slowThresholdMs}ms threshold)."
            )
        }

        // ── Navigation anomalies ────────────────────────────────────────
        val recent = screenHistory.takeLast(6)
        val immediateDup = recent.zipWithNext().any { (a, b) -> a == b }
        val loop = recent.size >= 4 && recent.toSet().size <= 2
        if (immediateDup || loop) {
            candidates += Candidate(
                BugCategory.ANDROID_NAVIGATION, Confidence.MEDIUM,
                "Navigation pattern looks abnormal (duplicate or looping routes): ${recent.joinToString(" → ")}."
            )
        }

        // ── Accessibility ───────────────────────────────────────────────
        val criticalA11y = warnings.count { it.severity == WarningSeverity.CRITICAL }
        if (criticalA11y > 0) {
            candidates += Candidate(
                BugCategory.ACCESSIBILITY, if (criticalA11y >= 2) Confidence.MEDIUM else Confidence.LOW,
                "$criticalA11y critical accessibility issue(s) on the current screen."
            )
        }

        // ── Testability ─────────────────────────────────────────────────
        val missingTags = warnings.count { it.id == "QA_CLICKABLE_MISSING_TEST_TAG" }
        val dupTags = warnings.count { it.id == "QA_DUPLICATE_TEST_TAG" }
        if (missingTags >= 3 || dupTags > 0) {
            candidates += Candidate(
                BugCategory.AUTOMATION_TESTABILITY, Confidence.LOW,
                "Testability gaps: $missingTags clickable element(s) without test tags, $dupTags duplicate tag(s)."
            )
        }

        // ── Configuration / environment ─────────────────────────────────
        if (buildSafetyIssues.isNotEmpty()) {
            candidates += Candidate(
                BugCategory.CONFIGURATION_ENVIRONMENT, Confidence.MEDIUM,
                "Build/environment looks unsafe: ${buildSafetyIssues.first()}"
            )
        }

        // ── Client-side fallback: error logged but no network at all ─────
        if (candidates.isEmpty() && network.isEmpty()) {
            return BugClassification.UNKNOWN.copy(
                category = BugCategory.ANDROID_UI,
                reasons = listOf("No network activity captured. If a failure was seen, it is likely client-side (UI/state) — or the OkHttp interceptor is not installed.")
            )
        }

        if (candidates.isEmpty()) return BugClassification.UNKNOWN

        // Pick the strongest candidate; group reasons of the winning category.
        val winner = requireNotNull(candidates.maxByOrNull { it.confidence.ordinal }) { "No candidates to classify" }
        val reasons = candidates.filter { it.category == winner.category }.map { it.reason } +
            candidates.filter { it.category != winner.category }.map { "Also: ${it.reason}" }

        return BugClassification(winner.category, winner.confidence, reasons.take(5))
    }
}
