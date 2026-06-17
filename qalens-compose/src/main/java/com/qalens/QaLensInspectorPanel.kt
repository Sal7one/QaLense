package com.qalens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PanelBg     = Color(0xE6111827)
private val PanelText   = Color.White
private val PanelMuted  = Color(0xFFAAAAAA)
private val PanelAccent = Color(0xFF60A5FA)
private val PanelWarn   = Color(0xFFFBBF24)
private val PanelError  = Color(0xFFF87171)
private val PanelGreen  = Color(0xFF4ADE80)
private val PanelLine   = Color.White.copy(alpha = 0.12f)

private enum class InspectorTab(val label: String) {
    OVERVIEW("Overview"),
    BUNDLE("Bug Bundle"),
    REPRO("Repro"),
    SCREEN_HEALTH("Screen Health"),
    NETWORK("Network"),
    NAV("Navigation"),
    ACCESS("Accessibility"),
    TAGS("Automation Tags"),
    DEVICE("Device & Build"),
    TOOLS("Tools"),
    INSPECT("Inspect"),
    LOGS("Logs")
}

// Persists across panel open/close (the panel composable is recreated each open, so a plain
// `remember` would reset). Restores the last-viewed tab when the overlay is reopened.
private var lastInspectorTab = InspectorTab.OVERVIEW

@Composable
internal fun QaLensInspectorPanel(
    modifier: Modifier = Modifier,
    state: QaLensUiState,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    onToggleInspect: () -> Unit,
    onSelectNode: (InspectNode?) -> Unit
) {
    var tab by remember { mutableStateOf(lastInspectorTab) }
    fun select(t: InspectorTab) { tab = t; lastInspectorTab = t }
    val context = LocalContext.current

    Column(
        modifier = modifier
            // Docking bottom shrinks the panel so the freed top edge is reachable.
            .fillMaxHeight(if (state.dockBottom) 0.6f else 1f)
            .width(320.dp)
            .graphicsLayer { alpha = state.overlayAlpha }   // transparency slider
            .background(PanelBg)
            // Absorb background taps so they don't fall through to the app behind the overlay,
            // but don't consume drag events so scroll inside the panel still works.
            // awaitFirstDown(requireUnconsumed=true) is a no-op when a child button already
            // consumed the DOWN — so button clicks are unaffected.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().also { it.consume() }
                    waitForUpOrCancellation()?.consume()
                }
            }
            // Respect system bars so header buttons aren't hidden under the status bar.
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        // ── Transparency strip (above the header) ────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("◑", color = PanelMuted, fontSize = 13.sp)
            Slider(
                value = state.overlayAlpha,
                onValueChange = { QaLens.setOverlayAlpha(it) },
                valueRange = 0.1f..1f,
                modifier = Modifier.weight(1f).height(20.dp).padding(horizontal = 8.dp),
                colors = SliderDefaults.colors(
                    thumbColor = PanelAccent, activeTrackColor = PanelAccent,
                    inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                )
            )
            Text("Lock", color = PanelGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable { QaLens.setWatchMode(true) }
                    .background(PanelGreen.copy(alpha = 0.12f), MaterialTheme.shapes.small)
                    .padding(horizontal = 8.dp, vertical = 4.dp))
        }

        // ── Header ──────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(PanelAccent, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    // Tap the title to dock the panel to the bottom (and back) — frees the opposite
                    // edge so you can reach app UI that was behind the panel.
                    Text("QaLens", color = PanelText, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        modifier = Modifier.clickable { QaLens.toggleDock() })
                    Spacer(Modifier.width(4.dp))
                    Text(if (state.dockBottom) "↑" else "↓", color = PanelMuted, fontSize = 12.sp,
                        modifier = Modifier.clickable { QaLens.toggleDock() })
                    if (state.isRecording) {
                        Spacer(Modifier.width(8.dp))
                        Text("● REC", color = PanelError, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                            modifier = Modifier
                                .background(PanelError.copy(alpha = 0.15f), MaterialTheme.shapes.extraSmall)
                                .padding(horizontal = 6.dp, vertical = 1.dp))
                    }
                }
                Text(
                    "${state.screen.displayName} · ${state.nodes.size}n · ${state.warnings.size}⚠",
                    color = PanelMuted, fontSize = 11.sp
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PanelButton("📷", onClick = { QaLens.takeScreenshot() })
                PanelButton("⟳", onClick = onRefresh)
                PanelButton(
                    label = if (state.isInspectMode) "Ins●" else "Ins",
                    tint  = if (state.isInspectMode) PanelWarn else PanelMuted,
                    onClick = onToggleInspect
                )
                PanelButton(
                    label = if (state.isTagMode) "Tag●" else "Tag",
                    tint  = if (state.isTagMode) PanelGreen else PanelMuted,
                    onClick = {
                        val turningOn = !state.isTagMode
                        QaLens.toggleTagMode()
                        if (turningOn) QaLens.closePanel()   // the canvas is behind the panel
                    }
                )
                PanelButton("✕", onClick = onClose)
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Tab strip ────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            InspectorTab.entries.forEach { item ->
                val active = tab == item
                Text(
                    text = item.label,
                    color = if (active) PanelAccent else PanelMuted,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier
                        .clickable { select(item) }
                        .background(
                            if (active) PanelAccent.copy(alpha = 0.14f) else Color.Transparent,
                            CircleShape
                        )
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                )
            }
        }

        HorizontalDivider(color = PanelLine, thickness = 1.dp)
        Spacer(Modifier.height(8.dp))

        // ── Content area – Nav/Network/Repro own their scroll, others share one ─
        Box(Modifier.weight(1f)) {
            when (tab) {
                InspectorTab.OVERVIEW      -> ScrollContent { OverviewTab(state, context) { select(it) } }
                InspectorTab.BUNDLE        -> ScrollContent { BugBundleTab(state, context) }
                InspectorTab.REPRO         -> ReproTab(state, context)
                InspectorTab.SCREEN_HEALTH -> ScrollContent { ScreenHealthTab(state, context) }
                InspectorTab.NAV           -> NavTab(state)
                InspectorTab.INSPECT       -> ScrollContent { InspectTab(state, onSelectNode) }
                InspectorTab.ACCESS        -> ScrollContent { WarningsTab(state) }
                InspectorTab.TAGS          -> ScrollContent { TestTagsTab(state, context) }
                InspectorTab.DEVICE        -> ScrollContent { DeviceTab(state) }
                InspectorTab.LOGS          -> ScrollContent { LogsTab(state, context) }
                InspectorTab.NETWORK       -> NetworkTab(state)
                InspectorTab.TOOLS         -> ScrollContent { ToolsTab(state, context) }
            }
        }
    }
}

// Wrapper for tabs that just need a single vertical scroll
@Composable
private fun ScrollContent(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        content()
        Spacer(Modifier.height(16.dp))
    }
}

// ── Nav tab: back-stack timeline + independently-scrollable event log ──────────

@Composable
private fun NavTab(state: QaLensUiState) {
    // history is already capped at 25 entries in QaLens.setScreen
    val history = state.screen.history
    // cap nav event display — if QA sessions run long, the log can have hundreds of entries
    val navEvents = state.events
        .filter { it.type == QaEventType.BREADCRUMB && it.message.startsWith("Navigation") }
        .takeLast(50)

    Column(Modifier.fillMaxSize()) {
        // Section: back stack timeline
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Back Stack", color = PanelText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Text("${history.size} entries", color = PanelMuted, fontSize = 10.sp)
        }
        Spacer(Modifier.height(6.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            if (history.isEmpty()) {
                Text("No navigation recorded yet.", color = PanelMuted, fontSize = 11.sp)
            } else {
                history.forEachIndexed { idx, route ->
                    val isCurrent = idx == history.lastIndex
                    BackStackEntry(route = route, isCurrent = isCurrent, isLast = isCurrent)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = PanelLine)
        Spacer(Modifier.height(10.dp))

        // Section: nav events log (fills remaining space, scrollable)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Navigation Events", color = PanelText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Text("${navEvents.size} events", color = PanelMuted, fontSize = 10.sp)
        }
        Spacer(Modifier.height(6.dp))

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            if (navEvents.isEmpty()) {
                Text("No navigation events yet.", color = PanelMuted, fontSize = 11.sp)
            } else {
                navEvents.asReversed().forEachIndexed { idx, event ->
                    NavEventRow(event, isFirst = idx == 0)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun BackStackEntry(route: String, isCurrent: Boolean, isLast: Boolean) {
    val dotColor = if (isCurrent) PanelAccent else PanelMuted.copy(alpha = 0.5f)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Timeline column: dot + vertical line
        Column(
            Modifier.width(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(if (isCurrent) 10.dp else 7.dp)
                    .background(dotColor, CircleShape)
            )
            if (!isLast) {
                Box(Modifier.width(1.dp).height(16.dp).background(PanelLine))
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            route,
            color = if (isCurrent) PanelText else PanelMuted,
            fontSize = 12.sp,
            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
        )
        if (isCurrent) {
            Spacer(Modifier.width(6.dp))
            Text("now", color = PanelAccent, fontSize = 10.sp,
                modifier = Modifier
                    .background(PanelAccent.copy(alpha = 0.12f), MaterialTheme.shapes.extraSmall)
                    .padding(horizontal = 4.dp, vertical = 2.dp))
        }
    }
}

@Composable
private fun NavEventRow(event: QaEvent, isFirst: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .drawBehind {
                if (!isFirst) {
                    drawLine(
                        color = PanelLine,
                        start = Offset(0f, 0f),
                        end = Offset(size.width, 0f),
                        strokeWidth = 1.dp.toPx()
                    )
                }
            }
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                event.message.removePrefix("Navigation → ").removePrefix("Navigation changed: "),
                color = PanelAccent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ── Other tabs ────────────────────────────────────────────────────────────────

@Composable
private fun InspectTab(state: QaLensUiState, onSelectNode: (InspectNode?) -> Unit) {
    val selected = state.selectedNode
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (selected != null) NodeCard(selected, selected = true, onClick = {})
        else Text("Turn on Inspect, then tap a highlighted component.", color = PanelMuted, fontSize = 12.sp)

        val displayNodes = state.nodes.take(60)
        Text(
            "Components (${state.nodes.size}${if (state.nodes.size > 60) ", showing 60" else ""})",
            color = PanelText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp
        )
        displayNodes.forEach { node ->
            NodeCard(node, selected = selected?.id == node.id) { onSelectNode(node) }
        }
    }
}

@Composable
private fun NodeCard(node: InspectNode, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (selected) PanelAccent.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f),
                MaterialTheme.shapes.small
            )
            .padding(8.dp)
    ) {
        Text(node.label, color = PanelText, fontWeight = FontWeight.Medium, fontSize = 12.sp)
        Text("${node.source} · ${node.widthDp.toInt()}×${node.heightDp.toInt()}dp", color = PanelMuted, fontSize = 11.sp)
        node.testTag?.let { Text("tag: $it", color = PanelAccent, fontSize = 11.sp) }
        if (node.warnings.isNotEmpty()) {
            node.warnings.forEach { Text("⚠ ${it.title}", color = PanelError, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun WarningsTab(state: QaLensUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.warnings.isEmpty()) Text("No warnings.", color = PanelMuted, fontSize = 12.sp)
        state.warnings.forEach { w ->
            Column(
                Modifier.fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.05f), MaterialTheme.shapes.small)
                    .padding(8.dp)
            ) {
                Text("[${w.severity}] ${w.title}", color = PanelWarn, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Text(w.description, color = PanelText, fontSize = 11.sp)
                Text("rule=${w.id}", color = PanelMuted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun TestTagsTab(state: QaLensUiState, context: Context) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PanelButton("👁  Show Tags On Screen", tint = PanelGreen) {
            QaLens.setTagMode(true)
            QaLens.closePanel()
        }
        Text(
            "Draws every visible tag on its component (green). Interactive components with NO tag show red — they're unreachable from UI tests. Tap a tag to copy it.",
            color = PanelMuted, fontSize = 11.sp
        )
        PanelButton("Copy ${state.testTags.size} Tags") {
            copy(context, "QaLens Test Tags", state.testTags.joinToString("\n"))
        }
        if (state.testTags.isEmpty()) Text("No visible test tags.", color = PanelMuted, fontSize = 12.sp)
        state.testTags.forEach { tag ->
            Text("• $tag", color = PanelText, fontSize = 12.sp,
                modifier = Modifier.clickable { copy(context, "Tag", tag) })
        }
    }
}

@Composable
private fun DeviceTab(state: QaLensUiState) {
    val d = state.device
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Build safety banner first — managers care about testing the right build.
        state.buildSafety?.let { bs ->
            Text(bs.banner(d), color = PanelText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            bs.issues.forEach { Text("⚠ $it", color = PanelWarn, fontSize = 11.sp) }
            Spacer(Modifier.height(6.dp))
        }
        PanelKV("App",     "${d.appName} ${d.appVersion} (${d.versionCode})")
        PanelKV("Variant", d.buildVariant)
        PanelKV("Env",     d.environment.orEmpty())
        PanelKV("Git",     d.gitSha.orEmpty())
        PanelKV("Activity", state.screen.activityName)
        PanelKV("Route",   state.screen.route.orEmpty())
        PanelKV("Device",  "${d.manufacturer} ${d.deviceModel}")
        PanelKV("Android", "${d.androidVersion} / SDK ${d.sdkVersion}")
        PanelKV("Screen",  "${d.screenWidthDp}×${d.screenHeightDp}dp · ×${d.density}")
        PanelKV("Font",    d.fontScale.toString())
        PanelKV("Layout",  if (d.isRtl) "RTL" else "LTR")

        // Feature flags — first-class evidence
        if (state.featureFlags.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Feature Flags", color = PanelText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Spacer(Modifier.height(2.dp))
            state.featureFlags.forEach { (flag, on) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(flag, color = PanelMuted, fontSize = 11.sp)
                    Text(if (on) "ON" else "OFF", color = if (on) PanelGreen else PanelMuted,
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (state.recomposeCounts.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Recompose Counts", color = PanelText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Text("Reset", color = PanelAccent, fontSize = 11.sp,
                    modifier = Modifier.clickable { QaLens.resetRecomposeCounters() }
                        .padding(4.dp))
            }
            Spacer(Modifier.height(4.dp))
            state.recomposeCounts.entries
                .sortedByDescending { it.value }
                .take(15)
                .forEach { (name, count) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        val ratio = count.toFloat() / (state.recomposeCounts.values.maxOrNull() ?: 1)
                        val barColor = when {
                            ratio > 0.7f -> PanelError
                            ratio > 0.3f -> PanelWarn
                            else -> PanelAccent
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .height(18.dp)
                                .background(Color.White.copy(0.05f), MaterialTheme.shapes.extraSmall)
                        ) {
                            Box(
                                Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(ratio)
                                    .background(barColor.copy(alpha = 0.25f), MaterialTheme.shapes.extraSmall)
                            )
                            Text(name.take(22), color = PanelText, fontSize = 10.sp,
                                modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 4.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text("$count×", color = barColor, fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(36.dp))
                    }
                }
        }

        // App-provided data sources (DataStore prefs / Room snapshots)
        if (state.dataSources.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            state.dataSources.forEach { (source, values) ->
                Text(source, color = PanelText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Spacer(Modifier.height(2.dp))
                values.forEach { (k, v) -> PanelKV(k, v) }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun LogsTab(state: QaLensUiState, context: Context) {
    // Built for log-heavy apps (8+ network calls per screen, chatty Timber): text filter,
    // level chips, and consecutive-duplicate collapsing so spam compresses to "message ×N".
    var filter by remember { mutableStateOf("") }
    var level by remember { mutableStateOf<QaEventType?>(null) }   // null = all

    val filtered = state.events.asReversed().filter { e ->
        (level == null || e.type == level) &&
            (filter.isBlank() ||
                e.message.contains(filter, ignoreCase = true) ||
                e.tag?.contains(filter, ignoreCase = true) == true)
    }
    val grouped = mutableListOf<Pair<QaEvent, Int>>()
    filtered.forEach { e ->
        val last = grouped.lastOrNull()
        if (last != null && last.first.message == e.message &&
            last.first.type == e.type && last.first.tag == e.tag) {
            grouped[grouped.size - 1] = last.first to (last.second + 1)
        } else {
            grouped += e to 1
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Filter field
        Box(
            Modifier.fillMaxWidth()
                .background(Color.White.copy(alpha = 0.08f), MaterialTheme.shapes.small)
                .border(1.dp, if (filter.isNotBlank()) PanelAccent else PanelLine, MaterialTheme.shapes.small)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            BasicTextField(
                value = filter,
                onValueChange = { filter = it },
                textStyle = TextStyle(color = PanelText, fontSize = 12.sp),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (filter.isEmpty()) Text("Filter logs…", color = PanelMuted, fontSize = 12.sp)
                    inner()
                }
            )
        }

        // Level chips
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LogChip("All", level == null) { level = null }
            LogChip("Events", level == QaEventType.EVENT) { level = QaEventType.EVENT }
            LogChip("Logs", level == QaEventType.LOG) { level = QaEventType.LOG }
            LogChip("Crumbs", level == QaEventType.BREADCRUMB) { level = QaEventType.BREADCRUMB }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${grouped.size} rows · ${state.events.size} events kept", color = PanelMuted, fontSize = 10.sp)
            PanelButton("Copy") {
                copy(context, "QaLens Logs", filtered.asReversed().joinToString("\n") {
                    "${it.timestampMillis} [${it.type}] ${it.tag.orEmpty()} ${it.message}"
                })
            }
        }

        if (grouped.isEmpty()) Text("No matching log entries.", color = PanelMuted, fontSize = 11.sp)
        grouped.take(250).forEach { (event, count) ->
            val color = when (event.type) {
                QaEventType.EVENT      -> PanelAccent
                QaEventType.BREADCRUMB -> PanelWarn
                else                   -> PanelMuted
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(
                    "[${event.type}] ${event.tag.orEmpty().let { if (it.isNotBlank()) "$it · " else "" }}${event.message}",
                    color = color, fontSize = 11.sp, modifier = Modifier.weight(1f)
                )
                if (count > 1) {
                    Text(
                        "×$count", color = PanelWarn, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .background(PanelWarn.copy(alpha = 0.15f), MaterialTheme.shapes.extraSmall)
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LogChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (active) PanelAccent else PanelMuted,
        fontSize = 11.sp,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(
                if (active) PanelAccent.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.06f),
                CircleShape
            )
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

// ── Network tab ──────────────────────────────────────────────────────────────

@Composable
private fun NetworkTab(state: QaLensUiState) {
    val events = state.networkEvents.asReversed()
    val health = remember(state.networkEvents) {
        NetworkHealthEngine.summarize(state.networkEvents)
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${state.networkEvents.size} requests", color = PanelMuted, fontSize = 11.sp)
            Text("Clear", color = PanelAccent, fontSize = 11.sp,
                modifier = Modifier.clickable { QaLens.clearNetworkLog() }.padding(4.dp))
        }
        Spacer(Modifier.height(6.dp))

        if (events.isEmpty()) {
            Text("No requests yet. Add QaLensOkHttpInterceptor to your OkHttpClient.", color = PanelMuted, fontSize = 12.sp)
        } else {
            // Health summary
            Row(
                Modifier.fillMaxWidth()
                    .background(scoreColor(health.healthScore).copy(alpha = 0.10f), MaterialTheme.shapes.small)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Network Health · ${health.band}", color = PanelText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    Text("${health.failed} failed · ${health.slow} slow · avg ${health.avgLatencyMs}ms · p95 ${health.p95LatencyMs}ms",
                        color = PanelMuted, fontSize = 10.sp)
                }
                Text("${health.healthScore}", color = scoreColor(health.healthScore),
                    fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Spacer(Modifier.height(8.dp))

            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                events.forEach { event -> NetworkEventRow(event) }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun NetworkEventRow(event: NetworkEvent) {
    val statusColor = when {
        event.error != null     -> PanelError
        event.status in 200..299 -> PanelAccent
        event.status in 300..399 -> PanelWarn
        event.status >= 400     -> PanelError
        else                    -> PanelMuted
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .background(Color.White.copy(alpha = 0.04f), MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Method badge
        Box(
            Modifier
                .background(statusColor.copy(alpha = 0.15f), MaterialTheme.shapes.extraSmall)
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Text(event.method, color = statusColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                event.shortUrl.take(38),
                color = PanelText, fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                buildString {
                    append(event.latencyLabel)
                    if (event.responseBodyBytes > 0) append(" · ${event.responseBodyBytes / 1024}kb")
                    event.error?.let { append(" · $it") }
                },
                color = PanelMuted, fontSize = 10.sp
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            event.statusLabel, color = statusColor, fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ── Tools tab ─────────────────────────────────────────────────────────────────

@Composable
private fun ToolsTab(state: QaLensUiState, context: Context) {
    var deepLink by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // ── Deep link scenarios ─────────────────────────────────────────
        if (state.deepLinkScenarios.isNotEmpty()) {
            Text("Deep Link Scenarios", color = PanelText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            state.deepLinkScenarios.forEach { scenario ->
                ScenarioRow(scenario, state.scenarioRuns[scenario.name], context)
            }
            HorizontalDivider(color = PanelLine)
        }

        // ── Manual deep link launcher ───────────────────────────────────
        Text("Manual Deep Link", color = PanelText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Box(
            Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.08f), MaterialTheme.shapes.small)
                .border(1.dp, if (deepLink.isNotBlank()) PanelAccent else PanelLine, MaterialTheme.shapes.small)
                .padding(horizontal = 10.dp, vertical = 10.dp)
        ) {
            BasicTextField(
                value = deepLink,
                onValueChange = { deepLink = it },
                textStyle = TextStyle(color = PanelText, fontSize = 13.sp),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (deepLink.isEmpty()) {
                        Text("myapp://screen/id", color = PanelMuted, fontSize = 12.sp)
                    }
                    inner()
                }
            )
        }
        PanelButton("Launch →", tint = PanelAccent) {
            if (deepLink.isNotBlank()) QaLens.navigate(deepLink.trim())
        }

        HorizontalDivider(color = PanelLine)

        // ── Quick actions (all exports are redaction-aware) ─────────────
        Text("Quick Actions", color = PanelText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        PanelButton("🛠  Open Control Room", tint = PanelAccent) { openControlRoom(context) }
        PanelButton("📷  Share Screenshot") { QaLens.takeScreenshot() }
        PanelButton("Copy Jira Bug") { copy(context, "QaLens Jira Bug", QaLens.buildJiraReport()) }
        PanelButton("Copy Full QA Report") { copy(context, "QaLens Full Report", QaLens.buildFullReport()) }

        HorizontalDivider(color = PanelLine)

        Text("Session Recording (.sal)", color = PanelText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        if (state.isRecording) {
            PanelButton("■  Stop & Share Recording", tint = PanelError) { QaLens.stopRecording() }
            Text("● Recording… screen + state + network + logs are being captured.",
                color = PanelError, fontSize = 11.sp)
        } else {
            PanelButton("●  Record Session", tint = PanelGreen) { QaLens.startRecording() }
            Text("Frames (~2 fps) + synced tracks → shareable .sal. No permission needed.",
                color = PanelMuted, fontSize = 11.sp)
            PanelButton("●  Record HD Video", tint = PanelGreen) { QaLens.startRecording(video = true) }
            Text("MediaProjection H.264 — asks for screen-capture permission once.",
                color = PanelMuted, fontSize = 11.sp)
        }

        HorizontalDivider(color = PanelLine)

        RecordingsSection(state, context)

        HorizontalDivider(color = PanelLine)

        Text("Session", color = PanelText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        PanelButton("Clear Event Log") { QaLens.clearLogs() }
        PanelButton("Clear Network Log") { QaLens.clearNetworkLog() }
        PanelButton("Reset Recompose Counters") { QaLens.resetRecomposeCounters() }
        PanelButton("Restart Activity", tint = PanelError) { QaLens.restartActivity() }
    }
}

@Composable
private fun RecordingsSection(state: QaLensUiState, context: Context) {
    // Refresh the list whenever the Tools tab is shown.
    LaunchedEffect(Unit) { QaLens.refreshRecordings() }
    val recs = state.recordings

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Saved Recordings (${recs.size})", color = PanelText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text("Refresh", color = PanelAccent, fontSize = 11.sp,
            modifier = Modifier.clickable { QaLens.refreshRecordings() }.padding(4.dp))
    }
    Text(
        "${RecordingInfo.humanSize(state.recordingsBytes)} on device · keeps newest 5 automatically",
        color = PanelMuted, fontSize = 11.sp
    )
    Spacer(Modifier.height(6.dp))

    if (recs.isEmpty()) {
        Text("No saved recordings yet. Record a session to create a .sal.", color = PanelMuted, fontSize = 11.sp)
    }
    recs.forEach { r ->
        Column(
            Modifier.fillMaxWidth()
                .background(Color.White.copy(alpha = 0.05f), MaterialTheme.shapes.small)
                .padding(10.dp)
        ) {
            Text(formatDate(r.createdAtMillis), color = PanelText, fontWeight = FontWeight.Medium, fontSize = 12.sp)
            Text("${r.formattedSize} · ${r.name}", color = PanelMuted, fontSize = 10.sp, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PanelButton("Share", tint = PanelAccent) { QaLens.shareRecording(r) }
                PanelButton("Delete", tint = PanelError) { QaLens.deleteRecording(r) }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
    if (recs.isNotEmpty()) {
        PanelButton("Clear All Recordings", tint = PanelError) { QaLens.deleteAllRecordings() }
    }
}

@Composable
private fun ScenarioRow(scenario: DeepLinkScenario, run: ScenarioRun?, context: Context) {
    val (badge, badgeColor) = when (run?.status) {
        ScenarioStatus.PASS    -> "PASS" to PanelGreen
        ScenarioStatus.FAIL    -> "FAIL" to PanelError
        ScenarioStatus.PENDING -> "…" to PanelWarn
        ScenarioStatus.LAUNCHED -> "RAN" to PanelAccent
        else                    -> null to PanelMuted
    }
    Column(
        Modifier.fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), MaterialTheme.shapes.small)
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(scenario.name, color = PanelText, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                Text(scenario.uri, color = PanelMuted, fontSize = 10.sp, maxLines = 1)
                if (scenario.tags.isNotEmpty()) {
                    Text(scenario.tags.joinToString(" ") { "#$it" }, color = PanelAccent, fontSize = 10.sp)
                }
            }
            badge?.let {
                Text(it, color = badgeColor, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(badgeColor.copy(alpha = 0.15f), MaterialTheme.shapes.extraSmall)
                        .padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
        if (run?.status == ScenarioStatus.FAIL && run.actualRoute != null) {
            Text("expected '${scenario.expectedRoute}', got '${run.actualRoute}'", color = PanelError, fontSize = 10.sp)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PanelButton("Launch →", tint = PanelAccent) { QaLens.runScenario(scenario) }
            PanelButton("Copy URI") { copy(context, "Deep Link", scenario.uri) }
        }
    }
}

// ── Watch mode HUD ───────────────────────────────────────────────────────────────
// Translucent, live, and NON-interactive: only the control bar (dock + Stop) consumes touches,
// so QA can use the real app underneath while the HUD keeps updating.

@Composable
internal fun QaLensWatchHud(
    modifier: Modifier = Modifier,
    state: QaLensUiState,
    onStop: () -> Unit,
    onDock: () -> Unit
) {
    Column(modifier.width(240.dp)) {
        // Control bar — always opaque + the only tappable part.
        Row(
            Modifier.fillMaxWidth()
                .background(PanelBg, MaterialTheme.shapes.small)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("● WATCH", color = PanelGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(if (state.dockBottom) "↑ top" else "↓ bottom", color = PanelMuted, fontSize = 11.sp,
                modifier = Modifier.clickable { onDock() }.padding(horizontal = 6.dp, vertical = 2.dp))
            Spacer(Modifier.width(6.dp))
            Text("■ Stop", color = PanelError, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                modifier = Modifier.clickable { onStop() }
                    .background(PanelError.copy(alpha = 0.15f), MaterialTheme.shapes.extraSmall)
                    .padding(horizontal = 8.dp, vertical = 4.dp))
        }

        Spacer(Modifier.height(6.dp))

        // Live info — translucent and non-interactive (taps fall through to the app).
        Column(
            Modifier.fillMaxWidth()
                .graphicsLayer { alpha = state.overlayAlpha }
                .background(PanelBg, MaterialTheme.shapes.small)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(state.screen.displayName, color = PanelText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            state.screen.route?.takeIf { it.isNotBlank() }?.let { Text(it, color = PanelMuted, fontSize = 10.sp) }
            state.score?.let { Text("Score ${it.score}/100 · ${it.band}", color = scoreColor(it.score), fontSize = 11.sp) }
            Text("net ${state.networkEvents.size} · warn ${state.warnings.size} · logs ${state.events.size}",
                color = PanelMuted, fontSize = 10.sp)
            state.events.lastOrNull()?.let { Text("› ${it.message.take(42)}", color = PanelAccent, fontSize = 10.sp) }
        }
    }
}

// ── Overview tab (manager landing) ─────────────────────────────────────────────

@Composable
private fun OverviewTab(state: QaLensUiState, context: Context, onNavigateTab: (InspectorTab) -> Unit) {
    val score = state.score
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // Build safety banner
        state.buildSafety?.let { bs ->
            Column(
                Modifier.fillMaxWidth()
                    .background(
                        (if (bs.isSafe) PanelGreen else PanelWarn).copy(alpha = 0.12f),
                        MaterialTheme.shapes.small
                    )
                    .padding(10.dp)
            ) {
                Text(bs.banner(state.device), color = PanelText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                bs.issues.forEach { Text("⚠ $it", color = PanelWarn, fontSize = 11.sp) }
            }
        }

        // Stat grid
        score?.let {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatChip("Critical", it.criticalIssues, if (it.criticalIssues > 0) PanelError else PanelGreen, Modifier.weight(1f))
                StatChip("Warnings", it.warnings, if (it.warnings > 0) PanelWarn else PanelGreen, Modifier.weight(1f))
                StatChip("No tag", it.missingTags, if (it.missingTags > 0) PanelWarn else PanelGreen, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatChip("Failed API", it.failedApis, if (it.failedApis > 0) PanelError else PanelGreen, Modifier.weight(1f))
                StatChip("Slow API", it.slowApis, if (it.slowApis > 0) PanelWarn else PanelGreen, Modifier.weight(1f))
                StatChip("Dup tag", it.duplicateTags, if (it.duplicateTags > 0) PanelWarn else PanelGreen, Modifier.weight(1f))
            }
        }

        // Likely cause
        state.classification?.let { c ->
            Column(
                Modifier.fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.05f), MaterialTheme.shapes.small)
                    .padding(10.dp)
            ) {
                Text("Likely Owner", color = PanelMuted, fontSize = 11.sp)
                Text("${c.category.display}  ·  ${c.confidence}", color = PanelAccent,
                    fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                c.reasons.take(2).forEach { Text("• $it", color = PanelText, fontSize = 11.sp) }
            }
        }

        HorizontalDivider(color = PanelLine)

        // Primary actions
        Text("Actions", color = PanelText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        if (state.isRecording) {
            PanelButton("■  Stop & Share Recording", tint = PanelError) { QaLens.stopRecording() }
        } else {
            PanelButton("●  Record Session (.sal)", tint = PanelGreen) { QaLens.startRecording() }
        }
        PanelButton("📷  Capture Evidence", tint = PanelAccent) { QaLens.takeScreenshot() }
        PanelButton("Copy Jira Bug") { copy(context, "QaLens Jira Bug", QaLens.buildJiraReport()) }
        PanelButton("Copy Repro Steps") { copy(context, "QaLens Repro Steps", QaLens.buildReproSteps()) }
        PanelButton("Open Screen Health →") { onNavigateTab(InspectorTab.SCREEN_HEALTH) }
        PanelButton("🛠  Open Control Room") { openControlRoom(context) }
    }
}

// ── Bug Bundle tab ──────────────────────────────────────────────────────────────

@Composable
private fun BugBundleTab(state: QaLensUiState, context: Context) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("One-tap evidence bundle for the current screen. Nothing is uploaded — exports go to your clipboard or the share sheet.",
            color = PanelMuted, fontSize = 11.sp)

        // Completeness
        val bundle = remember(state) { QaLens.evidenceBundle() }
        val c = bundle.completeness
        Column(
            Modifier.fillMaxWidth()
                .background(Color.White.copy(alpha = 0.05f), MaterialTheme.shapes.small)
                .padding(10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Evidence Completeness", color = PanelText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Text("${c.percent}%", color = scoreColor(c.percent), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            if (c.missing.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                c.missing.forEach { Text("• missing $it", color = PanelMuted, fontSize = 11.sp) }
            }
            if (!bundle.networkAvailable) {
                Text("⚠ Network capture unavailable — add QaLensOkHttpInterceptor.", color = PanelWarn, fontSize = 11.sp)
            }
        }

        // Likely owner
        Column(
            Modifier.fillMaxWidth()
                .background(Color.White.copy(alpha = 0.05f), MaterialTheme.shapes.small)
                .padding(10.dp)
        ) {
            Text("Likely Owner: ${bundle.classification.category.display} (${bundle.classification.confidence})",
                color = PanelAccent, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            bundle.classification.reasons.forEach { Text("• $it", color = PanelText, fontSize = 11.sp) }
        }

        HorizontalDivider(color = PanelLine)
        Text("Export", color = PanelText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        PanelButton("📷  Share Annotated Screenshot", tint = PanelAccent) { QaLens.takeScreenshot() }
        PanelButton("Copy Jira Bug") { copy(context, "QaLens Jira Bug", QaLens.buildJiraReport()) }
        PanelButton("Copy Slack Summary") { copy(context, "QaLens Slack Summary", QaLens.buildSlackSummary()) }
        PanelButton("Copy Repro Steps") { copy(context, "QaLens Repro Steps", QaLens.buildReproSteps()) }
        PanelButton("Copy Full QA Report") { copy(context, "QaLens Full Report", QaLens.buildFullReport()) }
    }
}

// ── Repro tab (timeline + steps) ─────────────────────────────────────────────────

@Composable
private fun ReproTab(state: QaLensUiState, context: Context) {
    val bundle = remember(state) { QaLens.evidenceBundle() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Timeline (${bundle.timeline.size})", color = PanelText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Text("Copy Steps", color = PanelAccent, fontSize = 11.sp,
                modifier = Modifier.clickable { copy(context, "QaLens Repro Steps", QaLens.buildReproSteps()) }.padding(4.dp))
        }
        Spacer(Modifier.height(6.dp))

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (bundle.timeline.isEmpty()) {
                Text("No timeline events yet. Navigate, trigger network calls, or call QaLens.event() — then reopen.",
                    color = PanelMuted, fontSize = 11.sp)
            } else {
                bundle.timeline.forEach { e -> TimelineRow(e) }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = PanelLine)
            Spacer(Modifier.height(8.dp))

            Text("Generated Steps", color = PanelText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            bundle.repro.steps.forEach { Text(it, color = PanelText, fontSize = 12.sp, modifier = Modifier.padding(vertical = 1.dp)) }
            Spacer(Modifier.height(8.dp))
            Text("Expected", color = PanelMuted, fontSize = 11.sp)
            Text(bundle.repro.expected, color = PanelGreen, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Text("Actual", color = PanelMuted, fontSize = 11.sp)
            Text(bundle.repro.actual, color = PanelError, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TimelineRow(event: TimelineEvent) {
    val color = when (event.kind) {
        TimelineKind.ERROR      -> PanelError
        TimelineKind.NETWORK    -> PanelAccent
        TimelineKind.NAVIGATION, TimelineKind.SCREEN -> PanelGreen
        TimelineKind.ACTION     -> PanelText
        else                    -> PanelMuted
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(formatClock(event.timestampMillis), color = PanelMuted, fontSize = 10.sp,
            modifier = Modifier.width(56.dp))
        Column(Modifier.weight(1f)) {
            Text(event.title, color = color, fontSize = 11.sp,
                fontWeight = if (event.isError) FontWeight.SemiBold else FontWeight.Normal)
            event.detail?.let { Text(it, color = PanelMuted, fontSize = 10.sp) }
        }
    }
}

// ── Screen Health tab (session quality map) ──────────────────────────────────────

@Composable
private fun ScreenHealthTab(state: QaLensUiState, context: Context) {
    val screens = state.screenQuality.values.sortedBy { it.latestScore }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Current-screen contract (only when the app registered one for this screen)
        state.contractResult?.let { cr ->
            Column(
                Modifier.fillMaxWidth()
                    .background(
                        (if (cr.passed) PanelGreen else PanelError).copy(alpha = 0.10f),
                        MaterialTheme.shapes.small
                    )
                    .padding(10.dp)
            ) {
                Text("${cr.screen} Contract  ·  ${cr.passCount}/${cr.total}",
                    color = if (cr.passed) PanelGreen else PanelError,
                    fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                cr.results.forEach { r ->
                    Row {
                        Text(if (r.passed) "✓" else "✕",
                            color = if (r.passed) PanelGreen else PanelError,
                            fontSize = 11.sp, modifier = Modifier.width(16.dp))
                        Text(
                            r.description + (r.detail?.let { " — $it" } ?: ""),
                            color = if (r.passed) PanelText else PanelError, fontSize = 11.sp
                        )
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Visited Screens (${screens.size})", color = PanelText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Text("Copy Summary", color = PanelAccent, fontSize = 11.sp,
                modifier = Modifier.clickable { copy(context, "QaLens Session Summary", QaLens.buildSessionSummary()) }.padding(4.dp))
        }
        if (screens.isEmpty()) {
            Text("Navigate around the app (with QaLensNavHost) to build the session quality map.",
                color = PanelMuted, fontSize = 11.sp)
        }
        screens.forEach { s ->
            Row(
                Modifier.fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.05f), MaterialTheme.shapes.small)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(s.status, fontSize = 14.sp, modifier = Modifier.width(22.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.screenName, color = PanelText, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                    Text("visits ${s.visitCount} · crit ${s.criticalIssues} · failApi ${s.failedApis} · slow ${s.slowApis} · noTag ${s.missingTags}",
                        color = PanelMuted, fontSize = 10.sp)
                }
                Text("${s.latestScore}", color = scoreColor(s.latestScore),
                    fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: Int, tint: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Color.White.copy(alpha = 0.05f), MaterialTheme.shapes.small)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("$value", color = tint, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(label, color = PanelMuted, fontSize = 9.sp)
    }
}

private fun scoreColor(score: Int): Color = when {
    score >= 85 -> Color(0xFF4ADE80)
    score >= 70 -> Color(0xFFFBBF24)
    score >= 50 -> Color(0xFFFB923C)
    else        -> Color(0xFFF87171)
}

private fun formatClock(millis: Long): String =
    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(millis))

private fun formatDate(millis: Long): String =
    java.text.SimpleDateFormat("MMM d · HH:mm", java.util.Locale.US).format(java.util.Date(millis))

// ── Shared helpers ────────────────────────────────────────────────────────────

@Composable
private fun PanelButton(label: String, tint: Color = PanelMuted, onClick: () -> Unit) {
    Text(
        text = label, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(
                if (tint == PanelMuted) Color.White.copy(alpha = 0.07f) else tint.copy(alpha = 0.12f),
                MaterialTheme.shapes.small
            )
            .border(1.dp, tint.copy(alpha = 0.25f), MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

@Composable
private fun PanelKV(key: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(key, color = PanelMuted, fontSize = 11.sp, modifier = Modifier.width(64.dp))
        Text(value.ifBlank { "—" }, color = PanelText, fontSize = 11.sp)
    }
}

private fun copy(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    QaLens.log("Copied $label")
}

private fun openControlRoom(context: Context) {
    runCatching {
        context.startActivity(
            android.content.Intent(context, QaLensControlActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { QaLens.log("Could not open Control Room: ${it.message}") }
}
