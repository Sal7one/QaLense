package com.qalens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.qalens.android.AppSalMacro
import com.qalens.android.AppSalQuery
import com.qalens.android.QaLensAppSal
import com.qalens.android.QaLensPrefs
import com.qalens.android.QaLensProfiles
import com.qalens.android.QaProfile
import java.io.File
import kotlinx.coroutines.launch
import org.json.JSONObject

private val Bg        = Color(0xFF0B0F17)
private val Card      = Color(0xFF151B26)
private val CardLine  = Color.White.copy(alpha = 0.08f)
private val TxtMain   = Color(0xFFF1F5F9)
private val TxtMuted  = Color(0xFF8B98A9)
private val Accent    = Color(0xFF60A5FA)
private val Green     = Color(0xFF4ADE80)
private val Amber     = Color(0xFFFBBF24)
private val Red       = Color(0xFFF87171)

/**
 * QaLens Control Room — a service-first command & control surface that lives in its OWN task
 * (separate launcher icon, own taskAffinity), so it keeps working even when the host app's overlay
 * is hidden, broken, or detached. From here QA can start/stop/discard recordings, heal the overlay,
 * manage saved .sal files, flip persisted settings, and grant the permissions QaLens relies on.
 */
class QaLensControlActivity : ComponentActivity() {

    private var notifGranted by mutableStateOf(true)
    private var drawOverGranted by mutableStateOf(false)
    /** Bumps when an .appsal import lands so config-backed cards re-read their state. */
    private var configVersion by mutableStateOf(0)
    private var importSummary by mutableStateOf<String?>(null)

    private val importAppSal = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val text = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        val config = text?.let { QaLensAppSal.decode(it) }
        if (config == null) {
            importSummary = "✕ Not a valid .appsal file"
            return@registerForActivityResult
        }
        if (config.packageName.isNotBlank() && config.packageName != packageName) {
            importSummary = "✕ Config is for '${config.packageName}', this app is '$packageName'"
            return@registerForActivityResult
        }
        val summary = QaLensAppSal.apply(this, config)
        // Push the applied prefs into live QaLens state too.
        QaLens.setPanelMinimal(config.panelMode == "minimal")
        QaLens.setOverlayAlpha(config.overlayAlpha)
        importSummary = "✓ Imported: $summary"
        configVersion++
        QaLens.log("Imported .appsal: $summary")
    }

    fun pickAppSal() = runCatching { importAppSal.launch(arrayOf("*/*")) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        QaLens.rememberApplication(application)
        setContent { ControlRoom(notifGranted, drawOverGranted, configVersion, importSummary) }
    }

    override fun onResume() {
        super.onResume()
        notifGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        drawOverGranted = Settings.canDrawOverlays(this)
        QaLens.refreshRecordings()
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────────

/** Launcher activity of the host app, excluding QaLens-owned screens. */
private fun hostLaunchIntent(context: Context): Intent? {
    val internal = setOf("com.qalens.QaLensControlActivity", "com.qalens.replay.QaLensPlayerActivity")
    val probe = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setPackage(context.packageName)
    val match = context.packageManager.queryIntentActivities(probe, 0)
        .firstOrNull { it.activityInfo.name !in internal } ?: return null
    return Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setClassName(context, match.activityInfo.name)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}

/** Arm a recording, then jump into the host app — it starts on the app's first resumed screen. */
private fun armAndJump(context: Context, video: Boolean): String? {
    if (!QaLens.config.value.enabled) return "QaLens is disabled in this app. Ask your developer to enable it before recording."
    if (video && !QaLens.config.value.allowUnmaskedVideo) {
        return "HD recording is disabled by this app’s privacy settings. Use frame recording or ask your developer to enable unmasked video."
    }
    val launch = hostLaunchIntent(context)
    if (launch == null) {
        return "No host app launcher was found. Open the app and start recording from its overlay."
    }
    // Control Room may be the first SDK screen, with Startup removed by the consuming app.
    if (!QaLensActivityInstaller.isInstalled) {
        val application = context.applicationContext as? android.app.Application
            ?: return "App initialization is unavailable. Open the app and try again."
        QaLens.install(application)
    }
    QaLens.armRecording(video)
    return runCatching { context.startActivity(launch); null }.getOrElse {
        QaLens.consumePendingRecording()
        "Could not open the app. Open it manually and start recording from its overlay."
    }
}

private fun openInPlayer(context: Context, info: RecordingInfo): Boolean {
    val file = File(info.path)
    if (!file.exists()) return false
    return runCatching {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".qalens.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setClassName(context, "com.qalens.replay.QaLensPlayerActivity")
                .setDataAndType(uri, "application/octet-stream")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess
}

// ── UI ──────────────────────────────────────────────────────────────────────────

@Composable
private fun ControlRoom(
    notifGranted: Boolean,
    drawOverGranted: Boolean,
    configVersion: Int,
    importSummary: String?
) {
    val state by QaLens.state.collectAsState()
    val webhookStates by QaLensWebhook.states.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val pairingRequest by QaLensPcPairing.request.collectAsState()
    pairingRequest?.let { request ->
        AlertDialog(
            modifier = Modifier.qaHiddenFromReports(),
            onDismissRequest = { QaLensPcPairing.clear() },
            title = { Text("Connect QaLens desktop?") },
            text = { Text("Your authorized adb computer requested access to this app’s Compose tree, component attributes, observations and saved recordings. It can run UI actions and request recordings, bug clips and masked screenshots. HD still requires Android consent. Screen mirror and automatic recording copy are separate choices on the PC. Approve only for your QA session; Stop PC inspector revokes access.") },
            confirmButton = { TextButton(onClick = {
                if (QaLensPcPairing.approve(request)) {
                    hostLaunchIntent(context)?.let { runCatching { context.startActivity(it) } }
                }
            }) { Text("Approve desktop") } },
            dismissButton = { TextButton(onClick = { QaLensPcPairing.clear() }) { Text("Deny") } }
        )
    }
    var webhookUrl by remember { mutableStateOf(QaLensPrefs.webhookUrl(context)) }
    // Bumps when the active QA profile switches so webhook fields re-seed from the new prefs.
    var profilesVersion by remember { mutableStateOf(0) }
    // Two-tap delete: first tap arms ("Confirm?"), second deletes. Path of the armed recording,
    // or "*" for clear-all. Tapping anything else disarms.
    var armedDelete by remember { mutableStateOf<String?>(null) }
    var recordingError by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().background(Bg)
            .windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
            .verticalScroll(rememberScrollState())
            .semantics { contentDescription = "Control Room sections" }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(20.dp))

        // ── Header ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(if (state.isRecording) Red else Green, CircleShape))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("QaLens Control Room", color = TxtMain, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(
                    "${state.device.appName} ${state.device.appVersion} · ${state.device.buildVariant}" +
                        (state.device.environment?.let { " · $it" } ?: ""),
                    color = TxtMuted, fontSize = 11.sp
                )
            }
        }

        // ── Status strip ──
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusPill(if (state.isSavingRecording) "saving…" else if (state.isRecording) "● REC" else "idle", if (state.isRecording) Red else TxtMuted)
            StatusPill(if (state.overlayEnabled) "overlay on" else "overlay off", if (state.overlayEnabled) Green else Amber)
            StatusPill(if (notifGranted) "notif ✓" else "notif ✕", if (notifGranted) Green else Red)
            StatusPill(if (drawOverGranted) "float ✓" else "float ✕", if (drawOverGranted) Green else Amber)
        }

        // ── Recording ──
        ControlCard("Session Recording") {
            if (state.isSavingRecording) {
                Text("Saving your recording…", color = Accent, fontSize = 12.sp)
            } else if (state.isRecording) {
                Text("Recording in progress…", color = Red, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                BigButton("■  Stop & Share", Red) { QaLens.stopRecording() }
                Spacer(Modifier.height(6.dp))
                BigButton("✕  Discard (no share)", TxtMuted) { QaLens.panicRestore() }
            } else {
                Text(
                    "Starts in the app, not here — tapping a button below jumps back into the app and begins capturing on its first screen.",
                    color = TxtMuted, fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))
                BigButton("●  Record Session (frames, no permission)", Green) { recordingError = armAndJump(context, video = false) }
                Spacer(Modifier.height(6.dp))
                BigButton("●  Record HD Video (MediaProjection)", Accent) { recordingError = armAndJump(context, video = true) }
            }
            recordingError?.let { Text(it, color = Red, fontSize = 12.sp) }
            Spacer(Modifier.height(8.dp))
            Text(
                if (drawOverGranted)
                    "Stop control: floating chip over any screen + notification + shake."
                else
                    "Stop control: in-app REC chip + notification + shake. Grant “Draw over apps” below for a floating chip that survives navigation.",
                color = TxtMuted, fontSize = 10.sp
            )
        }

        ControlCard("Desktop connection") {
            QaLensPcInspectorControls(QaLensOverlayColors.lightHost)
        }

        // ── Rescue ──
        ControlCard("Rescue & Overlay") {
            BigButton("⚑  PANIC RESTORE — fix everything", Amber) { QaLens.panicRestore() }
            Spacer(Modifier.height(4.dp))
            Text(
                "Discards any stuck recording, re-attaches and un-hides the overlay, resets opacity and panel state.",
                color = TxtMuted, fontSize = 10.sp
            )
            Spacer(Modifier.height(10.dp))
            SwitchRow(
                "Inject overlay into app",
                "Off = QaLens fully detaches from the app (rules out navigation interference). Control Room and notification stay available.",
                checked = state.overlayEnabled
            ) { QaLens.setOverlayEnabled(it) }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Overlay opacity", color = TxtMain, fontSize = 12.sp)
                Slider(
                    value = state.overlayAlpha,
                    onValueChange = { QaLens.setOverlayAlpha(it) },
                    valueRange = 0.1f..1f,
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent,
                        inactiveTrackColor = Color.White.copy(alpha = 0.15f))
                )
                Text("${(state.overlayAlpha * 100).toInt()}%", color = TxtMuted, fontSize = 11.sp)
            }
            QaLensActionWrap {
                SmallButton("Open panel in app") {
                    QaLens.openPanel()
                    hostLaunchIntent(context)?.let { context.startActivity(it) }
                }
                SmallButton(if (state.dockBottom) "Dock: bottom" else "Dock: top") { QaLens.toggleDock() }
            }
        }

        // ── Permissions ──
        if (!notifGranted || !drawOverGranted) {
            ControlCard("Permissions") {
                if (!notifGranted) {
                    PermissionRow(
                        "Notifications", "Required for the persistent QaLens control & Stop Recording action.",
                    ) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            (context as? ComponentActivity)
                                ?.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0x4154)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (!drawOverGranted) {
                    PermissionRow(
                        "Draw over other apps", "Enables the floating REC/stop chip that survives any navigation.",
                    ) {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}"))
                            )
                        }
                    }
                }
            }
        }

        // ── Recordings ──
        ControlCard("Saved Recordings (${state.recordings.size})") {
            Text(
                "${RecordingInfo.humanSize(state.recordingsBytes)} on device · up to 30 kept within 1 GiB",
                color = TxtMuted, fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))
            if (state.recordings.isEmpty()) {
                Text("No saved .sal recordings yet.", color = TxtMuted, fontSize = 12.sp)
            }
            state.recordings.forEach { r ->
                Column(
                    Modifier.fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(10.dp))
                        .padding(10.dp)
                ) {
                    Text(formatDate(r.createdAtMillis), color = TxtMain, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                    Text("${r.formattedSize} · ${r.name}", color = TxtMuted, fontSize = 10.sp, maxLines = 1)
                    Spacer(Modifier.height(6.dp))
                    QaLensActionWrap {
                        SmallButton("▶ Play", Accent) {
                            if (!openInPlayer(context, r)) QaLens.log("QaLens Player module not installed")
                        }
                        SmallButton("Share") { QaLens.shareRecording(r) }
                        if (webhookUrl.isNotBlank()) {
                            SmallButton("⇪ Webhook", Accent) { QaLensWebhook.upload(context, r) }
                        }
                        if (armedDelete == r.path) {
                            SmallButton("Confirm delete?", Red) {
                                QaLens.deleteRecording(r)
                                armedDelete = null
                            }
                        } else {
                            SmallButton("Delete", Red) { armedDelete = r.path }
                        }
                    }
                    WebhookStatusLine(webhookStates[r.path])
                }
                Spacer(Modifier.height(6.dp))
            }
            if (state.recordings.isNotEmpty()) {
                if (armedDelete == "*") {
                    SmallButton("Confirm: delete ALL recordings?", Red) {
                        QaLens.deleteAllRecordings()
                        armedDelete = null
                    }
                } else {
                    SmallButton("Clear all recordings", Red) { armedDelete = "*" }
                }
            }
        }

        // ── Webhook · AI analysis ──
        // ── QA Profiles: one shared phone, many testers — each with their own webhook identity ──
        ControlCard("QA Profiles · Who is testing?") {
            ProfilesSection(
                context = context,
                refresh = profilesVersion,
                onSwitched = {
                    webhookUrl = QaLensPrefs.webhookUrl(context)
                    profilesVersion++
                }
            )
        }

        ControlCard("Webhook · AI Analysis") {
            Text(
                "Ship a .sal to your analysis backend. It receives the file (multipart \"file\") plus X-QaLens-* headers — including X-QaLens-Digest, a one-line JSON triage summary, and X-QaLens-User from the active profile — and query params. Every .sal carries analysis.json + for_ai.md so any AI can analyze it on arrival.",
                color = TxtMuted, fontSize = 10.sp
            )
            Spacer(Modifier.height(10.dp))
            // key(profilesVersion): switching profile re-seeds every field from the new prefs.
            androidx.compose.runtime.key(profilesVersion) {
                var headerName by remember { mutableStateOf(QaLensPrefs.webhookHeaderName(context)) }
                var headerValue by remember { mutableStateOf(QaLensPrefs.webhookHeaderValue(context)) }
                var params by remember { mutableStateOf(QaLensPrefs.webhookParams(context)) }
                var includeMeta by remember { mutableStateOf(QaLensPrefs.webhookIncludeMeta(context)) }
                SettingField("Endpoint URL", webhookUrl, "https://qa.example.com/api/sal") {
                    webhookUrl = it
                    QaLensPrefs.setWebhookUrl(context, it)
                    headerValue = QaLensPrefs.webhookHeaderValue(context)
                    QaLensProfiles.syncActiveFromPrefs(context)
                }


                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(0.45f)) {
                        SettingField("Auth header", headerName, "Authorization") {
                            headerName = it; QaLensPrefs.setWebhookHeaderName(context, it)
                            QaLensProfiles.syncActiveFromPrefs(context)
                        }
                    }
                    Box(Modifier.weight(0.55f)) {
                        SettingField("Header value", headerValue, "Bearer …") {
                            headerValue = it; QaLensPrefs.setWebhookHeaderValue(context, it)
                            QaLensProfiles.syncActiveFromPrefs(context)
                        }
                    }
                }
                SettingField("Extra query params", params, "team=payments&pipeline=nightly") {
                    params = it; QaLensPrefs.setWebhookParams(context, it)
                    QaLensProfiles.syncActiveFromPrefs(context)
                }
                SwitchRow(
                    "Attach session metadata",
                    "Adds app/version/env/device/createdAt/user as query params.",
                    checked = includeMeta
                ) {
                    includeMeta = it; QaLensPrefs.setWebhookIncludeMeta(context, it)
                    QaLensProfiles.syncActiveFromPrefs(context)
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallButton("Test endpoint", Accent) { QaLensWebhook.test(context) }
            }
            WebhookStatusLine(webhookStates[QaLensWebhook.TEST_KEY])
        }

        // ── QA Experience: panel style + macros ──
        ControlCard("QA Experience") {
            SwitchRow(
                "Tester quick actions",
                "Start with capture and inspection actions. Review evidence opens the five evidence views. Off opens Review evidence directly.",
                checked = state.minimalPanel
            ) { QaLens.setPanelMinimal(it) }
            Spacer(Modifier.height(10.dp))
            androidx.compose.runtime.key(configVersion) { MacrosSection(context) }
        }

        // ── Database: raw SQL into the app's own SQLite/Room DBs ──
        ControlCard("Database · Raw SQL") {
            androidx.compose.runtime.key(configVersion) { DatabaseSection(context) }
        }

        // ── App data: SharedPreferences + DataStore ──
        ControlCard("App Data · Prefs & DataStore") {
            AppDataSection(context)
        }

        // ── App config: .appsal import/export ──
        ControlCard("App Config · .appsal") {
            androidx.compose.runtime.key(configVersion) {
                val cfg = remember { QaLensAppSal.current(context) }
                Text(
                    "${cfg.packageName} · panel=${if (state.minimalPanel) "minimal" else "full"} · " +
                        "${cfg.queries.size} queries · ${cfg.macros.size} macros" +
                        (if (cfg.webhookUrl.isNotBlank()) " · webhook set" else ""),
                    color = TxtMuted, fontSize = 11.sp
                )
            }
            importSummary?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = if (it.startsWith("✓")) Green else Red, fontSize = 11.sp)
            }
            Spacer(Modifier.height(8.dp))
            var includeSecrets by remember { mutableStateOf(false) }
            SwitchRow(
                "Include secrets in export",
                "Off = the webhook auth value is exported masked (safe to share with the team).",
                checked = includeSecrets
            ) { includeSecrets = it }
            Spacer(Modifier.height(6.dp))
            QaLensActionWrap {
                SmallButton("⇪ Export & share", Accent) { exportAppSal(context, includeSecrets) }
                SmallButton("⤓ Import .appsal", Green) {
                    (context as? QaLensControlActivity)?.pickAppSal()
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "One file per app package: panel style, overlay prefs, webhook, saved queries, macros. Share it and the whole team tests with the same setup.",
                color = TxtMuted, fontSize = 10.sp
            )
        }

        // ── Footer ──
        HorizontalDivider(color = CardLine)
        Text(
            "QaLens debug build tool · nothing leaves the device unless you share it.",
            color = TxtMuted, fontSize = 10.sp
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ControlCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(Card, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Text(title, color = TxtMain, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun StatusPill(label: String, tint: Color) {
    Text(
        label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(tint.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun BigButton(label: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .background(tint.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = tint, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

@Composable
private fun SmallButton(label: String, tint: Color = TxtMuted, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = tint, fontSize = 12.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

// ── .appsal export ──────────────────────────────────────────────────────────

private fun exportAppSal(context: Context, includeSecrets: Boolean) {
    runCatching {
        val json = QaLensAppSal.encode(QaLensAppSal.current(context), includeSecrets)
        val dir = File(context.cacheDir, "qalens").apply { mkdirs() }
        val file = File(dir, "${context.packageName}.appsal")
        file.writeText(json)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".qalens.fileprovider", file)
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, "QaLens app config (.appsal) for ${context.packageName}")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Share .appsal config"
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        QaLens.log("Exported ${file.name}")
    }.onFailure { QaLens.log("Export .appsal failed: ${it.message}") }
}

// ── QA Profiles section ─────────────────────────────────────────────────────

@Composable
private fun ProfilesSection(context: Context, refresh: Int, onSwitched: () -> Unit) {
    var profiles by remember(refresh) { mutableStateOf(QaLensProfiles.list(context)) }
    var active by remember(refresh) { mutableStateOf(QaLensProfiles.activeName(context)) }
    var newName by remember { mutableStateOf("") }
    var newUser by remember { mutableStateOf("") }
    var armedDelete by remember { mutableStateOf<String?>(null) }

    Text(
        "Shared test phone? Each tester keeps their own webhook identity (endpoint, bearer, Jira user). Switching profiles swaps the Webhook card below — uploads are sent and attributed as the active person.",
        color = TxtMuted, fontSize = 10.sp
    )
    Spacer(Modifier.height(8.dp))

    if (profiles.isEmpty()) {
        Text("No profiles yet — set up the webhook below, then save it as yours.", color = TxtMuted, fontSize = 11.sp)
    }
    profiles.forEach { p ->
        val isActive = p.name == active
        Row(
            Modifier.fillMaxWidth()
                .padding(bottom = 6.dp)
                .background(
                    if (isActive) Accent.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.04f),
                    RoundedCornerShape(10.dp)
                )
                .clickable {
                    QaLensProfiles.activate(context, p)
                    active = p.name
                    QaLens.log("QA profile switched → ${p.name}")
                    onSwitched()
                }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(8.dp).background(if (isActive) Green else TxtMuted.copy(alpha = 0.4f), CircleShape))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    p.name + (if (isActive) "  · active" else ""),
                    color = if (isActive) Accent else TxtMain,
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                )
                Text(
                    (p.user.ifBlank { "no user id" }) + " · " +
                        (p.webhookUrl.ifBlank { "no webhook" }).take(40) +
                        (if (p.headerValue.isNotBlank()) " · 🔑" else ""),
                    color = TxtMuted, fontSize = 10.sp, maxLines = 1
                )
            }
            if (armedDelete == p.name) {
                SmallButton("Confirm?", Red) {
                    QaLensProfiles.delete(context, p.name)
                    profiles = QaLensProfiles.list(context)
                    active = QaLensProfiles.activeName(context)
                    armedDelete = null
                }
            } else {
                SmallButton("✕", Red) { armedDelete = p.name }
            }
        }
    }

    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) { SettingField("Your name", newName, "Alex") { newName = it } }
        Box(Modifier.weight(1f)) { SettingField("Jira / backend user", newUser, "alex.qa") { newUser = it } }
    }
    SmallButton("＋ Save current webhook as my profile", Accent) {
        if (newName.isNotBlank()) {
            val p = QaLensProfiles.captureCurrent(context, newName.trim(), newUser.trim())
            QaLensProfiles.save(context, p)
            QaLensProfiles.activate(context, p)
            profiles = QaLensProfiles.list(context)
            active = p.name
            newName = ""; newUser = ""
            onSwitched()
        }
    }
}

// ── Macros section ──────────────────────────────────────────────────────────

@Composable
private fun MacrosSection(context: Context) {
    var macros by remember { mutableStateOf(QaLensAppSal.macros(context)) }
    var newName by remember { mutableStateOf("") }
    var newSteps by remember { mutableStateOf("") }
    var runError by remember { mutableStateOf<String?>(null) }

    Text("Macros", color = TxtMain, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    Text(
        "One step per line: deeplink <uri> · wait <ms> · tap <tag|text> · type <tag> <text> · record [video] · stop · screenshot · mark <text>. tap/type wait up to 5s for the target — a macro can complete a full login alone.",
        color = TxtMuted, fontSize = 10.sp
    )
    Spacer(Modifier.height(6.dp))
    runError?.let { Text(it, color = Red, fontSize = 12.sp) }
    if (macros.isEmpty()) Text("No macros yet.", color = TxtMuted, fontSize = 11.sp)
    macros.forEach { m ->
        Row(
            Modifier.fillMaxWidth()
                .padding(bottom = 6.dp)
                .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(m.name, color = TxtMain, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(m.steps.joinToString("  →  ").take(80), color = TxtMuted, fontSize = 10.sp)
            }
            SmallButton("▶ Run", Green) {
                val launch = hostLaunchIntent(context)
                runError = if (launch == null) "No host app launcher was found. Open the app to run this macro."
                    else QaLensMacros.runFromControlRoom(m) { context.startActivity(launch) }
            }
            Spacer(Modifier.width(6.dp))
            SmallButton("✕", Red) {
                macros = macros.filterNot { it.name == m.name }
                QaLensAppSal.setMacros(context, macros)
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    SettingField("New macro name", newName, "Smoke: accounts + transfer") { newName = it }
    SettingField("Steps (one per line)", newSteps, "deeplink qalenssample://accounts\nwait 1000\nscreenshot", singleLine = false) { newSteps = it }
    SmallButton("＋ Save macro", Accent) {
        val steps = newSteps.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (newName.isNotBlank() && steps.isNotEmpty()) {
            macros = macros.filterNot { it.name == newName } + AppSalMacro(newName.trim(), steps)
            QaLensAppSal.setMacros(context, macros)
            newName = ""; newSteps = ""
        }
    }
}

// ── Database section ────────────────────────────────────────────────────────

@Composable
private fun DatabaseSection(context: Context) {
    var dbs by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedDb by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(true) }
    var scanVersion by remember { mutableStateOf(0) }
    var pickerOpen by remember { mutableStateOf(false) }
    val config by QaLens.config.collectAsState()
    var sql by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<QaLensDataTools.QueryResult?>(null) }
    var queries by remember { mutableStateOf(QaLensAppSal.queries(context)) }
    var saveName by remember { mutableStateOf("") }
    val queryScope = androidx.compose.runtime.rememberCoroutineScope()
    var queryJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var cancellation by remember { mutableStateOf<android.os.CancellationSignal?>(null) }
    var queryRunning by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { cancellation?.cancel(); queryJob?.cancel() } }
    LaunchedEffect(scanVersion) {
        scanning = true
        dbs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { QaLensDataTools.databases(context.applicationContext) }
        if (selectedDb !in dbs) selectedDb = dbs.firstOrNull().orEmpty()
        scanning = false
    }
    LaunchedEffect(config.enabled) { if (!config.enabled) { cancellation?.cancel(); queryJob?.cancel() } }
    fun runQuery(db: String, query: String) {
        if (queryRunning || query.isBlank() || !QaLens.config.value.enabled) return
        val signal = android.os.CancellationSignal()
        cancellation = signal
        queryRunning = true
        result = null
        showAll = false
        queryJob = queryScope.launch {
            try {
                result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    QaLensDataTools.runQuery(context.applicationContext, db, query, signal)
                }
            } finally { queryRunning = false }
        }
    }

    Text("Query this app's SQLite databases. Writes change app data; Cancel does not undo completed writes.",
        color = TxtMuted, fontSize = 12.sp)
    if (!config.enabled) Text("QaLens is disabled. Enable it before running queries.", color = Amber, fontSize = 12.sp)
    Spacer(Modifier.height(8.dp))
    Box(Modifier.fillMaxWidth()) {
        DataButton(if (scanning) "Finding databases…" else selectedDb.ifBlank { "No SQLite databases found" },
            Modifier.fillMaxWidth(), enabled = dbs.isNotEmpty() && !queryRunning, description = "Choose database") { pickerOpen = true }
        DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
            dbs.forEach { db ->
                DropdownMenuItem(text = { Text(db) }, onClick = { selectedDb = db; result = null; pickerOpen = false })
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DataButton("List tables", Modifier.weight(1f), enabled = selectedDb.isNotBlank() && !queryRunning && config.enabled) {
            sql = "SELECT name, type FROM sqlite_master WHERE type = 'table' ORDER BY name"
            runQuery(selectedDb, sql)
        }
        DataButton("Rescan", Modifier.weight(1f), enabled = !scanning && !queryRunning) { scanVersion++ }
    }
    Spacer(Modifier.height(8.dp))
    SettingField("SQL query", sql,
        "SELECT * FROM accounts LIMIT 10", singleLine = false) { sql = it }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DataButton(if (queryRunning) "Running…" else "Run query", Modifier.weight(1f), Green,
            enabled = !queryRunning && sql.isNotBlank() && selectedDb.isNotBlank() && config.enabled) { runQuery(selectedDb, sql) }
        if (queryRunning) DataButton("Cancel query", Modifier.weight(1f)) { cancellation?.cancel(); queryJob?.cancel() }
    }
    Spacer(Modifier.height(8.dp))
    SettingField("Query name", saveName, "Name this query to reuse it") { saveName = it }
    DataButton("Save query", Modifier.fillMaxWidth(), Accent, enabled = saveName.isNotBlank() && sql.isNotBlank() && selectedDb.isNotBlank()) {
        queries = queries.filterNot { it.name == saveName.trim() } + AppSalQuery(saveName.trim(), selectedDb, sql.trim())
        QaLensAppSal.setQueries(context, queries)
        saveName = ""
    }

    result?.let { r ->
        Spacer(Modifier.height(8.dp))
        when {
            r.error != null -> Text("Query failed: ${r.error}", color = Red, fontSize = 12.sp)
            r.rowsAffected >= 0 -> Text("${r.rowsAffected} row(s) affected · ${r.durationMs}ms", color = Amber, fontSize = 12.sp)
            else -> {
                val visibleRows = if (showAll) r.rows else r.rows.take(12)
                val columns = r.columns.take(30)
                Text("${visibleRows.size} row previews · ${r.rows.size} rows available · limit 100 · ${r.durationMs}ms",
                    color = Green, fontSize = 12.sp)
                Text("Scroll within results for rows and sideways for columns. Cell previews: 80 characters.", color = TxtMuted, fontSize = 11.sp)
                if (r.columns.size > columns.size) Text("Showing the first 30 of ${r.columns.size} columns. Select the columns you need in SQL.", color = Amber, fontSize = 11.sp)
                Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .background(Color.White.copy(alpha = .04f), RoundedCornerShape(8.dp)).padding(8.dp)) {
                    Row { columns.forEach { Text(it, color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(160.dp).padding(6.dp)) } }
                    if (visibleRows.isEmpty()) Text("No rows returned.", color = TxtMuted, fontSize = 12.sp)
                    else LazyColumn(Modifier.width(160.dp * columns.size).heightIn(max = 260.dp)
                        .semantics { contentDescription = "SQL result rows" }) {
                        items(visibleRows.size) { index ->
                            Row { visibleRows[index].take(columns.size).forEach { cell -> Text(cell, color = TxtMain,
                                fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 3,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.width(160.dp).padding(6.dp)) } }
                        }
                    }
                }
                if (r.rows.size > 12) DataButton(if (showAll) "Show first 12 rows" else "Show all ${r.rows.size} rows",
                    Modifier.fillMaxWidth()) { showAll = !showAll }
            }
        }
    }

    // Saved queries
    if (queries.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Text("Saved queries", color = TxtMain, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        queries.forEach { q ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(q.name, color = TxtMain, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Text("[${q.db}] ${q.sql.take(120)}", color = TxtMuted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DataButton("Run", Modifier.weight(1f), Green, enabled = !queryRunning && config.enabled, description = "Run saved query ${q.name}") {
                        selectedDb = q.db.ifBlank { selectedDb }
                        sql = q.sql
                        runQuery(selectedDb, q.sql)
                    }
                    DataButton("Delete", Modifier.weight(1f), Red, description = "Delete saved query ${q.name}") {
                        queries = queries.filterNot { it.name == q.name }
                        QaLensAppSal.setQueries(context, queries)
                    }
                }
            }
        }
    }
}

// ── App data section (SharedPrefs + DataStore) ──────────────────────────────

@Composable
private fun AppDataSection(context: Context) {
    val state by QaLens.state.collectAsState()
    val config by QaLens.config.collectAsState()
    val statuses by QaLens.dataStoreValueStatus.collectAsState()
    var expandedSource by remember { mutableStateOf<String?>(null) }
    var seeded by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var revision by remember { mutableStateOf(0) }
    var showFiles by remember { mutableStateOf(false) }
    var expandedPrefs by remember { mutableStateOf<String?>(null) }
    var prefsFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var dataStoreFiles by remember { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }
    var prefValues by remember { mutableStateOf<Map<String, String>?>(null) }
    var prefPolicy by remember { mutableStateOf<QaLensConfig?>(null) }
    var encryptedPrefs by remember { mutableStateOf(false) }
    val input = Triple(state.dataSources, config, search)
    val preview by backgroundPanelState(input, null as Triple<QaLensConfig, String, Map<String, Map<String, String>>>?) { (sources, cfg, query) ->
        val filtered = sources.entries.take(30).mapNotNull { (name, values) ->
            val fields = DataValuePreview.sanitize(values, cfg).filter { (key, value) ->
                query.isBlank() || name.contains(query, true) || key.contains(query, true) || value.contains(query, true)
            }
            if (query.isBlank() || fields.isNotEmpty() || name.contains(query, true)) name to fields else null
        }.toMap()
        Triple(cfg, query, filtered)
    }
    // Additional privacy rules apply before displaying cached evidence; stale policy previews hide.
    val ready = preview?.let { it.first == config && it.second == search } == true
    val sources = if (config.enabled && ready) preview?.third.orEmpty() else emptyMap()
    LaunchedEffect(sources.keys, statuses.keys) {
        if (!seeded && sources.isNotEmpty()) {
            expandedSource = statuses.keys.firstOrNull { it in sources } ?: sources.keys.firstOrNull()
            seeded = true
        }
    }
    LaunchedEffect(revision, showFiles) {
        if (showFiles) {
            val files = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                QaLensDataTools.sharedPrefsFiles(context.applicationContext).filterNot { it.startsWith("qalens") } to
                    QaLensDataTools.dataStoreFiles(context.applicationContext)
            }
            prefsFiles = files.first; dataStoreFiles = files.second
        }
    }
    LaunchedEffect(expandedPrefs, revision, config) {
        prefValues = null; prefPolicy = null; encryptedPrefs = false
        val name = expandedPrefs ?: return@LaunchedEffect
        if (!config.enabled) return@LaunchedEffect
        val loaded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val raw = QaLensDataTools.readSharedPrefs(context.applicationContext, name)
            val encrypted = raw.keys.any { it.startsWith("__androidx_security_crypto_encrypted_prefs_") }
            encrypted to if (encrypted) emptyMap() else DataValuePreview.sanitize(raw, config)
        }
        encryptedPrefs = loaded.first; prefValues = loaded.second; prefPolicy = config
    }

    Text("Live app values", color = TxtMain, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    Text("Read-only fields exposed by the app. Values are redacted; changes update here and join recording evidence.",
        color = TxtMuted, fontSize = 12.sp)
    DataButton("Refresh app data", Modifier.fillMaxWidth(), enabled = config.enabled) { revision++; QaLens.refreshAppData() }
    if (!config.enabled) Text("QaLens is disabled; values are hidden and observation is paused.", color = Amber, fontSize = 12.sp)
    else if (ready && sources.isEmpty() && statuses.isEmpty() && search.isBlank()) Text(
        "No values connected. Ask the app developer to expose allowed settings from the existing DataStore or app state. Encrypted stores need the app's decoded values.",
        color = TxtMuted, fontSize = 12.sp)
    if (state.dataSources.isNotEmpty() || statuses.isNotEmpty()) SettingField("Search app data", search, "Source, key or value") { search = it.take(200) }
    if (config.enabled && !ready) Text("Preparing app values…", color = TxtMuted, fontSize = 12.sp)
    val names = (sources.keys + statuses.keys.filter { search.isBlank() || it.contains(search, true) }).sorted().take(30)
    var matches = 0
    var remainingFields = 100
    var omittedFields = false
    names.forEach { name ->
        val fields = sources[name].orEmpty()
        if (search.isBlank() || fields.isNotEmpty() || name.contains(search, true)) {
            matches++
            val status = statuses[name]
            val label = when (status?.phase) {
                DataStoreValuePhase.WAITING -> "Waiting for values"
                DataStoreValuePhase.LIVE -> "Receiving updates"
                DataStoreValuePhase.PAUSED -> "Observation paused"
                DataStoreValuePhase.STOPPED -> "Source ended · last values"
                DataStoreValuePhase.ERROR -> "Source unavailable · last values"
                null -> "App snapshot"
            }
            DataButton(name, Modifier.fillMaxWidth(), Accent, description = "App values source $name") {
                expandedSource = if (expandedSource == name) null else name
            }
            val fieldCount = fields.size
            Text("$label · $fieldCount ${if (fieldCount == 1) "field" else "fields"}" +
                status?.updatedAtMillis?.let { " · received ${formatDate(it)}" }.orEmpty(), color = TxtMuted, fontSize = 11.sp)
            if (expandedSource == name || search.isNotBlank()) {
                if (fields.isEmpty()) Text("No exposed fields yet.", color = TxtMuted, fontSize = 12.sp)
                fields.forEach { (key, value) ->
                    if (remainingFields > 0) { DataValueRow(key, value); remainingFields-- }
                    else omittedFields = true
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (config.enabled && ready && search.isNotBlank() && matches == 0) Text("No matching app values.", color = TxtMuted, fontSize = 12.sp)
    if (omittedFields) Text("Showing the first 100 matching fields. Refine search to see other values.", color = Amber, fontSize = 12.sp)
    Text("Previews show up to 30 sources and 100 fields per source. File details below are separate from live values.", color = TxtMuted, fontSize = 11.sp)
    DataButton(if (showFiles) "Hide storage files" else "Show storage files", Modifier.fillMaxWidth()) { showFiles = !showFiles }
    if (showFiles) {
        Text("Preferences files (${prefsFiles.size})", color = TxtMain, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        Text("Tap to read a redacted snapshot. SDK configuration files are excluded.", color = TxtMuted, fontSize = 11.sp)
        prefsFiles.take(50).forEach { name ->
            DataButton(name, Modifier.fillMaxWidth(), description = "Read preferences $name") { expandedPrefs = if (expandedPrefs == name) null else name }
            if (expandedPrefs == name && config.enabled) {
                when {
                    prefValues == null || prefPolicy != config -> Text("Reading preferences…", color = TxtMuted, fontSize = 12.sp)
                    encryptedPrefs -> Text("Encrypted preferences: connect decoded fields from the app to Live app values.", color = Amber, fontSize = 12.sp)
                    prefValues!!.isEmpty() -> Text("No readable preference values.", color = TxtMuted, fontSize = 12.sp)
                    else -> prefValues!!.forEach { (key, value) -> DataValueRow(key, value) }
                }
            }
        }
        Text("DataStore backing files (${dataStoreFiles.size})", color = TxtMain, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        Text("File metadata only. The app owns the serializer and any decryption; connect decoded fields above to inspect values.", color = TxtMuted, fontSize = 12.sp)
        dataStoreFiles.take(50).forEach { (name, size) -> Text("${name.take(200)} · $size B", color = TxtMuted, fontSize = 12.sp) }
    }
}

@Composable
private fun DataValueRow(key: String, value: String) {
    Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha = .04f), RoundedCornerShape(8.dp)).padding(10.dp)) {
        Text(key, color = Accent, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(value, color = TxtMain, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
            maxLines = 6, overflow = TextOverflow.Ellipsis)
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun DataButton(label: String, modifier: Modifier = Modifier, tint: Color = TxtMuted,
    enabled: Boolean = true, description: String = label, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 48.dp).background(tint.copy(alpha = if (enabled) .12f else .05f), RoundedCornerShape(8.dp))
        .semantics { contentDescription = description }.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) tint else TxtMuted.copy(alpha = .6f), fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Labeled, persisted-on-change text field (Webhook / Macros / Database cards). */
@Composable
private fun SettingField(
    label: String,
    value: String,
    placeholder: String,
    singleLine: Boolean = true,
    onChange: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        if (label.isNotBlank()) {
            Text(label, color = TxtMuted, fontSize = 10.sp)
            Spacer(Modifier.height(3.dp))
        }
        Box(
            Modifier.fillMaxWidth()
                .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                .border(1.dp, if (value.isNotBlank()) Accent.copy(alpha = 0.5f) else CardLine, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 9.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = TextStyle(color = TxtMain, fontSize = 12.sp),
                singleLine = singleLine,
                modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp)
                    .semantics { contentDescription = label.ifBlank { placeholder } }
            )
        }
        if (value.isEmpty()) Text(placeholder, color = TxtMuted, fontSize = 11.sp)
    }
}

/** Live upload status under a recording / the test button. */
private fun webhookReplySummary(body: String): String {
    val reply = runCatching { JSONObject(body) }.getOrNull()
    if (reply != null) {
        val error = reply.optString("error").trim()
        if (error.isNotEmpty()) return error.take(120)
        val message = reply.optString("message").trim()
        if (message.isNotEmpty()) return message.take(120)
        if (reply.optString("engine").startsWith("qalens-mock-")) {
            val severity = reply.optString("severity").trim()
            val owner = reply.optString("likelyOwner").trim()
            if (severity.isNotEmpty()) return "Local demo verdict: $severity" +
                owner.takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()
        }
        return reply.optString("summary").trim().replace(Regex("\\s+"), " ").take(120)
    }
    val plain = body.trim().replace(Regex("\\s+"), " ")
    return plain.takeIf { !it.startsWith("{") && !it.startsWith("<") }?.take(120).orEmpty()
}

@Composable
private fun WebhookStatusLine(state: QaLensWebhook.UploadState?) {
    when (state) {
        null -> Unit
        is QaLensWebhook.UploadState.Uploading -> {
            Spacer(Modifier.height(5.dp))
            Text("⇪ Uploading…", color = Amber, fontSize = 10.sp)
        }
        is QaLensWebhook.UploadState.Done -> {
            Spacer(Modifier.height(5.dp))
            val tint = if (state.success) Green else Red
            val verdict = if (state.success) "✓ Backend accepted" else "✕ Backend rejected"
            val detail = webhookReplySummary(state.body)
            Text("$verdict (HTTP ${state.code})" + detail.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                color = tint, fontSize = 10.sp)
        }
        is QaLensWebhook.UploadState.Failed -> {
            Spacer(Modifier.height(5.dp))
            Text("✕ Upload failed · ${state.error}", color = Red, fontSize = 10.sp)
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TxtMain, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = TxtMuted, fontSize = 10.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Green, checkedThumbColor = Color.White)
        )
    }
}

@Composable
private fun PermissionRow(title: String, subtitle: String, onGrant: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TxtMain, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = TxtMuted, fontSize = 10.sp)
        }
        SmallButton("Grant", Accent, onGrant)
    }
}

private fun formatDate(millis: Long): String =
    java.text.SimpleDateFormat("MMM d · HH:mm", java.util.Locale.US).format(java.util.Date(millis))
