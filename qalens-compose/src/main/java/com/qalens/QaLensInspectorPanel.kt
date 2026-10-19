package com.qalens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val PanelBg: Color @Composable get() = qaLensColorsFor(LocalContext.current).panel
private val PanelText: Color @Composable get() = qaLensColorsFor(LocalContext.current).fg
private val PanelMuted: Color @Composable get() = qaLensColorsFor(LocalContext.current).fg2
private val PanelAccent: Color @Composable get() = qaLensColorsFor(LocalContext.current).accent
private val PanelWarn: Color @Composable get() = qaLensColorsFor(LocalContext.current).warn
private val PanelError: Color @Composable get() = qaLensColorsFor(LocalContext.current).err
private val PanelGreen: Color @Composable get() = qaLensColorsFor(LocalContext.current).ok
private val PanelLine: Color @Composable get() = qaLensColorsFor(LocalContext.current).border

private val PanelSurface: Color @Composable get() = qaLensColorsFor(LocalContext.current).panel2

private enum class InspectorTab(val label: String) {
    ACTIVITY("Activity"), NETWORK("Network"), LOGS("Logs"), ELEMENTS("Elements"), DEVICE("Device")
}

// Persists across panel open/close (the panel composable is recreated each open, so a plain
// `remember` would reset). Restores the last-viewed tab when the overlay is reopened.
private var lastInspectorTab = InspectorTab.ACTIVITY
private data class CrashPreviewInput(val crashes: List<QaLensCrash>, val config: QaLensConfig)

@Composable
internal fun QaLensInspectorPanel(
    modifier: Modifier = Modifier,
    state: QaLensUiState,
    onClose: () -> Unit,
    onRefresh: () -> Unit
) {
    val config by QaLens.config.collectAsState()
    val copyAsync = backgroundCopier(LocalContext.current)
    var tab by remember { mutableStateOf(lastInspectorTab) }
    var customTabTitle by remember { mutableStateOf<String?>(null) }
    var globalSearch by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    fun clearSearch() { globalSearch = ""; searchQuery = "" }
    fun select(t: InspectorTab) { clearSearch(); tab = t; lastInspectorTab = t; customTabTitle = null }
    if (LocalOnBackPressedDispatcherOwner.current != null) {
        BackHandler(enabled = globalSearch.isNotEmpty()) { clearSearch() }
    }
    val customTabs = remember { QaLens.registeredTabs() }
    fun selectCustom(title: String) { clearSearch(); customTabTitle = title }
    val context = LocalContext.current

    // B14: debounce the search input so each keystroke doesn't rescan the whole state.
    LaunchedEffect(globalSearch) {
        delay(150)
        searchQuery = globalSearch
    }

    Column(
        modifier = modifier
            // Evidence needs a stable viewport; opacity/docking remain SDK settings for the HUD.
            .fillMaxHeight()
            .width(320.dp)
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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PanelButton("‹ Quick actions", accessibilityLabel = "Back to quick actions", modifier = Modifier.width(148.dp)) { QaLens.setPanelMinimal(true) }
            Spacer(Modifier.weight(1f))
            PanelButton("Close", accessibilityLabel = "Close diagnostics", onClick = onClose)
        }
        Text("Review evidence", color = PanelText, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(state.screen.displayName, color = PanelMuted, fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelButton("Copy bug report", tint = PanelAccent, modifier = Modifier.weight(2f)) {
                copyAsync("QaLens Bug") {
                    val current = QaLens.state.value
                    val cfg = QaLens.config.value
                    QaLens.buildMarkdownReport() + current.crashes.joinToString("", prefix = if (current.crashes.isEmpty()) "" else "\n\nCaptured failures\n") {
                        cfg.redact("\n${it.type.display}: ${it.throwable.orEmpty()}\n${it.stackTrace}\n")
                    }
                }
            }
            PanelButton("Refresh", modifier = Modifier.weight(1f), onClick = onRefresh)
        }
        Spacer(Modifier.height(8.dp))

        // ── Error banner (surfaced failures: recording/screenshot/export/…) ──────
        if (state.errors.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(PanelError.copy(alpha = 0.12f), MaterialTheme.shapes.extraSmall)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().clickable { expanded = !expanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⚠", color = PanelError, fontSize = 12.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${state.errors.size} issue${if (state.errors.size > 1) "s" else ""}",
                            color = PanelError, fontWeight = FontWeight.SemiBold, fontSize = 12.sp
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            state.errors.lastOrNull()?.let { it.kind.name.lowercase() + ": " + it.message } ?: "",
                            color = PanelMuted, fontSize = 10.sp, maxLines = 1
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (expanded) "▲" else "▼", color = PanelMuted, fontSize = 10.sp)
                        Spacer(Modifier.width(6.dp))
                        Text("Clear", color = PanelMuted, fontSize = 10.sp,
                            modifier = Modifier.clickable { QaLens.clearErrors() })
                    }
                }
                if (expanded) {
                    Column(Modifier.heightIn(max = 140.dp).verticalScroll(rememberScrollState())) {
                        state.errors.takeLast(5).forEach { err ->
                            val retry = err.retry
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("${err.kind.name}", color = PanelError, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    Text(err.message.take(500), color = PanelText, fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                                Row {
                                    if (retry != null) {
                                        Text("↻", color = PanelAccent, fontSize = 13.sp,
                                            modifier = Modifier.clickable { retry.invoke() })
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text("✕", color = PanelMuted, fontSize = 11.sp,
                                        modifier = Modifier.clickable { QaLens.dismissError(err.id) })
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        if (tab != InspectorTab.ELEMENTS && tab != InspectorTab.DEVICE && customTabTitle == null) {
            Text("Search activity, requests and logs", color = PanelMuted, fontSize = 11.sp)
            BasicTextField(
                value = globalSearch,
                onValueChange = { globalSearch = it.take(256) },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PanelSurface, MaterialTheme.shapes.extraSmall)
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 8.dp, vertical = 12.dp)
                    .semantics { contentDescription = "Search evidence" }
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                            clearSearch()
                            true
                        } else false
                    },
                singleLine = true,
                textStyle = TextStyle(color = PanelText, fontSize = 12.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(PanelAccent)
            )
            Spacer(Modifier.height(6.dp))
        }

        // ── Tab strip ────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup()
                .semantics { contentDescription = "Diagnostic tabs" },
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            InspectorTab.entries.forEach { item ->
                val active = tab == item && customTabTitle == null
                Text(
                    text = item.label,
                    color = if (active) PanelAccent else PanelMuted,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier
                        .selectable(selected = active, role = Role.Tab) { select(item) }
                        .background(
                            if (active) PanelAccent.copy(alpha = 0.14f) else Color.Transparent,
                            CircleShape
                        )
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 9.dp, vertical = 12.dp)
                )
            }
            // B3: registered custom tabs appear after the built-ins.
            customTabs.forEach { provider ->
                val active = customTabTitle == provider.title
                Text(
                    text = provider.title,
                    color = if (active) PanelAccent else PanelMuted,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier
                        .selectable(selected = active, role = Role.Tab) { selectCustom(provider.title) }
                        .background(
                            if (active) PanelAccent.copy(alpha = 0.14f) else Color.Transparent,
                            CircleShape
                        )
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 9.dp, vertical = 12.dp)
                )
            }
        }

        HorizontalDivider(color = PanelLine, thickness = 1.dp)
        Spacer(Modifier.height(8.dp))

        // Evidence lists own their scroll; device and host extensions use one scroll container.
        Box(Modifier.weight(1f)) {
            val q = searchQuery.trim()
            if (q.isNotEmpty()) {
                // B14: global search — unified results across all tracks.
                GlobalSearchResults(
                    state, q,
                    onOpenNode = { node -> clearSearch(); inspectFromEvidence(node) },
                    onOpenNetwork = { select(InspectorTab.NETWORK); clearSearch() },
                    onOpenLogs = { select(InspectorTab.LOGS); clearSearch() }
                )
            } else {
                val activeCustom = customTabs.firstOrNull { it.title == customTabTitle }
                if (activeCustom != null) {
                    ScrollContent { activeCustom.Content(state, config) }
                } else when (tab) {
                    InspectorTab.ACTIVITY -> ActivityTab(state, context)
                    InspectorTab.NETWORK -> NetworkTab(state)
                    InspectorTab.LOGS -> LogsTab(state, context)
                    InspectorTab.ELEMENTS -> ElementsTab(state, context)
                    InspectorTab.DEVICE -> ScrollContent { DeviceTab(state) }
                }
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

// ── Other tabs ────────────────────────────────────────────────────────────────

@Composable
private fun ElementsTab(state: QaLensUiState, context: Context) {
    var showChecks by remember { mutableStateOf(false) }
    if (LocalOnBackPressedDispatcherOwner.current != null) {
        BackHandler(enabled = showChecks) { showChecks = false }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelButton("Show tags") { QaLens.setTagMode(true); QaLens.closePanel() }
            PanelButton(if (showChecks) "Back to search" else "View checks", tint = PanelAccent) { showChecks = !showChecks }
        }
        if (!showChecks) {
            QaLensSelectorBrowser(qaLensColorsFor(context)) { id ->
                QaLens.refreshInspection()
                QaLens.state.value.nodes.firstOrNull { it.id == id }?.let(::inspectFromEvidence)
            }
        } else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("Checks on observed elements", color = PanelText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text("Automatic checks can miss problems. Tap a finding to inspect its element.", color = PanelMuted, fontSize = 11.sp)
            }
            if (state.warnings.isEmpty()) item {
                Text("No automatic findings in the current snapshot.", color = PanelMuted, fontSize = 12.sp)
            }
            items(state.warnings) { warning ->
                val node = state.nodes.firstOrNull { it.id == warning.nodeId }
                Column(Modifier.fillMaxWidth().background(PanelSurface, MaterialTheme.shapes.small)
                    .clickable(enabled = node != null, role = Role.Button) { node?.let(::inspectFromEvidence) }.padding(10.dp)) {
                    Text("${warning.severity}: ${warning.title.take(200)}", color = PanelWarn, fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(warning.description.take(500), color = PanelText, fontSize = 11.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Text("rule=${warning.id}", color = PanelMuted, fontSize = 10.sp)
                }
            }
            state.contractResult?.let { contract ->
                item { Text("App-defined checks: ${contract.screen}", color = PanelText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                items(contract.results) { result ->
                    Text("${if (result.passed) "Passed" else "Failed"}: ${result.description}" +
                        (result.detail?.let { " · $it" } ?: ""), color = if (result.passed) PanelText else PanelError,
                        fontSize = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun inspectFromEvidence(node: InspectNode) {
    QaLens.setWatchMode(false)
    QaLens.setInspectMode(true)
    QaLens.previewNode(node)
    QaLens.closePanel()
}

@Composable
private fun DeviceTab(state: QaLensUiState) {
    val context = LocalContext.current
    val d = state.device
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Network sources: " + state.networkSources.sorted().joinToString().ifEmpty { "none declared" },
            color = PanelMuted, fontSize = 11.sp)
        PanelButton("Copy integration check", tint = PanelAccent) { copy(context, "QaLens integration", QaLens.integrationReport()) }
        if (QaLensChuckerBridge.isAvailable(context)) {
            PanelButton("Open Chucker", tint = PanelAccent) { QaLensChuckerBridge.launch(context) }
        }
        // Build and environment facts are useful when verifying the installed build.
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
                                .background(PanelSurface, MaterialTheme.shapes.extraSmall)
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

        // ── Frame-timing / jank (B2) ──────────────────────────────────────────
        if (state.frameMetrics.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Frame Timing / Jank", color = PanelText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            val digest = remember(state.frameMetrics) { com.qalens.JankAnalyzer.analyze(state.frameMetrics) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    PanelKV("Samples", "${digest.sampleCount}")
                    PanelKV("p95", "${digest.p95TotalMs}ms")
                    PanelKV("p99", "${digest.p99TotalMs}ms")
                }
                Column(Modifier.weight(1f)) {
                    val jankColor = if (digest.jankRate > 0.3f) PanelError else if (digest.jankCount > 0) PanelWarn else PanelGreen
                    PanelKV("Jank", "${digest.jankCount} (${(digest.jankRate * 100).toInt()}%)")
                    Text("jank", color = jankColor, fontSize = 9.sp)
                    PanelKV("Frozen", "${digest.frozenCount}")
                    PanelKV("Worst", "${digest.worstFrameMs}ms")
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
    val copyAsync = backgroundCopier(context)
    // Built for log-heavy apps (8+ network calls per screen, chatty Timber): text filter,
    // level chips, and consecutive-duplicate collapsing so spam compresses to "message ×N".
    var filter by remember { mutableStateOf("") }
    var level by remember { mutableStateOf<QaEventType?>(null) }   // null = all

    val result by backgroundPanelState(PanelLogInput(state.events, level, filter), PanelLogRows.EMPTY,
        PanelLogRows::build)
    val filtered = result.events
    val grouped = result.rows

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Filter field
        Box(
            Modifier.fillMaxWidth()
                .background(PanelSurface, MaterialTheme.shapes.small)
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
                copyAsync("QaLens Logs") { filtered.asReversed().joinToString("\n") {
                    "${it.timestampMillis} [${it.type}] ${it.tag.orEmpty()} ${it.message}"
                } }
            }
        }

        if (grouped.isEmpty()) Text("No matching log entries.", color = PanelMuted, fontSize = 11.sp)
        Text("Live dashboard window · recordings retain separate evidence", color = PanelMuted, fontSize = 10.sp)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(grouped) { (event, count) ->
                val color = when (event.type) {
                    QaEventType.EVENT      -> PanelAccent
                    QaEventType.BREADCRUMB -> PanelWarn
                    else                   -> PanelMuted
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text(
                        "[${event.type}] ${event.tag.orEmpty().let { if (it.isNotBlank()) "$it · " else "" }}${event.message.take(2_000)}",
                        color = color, fontSize = 11.sp, modifier = Modifier.weight(1f),
                        maxLines = 8, overflow = TextOverflow.Ellipsis
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
                if (active) PanelAccent.copy(alpha = 0.15f) else PanelSurface,
                CircleShape
            )
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

// ── Network tab ──────────────────────────────────────────────────────────────

@Composable
private fun NetworkTab(state: QaLensUiState) {
    val cfg by QaLens.config.collectAsState()
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${state.networkEvents.size} requests", color = PanelMuted, fontSize = 11.sp)
                // B5: connectivity chip — shows the current network type + bars.
                state.connectivity?.let { conn ->
                    Spacer(Modifier.width(8.dp))
                    val connColor = if (conn.type == com.qalens.ConnectivityType.OFFLINE) PanelError else PanelGreen
                    val connLabel = when (conn.type) {
                        com.qalens.ConnectivityType.OFFLINE -> "offline"
                        com.qalens.ConnectivityType.WIFI -> "wifi"
                        com.qalens.ConnectivityType.CELLULAR -> "cell"
                        com.qalens.ConnectivityType.ETHERNET -> "eth"
                        com.qalens.ConnectivityType.UNKNOWN -> "?"
                    } + (if (conn.strengthBars > 0) " · bandwidth ${conn.strengthBars}/4 (est.)" else "")
                    Text(connLabel, color = connColor, fontSize = 10.sp,
                        modifier = Modifier.background(connColor.copy(alpha = 0.12f), MaterialTheme.shapes.extraSmall)
                            .padding(horizontal = 5.dp, vertical = 1.dp))
                }
            }
            Text("Clear", color = PanelAccent, fontSize = 11.sp,
                modifier = Modifier.clickable { QaLens.clearNetworkLog() }.padding(4.dp))
        }
        Spacer(Modifier.height(6.dp))

        if (events.isEmpty()) {
            val hint = when {
                !cfg.captureNetwork ->
                    "Network capture disabled (QaLensConfig.captureNetwork = false)."
                cfg.networkFromChucker ->
                    "Attach QaLensOkHttpInterceptor alongside ChuckerInterceptor; automatic Chucker transaction forwarding is unsupported."
                else ->
                    "No requests yet. Add QaLensOkHttpInterceptor to your OkHttpClient."
            }
            Text(hint, color = PanelMuted, fontSize = 12.sp)
        } else {
            Text("${health.failed} failed · ${health.slow} slow · p95 ${health.p95LatencyMs}ms",
                color = PanelMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))

            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(events) { event -> NetworkEventRow(event) }
                item { Spacer(Modifier.height(16.dp)) }
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
            .background(PanelSurface, MaterialTheme.shapes.small)
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
                    event.error?.let { append(" · ${it.take(2_000)}") }
                },
                color = PanelMuted, fontSize = 10.sp, maxLines = 6, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            event.statusLabel, color = statusColor, fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
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
            Text("net ${state.networkEvents.size} · warn ${state.warnings.size} · logs ${state.events.size}",
                color = PanelMuted, fontSize = 10.sp)
            state.events.lastOrNull()?.let { Text("› ${it.message.take(42)}", color = PanelAccent, fontSize = 10.sp) }
        }
    }
}

// ── Observed activity and failures ──────────────────────────────────────────────

@Composable
private fun ActivityTab(state: QaLensUiState, context: Context) {
    val copyAsync = backgroundCopier(context)
    val config by QaLens.config.collectAsState()
    val crashInput = CrashPreviewInput(state.crashes, config)
    val crashPreview by backgroundPanelState(crashInput, null as Pair<CrashPreviewInput, List<QaLensCrash>>?) { input ->
        input to input.crashes.asReversed().map { QaLensCrashEvidence.sanitize(it, input.config) }
    }
    // Hide old previews while a new privacy configuration is being applied on the worker.
    val crashes = crashPreview?.takeIf { it.first == crashInput }?.second.orEmpty()
    val bundle by panelEvidence(state)
    val evidence = bundle
    if (evidence == null) {
        Text("Preparing activity…", color = PanelMuted, fontSize = 12.sp)
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Timeline (${evidence.timeline.size})", color = PanelText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Text("Copy observed steps", color = PanelAccent, fontSize = 11.sp,
                modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { copyAsync("QaLens Repro Steps") { QaLens.buildReproSteps() } }.padding(vertical = 12.dp))
        }
        Spacer(Modifier.height(6.dp))

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(crashes) { crash ->
                var expanded by remember(crash) { mutableStateOf(false) }
                Column(Modifier.fillMaxWidth().background(PanelError.copy(alpha = .08f), MaterialTheme.shapes.small)
                    .padding(10.dp)) {
                    Text("${crash.type.display} · ${formatClock(crash.timestampMillis)}", color = PanelError,
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(crash.throwable.orEmpty().take(500), color = PanelText, fontSize = 11.sp,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    PanelButton(if (expanded) "Hide stack trace" else "Show stack trace") { expanded = !expanded }
                    if (expanded) Text(crash.stackTrace.take(16_384), color = PanelMuted, fontSize = 11.sp)
                }
                Spacer(Modifier.height(8.dp))
            }
            if (evidence.timeline.isEmpty()) {
                item {
                    Text("No timeline events yet. Navigate, trigger network calls, or call QaLens.event().",
                        color = PanelMuted, fontSize = 11.sp)
                }
            }
            // Rows are composed only when visible; the evidence retains the complete timeline.
            items(evidence.timeline.asReversed()) { e -> TimelineRow(e) }

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
            Text(event.title.take(2_000), color = color, fontSize = 11.sp,
                maxLines = 8, overflow = TextOverflow.Ellipsis,
                fontWeight = if (event.isError) FontWeight.SemiBold else FontWeight.Normal)
            event.detail?.let { Text(it.take(2_000), color = PanelMuted, fontSize = 10.sp,
                maxLines = 6, overflow = TextOverflow.Ellipsis) }
        }
    }
}

private fun formatClock(millis: Long): String =
    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(millis))

// ── Shared helpers ────────────────────────────────────────────────────────────

@Composable
private fun PanelButton(label: String, tint: Color = PanelMuted, accessibilityLabel: String? = null,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .semantics { accessibilityLabel?.let { contentDescription = it } }
            .clickable(role = Role.Button, onClick = onClick)
            .background(
                if (tint == PanelMuted) PanelSurface else tint.copy(alpha = 0.12f),
                MaterialTheme.shapes.small
            )
            .border(1.dp, tint.copy(alpha = 0.25f), MaterialTheme.shapes.small)
            .heightIn(min = 48.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
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

private enum class SearchGroup(val header: String) {
    EVENTS("Events"),
    NETWORK("Network"),
    LOGS("Logs"),
    COMPONENTS("Components")
}

private data class SearchInput(val events: List<QaEvent>, val networkEvents: List<NetworkEvent>,
    val nodes: List<InspectNode>, val query: String)

private data class SearchHit(
    val group: SearchGroup,
    val label: String,
    val detail: String,
    val node: InspectNode? = null
)

/** B14: Unified search across all tracks — events, network, logs, and nodes. */
@Composable
private fun GlobalSearchResults(
    state: QaLensUiState,
    query: String,
    onOpenNode: (InspectNode) -> Unit,
    onOpenNetwork: () -> Unit,
    onOpenLogs: () -> Unit
) {
    val hits by backgroundPanelState(
        SearchInput(state.events, state.networkEvents, state.nodes, query), emptyList<SearchHit>()
    ) { input ->
        val query = input.query
        val out = mutableListOf<SearchHit>()
        // Events + logs (both live in state.events; LOG type surfaces under the Logs group).
        input.events.forEach { e ->
            if (e.message.contains(query, ignoreCase = true) ||
                (e.tag?.contains(query, ignoreCase = true) == true)) {
                out.add(SearchHit(
                    group = if (e.type == QaEventType.LOG) SearchGroup.LOGS else SearchGroup.EVENTS,
                    label = e.tag ?: e.type.name,
                    detail = e.message
                ))
            }
        }
        // Network: url / method / error / status.
        input.networkEvents.forEach { ne ->
            if (ne.url.contains(query, ignoreCase = true) ||
                ne.method.contains(query, ignoreCase = true) ||
                (ne.error?.contains(query, ignoreCase = true) == true) ||
                ne.status.toString().contains(query)) {
                out.add(SearchHit(
                    group = SearchGroup.NETWORK,
                    label = ne.method,
                    detail = buildString {
                        append(ne.shortUrl)
                        if (ne.status != 0) append(" · ${ne.status}")
                        ne.error?.let { append(" · $it") }
                    }
                ))
            }
        }
        // Components: qaName / testTag / text / contentDescription.
        input.nodes.forEach { node ->
            if (node.qaName?.contains(query, ignoreCase = true) == true ||
                node.testTag?.contains(query, ignoreCase = true) == true ||
                node.text.any { it.contains(query, ignoreCase = true) } ||
                node.contentDescription.any { it.contains(query, ignoreCase = true) }) {
                out.add(SearchHit(
                    group = SearchGroup.COMPONENTS,
                    label = node.label,
                    detail = "<${node.testTag ?: node.role ?: "?"}>",
                    node = node
                ))
            }
        }
        out
    }

    LazyColumn(Modifier.fillMaxSize()) {
        if (hits.isEmpty()) {
            item {
                Text("No matches for \"$query\"", color = PanelMuted, fontSize = 12.sp,
                modifier = Modifier.padding(16.dp))
            }
        } else {
            item {
                Text("${hits.size} result${if (hits.size != 1) "s" else ""} across all tracks",
                color = PanelMuted, fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 6.dp))
            }
            SearchGroup.entries.forEach { group ->
                val grouped = hits.filter { it.group == group }
                if (grouped.isNotEmpty()) {
                    item { Text(group.header, color = PanelText, fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) }
                    items(grouped) { hit -> SearchHitRow(hit, onOpenNode, onOpenNetwork, onOpenLogs) }
                }
            }
        }
    }
}

@Composable
private fun SearchHitRow(
    hit: SearchHit,
    onOpenNode: (InspectNode) -> Unit,
    onOpenNetwork: () -> Unit,
    onOpenLogs: () -> Unit
) {
    val chip = when (hit.group) {
        SearchGroup.EVENTS -> "EVENT" to PanelAccent
        SearchGroup.NETWORK -> "NETWORK" to PanelGreen
        SearchGroup.LOGS -> "LOG" to PanelWarn
        SearchGroup.COMPONENTS -> "COMPONENT" to PanelAccent
    }
    val onClick: (() -> Unit)? = when (hit.group) {
        SearchGroup.COMPONENTS -> hit.node?.let { node -> ({ onOpenNode(node) }) }
        SearchGroup.NETWORK -> onOpenNetwork
        SearchGroup.EVENTS, SearchGroup.LOGS -> onOpenLogs
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelSurface, MaterialTheme.shapes.extraSmall)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(chip.first, color = chip.second, fontSize = 9.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(chip.second.copy(alpha = 0.12f), CircleShape)
                .padding(horizontal = 6.dp, vertical = 2.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(hit.label, color = PanelText, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(hit.detail, color = PanelMuted, fontSize = 10.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
    Spacer(Modifier.height(3.dp))
}
