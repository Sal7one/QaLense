package com.qalens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qalens.android.QaLensPrefs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/*
 * Overlay surfaces now read their colours from QaLensTokens instead of per-file literals.
 * The old build used #111827/#FFC107/#00C853/#E53935 here for every host app, which is why
 * the floating layer looked pasted on. `colors` is resolved once per composition from the
 * configuration (the overlay is decor-attached, so it cannot read the host's MaterialTheme).
 */

@Composable
internal fun QaLensOverlay() {
    val state by QaLens.state.collectAsState()
    val view = LocalView.current
    val colors = qaLensColorsFor(LocalContext.current)

    // Only refresh on inspect/tag mode toggle — not on panel open/close, which would capture the
    // panel's own nodes and create a "double UI" effect in the canvas.
    LaunchedEffect(state.isInspectMode, state.isTagMode) {
        if (state.isInspectMode || state.isTagMode) {
            QaLens.refreshInspection(view.rootView)
        }
    }

    // Compose semantics can change without a View layout pass (for example a toggle state or
    // content description). Keep the live visual modes current without polling in normal use.
    LaunchedEffect(state.isInspectMode, state.isTagMode, state.isWatchMode, view) {
        while ((state.isInspectMode || state.isTagMode) && !state.isWatchMode) {
            delay(500)
            QaLens.refreshInspection(view.rootView)
        }
    }

    val dockAlign = if (state.dockBottom) Alignment.BottomEnd else Alignment.TopEnd

    // While recording the overlay is hidden entirely; the stop control is the REC chip in its own
    // window (QaLensSystemChip — overlay or in-app mode). Render nothing as a belt-and-braces
    // guard in case something un-hides the overlay mid-recording.
    if (state.isRecording) return

    Box(Modifier.fillMaxSize()) {
        // Hide canvases while a panel/HUD is open — avoids bounding boxes over panel content.
        if (state.isInspectMode && !state.isPanelOpen && !state.isWatchMode) {
            InspectCanvas(
                nodes = state.nodes,
                selectedNode = state.selectedNode,
                onSelect = QaLens::previewNode,
                colors = colors
            )
        }
        if (state.isTagMode && !state.isPanelOpen && !state.isWatchMode) {
            TagCanvas(nodes = state.nodes, colors = colors)
        }

        if (!state.isPanelOpen && !state.isWatchMode) {
            QaLensBubble(
                modifier = Modifier,
                inspectMode = state.isInspectMode,
                warningCount = state.warnings.size,
                colors = colors,
                onTap = { QaLens.togglePanel() },
                onLongPress = { QaLens.toggleInspectMode(); QaLens.refreshInspection(view.rootView) }
            )
        }

        if (state.isWatchMode) {
            // No scrim: touches pass through to the app. Only the HUD's control bar is interactive.
            QaLensWatchHud(
                modifier = Modifier.align(dockAlign).statusBarsPadding().padding(12.dp),
                state = state,
                onStop = { QaLens.setWatchMode(false) },
                onDock = { QaLens.toggleDock() }
            )
        } else if (state.isPanelOpen) {
            // Scrim: dims the app, blocks touch-through to the app, closes panel on tap outside.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(colors.scrim)
                    .pointerInput(Unit) { detectTapGestures { QaLens.closePanel() } }
            )
            // Panel is drawn AFTER scrim so it is above it in z-order. QA chooses minimal vs full
            // (Control Room / .appsal); the tester sheet links back to the full panel.
            if (state.minimalPanel) {
                QaLensMinimalPanel(
                    modifier = Modifier.align(dockAlign).statusBarsPadding().padding(10.dp),
                    state = state,
                    onClose = QaLens::closePanel
                )
            } else {
                QaLensInspectorPanel(
                    modifier = Modifier.align(dockAlign),
                    state = state,
                    onClose = QaLens::closePanel,
                    onRefresh = { QaLens.refreshInspection(view.rootView) },
                    onToggleInspect = { QaLens.toggleInspectMode() },
                    onSelectNode = QaLens::selectNode
                )
            }
        }
    }
}

private enum class InspectFilter(val label: String) { ALL("All"), ACTIONS("Actions"), TAGGED("Tagged"), ISSUES("Issues") }

@Composable
private fun InspectCanvas(
    nodes: List<InspectNode>,
    selectedNode: InspectNode?,
    onSelect: (InspectNode?) -> Unit,
    colors: QaLensOverlayColors
) {
    var filter by remember { mutableStateOf(InspectFilter.ACTIONS) }
    val visibleNodes = when (filter) {
        InspectFilter.ALL -> nodes
        InspectFilter.ACTIONS -> nodes.filter { it.isClickable || it.isFocusable }
        InspectFilter.TAGGED -> nodes.filter { it.testTag != null }
        InspectFilter.ISSUES -> nodes.filter { it.warnings.isNotEmpty() }
    }
    val context = LocalContext.current
    val config by QaLens.config.collectAsState()

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.canvasWash)
            // Single taps inspect. QaLensOverlayHost explicitly forwards two-finger drags.
            .pointerInput(visibleNodes) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                    val moved = (up.position - down.position).getDistance()
                    if (moved < viewConfiguration.touchSlop) {
                        up.consume()
                        val selected = visibleNodes
                            .filter { it.bounds.contains(down.position.x, down.position.y) }
                            .minByOrNull { it.bounds.width * it.bounds.height }
                        onSelect(selected)
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            visibleNodes.forEach { node ->
                val isSelected = selectedNode?.id == node.id
                val hasWarnings = node.warnings.isNotEmpty()
                val color = when {
                    isSelected -> colors.accent
                    hasWarnings -> colors.warn
                    node.testTag != null -> colors.ok
                    else -> colors.info
                }

                drawRect(
                    color = color,
                    topLeft = Offset(node.bounds.left.toFloat(), node.bounds.top.toFloat()),
                    size = Size(node.bounds.width.toFloat(), node.bounds.height.toFloat()),
                    style = Stroke(width = if (isSelected) 4.dp.toPx() else 2.dp.toPx())
                )

                if (hasWarnings) {
                    drawCircle(
                        color = colors.warn,
                        radius = 6.dp.toPx(),
                        center = Offset(node.bounds.right.toFloat(), node.bounds.top.toFloat())
                    )
                }
            }
        }

        MovableOverlay("inspector", Offset(0.5f, 0.8f), Modifier.widthIn(max = 320.dp).fillMaxWidth()) { drag ->
            Column(Modifier.background(colors.panel, RoundedCornerShape(QaLensDimens.rMd)),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("━━  Move inspector · two fingers scroll app", color = colors.fg2, fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().heightIn(min = QaLensDimens.touchMin)
                        .semantics { contentDescription = "Move inspector" }.then(drag).padding(vertical = 12.dp))
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    selectedNode?.let { node ->
                        Surface(
                            color = colors.panel,
                            contentColor = colors.fg,
                            shape = RoundedCornerShape(QaLensDimens.rMd),
                            shadowElevation = 8.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    config.redact(node.label),
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${node.role ?: "Component"} · ${node.widthDp.toInt()}×${node.heightDp.toInt()}dp" +
                                        (node.testTag?.let { " · ${config.redact(it)}" } ?: ""),
                                    color = colors.fg2,
                                    fontSize = 11.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (node.warnings.isNotEmpty()) {
                                    Text(
                                        node.warnings.take(2).joinToString(" · ") { it.title },
                                        color = colors.warn,
                                        fontSize = 11.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                node.testTag?.let { tag ->
                                    Text(
                                        "Copy test tag",
                                        color = colors.accent,
                                        fontSize = 12.sp,
                                        modifier = Modifier.heightIn(min = QaLensDimens.touchMin)
                                            .clickable(role = Role.Button) {
                                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                                    as android.content.ClipboardManager
                                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("QaLens test tag", tag))
                                            }
                                            .padding(vertical = 10.dp)
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().background(colors.panel, RoundedCornerShape(QaLensDimens.rMd)).padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        InspectFilter.entries.forEach { option ->
                            Text(
                                option.label,
                                color = if (filter == option) colors.accent else colors.fg2,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                                    .heightIn(min = QaLensDimens.touchMin)
                                    .background(if (filter == option) colors.accentWash else Color.Transparent,
                                        RoundedCornerShape(QaLensDimens.rSm))
                                    .clickable(role = Role.Button) { filter = option; onSelect(null) }
                                    .padding(horizontal = 2.dp, vertical = 12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Tag mode canvas — inspect's sibling for automation engineers: every visible component with a
 * test tag gets a green outline and its tag drawn right on it; interactive components WITHOUT a
 * tag get a red outline + dot (they'll be unreachable from UI tests). Tap any tagged component to
 * copy its tag. Two-finger drags scroll the host through QaLensOverlayHost.
 */
@Composable
private fun TagCanvas(nodes: List<InspectNode>, colors: QaLensOverlayColors) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val tagged = nodes.filter { it.testTag != null }
    val untaggedInteractive = nodes.filter { it.testTag == null && it.isClickable }

    fun copyTag(tag: String) {
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("QaLens tag", tag))
        QaLens.log("Copied tag: $tag")
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.canvasWash)
            // Consume confirmed taps; the overlay host forwards two-finger drags.
            .pointerInput(tagged) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                    val moved = (up.position - down.position).getDistance()
                    if (moved < viewConfiguration.touchSlop) {
                        up.consume()
                        tagged
                            .filter { it.bounds.contains(down.position.x, down.position.y) }
                            .minByOrNull { it.bounds.width * it.bounds.height }
                            ?.testTag?.let(::copyTag)
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            tagged.forEach { node ->
                drawRect(
                    color = colors.ok,
                    topLeft = Offset(node.bounds.left.toFloat(), node.bounds.top.toFloat()),
                    size = Size(node.bounds.width.toFloat(), node.bounds.height.toFloat()),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
            untaggedInteractive.forEach { node ->
                drawRect(
                    color = colors.err,
                    topLeft = Offset(node.bounds.left.toFloat(), node.bounds.top.toFloat()),
                    size = Size(node.bounds.width.toFloat(), node.bounds.height.toFloat()),
                    style = Stroke(width = 2.dp.toPx())
                )
                drawCircle(
                    color = colors.err,
                    radius = 5.dp.toPx(),
                    center = Offset(node.bounds.right.toFloat(), node.bounds.top.toFloat())
                )
            }
        }

        // Tag chips drawn ON the components (cap to keep composition light on busy screens).
        tagged.take(60).forEach { node ->
            Text(
                text = node.testTag.orEmpty().take(36),
                color = colors.fg,
                fontSize = 10.sp,
                maxLines = 1,
                modifier = Modifier
                    .absoluteOffset { IntOffset(node.bounds.left, (node.bounds.top - 36).coerceAtLeast(8)) }
                    .background(colors.panel3, MaterialTheme.shapes.extraSmall)
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }

        // Legend + summary pill at the bottom — tapping it EXITS tag mode (QA must never be
        // stuck in an overlay mode with no visible way out).
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
                .background(colors.panel, CircleShape)
                .pointerInput(Unit) { detectTapGestures(onTap = { QaLens.setTagMode(false) }) }
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "TAG MODE · ${tagged.size} tagged · ${untaggedInteractive.size} untagged · tap a tag to copy",
                color = colors.fg,
                fontSize = 11.sp
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "✕ EXIT",
                color = colors.err,
                fontSize = 11.sp,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

/** Physical positions are relative to the usable viewport, never layout-direction offsets.
 * Both size and system/IME insets participate in clamping. Only the handle receives dock drags.
 */
@Composable
private fun MovableOverlay(
    key: String,
    initial: Offset,
    itemModifier: Modifier,
    content: @Composable (Modifier) -> Unit
) {
    val context = LocalContext.current
    var fraction by remember(key) { mutableStateOf(Offset(
        QaLensPrefs.overlayPosition(context, "${key}_x", initial.x),
        QaLensPrefs.overlayPosition(context, "${key}_y", initial.y)
    )) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var item by remember { mutableStateOf(IntSize.Zero) }
    val spaceX = (viewport.width - item.width).coerceAtLeast(0).toFloat()
    val spaceY = (viewport.height - item.height).coerceAtLeast(0).toFloat()
    val drag = Modifier.pointerInput(spaceX, spaceY) {
        detectDragGestures(onDragEnd = {
            QaLensPrefs.setOverlayPosition(context, key, fraction.x, fraction.y)
        }) { change, amount ->
            change.consume()
            fraction = Offset(
                if (spaceX > 0) (fraction.x + amount.x / spaceX).coerceIn(0f, 1f) else fraction.x,
                if (spaceY > 0) (fraction.y + amount.y / spaceY).coerceIn(0f, 1f) else fraction.y
            )
        }
    }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp)
        .onSizeChanged { viewport = it }) {
        val height = with(LocalDensity.current) { viewport.height.toDp() }
        Box(itemModifier.align(androidx.compose.ui.AbsoluteAlignment.TopLeft)
            .absoluteOffset { IntOffset((fraction.x * spaceX).roundToInt(), (fraction.y * spaceY).roundToInt()) }
            .heightIn(max = height.coerceAtLeast(1.dp)).onSizeChanged { item = it }) { content(drag) }
    }
}

@Composable
private fun QaLensBubble(
    modifier: Modifier,
    inspectMode: Boolean,
    warningCount: Int,
    colors: QaLensOverlayColors,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    val label = if (inspectMode) "INS" else "QA"
    val color = if (inspectMode) colors.accent else colors.panel
    MovableOverlay("bubble", Offset(1f, 0f), modifier.size(QaLensDimens.bubble)) { drag ->
      Box(Modifier.size(QaLensDimens.bubble)
            .background(color, CircleShape)
            .border(QaLensDimens.bubbleBorder, Color.White.copy(alpha = 0.9f), CircleShape)
            .semantics { contentDescription = "QaLens bubble" }
            .then(drag)
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() }) },
          contentAlignment = Alignment.Center) {
          Text(if (warningCount > 0) "$label\n$warningCount" else label, color = colors.fg)
      }
    }
}
