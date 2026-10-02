package com.qalens

import android.app.Activity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Explicit component previews. No files, automatic captures, or arbitrary custom-value toString. */
internal object QaLensBridgeComponents {
    private val mutableStatus = MutableStateFlow("")
    val status = mutableStatus.asStateFlow()
    private val pending = linkedMapOf<String, Map<String, Any?>>()
    private var bytes = 0
    private var sequence = 0L
    private var dropped = 0
    private var session = java.util.UUID.randomUUID().toString()

    @Synchronized fun clear() {
        pending.clear(); bytes = 0; dropped = 0
        session = java.util.UUID.randomUUID().toString()
        mutableStatus.value = ""
    }
    fun report(message: String) { mutableStatus.value = message }
    @Synchronized fun enqueue(document: Map<String, Any?>) {
        val size = JSONObject(document).toString().toByteArray().size
        if (size > 256 * 1024) { report("Component exceeds 256 KiB; narrow its semantics"); return }
        while (pending.size >= 10 || bytes + size > 1024 * 1024) {
            val oldest = pending.keys.first()
            bytes -= JSONObject(pending.remove(oldest)!!).toString().toByteArray().size
            dropped++
        }
        pending["$session:${++sequence}"] = document
        bytes += size
        report("Queued for PC · review before saving")
    }
    @Synchronized fun inbox(): Map<String, Any?> = mapOf<String, Any?>("ok" to true, "session" to session,
        "dropped" to dropped, "transfers" to pending.map { (id, document) -> mapOf<String, Any?>("id" to id, "document" to document) })
    @Synchronized fun acknowledge(ids: List<String>) {
        ids.forEach { id -> pending.remove(id)?.let { bytes -= JSONObject(it).toString().toByteArray().size } }
    }

    fun password(node: SemanticsNode?): Boolean {
        var current = node
        var depth = 0
        while (current != null && depth++ < 64) {
            if (current.config.contains(SemanticsProperties.Password)) return true
            current = current.parent
        }
        return current != null // Conservative value masking when ancestry exceeds our budget.
    }

    fun capture(activity: Activity, target: InspectNode, visible: List<InspectNode>, raw: Map<String, SemanticsNode>): Map<String, Any?> {
        val node = raw[target.id] ?: throw QaLensBridgeFailure(409, "Component has no live semantics")
        val secret = password(node)
        val byId = visible.associateBy { it.id }
        fun summary(semantic: SemanticsNode): Map<String, Any?> {
            val model = byId[QaLensActivityInstaller.semanticsId(semantic)]
            val siblings = semantic.parent?.children?.filter { QaLensActivityInstaller.semanticsId(it) in byId }
                ?: raw.values.filter { it.parent == null && QaLensActivityInstaller.semanticsId(it) in byId }
            return mapOf<String, Any?>("tag" to model?.testTag, "role" to model?.role,
                "siblingIndex" to siblings.indexOfFirst { it.id == semantic.id && it.root === semantic.root })
        }
        val path = mutableListOf<Map<String, Any?>>()
        var parent: SemanticsNode? = node
        var traversed = 0
        while (parent != null && traversed++ < 64) {
            if (QaLensActivityInstaller.semanticsId(parent) in byId) path.add(summary(parent))
            parent = parent.parent
        }
        // Match actual public keys, not their names: a custom key can impersonate "Text".
        val safeKeys = with(SemanticsProperties) { setOf(TestTag, ContentDescription, StateDescription, Text, EditableText,
            Disabled, Selected, Heading, Focused, Password, Error, PaneTitle, LiveRegion, Role, ToggleableState, ImeAction,
            IsDialog, IsPopup, IsTraversalGroup, TraversalIndex, InvisibleToUser, SelectableGroup, IsEditable, MaxTextLength,
            TextSelectionRange, TextSubstitution, IsShowingTextSubstitution, ProgressBarRangeInfo, CollectionInfo, CollectionItemInfo, QaNameKey) }
        val suppressedNames = setOf("Text", "EditableText", "InputText", "ContentDescription", "StateDescription", "Error", "PaneTitle", "TextSelectionRange", "TextSubstitution", "QaLensName")
        val attributes = node.config.take(100).map { (key, value) ->
            val safe = when {
                secret && key.name in suppressedNames -> null
                key !in safeKeys -> null
                value is String -> value
                value is AnnotatedString -> value.text
                value is Boolean -> value
                value is Number -> value.takeIf { it.toDouble().isFinite() }
                value is Unit -> true
                value is TextRange -> mapOf<String, Any?>("start" to value.start, "end" to value.end)
                value is ProgressBarRangeInfo -> mapOf<String, Any?>("current" to value.current.takeIf { it.isFinite() },
                    "minimum" to value.range.start.takeIf { it.isFinite() }, "maximum" to value.range.endInclusive.takeIf { it.isFinite() }, "steps" to value.steps)
                value is CollectionInfo -> mapOf<String, Any?>("rows" to value.rowCount, "columns" to value.columnCount)
                value is CollectionItemInfo -> mapOf<String, Any?>("row" to value.rowIndex, "rowSpan" to value.rowSpan,
                    "column" to value.columnIndex, "columnSpan" to value.columnSpan)
                value is List<*> -> value.take(8).mapNotNull { when (it) { is String -> it; is AnnotatedString -> it.text; else -> null } }
                key.name in setOf("Role", "LiveRegion", "ToggleableState", "ImeAction") -> value.toString()
                else -> null
            }
            mapOf<String, Any?>("name" to key.name, "value" to safe, "coverage" to when {
                secret && key.name in suppressedNames -> "password omitted"
                key !in safeKeys || safe == null -> "name only; value unsupported"
                else -> "public semantics"
            })
        }
        val children = node.children.filter { QaLensActivityInstaller.semanticsId(it) in byId }
        return mapOf<String, Any?>("schema" to "qalens.component", "version" to 1,
            "capturedAtMillis" to System.currentTimeMillis(), "liveNodeId" to target.id,
            "content" to mapOf<String, Any?>("package" to activity.packageName, "activity" to activity.javaClass.name,
                "screen" to QaLens.state.value.screen.displayName, "route" to QaLens.state.value.screen.route,
                "viewport" to mapOf<String, Any?>("width" to activity.window.decorView.width, "height" to activity.window.decorView.height,
                    "density" to activity.resources.displayMetrics.density),
                "component" to mapOf<String, Any?>("tag" to target.testTag, "label" to if (secret) "[protected]" else target.label.takeUnless { it == target.id } ?: "Component",
                    "role" to target.role, "text" to if (secret) emptyList() else target.text.take(8),
                    "description" to if (secret) emptyList() else target.contentDescription.take(4),
                    "value" to if (secret) null else node.config.getOrNull(SemanticsProperties.EditableText)?.text,
                    "state" to if (secret) null else target.stateDescription,
                    "enabled" to target.isEnabled, "clickable" to target.isClickable, "focusable" to target.isFocusable,
                    "selected" to target.isSelected, "heading" to target.isHeading, "password" to node.config.contains(SemanticsProperties.Password), "valuesProtected" to secret,
                    "bounds" to mapOf<String, Any?>("left" to target.bounds.left, "top" to target.bounds.top, "right" to target.bounds.right, "bottom" to target.bounds.bottom),
                    "omittedAttributes" to (node.config.count() - minOf(node.config.count(), 100)),
                    "sizeDp" to mapOf<String, Any?>("width" to target.widthDp, "height" to target.heightDp), "attributes" to attributes),
                "tree" to mapOf<String, Any?>("path" to path.asReversed(), "children" to children.take(100).map(::summary),
                    "omittedChildren" to (children.size - minOf(children.size, 100)), "pathTruncated" to (parent != null)),
                "coverage" to "Visible Compose semantics only. Strings <=2048 characters, attributes <=100, lists <=8. Custom values/actions/private state are not serialized; hidden nodes excluded."))
    }
}
