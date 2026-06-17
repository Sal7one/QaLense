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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
private fun armAndJump(context: Context, video: Boolean) {
    val launch = hostLaunchIntent(context)
    if (launch == null) {
        QaLens.log("Control Room: no host launcher activity found")
        return
    }
    QaLens.armRecording(video)
    context.startActivity(launch)
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
    var webhookUrl by remember { mutableStateOf(QaLensPrefs.webhookUrl(context)) }
    // Bumps when the active QA profile switches so webhook fields re-seed from the new prefs.
    var profilesVersion by remember { mutableStateOf(0) }
    // Two-tap delete: first tap arms ("Confirm?"), second deletes. Path of the armed recording,
    // or "*" for clear-all. Tapping anything else disarms.
    var armedDelete by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().background(Bg)
            .verticalScroll(rememberScrollState())
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
            StatusPill(if (state.isRecording) "● REC" else "idle", if (state.isRecording) Red else TxtMuted)
            StatusPill(if (state.overlayEnabled) "overlay on" else "overlay off", if (state.overlayEnabled) Green else Amber)
            StatusPill(if (notifGranted) "notif ✓" else "notif ✕", if (notifGranted) Green else Red)
            StatusPill(if (drawOverGranted) "float ✓" else "float ✕", if (drawOverGranted) Green else Amber)
        }

        // ── Recording ──
        ControlCard("Session Recording") {
            if (state.isRecording) {
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
                BigButton("●  Record Session (frames, no permission)", Green) { armAndJump(context, video = false) }
                Spacer(Modifier.height(6.dp))
                BigButton("●  Record HD Video (MediaProjection)", Accent) { armAndJump(context, video = true) }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (drawOverGranted)
                    "Stop control: floating chip over any screen + notification + shake."
                else
                    "Stop control: in-app REC chip + notification + shake. Grant “Draw over apps” below for a floating chip that survives navigation.",
                color = TxtMuted, fontSize = 10.sp
            )
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
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                "${RecordingInfo.humanSize(state.recordingsBytes)} on device · newest 5 kept automatically",
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
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                SettingField("Endpoint URL", webhookUrl, "https://qa.example.com/api/sal") {
                    webhookUrl = it
                    QaLensPrefs.setWebhookUrl(context, it)
                    QaLensProfiles.syncActiveFromPrefs(context)
                }

                var headerName by remember { mutableStateOf(QaLensPrefs.webhookHeaderName(context)) }
                var headerValue by remember { mutableStateOf(QaLensPrefs.webhookHeaderValue(context)) }
                var params by remember { mutableStateOf(QaLensPrefs.webhookParams(context)) }
                var includeMeta by remember { mutableStateOf(QaLensPrefs.webhookIncludeMeta(context)) }

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
                "QA Minimal panel",
                "Big colorful actions + one-tap macros for testers. Off = the full developer panel.",
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
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
    Text(
        label, color = tint, fontSize = 12.sp,
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
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

    Text("Macros", color = TxtMain, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    Text(
        "One step per line: deeplink <uri> · wait <ms> · tap <tag|text> · type <tag> <text> · record [video] · stop · screenshot · mark <text>. tap/type wait up to 5s for the target — a macro can complete a full login alone.",
        color = TxtMuted, fontSize = 10.sp
    )
    Spacer(Modifier.height(6.dp))
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
            SmallButton("▶ Run", Green) { QaLensMacros.run(m) }
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
    var dbs by remember { mutableStateOf(QaLensDataTools.databases(context)) }
    var selectedDb by remember { mutableStateOf(dbs.firstOrNull() ?: "") }
    var sql by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<QaLensDataTools.QueryResult?>(null) }
    var queries by remember { mutableStateOf(QaLensAppSal.queries(context)) }
    var saveName by remember { mutableStateOf("") }

    if (dbs.isEmpty()) {
        Text("No SQLite databases in this app yet.", color = TxtMuted, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        SmallButton("Rescan") { dbs = QaLensDataTools.databases(context); selectedDb = dbs.firstOrNull() ?: "" }
        return
    }

    // DB picker chips
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        dbs.take(4).forEach { db ->
            val active = db == selectedDb
            Text(
                db, fontSize = 11.sp,
                color = if (active) Accent else TxtMuted,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .background(if (active) Accent.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
                    .clickable { selectedDb = db }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    SettingField("SQL (SELECT renders rows · writes report rows affected)", sql,
        "SELECT * FROM accounts LIMIT 10", singleLine = false) { sql = it }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SmallButton("▶ Run", Green) {
            if (sql.isNotBlank()) result = QaLensDataTools.runQuery(context, selectedDb, sql)
        }
        SmallButton("Save as…", Accent) {
            if (saveName.isNotBlank() && sql.isNotBlank()) {
                queries = queries.filterNot { it.name == saveName } + AppSalQuery(saveName.trim(), selectedDb, sql.trim())
                QaLensAppSal.setQueries(context, queries)
                saveName = ""
            }
        }
        Box(Modifier.weight(1f)) {
            SettingField("", saveName, "query name") { saveName = it }
        }
    }

    // Result
    result?.let { r ->
        Spacer(Modifier.height(6.dp))
        when {
            r.error != null -> Text("✕ ${r.error}", color = Red, fontSize = 11.sp)
            r.rowsAffected >= 0 -> Text(
                "✓ ${r.rowsAffected} row(s) affected · ${r.durationMs}ms",
                color = Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
            )
            else -> Column(
                Modifier.fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Text("${r.totalRows} row(s) · ${r.durationMs}ms", color = Green, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                Text(r.columns.joinToString(" │ "), color = Accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                r.rows.take(12).forEach { row ->
                    Text(row.joinToString(" │ "), color = TxtMain, fontSize = 10.sp, maxLines = 1)
                }
                if (r.totalRows > 12) Text("… +${r.totalRows - 12} more", color = TxtMuted, fontSize = 10.sp)
            }
        }
    }

    // Saved queries
    if (queries.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Text("Saved queries", color = TxtMain, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        queries.forEach { q ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(q.name, color = TxtMain, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Text("[${q.db}] ${q.sql.take(60)}", color = TxtMuted, fontSize = 9.5.sp, maxLines = 1)
                }
                SmallButton("▶") {
                    selectedDb = q.db.ifBlank { selectedDb }
                    sql = q.sql
                    result = QaLensDataTools.runQuery(context, selectedDb, q.sql)
                }
                Spacer(Modifier.width(4.dp))
                SmallButton("✕", Red) {
                    queries = queries.filterNot { it.name == q.name }
                    QaLensAppSal.setQueries(context, queries)
                }
            }
        }
    }
}

// ── App data section (SharedPrefs + DataStore) ──────────────────────────────

@Composable
private fun AppDataSection(context: Context) {
    var expanded by remember { mutableStateOf<String?>(null) }
    val prefsFiles = remember { QaLensDataTools.sharedPrefsFiles(context) }
    val dataStoreFiles = remember { QaLensDataTools.dataStoreFiles(context) }

    Text("SharedPreferences (${prefsFiles.size})", color = TxtMain, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    Spacer(Modifier.height(4.dp))
    if (prefsFiles.isEmpty()) Text("No SharedPreferences files.", color = TxtMuted, fontSize = 11.sp)
    prefsFiles.forEach { name ->
        Column(
            Modifier.fillMaxWidth()
                .padding(bottom = 4.dp)
                .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(8.dp))
                .clickable { expanded = if (expanded == name) null else name }
                .padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            Text((if (expanded == name) "▾ " else "▸ ") + name, color = TxtMain, fontSize = 11.sp)
            if (expanded == name) {
                Spacer(Modifier.height(4.dp))
                val values = QaLensDataTools.readSharedPrefs(context, name)
                if (values.isEmpty()) Text("(empty)", color = TxtMuted, fontSize = 10.sp)
                values.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(k, color = TxtMuted, fontSize = 10.sp, modifier = Modifier.weight(0.45f))
                        Text(v, color = TxtMain, fontSize = 10.sp, modifier = Modifier.weight(0.55f))
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    Text("DataStore files (${dataStoreFiles.size})", color = TxtMain, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    Spacer(Modifier.height(4.dp))
    if (dataStoreFiles.isEmpty()) Text("No DataStore files.", color = TxtMuted, fontSize = 11.sp)
    dataStoreFiles.forEach { (name, size) ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(name, color = TxtMain, fontSize = 11.sp)
            Text("${size} B", color = TxtMuted, fontSize = 10.sp)
        }
    }
    Text(
        "DataStore values are protobuf — live values surface via QaLens.observeDataStore/registerDataSource.",
        color = TxtMuted, fontSize = 9.5.sp
    )
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
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.isEmpty()) Text(placeholder, color = TxtMuted, fontSize = 12.sp)
                    inner()
                }
            )
        }
    }
}

/** Live upload status under a recording / the test button. */
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
            Text(
                "$verdict (HTTP ${state.code})" +
                    state.body.takeIf { it.isNotBlank() }?.let { " · ${it.take(160)}" }.orEmpty(),
                color = tint, fontSize = 10.sp
            )
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
