package com.qalens

/**
 * Session diff engine (B11). Compares two lightweight session projections to answer "what changed?"
 * — the most common QA question. Pure Kotlin, fully testable.
 *
 * Used by the web player's compare mode (C13), the Android player's diff summary, and
 * `sal_report.js --compare` (CI gate).
 */
object QaLensDiff {

    /** A lightweight projection of a `.sal` session — enough to diff without loading the full archive. */
    data class SalSessionSummary(
        val appName: String,
        val appVersion: String,
        val environment: String?,
        val durationMs: Long,
        val score: Int?,
        val likelyOwner: String?,
        val screensVisited: List<String>,
        val failedRequests: List<String>,   // method + shortUrl
        val errorCount: Int,
        val crashCount: Int,
        val anomalyTitles: List<String>
    )

    /** The result of comparing two sessions. */
    data class SalDiff(
        val scoreDelta: Int,                    // positive = improved, negative = regressed
        val likelyOwnerChanged: Boolean,
        val likelyOwnerBefore: String?,
        val likelyOwnerAfter: String?,
        val addedScreens: List<String>,
        val removedScreens: List<String>,
        val addedFailedRequests: List<String>,
        val resolvedFailedRequests: List<String>,
        val errorCountDelta: Int,
        val crashCountDelta: Int,
        val newAnomalies: List<String>,
        val resolvedAnomalies: List<String>,
        val durationDeltaMs: Long
    ) {
        /** True if the diff shows a regression (lower score, new failures, or new crashes). */
        val isRegression: Boolean get() =
            scoreDelta < 0 || addedFailedRequests.isNotEmpty() || crashCountDelta > 0

        /** A one-line summary suitable for a CI gate or a ticket. */
        val summary: String get() = buildString {
            if (scoreDelta != 0) append("score ${if (scoreDelta > 0) "+" else ""}$scoreDelta")
            if (addedFailedRequests.isNotEmpty()) append(", +${addedFailedRequests.size} failed")
            if (resolvedFailedRequests.isNotEmpty()) append(", -${resolvedFailedRequests.size} resolved")
            if (crashCountDelta != 0) append(", ${if (crashCountDelta > 0) "+" else ""}$crashCountDelta crashes")
            if (likelyOwnerChanged) append(", owner: $likelyOwnerBefore → $likelyOwnerAfter")
            if (isEmpty()) append("no significant changes")
        }
    }

    /**
     * Compare [baseline] (the known-good session) with [current] (the session under test).
     * Returns a [SalDiff] describing what changed.
     */
    fun diff(baseline: SalSessionSummary, current: SalSessionSummary): SalDiff {
        val baseScreens = baseline.screensVisited.toSet()
        val currScreens = current.screensVisited.toSet()
        val baseFailed = baseline.failedRequests.toSet()
        val currFailed = current.failedRequests.toSet()
        val baseAnomalies = baseline.anomalyTitles.toSet()
        val currAnomalies = current.anomalyTitles.toSet()

        return SalDiff(
            scoreDelta = (current.score ?: 0) - (baseline.score ?: 0),
            likelyOwnerChanged = baseline.likelyOwner != current.likelyOwner,
            likelyOwnerBefore = baseline.likelyOwner,
            likelyOwnerAfter = current.likelyOwner,
            addedScreens = (currScreens - baseScreens).toList(),
            removedScreens = (baseScreens - currScreens).toList(),
            addedFailedRequests = (currFailed - baseFailed).toList(),
            resolvedFailedRequests = (baseFailed - currFailed).toList(),
            errorCountDelta = current.errorCount - baseline.errorCount,
            crashCountDelta = current.crashCount - baseline.crashCount,
            newAnomalies = (currAnomalies - baseAnomalies).toList(),
            resolvedAnomalies = (baseAnomalies - currAnomalies).toList(),
            durationDeltaMs = current.durationMs - baseline.durationMs
        )
    }

    /** Build a [SalSessionSummary] from a parsed `.sal` manifest + analysis (used by web + CLI). */
    fun summaryFromSal(
        manifest: SalManifest,
        score: Int?,
        likelyOwner: String?,
        screensVisited: List<String>,
        failedRequests: List<String>,
        errorCount: Int,
        crashCount: Int,
        anomalyTitles: List<String>
    ): SalSessionSummary = SalSessionSummary(
        appName = manifest.appName,
        appVersion = manifest.appVersion,
        environment = manifest.environment,
        durationMs = manifest.durationMs,
        score = score,
        likelyOwner = likelyOwner,
        screensVisited = screensVisited,
        failedRequests = failedRequests,
        errorCount = errorCount,
        crashCount = crashCount,
        anomalyTitles = anomalyTitles
    )

    /** Render a diff as markdown (for `sal_report.js --compare` and the web player's export). */
    fun diffMarkdown(baseline: SalSessionSummary, current: SalSessionSummary, diff: SalDiff): String = buildString {
        appendLine("## Session Diff: ${baseline.appName} ${baseline.appVersion}")
        appendLine()
        appendLine("| | Baseline | Current |")
        appendLine("|---|---|---|")
        appendLine("| Score | ${baseline.score ?: "—"} | ${current.score ?: "—"} (${if (diff.scoreDelta >= 0) "+" else ""}${diff.scoreDelta}) |")
        appendLine("| Duration | ${baseline.durationMs / 1000}s | ${current.durationMs / 1000}s |")
        appendLine("| Errors | ${baseline.errorCount} | ${current.errorCount} |")
        appendLine("| Crashes | ${baseline.crashCount} | ${current.crashCount} |")
        appendLine("| Likely owner | ${baseline.likelyOwner ?: "—"} | ${current.likelyOwner ?: "—"} |")
        appendLine()
        if (diff.addedFailedRequests.isNotEmpty()) {
            appendLine("### New failures (not in baseline)")
            diff.addedFailedRequests.forEach { appendLine("- `$it`") }
            appendLine()
        }
        if (diff.resolvedFailedRequests.isNotEmpty()) {
            appendLine("### Resolved (fixed since baseline)")
            diff.resolvedFailedRequests.forEach { appendLine("- `$it`") }
            appendLine()
        }
        if (diff.addedScreens.isNotEmpty()) {
            appendLine("### New screens visited")
            diff.addedScreens.forEach { appendLine("- $it") }
            appendLine()
        }
        if (diff.newAnomalies.isNotEmpty()) {
            appendLine("### New anomalies")
            diff.newAnomalies.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("**Summary:** ${diff.summary}")
        if (diff.isRegression) appendLine("> ⚠ **Regression detected** — score dropped, new failures, or new crashes.")
    }
}
