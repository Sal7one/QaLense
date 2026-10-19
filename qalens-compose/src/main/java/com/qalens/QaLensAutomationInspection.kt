package com.qalens

import android.app.Activity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull

/** Copy public values on main, then redact/search/generate XML and selectors on a worker. */
internal object QaLensAutomationInspection {
    data class Capture(val nodes: List<QaLensSelectorNode>, val omitted: Int, val config: QaLensConfig) {
        fun redacted(): List<QaLensSelectorNode> = nodes.map { node -> node.copy(attributes = node.attributes.mapValues { (key, value) ->
            if (key in setOf("enabled", "selected", "heading", "clickable", "focusable", "tap", "type", "scroll")) value
            else QaLensSelectors.xmlValue(config.redact(value).take(2_048))
        }) }
    }

    fun capture(activity: Activity): Capture {
        if (!QaLens.config.value.enabled || !QaLens.config.value.enableSemanticsReflection) throw QaLensBridgeFailure(403, "Host disabled semantics inspection")
        val visible = QaLensActivityInstaller.readVisibleNodes(activity.window.decorView).filterNot { it.hiddenFromReports }
        val raw = QaLensActivityInstaller.rawSemanticsNodes(activity).associateBy(QaLensActivityInstaller::semanticsId)
        return capture(visible, raw)
    }

    fun capture(visible: List<InspectNode>, raw: Map<String, SemanticsNode>): Capture {
        val included = visible.take(QaLensSelectors.MAX_NODES).map { it.id }.toSet()
        val values = visible.take(QaLensSelectors.MAX_NODES).map { model ->
            val node = raw[model.id]
            val protected = QaLensBridgeComponents.password(node)
            var parent = node?.parent
            val seen = mutableSetOf(model.id)
            while (parent != null && QaLensActivityInstaller.semanticsId(parent) !in included && seen.size < 1_000) {
                if (!seen.add(QaLensActivityInstaller.semanticsId(parent))) { parent = null; break }
                parent = parent.parent
            }
            QaLensSelectorNode(model.id, parent?.let(QaLensActivityInstaller::semanticsId)?.takeIf(included::contains), buildMap {
                model.testTag?.let { put("tag", it) }
                put("label", if (protected) "[protected]" else model.label.takeUnless { it == model.id } ?: "Component")
                model.role?.let { put("role", it) }
                if (!protected) {
                    if (model.text.isNotEmpty()) put("text", model.text.take(8).joinToString("\n"))
                    if (model.contentDescription.isNotEmpty()) put("description", model.contentDescription.take(4).joinToString("\n"))
                }
                put("enabled", model.isEnabled.toString()); put("selected", model.isSelected.toString())
                put("heading", model.isHeading.toString()); put("clickable", model.isClickable.toString())
                put("focusable", model.isFocusable.toString())
                put("tap", (node?.config?.contains(SemanticsActions.OnClick) == true).toString())
                put("type", (node?.config?.contains(SemanticsActions.SetText) == true).toString())
                put("scroll", (node?.config?.contains(SemanticsActions.ScrollBy) == true).toString())
            })
        }
        return Capture(values, visible.size - values.size, QaLens.config.value)
    }

    fun suggestions(nodes: List<QaLensSelectorNode>, id: String): List<Map<String, Any?>> =
        QaLensSelectors.suggest(nodes, id).map { mapOf("title" to it.title, "xpath" to it.xpath, "matches" to it.matches, "stability" to it.stability) }
}
