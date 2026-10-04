package com.qalens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qalens.android.QaLensAppSal
import com.qalens.android.QaLensPrefs

/**
 * The tester-first surface. Keep the first view to three jobs: record, capture, and mark a bug.
 * Inspection, macros, and setup remain available under More tools and in the developer panel.
 */
@Composable
internal fun QaLensMinimalPanel(
    modifier: Modifier = Modifier,
    state: QaLensUiState,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val copyAsync = backgroundCopier(context)
    val colors = qaLensColorsFor(context)
    val macros = remember { QaLensAppSal.recentMacros(context, limit = 5) }
    val uploadStates by QaLensWebhook.states.collectAsState()
    var showMore by remember { mutableStateOf(false) }
    val latestRecording = state.recordings.firstOrNull()
    val webhookConfigured = QaLensPrefs.webhookUrl(context).isNotBlank()
    val uploadState = latestRecording?.let { uploadStates[it.path] }

    Column(
        modifier
            .width(QaLensDimens.sheetWidth)
            .background(colors.panel, RoundedCornerShape(QaLensDimens.sheetRadius))
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .semantics { contentDescription = "Quick actions sheet" }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(10.dp).background(
                    if (state.isRecording) colors.err else colors.accent,
                    CircleShape
                )
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Quick actions", color = colors.fg, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(
                    state.screen.displayName.ifBlank { "Ready to test" },
                    color = colors.fg2,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                "Close",
                color = colors.fg2,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.heightIn(min = QaLensDimens.touchMin)
                    .clickable(role = Role.Button, onClick = onClose)
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            )
        }

        Spacer(Modifier.height(16.dp))

        QuickAction(
            title = when {
                state.isSavingRecording -> "Saving session…"
                state.isRecording -> "Stop & save recording"
                else -> "Record a session"
            },
            detail = when {
                state.isSavingRecording -> "Keep using the app while it saves"
                state.isRecording -> "Finish this capture and save it to this device"
                else -> "Capture your steps and app activity"
            },
            symbol = if (state.isRecording) "■" else "●",
            tint = if (state.isRecording) colors.err else colors.ok,
            colors = colors,
            enabled = !state.isSavingRecording
        ) {
            onClose()
            QaLens.toggleRecording()
        }

        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) {
                CompactAction("Screenshot", "▣", colors.info, colors) {
                    onClose()
                    QaLens.takeScreenshot(share = false)
                }
            }
            Box(Modifier.weight(1f)) {
                CompactAction("Mark a bug", "★", colors.warn, colors) {
                    onClose()
                    QaLens.markMoment("Marked by QA")
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "Saved in this app. Tap Share or Send when you're ready.",
            color = colors.fg2,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            style = TextStyle(textDirection = TextDirection.ContentOrLtr)
        )

        if (webhookConfigured && latestRecording != null) {
            Spacer(Modifier.height(12.dp))
            QuickAction(
                title = when (uploadState) {
                    QaLensWebhook.UploadState.Uploading -> "Sending latest session…"
                    is QaLensWebhook.UploadState.Done -> if (uploadState.success) "Session sent" else "Send latest session again"
                    is QaLensWebhook.UploadState.Failed -> "Try sending again"
                    null -> "Send latest session"
                },
                detail = "${latestRecording.formattedSize} · ${latestRecording.name}",
                symbol = "↑",
                tint = when (uploadState) {
                    QaLensWebhook.UploadState.Uploading -> colors.info
                    is QaLensWebhook.UploadState.Done -> if (uploadState.success) colors.ok else colors.err
                    is QaLensWebhook.UploadState.Failed -> colors.err
                    null -> colors.accent
                },
                colors = colors,
                enabled = uploadState !is QaLensWebhook.UploadState.Uploading
            ) {
                QaLensWebhook.upload(context, latestRecording)
            }
            when (uploadState) {
                is QaLensWebhook.UploadState.Done -> Text(
                    if (uploadState.success) "The backend accepted this session." else "The backend returned HTTP ${uploadState.code}.",
                    color = if (uploadState.success) colors.ok else colors.err,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
                is QaLensWebhook.UploadState.Failed -> Text(
                    "Could not send. Check the team endpoint in Control Room.",
                    color = colors.err,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
                else -> Unit
            }
        } else if (!webhookConfigured) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Set up team sharing",
                color = colors.accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.heightIn(min = QaLensDimens.touchMin)
                    .clickable(role = Role.Button) { openControlRoom(context) }
                    .padding(vertical = 8.dp)
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            if (showMore) "Hide more tools  −" else "More tools  +",
            color = colors.fg2,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.heightIn(min = QaLensDimens.touchMin)
                .semantics(mergeDescendants = true) {
                    contentDescription = if (showMore) "Hide more tools" else "More tools"
                    stateDescription = if (showMore) "Expanded" else "Collapsed"
                }
                .clickable(role = Role.Button) { showMore = !showMore }
                .padding(vertical = 8.dp)
        )

        if (showMore) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    CompactAction("Copy bug report", "▤", colors.accent, colors) {
                        copyAsync("QaLens Bug") { QaLens.buildJiraReport() }
                        QaLens.log("Bug report copied")
                    }
                }
                Box(Modifier.weight(1f)) {
                    CompactAction("Device info", "▧", colors.info, colors) {
                        val d = state.device
                        copyText(
                            context,
                            "QaLens Device",
                            "${d.appName} ${d.appVersion} (${d.buildVariant})" +
                                (d.environment?.let { " · $it" } ?: "") +
                                (d.gitSha?.let { " · git $it" } ?: "") + "\n" +
                                "${d.manufacturer} ${d.deviceModel} · Android ${d.androidVersion} (SDK ${d.sdkVersion})\n" +
                                "screen ${d.screenWidthDp}×${d.screenHeightDp}dp · font ×${d.fontScale} · " +
                                (if (d.isRtl) "RTL" else "LTR") + " · screen: ${state.screen.displayName}"
                        )
                        QaLens.log("Device info copied")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    CompactAction("Automation tags", "#", colors.ok, colors) {
                        onClose()
                        QaLens.setTagMode(true)
                    }
                }
                Box(Modifier.weight(1f)) {
                    CompactAction("Control Room", "⚙", colors.accent, colors) {
                        openControlRoom(context)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            QaLensSelectorLauncher(colors)
            QaLensPcInspectorControls(colors)

            if (macros.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("RECENT MACROS", color = colors.fg2, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                macros.forEach { macro ->
                    Row(
                        Modifier.fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .background(colors.panel2, RoundedCornerShape(QaLensDimens.rSm))
                            .clickable(role = Role.Button) { onClose(); QaLensMacros.run(macro) }
                            .heightIn(min = QaLensDimens.touchMin)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(macro.name, color = colors.fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("${macro.steps.size} steps", color = colors.fg2, fontSize = 11.sp)
                        }
                        QaLensMacros.lastRunResult(macro.name)?.let { result ->
                            Text(
                                if (result.passed) "Passed" else "Failed",
                                color = if (result.passed) colors.ok else colors.err,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Developer diagnostics",
                    color = colors.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.heightIn(min = QaLensDimens.touchMin)
                        .clickable(role = Role.Button) { QaLens.setPanelMinimal(false) }
                        .padding(vertical = 8.dp)
                )
                Text(
                    "Watch app",
                    color = colors.fg2,
                    fontSize = 12.sp,
                    modifier = Modifier.heightIn(min = QaLensDimens.touchMin)
                        .clickable(role = Role.Button) { QaLens.setWatchMode(true) }
                        .padding(vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun QuickAction(
    title: String,
    detail: String,
    symbol: String,
    tint: Color,
    colors: QaLensOverlayColors,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .background(colors.panel2, RoundedCornerShape(QaLensDimens.rMd))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 68.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(symbol, modifier = Modifier.clearAndSetSemantics {}, color = tint, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = colors.fg, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(detail, color = colors.fg2, fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}

@Composable
private fun CompactAction(label: String, symbol: String, tint: Color, colors: QaLensOverlayColors, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(colors.panel2, RoundedCornerShape(QaLensDimens.rMd))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(symbol, modifier = Modifier.clearAndSetSemantics {}, color = tint, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(3.dp))
        Text(label, color = colors.fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

private fun copyText(context: Context, label: String, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun openControlRoom(context: Context) {
    runCatching {
        context.startActivity(
            Intent(context, QaLensControlActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
