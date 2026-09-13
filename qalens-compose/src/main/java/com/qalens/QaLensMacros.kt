package com.qalens

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import com.qalens.android.AppSalMacro
import com.qalens.android.QaLensAppSal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** B15: outcome of the most recent run of a macro, keyed by macro name (in-memory only). */
internal data class MacroRunResult(val passed: Boolean, val assertionFailures: Int)

/**
 * Runs QA macros — named step lists from the `.appsal` config, fired with one tap from the
 * minimal panel or the Control Room. Step DSL (one step per line, case-insensitive):
 *
 *   deeplink <uri>         open a deep link
 *   wait <ms>              pause
 *   tap <target>           tap a component (target = test tag, else visible text/description)
 *   type <target> <text>   set a text field's value (target = test tag, else label)
 *   record [video]         start a recording        stop          stop it
 *   screenshot             save annotated screenshot to private app cache
 *   mark <text>            starred breadcrumb in the timeline
 *
 * `tap`/`type` drive the real Compose semantics actions — the same handlers UI tests invoke — so
 * a macro can complete an entire login (type user → type password → tap submit) unattended.
 * Both WAIT for the target to appear (up to 5s, polling) before acting, so steps survive screen
 * transitions and network latency. Failures log + breadcrumb but never crash the session.
 */
internal object QaLensMacros {

    private const val AWAIT_MS = 5_000L
    private const val POLL_MS = 250L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var running = false
    private var job: kotlinx.coroutines.Job? = null
    fun cancel() { job?.cancel() }

    /** In-memory record of each macro's most recent run (no persistence). */
    private val lastRun = mutableMapOf<String, MacroRunResult>()

    internal fun lastRunResult(name: String): MacroRunResult? = lastRun[name]

    fun run(macro: AppSalMacro) {
        if (!QaLens.config.value.enabled) return
        if (running) { QaLens.log("Macro already running"); return }
        running = true
        QaLens.appContext?.let { QaLensAppSal.recordMacroUse(it, macro.name) }
        QaLens.breadcrumb("▶ Macro started: ${macro.name}")
        job = scope.launch {
            var assertionFailures = 0
            var stoppedStep: String? = null
            try {
                loop@ for (raw in macro.steps) {
                    val step = raw.trim()
                    if (step.isBlank()) continue
                    val verb = step.substringBefore(' ').lowercase()

                    // ── B15: assertion line — evaluated against the live snapshot ──
                    if (verb == "assert" || verb == "if") {
                        val parsed = parseMacroLine(step)
                        if (parsed == null) {
                            stoppedStep = step
                            assertionFailures++
                            QaLens.log("Macro '${macro.name}': invalid assertion '$step'")
                            break@loop
                        }
                        QaLens.refreshInspection()
                        val result = MacroEngine.execute(listOf(parsed), QaLens.state.value, QaLens.config.value)
                        val stepResult = result.steps.firstOrNull()
                        val detail = stepResult?.message?.takeIf { it.isNotBlank() }
                        if (stepResult?.passed == true) {
                            QaLens.event("assertion", "assertion ✓ ${describe(parsed)}")
                        } else {
                            assertionFailures++
                            stoppedStep = step
                            QaLens.event("assertion", "assertion ✕ ${describe(parsed)}" + (detail?.let { " — $it" } ?: ""))
                            QaLens.breadcrumb("✕ Macro '${macro.name}' stopped at: $step")
                            break@loop
                        }
                        // Small breather so navigation/recomposition can settle.
                        delay(150)
                        continue
                    }

                    // Interaction step — unchanged driver behavior.
                    val arg = step.substringAfter(' ', "").trim()
                    val ok = when (verb) {
                        "deeplink" -> QaLens.navigateObserved(arg)
                        "wait" -> { delay(arg.toLongOrNull()?.coerceIn(0, 30_000) ?: 500L); true }
                        "tap" -> tap(arg)
                        "type" -> type(arg.substringBefore(' '), arg.substringAfter(' ', "").trim())
                        "record" -> {
                            val video = arg.equals("video", ignoreCase = true)
                            if (QaLens.state.value.isRecording || QaLens.state.value.isSavingRecording ||
                                QaLens.currentActivity == null || (video && !QaLens.config.value.allowUnmaskedVideo)) false
                            else { QaLens.startRecording(video); awaitOutcome(30_000) { QaLens.state.value.isRecording } }
                        }
                        "stop" -> {
                            if (!QaLens.state.value.isRecording) false
                            else {
                                val before = QaLens.state.value.recordings.map { it.path }.toSet()
                                QaLens.stopRecording()
                                awaitOutcome(30_000) { !QaLens.state.value.isSavingRecording && QaLens.state.value.recordings.any { it.path !in before } }
                            }
                        }
                        "screenshot" -> captureScreenshot()
                        "mark" -> { QaLens.breadcrumb("⭐ ${arg.ifBlank { "Marked by QA" }}"); true }
                        else -> { QaLens.log("Macro '${macro.name}': unknown step '$step'"); false }
                    }
                    if (!ok) {
                        stoppedStep = step
                        QaLens.breadcrumb("✕ Macro '${macro.name}' stopped at: $step")
                        break@loop
                    }
                    // Small breather between steps so navigation/recomposition can settle.
                    delay(150)
                }

                // ── B15: finish summary + last-run record ──
                if (stoppedStep == null) {
                    QaLens.breadcrumb("■ Macro finished: ${macro.name} ✓")
                    QaLens.log("Macro '${macro.name}' finished: all ${macro.steps.size} step(s) passed")
                    lastRun[macro.name] = MacroRunResult(passed = true, assertionFailures = 0)
                } else if (assertionFailures > 0) {
                    QaLens.breadcrumb("■ Macro finished: ${macro.name} ✕ $assertionFailures assertion${if (assertionFailures > 1) "s" else ""} failed")
                    QaLens.log("Macro '${macro.name}' finished: ✕ $assertionFailures assertion(s) failed (stopped at: $stoppedStep)")
                    lastRun[macro.name] = MacroRunResult(passed = false, assertionFailures = assertionFailures)
                } else {
                    QaLens.log("Macro '${macro.name}' failed: interaction step '$stoppedStep' failed")
                    lastRun[macro.name] = MacroRunResult(passed = false, assertionFailures = 0)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                lastRun[macro.name] = MacroRunResult(false, assertionFailures)
                throw cancelled
            } catch (failure: Exception) {
                lastRun[macro.name] = MacroRunResult(false, assertionFailures)
                QaLens.log("Macro failed: ${failure.javaClass.simpleName}")
            } finally {
                running = false
            }
        }
    }

    private suspend fun awaitOutcome(timeoutMs: Long, check: () -> Boolean): Boolean =
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            while (QaLens.config.value.enabled) { if (check()) return@withTimeoutOrNull true; delay(100) }
            false
        } ?: false

    private suspend fun captureScreenshot(): Boolean {
        val activity = QaLens.currentActivity ?: return false
        return kotlinx.coroutines.withTimeoutOrNull(15_000) {
            kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { continuation ->
                val state = QaLens.state.value
                QaLensScreenCapture.captureAndShare(activity, state.nodes, state.selectedNode, share = false) {
                    if (continuation.isActive) continuation.resumeWith(Result.success(it))
                }
            }
        } ?: false
    }

    // ── UI driver (semantics actions — what UI tests invoke) ────────────────

    /** target matches: exact test tag → visible text (contains) → content description (contains). */
    private fun matches(node: SemanticsNode, target: String): Boolean {
        val c = node.config
        if (c.getOrNull(SemanticsProperties.TestTag) == target) return true
        val text = c.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
        if (text.contains(target, ignoreCase = true) && text.isNotBlank()) return true
        val desc = c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
        return desc.contains(target, ignoreCase = true) && desc.isNotBlank()
    }

    /** Polls the live semantics tree until a matching node (with [requiredAction]) appears. */
    private suspend fun await(target: String, requiredAction: (SemanticsNode) -> Boolean): SemanticsNode? {
        val deadline = System.currentTimeMillis() + AWAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val activity = QaLens.currentActivity
            if (activity != null) {
                val hit = QaLensActivityInstaller.rawSemanticsNodes(activity)
                    .filter { matches(it, target) && requiredAction(it) }
                    // Smallest match wins — "Transfer" should hit the button, not the whole screen.
                    .minByOrNull { it.boundsInWindow.width * it.boundsInWindow.height }
                if (hit != null) return hit
            }
            delay(POLL_MS)
        }
        return null
    }

    private suspend fun tap(target: String): Boolean {
        if (target.isBlank()) { QaLens.log("Macro tap: missing target"); return false }
        val node = await(target) { it.config.getOrNull(SemanticsActions.OnClick) != null }
            ?: return fail("tap", target)
        val done = runCatching {
            node.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true
        }.getOrDefault(false)
        if (done) QaLens.breadcrumb("⊙ Macro tapped: $target") else fail("tap", target)
        return done
    }

    private suspend fun type(target: String, text: String): Boolean {
        if (target.isBlank()) { QaLens.log("Macro type: missing target"); return false }
        val node = await(target) { it.config.getOrNull(SemanticsActions.SetText) != null }
            ?: return fail("type", target)
        val done = runCatching {
            // Focus first when the field supports it (some fields ignore SetText unfocused).
            node.config.getOrNull(SemanticsActions.RequestFocus)?.action?.invoke()
            node.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString(text)) == true
        }.getOrDefault(false)
        if (done) QaLens.breadcrumb("⌨ Macro typed into $target") else fail("type", target)
        return done
    }

    private fun fail(verb: String, target: String): Boolean {
        QaLens.log("Macro $verb failed: '$target' not found within ${AWAIT_MS / 1000}s (check the test tag)")
        return false
    }

    // ── B15: assertion descriptions ────────────────────────────────────────

    /** Canonical human description of an assertion step, used in the ✓/✕ timeline event. */
    private fun describe(step: MacroStep): String = when (step) {
        is MacroStep.AssertExists -> "assert exists ${step.selector}"
        is MacroStep.AssertLabel -> "assert label ${step.tag} \"${step.text}\""
        is MacroStep.AssertRoute -> "assert route ${step.expected}"
        is MacroStep.AssertNetwork -> "assert network ${step.urlContains}" +
            if (step.expectStatus != 0) " (status ${step.expectStatus})" else ""
        is MacroStep.AssertNoNetworkErrors -> "assert no network errors"
        is MacroStep.AssertNoSlowNetwork -> "assert no slow network (${step.slowThresholdMs}ms)"
        is MacroStep.If -> "if ${describeCondition(step.condition)}"
        is MacroStep.Tap -> "tap ${step.selector}"
        is MacroStep.Type -> "type ${step.selector}"
        is MacroStep.Scroll -> "scroll ${step.selector}"
        is MacroStep.Wait -> "wait ${step.seconds}s"
        else -> step.toString()
    }

    private fun describeCondition(condition: MacroCondition): String = when (condition) {
        is MacroCondition.Exists -> "exists ${condition.selector}"
        is MacroCondition.RouteEquals -> "route == ${condition.expected}"
        is MacroCondition.NetworkOk -> "network ok ${condition.urlContains}"
        is MacroCondition.NoErrors -> "no errors"
        is MacroCondition.ScoreAbove -> "score >= ${condition.threshold}"
        else -> "condition"
    }
}
