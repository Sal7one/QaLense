package com.qalens

/** Quality record for one screen accumulated across a QA session. Lives in memory only. */
data class ScreenQualitySnapshot(
    val key: String,
    val screenName: String,
    val route: String?,
    val firstVisitedMs: Long,
    val lastVisitedMs: Long,
    val visitCount: Int,
    val latestScore: Int,
    val bestScore: Int,
    val worstScore: Int,
    val criticalIssues: Int,
    val warnings: Int,
    val failedApis: Int,
    val slowApis: Int,
    val missingTags: Int
) {
    val status: String get() = when {
        worstScore < 50 || criticalIssues > 0 || failedApis > 0 -> "✕"
        latestScore < 75 -> "⚠"
        else -> "✓"
    }
}

/**
 * Pure, immutable store: each call folds a fresh observation into the existing map. The
 * orchestrator (QaLens) keeps the resulting map in its state so the UI stays reactive. Nothing
 * is written to disk — the map lives only for the process lifetime.
 */
object ScreenQualityStore {

    fun keyFor(screen: ScreenSnapshot): String =
        screen.route?.takeIf { it.isNotBlank() }
            ?: screen.screenName?.takeIf { it.isNotBlank() }
            ?: screen.activityName.ifBlank { "unknown" }

    fun record(
        existing: Map<String, ScreenQualitySnapshot>,
        screen: ScreenSnapshot,
        score: ReleaseReadinessScore,
        newVisit: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): Map<String, ScreenQualitySnapshot> {
        val key = keyFor(screen)
        val prev = existing[key]
        val snapshot = if (prev == null) {
            ScreenQualitySnapshot(
                key = key,
                screenName = screen.displayName,
                route = screen.route,
                firstVisitedMs = nowMs,
                lastVisitedMs = nowMs,
                visitCount = 1,
                latestScore = score.score,
                bestScore = score.score,
                worstScore = score.score,
                criticalIssues = score.criticalIssues,
                warnings = score.warnings,
                failedApis = score.failedApis,
                slowApis = score.slowApis,
                missingTags = score.missingTags
            )
        } else {
            prev.copy(
                screenName = screen.displayName,
                route = screen.route ?: prev.route,
                lastVisitedMs = nowMs,
                visitCount = prev.visitCount + if (newVisit) 1 else 0,
                latestScore = score.score,
                bestScore = maxOf(prev.bestScore, score.score),
                worstScore = minOf(prev.worstScore, score.score),
                criticalIssues = maxOf(prev.criticalIssues, score.criticalIssues),
                warnings = maxOf(prev.warnings, score.warnings),
                failedApis = maxOf(prev.failedApis, score.failedApis),
                slowApis = maxOf(prev.slowApis, score.slowApis),
                missingTags = maxOf(prev.missingTags, score.missingTags)
            )
        }
        return existing + (key to snapshot)
    }

    /** Session-wide average of latest scores, or null when nothing has been visited. */
    fun sessionScore(map: Map<String, ScreenQualitySnapshot>): Int? =
        map.values.takeIf { it.isNotEmpty() }?.map { it.latestScore }?.average()?.toInt()
}
