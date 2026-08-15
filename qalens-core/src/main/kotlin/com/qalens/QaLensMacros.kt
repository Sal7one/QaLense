package com.qalens

/**
 * B15: Macro assertions and conditional steps.
 *
 * A macro is a sequence of steps. Each step either interacts with the app
 * (tap, scroll, type, wait) or asserts a condition (exists, route, network, label).
 * An `IfStep` evaluates a condition and branches to a different sub-step.
 *
 * `MacroEngine.execute()` walks the steps against the current UI state,
 * collecting pass/fail for each step into a [MacroResult]. A [MacroResult] also
 * carries the subset of *failed assertion* steps ([AssertionFailure]) so callers
 * can surface them directly, and can be flattened into ASSERTION timeline events
 * via [MacroResult.toTimelineEvents].
 */
sealed class MacroStep {
    data class Tap(val selector: String) : MacroStep()
    data class Type(val selector: String, val text: String) : MacroStep()
    data class Scroll(val selector: String, val direction: String = "down") : MacroStep()
    data class Wait(val seconds: Double) : MacroStep()
    data class AssertExists(val selector: String) : MacroStep()
    data class AssertRoute(val expected: String) : MacroStep()
    data class AssertNetwork(val urlContains: String, val expectStatus: Int = 0) : MacroStep()
    data class AssertLabel(val tag: String, val text: String) : MacroStep()
    object AssertNoNetworkErrors : MacroStep()
    data class AssertNoSlowNetwork(val slowThresholdMs: Long = 2000) : MacroStep()
    data class If(
        val condition: MacroCondition,
        val thenStep: MacroStep,
        val elseStep: MacroStep? = null
    ) : MacroStep()
}

/** Condition evaluated by [MacroStep.If]. */
sealed class MacroCondition {
    data class Exists(val selector: String) : MacroCondition()
    data class RouteEquals(val expected: String) : MacroCondition()
    data class NetworkOk(val urlContains: String) : MacroCondition()
    data class NoSlowRequests(val slowThresholdMs: Long) : MacroCondition()
    data class NoErrors(val kind: ErrorKind? = null) : MacroCondition()
    data class ScoreAbove(val threshold: Double) : MacroCondition()
}

/** Result of one step in a macro run. */
data class StepResult(
    val step: MacroStep,
    val passed: Boolean,
    val message: String = ""
)

/** A single failed assertion step in a macro run (interaction steps are excluded). */
data class AssertionFailure(
    val step: MacroStep,
    val message: String
)

/** Overall result of executing a macro. */
data class MacroResult(
    val allPassed: Boolean,
    val steps: List<StepResult>,
    val errorCount: Int = steps.count { !it.passed },
    /** Failed assertion steps only (not interaction steps). Computed by [MacroEngine.execute]. */
    val failures: List<AssertionFailure> = emptyList()
)

object MacroEngine {

    /**
     * Execute [steps] against [state], returning a [MacroResult].
     *
     * Step matching is best-effort — the engine checks the current snapshot
     * without actually driving UI interactions. For real automation, pair
     * this with AccessibilityService tap/type commands.
     */
    fun execute(
        steps: List<MacroStep>,
        state: QaLensUiState,
        config: QaLensConfig = QaLensConfig()
    ): MacroResult {
        val results = steps.map { evaluate(it, state, config) }
        val failures = results
            .filter { !it.passed && isAssertion(it.step) }
            .map { AssertionFailure(it.step, it.message) }
        return MacroResult(
            allPassed = results.all { it.passed },
            steps = results,
            errorCount = results.count { !it.passed },
            failures = failures
        )
    }

    private fun evaluate(
        step: MacroStep,
        state: QaLensUiState,
        config: QaLensConfig
    ): StepResult = when (step) {
        is MacroStep.AssertExists -> {
            val found = state.nodes.any { matchesSelector(it, step.selector) }
            StepResult(step, found,
                if (found) "Found '${step.selector}'" else "Missing '${step.selector}'")
        }

        is MacroStep.AssertLabel -> {
            val found = state.nodes.any { node ->
                matchesSelector(node, step.tag) && nodeContainsText(node, step.text)
            }
            StepResult(step, found,
                if (found) "Label '${step.tag}' contains '${step.text}'"
                else "Label '${step.tag}' does not contain '${step.text}'")
        }

        is MacroStep.AssertRoute -> {
            val current = state.screen.route
            val ok = current == step.expected
            StepResult(step, ok,
                if (ok) "Route is '${step.expected}'" else "Expected '${step.expected}', got '$current'")
        }

        is MacroStep.AssertNetwork -> {
            val ok = state.networkEvents.any { ne ->
                ne.url.contains(step.urlContains) &&
                    (step.expectStatus == 0 || ne.status == step.expectStatus)
            }
            StepResult(step, ok,
                if (ok) "Network call to '${step.urlContains}' found"
                else "No matching network call to '${step.urlContains}'")
        }

        is MacroStep.AssertNoNetworkErrors -> {
            val errors = state.networkEvents.count { it.isError }
            val ok = errors == 0
            StepResult(step, ok,
                if (ok) "No network errors" else "Found $errors network error(s)")
        }

        is MacroStep.AssertNoSlowNetwork -> {
            val slow = state.networkEvents.filter { isSlow(it, step.slowThresholdMs) }
            val ok = slow.isEmpty()
            StepResult(step, ok,
                if (ok) "No slow network calls"
                else "Found ${slow.size} slow network call(s) (> ${step.slowThresholdMs}ms)")
        }

        is MacroStep.If -> {
            val condMet = evaluateCondition(step.condition, state, config)
            val target = if (condMet) step.thenStep else step.elseStep
            if (target != null) evaluate(target, state, config)
            else StepResult(step, condMet,
                if (condMet) "Condition met, no else branch" else "Condition not met, no else branch")
        }

        // Interaction steps (tap/type/scroll/wait) are recorded as "pending"
        // — they need a real driver to execute.
        is MacroStep.Tap, is MacroStep.Type, is MacroStep.Scroll -> {
            StepResult(step, false, "Interaction step requires a driver (not executed in snapshot mode)")
        }
        is MacroStep.Wait -> {
            StepResult(step, true, "Waited ${step.seconds}s")
        }
    }

    private fun evaluateCondition(
        condition: MacroCondition,
        state: QaLensUiState,
        @Suppress("UNUSED_PARAMETER") config: QaLensConfig
    ): Boolean = when (condition) {
        is MacroCondition.Exists -> state.nodes.any { matchesSelector(it, condition.selector) }
        is MacroCondition.RouteEquals -> state.screen.route == condition.expected
        is MacroCondition.NetworkOk -> state.networkEvents.any {
            it.url.contains(condition.urlContains) && !it.isError
        }
        is MacroCondition.NoSlowRequests -> state.networkEvents.none { isSlow(it, condition.slowThresholdMs) }
        is MacroCondition.NoErrors -> {
            val errors = if (condition.kind != null) state.errors.filter { it.kind == condition.kind } else state.errors
            errors.isEmpty()
        }
        is MacroCondition.ScoreAbove -> {
            state.score?.let { it.score >= condition.threshold } == true
        }
    }

    private fun matchesSelector(node: InspectNode, selector: String): Boolean {
        val s = selector.trim()
        return when {
            s.startsWith("tag:") -> node.testTag == s.removePrefix("tag:")
            s.startsWith("role:") -> node.role == s.removePrefix("role:")
            s.startsWith("qa:") -> node.qaName == s.removePrefix("qa:")
            else -> node.label.contains(s, ignoreCase = true) ||
                node.testTag?.contains(s, ignoreCase = true) == true ||
                node.text.any { it.contains(s, ignoreCase = true) }
        }
    }

    private fun nodeContainsText(node: InspectNode, text: String): Boolean =
        node.label.contains(text, ignoreCase = true) ||
            node.text.any { it.contains(text, ignoreCase = true) }

    /** A request is "slow" when it succeeded but exceeded [slowThresholdMs]. */
    private fun isSlow(event: NetworkEvent, slowThresholdMs: Long): Boolean =
        !event.isError && event.latencyMs > slowThresholdMs

    /** The assertion steps that feed [MacroResult.failures]. [MacroStep.AssertNetwork] is intentionally excluded. */
    private fun isAssertion(step: MacroStep): Boolean = when (step) {
        is MacroStep.AssertExists,
        is MacroStep.AssertLabel,
        is MacroStep.AssertRoute,
        is MacroStep.AssertNoNetworkErrors,
        is MacroStep.AssertNoSlowNetwork -> true
        else -> false
    }
}

// ── DSL parser ───────────────────────────────────────────────────────────────

private val LINE_WS = Regex("\\s+")

/**
 * Parses a single macro DSL line into a [MacroStep], or null when the line is not a
 * recognized assertion. Verbs are case-insensitive.
 *
 * Supported forms:
 *  - `assert exists <tag|text>`  (an optional `tag:` prefix is stripped)
 *  - `assert label <tag> <text>`
 *  - `assert route <route>`
 *  - `assert network no-errors`
 *  - `assert network no-slow [thresholdMs]`
 */
fun parseMacroLine(line: String): MacroStep? {
    val parts = line.trim().split(LINE_WS)
    if (parts.size < 2 || !parts[0].equals("assert", ignoreCase = true)) return null
    return when (parts[1].lowercase()) {
        "exists" -> {
            val selector = parts.drop(2).joinToString(" ")
            if (selector.isBlank()) null else MacroStep.AssertExists(stripTagPrefix(selector))
        }
        "label" -> {
            if (parts.size < 4) null
            else MacroStep.AssertLabel(parts[2], parts.drop(3).joinToString(" "))
        }
        "route" -> {
            val route = parts.drop(2).joinToString(" ")
            if (route.isBlank()) null else MacroStep.AssertRoute(route)
        }
        "network" -> when {
            parts.size >= 3 && parts[2].equals("no-errors", ignoreCase = true) ->
                MacroStep.AssertNoNetworkErrors
            parts.size >= 3 && parts[2].equals("no-slow", ignoreCase = true) ->
                MacroStep.AssertNoSlowNetwork(parts.getOrNull(3)?.toLongOrNull() ?: 2000L)
            else -> null
        }
        else -> null
    }
}

/**
 * Parses a macro script into a flat list of [MacroStep]s.
 *
 * Blocks use one step per branch:
 *  - `if <cond>` / <then-step> / `else` / <else-step>  (four lines), or
 *  - `if <cond>` / <then-step> / `else <else-step>`       (three lines).
 *
 * Blank lines are skipped, assertion lines map via [parseMacroLine], and unknown
 * non-assertion lines are dropped.
 */
fun parseMacroScript(lines: List<String>): List<MacroStep> {
    val steps = mutableListOf<MacroStep>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i].trim()
        if (line.isEmpty()) {
            i++
            continue
        }
        if (!line.startsWith("if ", ignoreCase = true)) {
            parseMacroLine(line)?.let { steps += it }
            i++
            continue
        }

        // 'if <cond>' block.
        val cond = parseCondition(line.substringAfter(' ').trim())
        val thenIdx = nextNonBlank(lines, i + 1)
        val thenStep = if (thenIdx < lines.size) parseMacroLine(lines[thenIdx].trim()) else null

        if (cond == null || thenStep == null) {
            // Malformed block: drop the 'if' and its consumed then-line.
            i = if (thenIdx < lines.size) thenIdx + 1 else i + 1
            continue
        }

        var elseStep: MacroStep? = null
        var next = thenIdx + 1
        val elseIdx = nextNonBlank(lines, next)
        if (elseIdx < lines.size) {
            val elseLine = lines[elseIdx].trim()
            when {
                elseLine.equals("else", ignoreCase = true) -> {
                    val stepIdx = nextNonBlank(lines, elseIdx + 1)
                    elseStep = if (stepIdx < lines.size) parseMacroLine(lines[stepIdx].trim()) else null
                    next = stepIdx + 1
                }
                elseLine.startsWith("else ", ignoreCase = true) -> {
                    elseStep = parseMacroLine(elseLine.substringAfter(' ').trim())
                    next = elseIdx + 1
                }
                else -> next = elseIdx
            }
        }

        steps += MacroStep.If(cond, thenStep, elseStep)
        i = next
    }
    return steps
}

private fun nextNonBlank(lines: List<String>, from: Int): Int {
    var i = from
    while (i < lines.size && lines[i].trim().isEmpty()) i++
    return i
}

private fun parseCondition(text: String): MacroCondition? {
    val parts = text.trim().split(LINE_WS)
    if (parts.isEmpty() || parts[0].isBlank()) return null
    return when (parts[0].lowercase()) {
        "exists" -> {
            val selector = parts.drop(1).joinToString(" ")
            if (selector.isBlank()) null else MacroCondition.Exists(stripTagPrefix(selector))
        }
        "route" -> {
            val route = parts.drop(1).joinToString(" ")
            if (route.isBlank()) null else MacroCondition.RouteEquals(route)
        }
        "network" -> when {
            parts.size >= 2 && parts[1].equals("ok", ignoreCase = true) ->
                MacroCondition.NetworkOk(parts.drop(2).joinToString(" "))
            parts.size >= 2 && parts[1].equals("no-slow", ignoreCase = true) ->
                MacroCondition.NoSlowRequests(parts.getOrNull(2)?.toLongOrNull() ?: 2000L)
            else -> null
        }
        "no-errors" -> MacroCondition.NoErrors()
        "score" -> {
            if (parts.size >= 3 && parts[1].equals("above", ignoreCase = true)) {
                parts[2].toDoubleOrNull()?.let { MacroCondition.ScoreAbove(it) }
            } else null
        }
        else -> null
    }
}

private fun stripTagPrefix(selector: String): String =
    if (selector.startsWith("tag:", ignoreCase = true)) selector.substring(4) else selector

// ── Timeline projection ──────────────────────────────────────────────────────

/**
 * Flattens a [MacroResult] into ASSERTION timeline events at [baseTimestampMillis].
 * Each failed assertion becomes a failing event; a fully-passing, non-empty run
 * becomes a single passing event.
 */
fun MacroResult.toTimelineEvents(baseTimestampMillis: Long): List<TimelineEvent> {
    if (failures.isNotEmpty()) {
        return failures.map { f ->
            TimelineEvent(
                timestampMillis = baseTimestampMillis,
                kind = TimelineKind.ASSERTION,
                title = "Assertion failed: ${assertionTarget(f.step)}",
                detail = f.message,
                isError = true
            )
        }
    }
    if (allPassed && steps.isNotEmpty()) {
        return listOf(
            TimelineEvent(
                timestampMillis = baseTimestampMillis,
                kind = TimelineKind.ASSERTION,
                title = "All macro assertions passed",
                detail = null,
                isError = false
            )
        )
    }
    return emptyList()
}

private fun assertionTarget(step: MacroStep): String = when (step) {
    is MacroStep.AssertExists -> step.selector
    is MacroStep.AssertLabel -> "${step.tag} '${step.text}'"
    is MacroStep.AssertRoute -> step.expected
    is MacroStep.AssertNoNetworkErrors -> "network no-errors"
    is MacroStep.AssertNoSlowNetwork -> "network no-slow"
    else -> step.toString()
}
