package com.qalens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun QaLensSelectorLauncher(colors: QaLensOverlayColors) {
    var open by remember { mutableStateOf(false) }
    Text("Search selectors & tags", color = colors.accent, fontSize = 12.sp,
        modifier = Modifier.heightIn(min = 44.dp).clickable(role = Role.Button) { open = true }.padding(vertical = 10.dp))
    if (open) QaLensSelectorDialog(colors) { open = false }
}

private fun copySelector(context: Context, value: String) {
    val clip = ClipData.newPlainText("QaLens XPath", value)
    if (android.os.Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply {
        putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
    }
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
}

@Composable
internal fun QaLensSelectorDetails(id: String, colors: QaLensOverlayColors) {
    val context = LocalContext.current
    val config by QaLens.config.collectAsState()
    var revision by remember(id) { mutableIntStateOf(0) }
    var suggestions by remember(id) { mutableStateOf<List<QaLensSelectorSuggestion>>(emptyList()) }
    var actions by remember(id) { mutableStateOf("") }
    var note by remember(id) { mutableStateOf("Reading selectors…") }
    LaunchedEffect(id, revision, config) {
        suggestions = emptyList()
        val activity = QaLens.currentActivity
        if (activity == null) { note = "Return to the app to read selectors"; return@LaunchedEffect }
        val capture = try { QaLensAutomationInspection.capture(activity) }
            catch (_: Exception) { note = "Semantics inspection unavailable"; return@LaunchedEffect }
        val result = withContext(Dispatchers.Default) {
            val nodes = capture.redacted()
            QaLensSelectors.suggest(nodes, id) to nodes.firstOrNull { it.id == id }?.attributes
        }
        suggestions = result.first
        actions = listOf("tap", "type", "scroll").filter { result.second?.get(it) == "true" }.joinToString(" · ")
        note = if (suggestions.isEmpty()) "Element changed; select it again" else
            "QaLens XPath · visible Compose tree" + if (capture.omitted > 0) " · counts partial: ${capture.omitted} nodes omitted" else ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Actions: ${actions.ifBlank { "none exposed" }}", color = colors.fg2, fontSize = 11.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Selectors", color = colors.fg, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Text("Refresh", color = colors.accent, fontSize = 12.sp,
                modifier = Modifier.heightIn(min = 44.dp).clickable(role = Role.Button) { revision++ }.padding(vertical = 10.dp))
        }
        Text(note, color = colors.fg2, fontSize = 10.sp)
        suggestions.take(4).forEach { suggestion ->
            Column(Modifier.fillMaxWidth().background(colors.accentWash).clickable(role = Role.Button) {
                copySelector(context, suggestion.xpath)
            }.semantics { contentDescription = "Copy ${suggestion.title} XPath, ${suggestion.matches} matches" }.padding(8.dp)) {
                Text("${suggestion.title} · ${suggestion.matches} match${if (suggestion.matches == 1) "" else "es"}", color = colors.accent, fontSize = 11.sp)
                Text(suggestion.xpath, color = colors.fg, fontSize = 11.sp)
                if (suggestion.stability != "tag") Text(if (suggestion.stability == "position") "Changes with tree position" else "Changes with displayed content", color = colors.fg2, fontSize = 10.sp)
            }
        }
    }
}

@Composable
internal fun QaLensSelectorBrowser(colors: QaLensOverlayColors, onChoose: (String) -> Unit) {
    var nodes by remember { mutableStateOf<List<QaLensSelectorNode>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var action by remember { mutableStateOf("") }
    var tagged by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var matches by remember { mutableStateOf<List<QaLensSelectorNode>>(emptyList()) }
    var note by remember { mutableStateOf("Reading the visible tree…") }
    var reading by remember { mutableStateOf(true) }
    var searching by remember { mutableStateOf(true) }
    val config by QaLens.config.collectAsState()
    LaunchedEffect(revision, config) {
        reading = true
        nodes = emptyList()
        val activity = QaLens.currentActivity
        if (activity == null) { note = "Return to the app to inspect elements"; reading = false; return@LaunchedEffect }
        val capture = try { QaLensAutomationInspection.capture(activity) }
            catch (_: Exception) { note = "Semantics inspection unavailable"; reading = false; return@LaunchedEffect }
        nodes = withContext(Dispatchers.Default) { capture.redacted() }
        note = "${nodes.size} visible elements" + if (capture.omitted > 0) " · ${capture.omitted} omitted" else ""
        reading = false
    }
    LaunchedEffect(nodes, query, tagged, action) {
        searching = true
        delay(150)
        matches = withContext(Dispatchers.Default) { QaLensSelectors.search(nodes, query, tagged, action) }
        searching = false
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Search tags, text, roles and actions", color = colors.fg2, fontSize = 12.sp)
        BasicTextField(query, { query = it.take(256) }, singleLine = true, textStyle = TextStyle(color = colors.fg, fontSize = 14.sp),
            modifier = Modifier.fillMaxWidth().background(colors.accentWash).heightIn(min = 44.dp)
                .padding(10.dp).semantics { contentDescription = "Search tags, text, roles and actions" })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val count = (if (tagged) 1 else 0) + (if (action.isNotEmpty()) 1 else 0)
            Text(if (count == 0) "Filters" else "Filters ($count)", color = colors.accent, fontSize = 12.sp,
                modifier = Modifier.heightIn(min = 48.dp).semantics {
                    contentDescription = "Element filters"
                    stateDescription = if (showFilters) "Expanded; $count active" else "Collapsed; $count active"
                }.clickable(role = Role.Button) { showFilters = !showFilters }.padding(vertical = 12.dp))
            Text("Refresh", color = colors.accent, fontSize = 12.sp,
                modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { revision++ }.padding(vertical = 12.dp))
        }
        Text(if (reading) "Reading elements…" else if (searching) "Searching…" else "${matches.size} matches · $note", color = colors.fg2, fontSize = 11.sp)
        // Expanded options scroll with results rather than consuming their entire viewport on
        // short displays / large fonts. The search and collapse control remain reachable above.
        LazyColumn(Modifier.weight(1f).semantics { contentDescription = "Element search results" },
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (showFilters) {
                item(key = "action-filters") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((label, value) in listOf("All" to "", "Tap" to "tap", "Type" to "type", "Scroll" to "scroll")) {
                            Text(label, color = if (action == value) colors.accent else colors.fg2, fontSize = 12.sp,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                                    stateDescription = if (action == value) "On" else "Off"
                                }.clickable(role = Role.Button) { action = value }.padding(vertical = 12.dp))
                        }
                    }
                }
                item(key = "tag-filter") {
                    Text(if (tagged) "Tagged only ✓" else "Tagged only", color = colors.accent, fontSize = 12.sp,
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Tagged only"; stateDescription = if (tagged) "On" else "Off" }
                            .clickable(role = Role.Button) { tagged = !tagged }.padding(vertical = 12.dp))
                }
            }
            if (!reading && !searching && matches.isEmpty()) item { Text("No matching elements.", color = colors.fg2, fontSize = 12.sp) }
            items(if (reading || searching) emptyList() else matches, key = { it.id }) { node ->
                Column(Modifier.fillMaxWidth().background(colors.accentWash).clickable(role = Role.Button) { onChoose(node.id) }.padding(10.dp)) {
                    Text(node.attributes["tag"]?.let { "Tag: $it" } ?: "No test tag", color = colors.accent, fontSize = 12.sp)
                    Text(node.attributes["label"].orEmpty(), color = colors.fg, fontSize = 12.sp)
                    Text(listOf("tap", "type", "scroll").filter { node.attributes[it] == "true" }.joinToString(" · ").ifBlank { node.attributes["role"] ?: "Component" }, color = colors.fg2, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
internal fun QaLensSelectorDialog(colors: QaLensOverlayColors, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = colors.panel, contentColor = colors.fg) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).imePadding().padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Selectors & tags", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text("Close", color = colors.accent, modifier = Modifier.heightIn(min = 44.dp).clickable(role = Role.Button) { onDismiss() }.padding(8.dp))
                }
                QaLensSelectorBrowser(colors) { id ->
                    QaLens.refreshInspection()
                    val node = QaLens.state.value.nodes.firstOrNull { it.id == id } ?: return@QaLensSelectorBrowser
                    QaLens.setWatchMode(false); QaLens.closePanel(); QaLens.setInspectMode(true); QaLens.previewNode(node)
                    onDismiss()
                }
            }
        }
    }
}
