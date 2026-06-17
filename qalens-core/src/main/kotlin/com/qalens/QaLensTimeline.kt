package com.qalens

/**
 * A single, human-readable entry in the reproduction timeline. Timeline events are *derived*
 * from data QaLens actually observed (navigation, network, manual events, scans) — QaLens never
 * fabricates interactions it could not see. See [TimelineMerger] for the honest source list.
 */
data class TimelineEvent(
    val timestampMillis: Long,
    val kind: TimelineKind,
    val title: String,
    val detail: String? = null,
    val isError: Boolean = false
)

enum class TimelineKind {
    SCREEN,      // screen / route became active
    NAVIGATION,  // explicit navigation event
    ACTION,      // app-reported business event (QaLens.event)
    NETWORK,     // an API call
    ERROR,       // a failure (network 4xx/5xx, error log, or app-reported)
    LOG,         // generic log / breadcrumb
    SCREENSHOT   // evidence captured
}

/**
 * Merges the separate event streams QaLens collects into one timeline sorted by time.
 *
 * Honest sourcing — what feeds the timeline and what does NOT:
 *  - Navigation events: yes (via QaLensNavHost / QaLensNavigationObserver or manual setScreen).
 *  - Network calls: yes, *if* QaLensOkHttpInterceptor is installed.
 *  - Business events / logs / breadcrumbs: yes, from QaLens.event()/log()/breadcrumb().
 *  - Raw user taps and text input: NO — Compose exposes no global tap/keystroke stream without
 *    an AccessibilityService or per-widget instrumentation, so we do not invent them.
 */
object TimelineMerger {

    fun merge(
        events: List<QaEvent>,
        network: List<NetworkEvent>,
        config: QaLensConfig
    ): List<TimelineEvent> {
        fun r(s: String?) = config.redact(s.orEmpty())

        val fromEvents = events.map { e ->
            val msg = r(e.message)
            val isNav = e.type == QaEventType.BREADCRUMB &&
                (msg.startsWith("Navigation") || msg.startsWith("Navigate"))
            val looksError = msg.contains("error", true) || msg.contains("fail", true) ||
                msg.contains("exception", true) || msg.contains("crash", true)
            val kind = when {
                isNav -> TimelineKind.NAVIGATION
                e.type == QaEventType.EVENT -> if (looksError) TimelineKind.ERROR else TimelineKind.ACTION
                looksError -> TimelineKind.ERROR
                else -> TimelineKind.LOG
            }
            TimelineEvent(
                timestampMillis = e.timestampMillis,
                kind = kind,
                title = when (kind) {
                    TimelineKind.NAVIGATION -> msg.removePrefix("Navigation → ").removePrefix("Navigate intent → ")
                        .let { "Navigated to $it" }
                    TimelineKind.ACTION -> r(e.tag).ifBlank { msg }
                    else -> msg
                },
                detail = if (kind == TimelineKind.ACTION && e.tag != null && r(e.tag) != msg) msg else null,
                isError = kind == TimelineKind.ERROR
            )
        }

        val fromNetwork = network.map { n ->
            val path = QaLensRedactor.redactUrl(n.url, config.redactionRules)
            TimelineEvent(
                timestampMillis = n.timestampMillis,
                kind = if (n.isError) TimelineKind.ERROR else TimelineKind.NETWORK,
                title = "${n.method} ${shortPath(path)} ${n.statusLabel} ${n.latencyLabel}".trim(),
                detail = n.error,
                isError = n.isError
            )
        }

        return (fromEvents + fromNetwork).sortedBy { it.timestampMillis }
    }

    private fun shortPath(url: String): String = try {
        val afterScheme = url.substringAfter("://", url)
        "/" + afterScheme.substringAfter("/", afterScheme).substringBefore("?")
    } catch (_: Exception) { url }
}

/** Generated reproduction steps with an honest expected/actual split. */
data class ReproSteps(
    val steps: List<String>,
    val expected: String,
    val actual: String
) {
    val hasData: Boolean get() = steps.isNotEmpty() &&
        steps.firstOrNull()?.startsWith("No interactions") != true
}

/**
 * Turns a merged timeline into numbered reproduction steps. Conservative and honest: it only
 * emits steps for events it actually saw, and clearly says so when it has nothing.
 */
object ReproStepGenerator {

    fun generate(timeline: List<TimelineEvent>): ReproSteps {
        if (timeline.isEmpty()) {
            return ReproSteps(
                steps = listOf("No interactions captured yet. Reproduce the issue with QaLens running, then capture again."),
                expected = "—",
                actual = "—"
            )
        }

        val steps = mutableListOf<String>()
        timeline.forEach { e ->
            when (e.kind) {
                TimelineKind.SCREEN, TimelineKind.NAVIGATION -> steps += e.title
                TimelineKind.ACTION -> steps += "Trigger: ${e.title}"
                TimelineKind.NETWORK -> { /* network is context, not a user step */ }
                TimelineKind.ERROR -> steps += "Observe: ${e.title}"
                TimelineKind.LOG, TimelineKind.SCREENSHOT -> { /* omit from steps */ }
            }
        }

        val firstError = timeline.firstOrNull { it.isError }
        val numbered = steps.distinct().take(12).mapIndexed { i, s -> "${i + 1}. $s" }

        val (expected, actual) = if (firstError != null) {
            "The action should complete successfully, or surface a specific, handled error." to
                "Failure observed: ${firstError.title}${firstError.detail?.let { " ($it)" } ?: ""}."
        } else {
            "Flow should complete without errors." to
                "No error was captured in this session — add a description of what looked wrong."
        }

        return ReproSteps(numbered, expected, actual)
    }
}
