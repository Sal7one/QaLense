package com.qalens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qalens.android.QaLensPrefs
import kotlin.math.roundToInt

@Composable
internal fun QaLensOverlay() {
    val state by QaLens.state.collectAsState()
    val view = LocalView.current

    // Only refresh on inspect/tag mode toggle — not on panel open/close, which would capture the
    // panel's own nodes and create a "double UI" effect in the canvas.
    LaunchedEffect(state.isInspectMode, state.isTagMode) {
        if (state.isInspectMode || state.isTagMode) {
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
                onSelect = QaLens::selectNode
            )
        }
        if (state.isTagMode && !state.isPanelOpen && !state.isWatchMode) {
            TagCanvas(nodes = state.nodes)
        }

        if (!state.isPanelOpen && !state.isWatchMode) {
            QaLensBubble(
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp),
                inspectMode = state.isInspectMode,
                warningCount = state.warnings.size,
                onTap = { QaLens.togglePanel(); QaLens.refreshInspection(view.rootView) },
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
                    .background(Color.Black.copy(alpha = 0.25f))
                    .pointerInput(Unit) { detectTapGestures { QaLens.closePanel() } }
            )
            // Panel is drawn AFTER scrim so it is above it in z-order. QA chooses minimal vs full
            // (Control Room / .appsal); the minimal sheet links back to the full panel.
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

@Composable
private fun InspectCanvas(
    nodes: List<InspectNode>,
    selectedNode: InspectNode?,
    onSelect: (InspectNode?) -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.04f))
            // Only consume confirmed taps (no movement); drags pass through to the
            // underlying app so the user can scroll the page while in inspect mode.
            .pointerInput(nodes) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                    val moved = (up.position - down.position).getDistance()
                    if (moved < viewConfiguration.touchSlop) {
                        up.consume()
                        val selected = nodes
                            .filter { it.bounds.contains(down.position.x, down.position.y) }
                            .minByOrNull { it.bounds.width * it.bounds.height }
                        onSelect(selected)
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            nodes.forEach { node ->
                val isSelected = selectedNode?.id == node.id
                val hasWarnings = node.warnings.isNotEmpty()
                val color = when {
                    isSelected -> Color(0xFFFFC107)
                    hasWarnings -> Color(0xFFE53935)
                    node.testTag != null -> Color(0xFF00C853)
                    else -> Color(0xFF2196F3)
                }

                drawRect(
                    color = color,
                    topLeft = Offset(node.bounds.left.toFloat(), node.bounds.top.toFloat()),
                    size = Size(node.bounds.width.toFloat(), node.bounds.height.toFloat()),
                    style = Stroke(width = if (isSelected) 4.dp.toPx() else 2.dp.toPx())
                )

                if (hasWarnings) {
                    drawCircle(
                        color = Color(0xFFE53935),
                        radius = 6.dp.toPx(),
                        center = Offset(node.bounds.right.toFloat(), node.bounds.top.toFloat())
                    )
                }
            }
        }

        selectedNode?.let { node ->
            Surface(
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .offset { IntOffset(node.bounds.left, (node.bounds.top - 52).coerceAtLeast(16)) }
                    .border(1.dp, Color(0xFFFFC107), MaterialTheme.shapes.small)
            ) {
                Text(
                    text = node.label.take(48),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

/**
 * Tag mode canvas — inspect's sibling for automation engineers: every visible component with a
 * test tag gets a green outline and its tag drawn right on it; interactive components WITHOUT a
 * tag get a red outline + dot (they'll be unreachable from UI tests). Tap any tagged component to
 * copy its tag. Drags pass through so the app stays scrollable.
 */
@Composable
private fun TagCanvas(nodes: List<InspectNode>) {
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
            .background(Color.Black.copy(alpha = 0.04f))
            // Consume only confirmed taps (like InspectCanvas); drags pass through to the app.
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
                    color = Color(0xFF00C853),
                    topLeft = Offset(node.bounds.left.toFloat(), node.bounds.top.toFloat()),
                    size = Size(node.bounds.width.toFloat(), node.bounds.height.toFloat()),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
            untaggedInteractive.forEach { node ->
                drawRect(
                    color = Color(0xFFE53935),
                    topLeft = Offset(node.bounds.left.toFloat(), node.bounds.top.toFloat()),
                    size = Size(node.bounds.width.toFloat(), node.bounds.height.toFloat()),
                    style = Stroke(width = 2.dp.toPx())
                )
                drawCircle(
                    color = Color(0xFFE53935),
                    radius = 5.dp.toPx(),
                    center = Offset(node.bounds.right.toFloat(), node.bounds.top.toFloat())
                )
            }
        }

        // Tag chips drawn ON the components (cap to keep composition light on busy screens).
        tagged.take(60).forEach { node ->
            Text(
                text = node.testTag.orEmpty().take(36),
                color = Color.White,
                fontSize = 10.sp,
                maxLines = 1,
                modifier = Modifier
                    .offset { IntOffset(node.bounds.left, (node.bounds.top - 36).coerceAtLeast(8)) }
                    .background(Color(0xE6006428), MaterialTheme.shapes.extraSmall)
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }

        // Legend + summary pill at the bottom — tapping it EXITS tag mode (QA must never be
        // stuck in an overlay mode with no visible way out).
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
                .background(Color(0xE6111827), CircleShape)
                .pointerInput(Unit) { detectTapGestures(onTap = { QaLens.setTagMode(false) }) }
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "TAG MODE · ${tagged.size} tagged · ${untaggedInteractive.size} untagged · tap a tag to copy",
                color = Color.White,
                fontSize = 11.sp
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "✕ EXIT",
                color = Color(0xFFF87171),
                fontSize = 11.sp,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

// The bubble leaves the composition whenever the panel/HUD is open, so its drag offset must live
// OUTSIDE the composable (a plain `remember` snapped it back to the corner on every panel
// open/close). File-level snapshot state survives recomposition; prefs survive process death.
private var bubbleOffset by mutableStateOf(Offset.Zero)
private var bubbleRestored = false

@Composable
private fun QaLensBubble(
    modifier: Modifier,
    inspectMode: Boolean,
    warningCount: Int,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    val context = LocalView.current.context
    LaunchedEffect(Unit) {
        if (!bubbleRestored) {
            bubbleRestored = true
            bubbleOffset = Offset(QaLensPrefs.bubbleX(context), QaLensPrefs.bubbleY(context))
        }
    }
    val label = if (inspectMode) "INS" else "QA"
    val color = if (inspectMode) Color(0xFFFFC107) else Color(0xFF111827)

    Box(
        modifier = modifier
            .offset { IntOffset(bubbleOffset.x.roundToInt(), bubbleOffset.y.roundToInt()) }
            .size(58.dp)
            .background(color, CircleShape)
            .border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { QaLensPrefs.setBubblePos(context, bubbleOffset.x, bubbleOffset.y) }
                ) { change, dragAmount ->
                    change.consume()
                    bubbleOffset += dragAmount
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onLongPress() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(text = if (warningCount > 0) "$label\n$warningCount" else label, color = Color.White)
    }
}
