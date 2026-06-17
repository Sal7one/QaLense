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

/**
 * Runs QA macros — named step lists from the `.appsal` config, fired with one tap from the
 * minimal panel or the Control Room. Step DSL (one step per line, case-insensitive):
 *
 *   deeplink <uri>         open a deep link
 *   wait <ms>              pause
 *   tap <target>           tap a component (target = test tag, else visible text/description)
 *   type <target> <text>   set a text field's value (target = test tag, else label)
 *   record [video]         start a recording        stop          stop it
 *   screenshot             save annotated screenshot to Photos
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

    fun run(macro: AppSalMacro) {
        if (running) { QaLens.log("Macro already running"); return }
        running = true
        QaLens.appContext?.let { QaLensAppSal.recordMacroUse(it, macro.name) }
        QaLens.breadcrumb("▶ Macro started: ${macro.name}")
        scope.launch {
            try {
                for (raw in macro.steps) {
                    val step = raw.trim()
                    if (step.isBlank()) continue
                    val verb = step.substringBefore(' ').lowercase()
                    val arg = step.substringAfter(' ', "").trim()
                    val ok = when (verb) {
                        "deeplink" -> { QaLens.navigate(arg); true }
                        "wait" -> { delay(arg.toLongOrNull()?.coerceIn(0, 30_000) ?: 500L); true }
                        "tap" -> tap(arg)
                        "type" -> type(arg.substringBefore(' '), arg.substringAfter(' ', "").trim())
                        "record" -> { QaLens.startRecording(video = arg.equals("video", ignoreCase = true)); true }
                        "stop" -> { QaLens.stopRecording(); true }
                        "screenshot" -> { QaLens.takeScreenshot(share = false); true }
                        "mark" -> { QaLens.breadcrumb("⭐ ${arg.ifBlank { "Marked by QA" }}"); true }
                        else -> { QaLens.log("Macro '${macro.name}': unknown step '$step' (skipped)"); true }
                    }
                    if (!ok) {
                        QaLens.breadcrumb("✕ Macro '${macro.name}' stopped at: $step")
                        return@launch
                    }
                    // Small breather between steps so navigation/recomposition can settle.
                    delay(150)
                }
                QaLens.breadcrumb("■ Macro finished: ${macro.name}")
            } finally {
                running = false
            }
        }
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
}
