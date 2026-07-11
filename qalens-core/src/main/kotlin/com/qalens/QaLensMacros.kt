package com.qalens

/**
 * B15: Macro assertions and conditional steps.
 *
 * A macro is a sequence of steps. Each step either interacts with the app
 * (tap, scroll, type, wait) or asserts a condition (exists, route, network).
 * An `IfStep` evaluates a condition and branches to a different sub-step.
 *
 * `MacroEngine.execute()` walks the steps against the current UI state,
 * collecting pass/fail for each step into a [MacroResult].
 */
sealed class MacroStep {
    data class Tap(val selector: String) : MacroStep()
    data class Type(val selector: String, val text: String) : MacroStep()
    data class Scroll(val selector: String, val direction: String = "down") : MacroStep()
    data class Wait(val seconds: Double) : MacroStep()
    data class AssertExists(val selector: String) : MacroStep()
    data class AssertRoute(val expected: String) : MacroStep()
    data class AssertNetwork(val urlContains: String, val expectStatus: Int = 0) : MacroStep()
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
    data class NoErrors(val kind: ErrorKind? = null) : MacroCondition()
    data class ScoreAbove(val threshold: Double) : MacroCondition()
}

/** Result of one step in a macro run. */
data class StepResult(
    val step: MacroStep,
    val passed: Boolean,
    val message: String = ""
)

/** Overall result of executing a macro. */
data class MacroResult(
    val allPassed: Boolean,
    val steps: List<StepResult>,
    val errorCount: Int = steps.count { !it.passed }
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
        return MacroResult(
            allPassed = results.all { it.passed },
            steps = results,
            errorCount = results.count { !it.passed }
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
}
