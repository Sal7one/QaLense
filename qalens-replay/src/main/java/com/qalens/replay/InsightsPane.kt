package com.qalens.replay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext

private val InsightText = Color(0xFFE6E6E6)
private val InsightMuted = Color(0xFF93A1B0)
private val InsightAccent = Color(0xFF60A5FA)
private val InsightWarning = Color(0xFFFFD180)

internal class InsightsUiState {
    var url by mutableStateOf("")
    var apiKey by mutableStateOf("")
    var model by mutableStateOf("")
    var question by mutableStateOf("")
    var expectedResult by mutableStateOf("")
    var actualResult by mutableStateOf("")
    var radiusMs by mutableStateOf(30_000L)
    var discovery by mutableStateOf<LocalModelDiscovery?>(null)
    var evidence by mutableStateOf<InsightsEvidence?>(null)
    var report by mutableStateOf<InsightsReport?>(null)
    var error by mutableStateOf<String?>(null)
    var status by mutableStateOf("")
    var busy by mutableStateOf(false)
    var sendingToPc by mutableStateOf(false)
    var reviewed by mutableStateOf(false)
    var showEvidence by mutableStateOf(false)
    var includeImage by mutableStateOf(false)
    var image by mutableStateOf<RecordingInsightImage?>(null)
    var imageError by mutableStateOf<String?>(null)
    var target by mutableStateOf("recorded-app")
    var runtimeRevision by mutableStateOf(0)
    var evidenceRevision by mutableStateOf(0)
    var lifecycleActive by mutableStateOf(true)
    var job: Job? = null
    var requestGeneration = 0
}

/** A full player page, not another overlay control. No request occurs merely by opening it. */
@Composable
internal fun InsightsPane(session: PlayerSession, focusMs: Long, runtime: InsightsRuntime, state: InsightsUiState, onSeek: (Long) -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("qalens_local_insights", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    var showKey by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (state.url.isBlank()) state.url = preferences.getString("url", "").orEmpty()
        if (state.model.isBlank()) state.model = preferences.getString("model", "").orEmpty()
    }
    LaunchedEffect(session, focusMs, state.radiusMs, state.question, state.expectedResult, state.actualResult, state.includeImage, state.target, runtime, state.evidenceRevision, state.lifecycleActive) {
        if (state.busy || !state.lifecycleActive) return@LaunchedEffect
        val question = state.question
        val expectedResult = state.expectedResult
        val actualResult = state.actualResult
        val radius = state.radiusMs
        val target = state.target
        val includeImage = state.includeImage
        state.error = null; state.reviewed = false; state.report = null; state.evidence = null; state.image = null; state.imageError = null
        delay(200)
        var prepared = withContext(Dispatchers.IO) {
            runCatching { InsightsEvidenceBuilder.build(session, focusMs, question, radius, target, runtime, expectedResult, actualResult) }
        }.getOrElse { state.error = it.message ?: "Evidence could not be prepared."; null }
        if (includeImage && prepared != null) {
            try {
                val image = RecordingInsightImageCapture.capture(session, focusMs)
                prepared = withContext(Dispatchers.IO) { InsightsEvidenceBuilder.withImage(prepared!!, image) }
                if (state.lifecycleActive && includeImage == state.includeImage && question == state.question && expectedResult == state.expectedResult &&
                    actualResult == state.actualResult && radius == state.radiusMs && target == state.target) state.image = image
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { state.imageError = failure.message ?: "The saved image could not be prepared." }
        }
        if (state.lifecycleActive && includeImage == state.includeImage && question == state.question && expectedResult == state.expectedResult &&
            actualResult == state.actualResult && radius == state.radiusMs && target == state.target) state.evidence = prepared
    }
    DisposableEffect(Unit) {
        onDispose {
            state.requestGeneration++; state.job?.cancel(); state.job = null; state.busy = false; state.sendingToPc = false; state.apiKey = ""
            state.image = null; state.includeImage = false; state.evidence = null; state.reviewed = false
        }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                state.lifecycleActive = false
                state.requestGeneration++; state.job?.cancel(); state.job = null; state.busy = false; state.sendingToPc = false; state.apiKey = ""; state.reviewed = false
                state.image = null; state.includeImage = false; state.evidence = null; state.report = null
                state.status = "Paused. Review and analyze again when ready."
            } else if (event == Lifecycle.Event.ON_RESUME) {
                state.lifecycleActive = true; state.evidenceRevision++; state.runtimeRevision++
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    fun cancel() {
        val wasSendingToPc = state.sendingToPc
        state.requestGeneration++; state.job?.cancel(); state.job = null; state.busy = false; state.sendingToPc = false; state.reviewed = false
        state.image = null; state.includeImage = false; state.evidence = null
        state.report = null; state.evidenceRevision++
        state.status = if (wasSendingToPc) "Stopped waiting for PC. An already-queued copy may still await PC review; no model request was started."
            else "Canceled. No model actions were executed."
    }
    fun resetAttachment() { state.reviewed = false; state.report = null; state.image = null; state.includeImage = false }
    fun discover() {
        state.job?.cancel()
        val generation = ++state.requestGeneration
        state.job = scope.launch {
            state.busy = true; state.error = null; state.status = "Checking installed chat models…"
            try {
                val endpoint = LocalModelEndpoint.parse(state.url)
                val key = state.apiKey
                val result = LocalModelClient.discover(endpoint, key)
                if (generation != state.requestGeneration) return@launch
                state.discovery = result
                if (state.model !in result.models) state.model = result.models.firstOrNull().orEmpty()
                preferences.edit().putString("url", state.url.trim()).apply()
                state.status = if (result.models.isEmpty())
                    "No chat model is installed or exposed. Load a chat/instruct model in your local server, then check again. Embedding models are excluded."
                    else "${result.models.size} chat model(s) found. Only model names were requested; recording data has not been sent."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (generation == state.requestGeneration) state.error = failure.message ?: "Could not connect to the local model." }
            finally { if (generation == state.requestGeneration) state.busy = false }
        }
    }
    fun analyze() {
        val reviewedEvidence = state.evidence ?: return
        if (!state.reviewed) return
        state.job?.cancel()
        val generation = ++state.requestGeneration
        state.job = scope.launch {
            state.busy = true; state.error = null; state.report = null; state.status = "Preparing the selected evidence…"
            try {
                val endpoint = LocalModelEndpoint.parse(state.url)
                val provider = state.discovery?.provider ?: error("Check installed models first.")
                val model = state.model
                val key = state.apiKey
                val evidence = reviewedEvidence
                require(evidence.itemTimes.isNotEmpty()) { "There is no captured text evidence to analyze. Video alone cannot be analyzed by this text model." }
                state.status = "Analyzing ${evidence.itemTimes.size} observations with $model…"
                val report = LocalModelClient.analyze(endpoint, provider, model, key, evidence)
                if (generation != state.requestGeneration) return@launch
                state.report = report
                preferences.edit().putString("model", model).apply()
                state.status = "Analysis complete. Model hypotheses need verification against the linked evidence."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (generation == state.requestGeneration) state.error = failure.message ?: "The local model analysis failed." }
            finally { if (generation == state.requestGeneration) state.busy = false }
        }
    }
    fun sendToPc() {
        val reviewedEvidence = state.evidence ?: return
        if (!state.reviewed || state.busy) return
        val completedReport = state.report
        val generation = ++state.requestGeneration
        state.job = scope.launch {
            state.busy = true; state.sendingToPc = true; state.error = null; state.status = "Queuing exactly the reviewed selection for the approved PC…"
            try {
                val outcome = PcInvestigationSender.send(context, reviewedEvidence, completedReport)
                if (generation == state.requestGeneration) state.status = outcome
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (generation == state.requestGeneration) state.error = failure.message ?: "The reviewed investigation could not be handed off."
            } finally { if (generation == state.requestGeneration) { state.busy = false; state.sendingToPc = false } }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Local insights", color = InsightText, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
        Text("Pause at the bug, review the evidence, then ask your local chat model what happened and what to check next.", color = InsightMuted, fontSize = 12.sp)
        Text("Investigate", color = InsightText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((target, label) in listOf("recorded-app" to "Recorded app", "qalens-player" to "QaLens player")) {
                InsightButton(label, !state.busy, Modifier.weight(1f), state.target == target) {
                    if (state.target != target) { state.target = target; state.evidence = null; resetAttachment() }
                }
            }
        }
        if (state.target == "qalens-player") {
            Text("Includes a frozen snapshot of this QaLens replay session. Its observed time is separate from the saved app events; no source code or private player internals are inspected.", color = InsightMuted, fontSize = 11.sp)
            InsightButton("Refresh player snapshot", !state.busy, Modifier.fillMaxWidth()) {
                state.runtimeRevision++; state.evidence = null; resetAttachment()
            }
            Text(runtime.json().toString(2), color = InsightMuted, fontSize = 10.sp,
                modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp).verticalScroll(rememberScrollState()).padding(8.dp))
        }
        Text("Selected moment ${insightTime(focusMs)} · ${state.radiusMs / 1000}s before and after", color = InsightAccent, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(10_000L, 30_000L, 60_000L).forEach { radius ->
                InsightButton("±${radius / 1000}s", !state.busy, Modifier.weight(1f), selected = radius == state.radiusMs) {
                    state.radiusMs = radius; resetAttachment()
                }
            }
        }
        InsightField("Local model URL", state.url, !state.busy, "http://10.0.2.2:1234") {
            state.url = it; state.discovery = null; state.error = null; resetAttachment()
        }
        Text("LM Studio or another OpenAI-compatible local server, with Ollama also supported. An emulator can use http://10.0.2.2:1234; a phone uses your PC's private IP. Localhost means this phone. HTTP follows the host app's debug network policy.", color = InsightMuted, fontSize = 11.sp)
        Text(if (showKey) "Hide optional API key" else "Optional API key", color = InsightAccent, fontSize = 12.sp,
            modifier = Modifier.clickable(role = Role.Button) { showKey = !showKey }.padding(vertical = 4.dp))
        if (showKey) {
            InsightField("API key (memory only)", state.apiKey, !state.busy, "", password = true) {
                state.apiKey = it; state.discovery = null; resetAttachment()
            }
            Text("Never saved, logged or included in copied evidence/reports. Cleared when this page closes.", color = InsightMuted, fontSize = 11.sp)
        }
        InsightButton("Check installed models", !state.busy && state.url.isNotBlank(), Modifier.fillMaxWidth(), ::discover)
        val found = state.discovery
        if (found != null && found.models.isNotEmpty()) {
            Text("Choose a chat model", color = InsightText, fontSize = 12.sp)
            Column(Modifier.fillMaxWidth().heightIn(max = 170.dp).verticalScroll(rememberScrollState())) {
                found.models.forEach { model ->
                    Text((if (model == state.model) "● " else "○ ") + model, color = if (model == state.model) InsightAccent else InsightText,
                        fontSize = 12.sp, modifier = Modifier.fillMaxWidth().clickable(enabled = !state.busy, role = Role.RadioButton) {
                            state.model = model; resetAttachment()
                        }.padding(10.dp))
                }
            }
        }
        InsightField("Question (optional)", state.question, !state.busy, "What is the bug here, what might cause it, and what should I check?") {
            state.question = it; state.evidence = null; resetAttachment()
        }
        InsightField("Expected result (optional)", state.expectedResult, !state.busy, "After pressing Play, the video should start.", maxLength = 1600, multiline = true) {
            state.expectedResult = it; state.evidence = null; resetAttachment()
        }
        InsightField("Actual result (optional)", state.actualResult, !state.busy, "After pressing Play, the video stays buffering.", maxLength = 1600, multiline = true) {
            state.actualResult = it; state.evidence = null; resetAttachment()
        }
        Text("These are tester reports, separate from captured proof. Leave them blank if the expected behavior or symptom is unknown.", color = InsightMuted, fontSize = 11.sp)
        val evidence = state.evidence
        Text("Evidence review", color = InsightText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text((if (state.includeImage) "✓ " else "□ ") + "Include one saved image at this moment (optional)",
            color = InsightText, fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().clickable(enabled = !state.busy, role = Role.Checkbox) {
                state.includeImage = !state.includeImage; state.image = null; state.evidence = null; state.report = null; state.reviewed = false
            }.padding(vertical = 6.dp))
        if (state.includeImage) {
            Text("Review this still before sending. HD recordings can contain unmasked sensitive UI. Requires an image-capable local model; a single image cannot establish playback motion/audio. No live capture is taken.",
                color = InsightWarning, fontSize = 12.sp)
            state.image?.let { image ->
                SavedImagePreview(image)
                Text("${insightTime(image.tMs)} · ${image.source} · ${image.bytes.size / 1024} KiB" +
                    if (image.approximate) " · approximate video frame time" else " · captured frame timestamp", color = InsightMuted, fontSize = 11.sp)
            } ?: Text("Preparing selected saved image…", color = InsightMuted, fontSize = 12.sp)
            state.imageError?.let { Text("$it Uncheck the image option to continue with text.", color = InsightWarning, fontSize = 12.sp) }
        }
        if (evidence == null) Text("Preparing captured tracks…", color = InsightMuted, fontSize = 12.sp)
        else {
            Text("${evidence.itemTimes.size} observations · ${evidence.json.length} text context characters · ${evidence.omitted} not selected", color = InsightText, fontSize = 12.sp)
            Text(evidence.counts.entries.joinToString(" · ") { "${it.key}: ${it.value}" }, color = InsightMuted, fontSize = 11.sp)
            Text("Sends selected captured logs, actions, network metadata/previews, app values, crashes and timing to this URL." +
                (if (evidence.image == null) " No video pixels or audio." else " Includes the reviewed saved still; no continuous video or audio.") +
                " Redaction is limited to what the host captured and common credential fields; review business data before sending.", color = InsightWarning, fontSize = 12.sp)
            Text(if (state.showEvidence) "Hide exact evidence" else "Review exact evidence and coverage", color = InsightAccent, fontSize = 12.sp,
                modifier = Modifier.clickable(role = Role.Button) { state.showEvidence = !state.showEvidence }.padding(vertical = 6.dp))
            if (state.showEvidence) {
                evidence.notes.forEach { Text("• $it", color = InsightMuted, fontSize = 11.sp) }
                Text(evidence.json, color = InsightMuted, fontSize = 10.sp,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).background(Color(0xFF1A1F27)).verticalScroll(rememberScrollState()).padding(8.dp))
            }
            Text(if (state.reviewed) "✓ Reviewed the selected data" else "□ I reviewed the selected data before Analyze or Send to PC",
                color = if (state.reviewed) InsightAccent else InsightText, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().clickable(enabled = !state.busy, role = Role.Checkbox) { state.reviewed = !state.reviewed }.padding(vertical = 6.dp))
        }
        if (state.busy) InsightButton(if (state.sendingToPc) "Stop waiting for PC" else "Cancel analysis", true, Modifier.fillMaxWidth(), ::cancel)
        else InsightButton("Analyze selected moment", state.reviewed && state.model.isNotBlank() && found != null && evidence?.itemTimes?.isNotEmpty() == true &&
            (!state.includeImage || evidence?.image != null),
            Modifier.fillMaxWidth(), ::analyze)
        Text("Send to PC queues the exact selected text, any reviewed saved still and the completed report if available. It requires an already-approved PC bridge, sends no full recording, and starts no pairing or model analysis. PC storage is temporary until you explicitly save.",
            color = InsightMuted, fontSize = 11.sp)
        InsightButton("Send to PC", !state.busy && state.reviewed && evidence != null && (!state.includeImage || evidence.image != null),
            Modifier.fillMaxWidth(), ::sendToPc)
        if (state.status.isNotBlank()) Text(state.status, color = InsightMuted, fontSize = 12.sp)
        state.error?.let { Text(it, color = Color(0xFFF87171), fontSize = 12.sp) }
        state.report?.let { report ->
            HorizontalDivider(color = InsightMuted.copy(alpha = 0.3f))
            report.qaReport?.let { qa ->
                Text("QA report · verify before filing", color = InsightText, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(qa.title, color = InsightText, fontSize = 13.sp)
                Text("Reproduction steps from selected captured actions", color = InsightAccent, fontWeight = FontWeight.SemiBold)
                if (qa.steps.isEmpty()) Text(if (state.target == "qalens-player") InsightsQaReportBuilder.PLAYER_STEPS_MISSING else InsightsQaReportBuilder.STEPS_MISSING,
                    color = InsightMuted, fontSize = 12.sp)
                qa.steps.forEachIndexed { index, step ->
                    Text("${index + 1}. ${step.action}", color = InsightText, fontSize = 12.sp)
                    EvidenceLinks(step.evidenceIds, state.evidence, onSeek) { state.showEvidence = true }
                }
                Text("Expected result · ${if (qa.expectedSource == "tester") "tester reported" else "not supplied"}", color = InsightAccent, fontWeight = FontWeight.SemiBold)
                Text(qa.expectedResult, color = InsightText, fontSize = 12.sp)
                Text("Actual result · ${when (qa.actualSource) { "tester" -> "tester reported"; "captured-evidence" -> "model interpretation of cited evidence"; else -> "not established" }}",
                    color = InsightAccent, fontWeight = FontWeight.SemiBold)
                Text(qa.actualResult, color = InsightText, fontSize = 12.sp)
                Text("Tester statements and model interpretations need verification; valid evidence links do not prove every word of a model observation.", color = InsightMuted, fontSize = 11.sp)
                InsightButton("Copy QA report", true, Modifier.fillMaxWidth()) {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("QaLens QA report", report.qaMarkdown))
                    state.status = "QA draft copied with captured steps and expected/actual sources. Review before filing."
                }
            }
            Text("Model assessment · verify against evidence", color = InsightText, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(report.summary, color = InsightText, fontSize = 13.sp)
            report.groundingWarnings.forEach { Text("⚠ $it", color = InsightWarning, fontSize = 12.sp) }
            if (report.observations.isNotEmpty()) Text("Model observations with captured references", color = InsightAccent, fontWeight = FontWeight.SemiBold)
            report.observations.forEach { item ->
                Text(item.text, color = InsightText, fontSize = 12.sp)
                EvidenceLinks(item.evidenceIds, state.evidence, onSeek) { state.showEvidence = true; state.status = "Current-player runtime snapshot is shown in the reviewed evidence. This is not a recorded host-app event." }
            }
            if (report.hypotheses.isNotEmpty()) Text("Possible causes", color = InsightAccent, fontWeight = FontWeight.SemiBold)
            report.hypotheses.forEach { item ->
                Text("${item.title} · ${item.confidence} confidence", color = InsightText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(item.reasoning, color = InsightMuted, fontSize = 12.sp)
                EvidenceLinks(item.evidenceIds, state.evidence, onSeek) { state.showEvidence = true; state.status = "Current-player runtime snapshot is shown in the reviewed evidence. This is not a recorded host-app event." }
                item.nextChecks.forEach { Text("• $it", color = InsightText, fontSize = 12.sp) }
            }
            Text("Missing evidence", color = InsightAccent, fontWeight = FontWeight.SemiBold)
            report.missingEvidence.forEach { Text("• $it", color = InsightMuted, fontSize = 12.sp) }
            if (report.recommendedChecks.isNotEmpty()) Text("Next checks", color = InsightAccent, fontWeight = FontWeight.SemiBold)
            report.recommendedChecks.forEach { Text("• $it", color = InsightText, fontSize = 12.sp) }
            InsightButton("Copy analysis JSON", true, Modifier.fillMaxWidth()) {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("QaLens local insights", report.json))
                state.status = "Analysis copied with timestamps and coverage. The API key, image bytes and video were not included."
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun EvidenceLinks(ids: List<String>, evidence: InsightsEvidence?, onSeek: (Long) -> Unit, onRuntime: () -> Unit) {
    ids.forEach { id -> evidence?.itemTimes?.get(id)?.let { time ->
        Text(if (id == "player:runtime") "$id · review current player snapshot" else "${insightTime(time)} · $id · show in recording",
            color = InsightAccent, fontSize = 12.sp,
            modifier = Modifier.clickable(role = Role.Button) { if (id == "player:runtime") onRuntime() else onSeek(time) }.padding(vertical = 4.dp))
    } }
}

@Composable
private fun InsightButton(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) =
    InsightButton(label, enabled, modifier, selected = false, onClick = onClick)

@Composable
private fun InsightButton(label: String, enabled: Boolean, modifier: Modifier, selected: Boolean, onClick: () -> Unit) {
    Button(onClick, modifier, enabled, colors = ButtonDefaults.buttonColors(
        containerColor = if (selected) InsightAccent else Color(0xFF24364C), contentColor = if (selected) Color(0xFF0E1116) else InsightText,
        disabledContainerColor = Color(0xFF1A1F27), disabledContentColor = InsightMuted)) { Text(label, fontSize = 12.sp) }
}

@Composable
private fun InsightField(label: String, value: String, enabled: Boolean, placeholder: String, password: Boolean = false,
    maxLength: Int = if (label.startsWith("Question")) 2000 else 2048, multiline: Boolean = label.startsWith("Question"), onChange: (String) -> Unit) {
    OutlinedTextField(value, { onChange(it.take(maxLength)) }, Modifier.fillMaxWidth().semantics { contentDescription = label },
        enabled = enabled, label = { Text(label) }, placeholder = { Text(placeholder) },
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        singleLine = !multiline, maxLines = 3,
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = InsightText, unfocusedTextColor = InsightText,
            disabledTextColor = InsightMuted, focusedLabelColor = InsightAccent, unfocusedLabelColor = InsightMuted,
            focusedBorderColor = InsightAccent, unfocusedBorderColor = InsightMuted, cursorColor = InsightAccent))
}

private fun insightTime(ms: Long): String = "%d:%02d.%03d".format(ms / 60_000, ms / 1000 % 60, ms % 1000)

@Composable
private fun SavedImagePreview(image: RecordingInsightImage) {
    var bitmap by remember(image) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(image) {
        var decoded: Bitmap? = null
        try {
            withContext(Dispatchers.IO) { decoded = BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size) }
            coroutineContext.ensureActive()
            bitmap = decoded
        } catch (cancelled: CancellationException) { decoded?.recycle(); throw cancelled }
    }
    DisposableEffect(bitmap) { val owned = bitmap; onDispose { owned?.recycle() } }
    bitmap?.let { Image(it.asImageBitmap(), "Selected saved image preview", Modifier.fillMaxWidth().heightIn(max = 220.dp)) }
}
