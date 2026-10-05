package com.qalens

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qalens.android.QaLensPrefs

/**
 * The tester-first surface. Keep the first view to three jobs: record, capture, and mark a bug.
 * Inspect and review evidence are direct destinations. Configuration belongs in Control Room.
 */
@Composable
internal fun QaLensMinimalPanel(
    modifier: Modifier = Modifier,
    state: QaLensUiState,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val colors = qaLensColorsFor(context)
    val config by QaLens.config.collectAsState()
    val uploadStates by QaLensWebhook.states.collectAsState()
    val latestRecording = state.recordings.firstOrNull()
    val webhookConfigured = QaLensPrefs.webhookUrl(context).isNotBlank()
    val uploadState = latestRecording?.let { uploadStates[it.path] }

    Column(
        modifier
            .width(QaLensDimens.sheetWidth)
            .background(colors.panel, RoundedCornerShape(QaLensDimens.sheetRadius))
            .padding(16.dp)
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
            Box(Modifier.heightIn(min = QaLensDimens.touchMin).width(64.dp)
                    .clickable(role = Role.Button, onClick = onClose)
                    .padding(6.dp), contentAlignment = Alignment.Center) {
                Text("Close", color = colors.fg2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Box(Modifier.fillMaxWidth().heightIn(min = QaLensDimens.touchMin)
            .background(colors.panel2, RoundedCornerShape(QaLensDimens.rMd))
            .semantics { contentDescription = "Review evidence" }
            .clickable(role = Role.Button) { QaLens.setPanelMinimal(false) }.padding(8.dp),
            contentAlignment = Alignment.Center) {
            Text("‹ Review evidence", color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
            .semantics { contentDescription = "Quick action list" }) {
            Spacer(Modifier.height(16.dp))

            if (state.isSavingRecording || state.isRecording) QuickAction(
                title = when {
                    state.isSavingRecording -> "Saving session…"
                    state.isRecording -> "Stop & save recording"
                    else -> "Stop & save recording"
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
            else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) {
                        CompactAction("Record", "Masked frames", "●", colors.ok, colors,
                            accessibilityLabel = "Record frames") { onClose(); QaLens.startRecording(video = false) }
                    }
                    Box(Modifier.weight(1f)) {
                        CompactAction("Record HD", "Full screen video", "●", colors.info, colors,
                            enabled = config.allowUnmaskedVideo, accessibilityLabel = "Record HD video") {
                            onClose(); QaLens.startRecording(video = true)
                        }
                    }
                }
                if (!config.allowUnmaskedVideo) Text("HD is disabled by this app’s privacy settings.",
                    color = colors.fg2, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }

            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    CompactAction("Screenshot", "Save current view", "▣", colors.info, colors) {
                        onClose()
                        QaLens.takeScreenshot(share = false)
                    }
                }
                Box(Modifier.weight(1f)) {
                    CompactAction("Mark a bug", "Flag + screenshot", "★", colors.warn, colors) {
                        onClose()
                        QaLens.markMoment("Marked by QA")
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "Saved locally. Share and replay in Control Room.",
                color = colors.fg2,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                style = TextStyle(textDirection = TextDirection.ContentOrLtr)
            )

            state.errors.lastOrNull()?.let { failure ->
                Text("Could not complete action: ${failure.message.take(500)}", color = colors.err, fontSize = 12.sp,
                    maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(10.dp))
            QuickAction("Inspect elements", "Select on the app; see tags, actions and selectors", "⌖", colors.accent, colors) {
                onClose()
                QaLens.setWatchMode(false)
                QaLens.setInspectMode(true)
            }
            Spacer(Modifier.height(10.dp))
            QuickAction("Inspect tags", "Show automation tags; tap a component to copy", "#", colors.accent, colors) {
                onClose()
                QaLens.setWatchMode(false)
                QaLens.setTagMode(true)
            }
            Spacer(Modifier.height(14.dp))
            QaLensPcInspectorLauncher(colors)
            Destination("Control Room", "Saved recordings, HD capture and team settings", colors) {
                onClose()
                openControlRoom(context)
            }
            if (webhookConfigured && latestRecording != null) {
                Spacer(Modifier.height(12.dp))
                QuickAction(
                    title = when (uploadState) {
                        QaLensWebhook.UploadState.Uploading -> "Sending latest session…"
                        is QaLensWebhook.UploadState.Done -> if (uploadState.success) "Session sent" else "Send latest session again"
                        is QaLensWebhook.UploadState.Failed -> "Try sending again"
                        null -> "Send latest session"
                    },
                    detail = when (uploadState) {
                        is QaLensWebhook.UploadState.Done -> if (uploadState.success) "The backend accepted this session." else "The backend returned HTTP ${uploadState.code}."
                        is QaLensWebhook.UploadState.Failed -> "Could not send. Check the team endpoint in Control Room."
                        else -> "${latestRecording.formattedSize} · ${latestRecording.name}"
                    },
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
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.fg, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(detail, color = colors.fg2, fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}

@Composable
private fun CompactAction(label: String, detail: String, symbol: String, tint: Color, colors: QaLensOverlayColors,
    enabled: Boolean = true, accessibilityLabel: String = label, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(colors.panel2, RoundedCornerShape(QaLensDimens.rMd))
            .semantics { contentDescription = accessibilityLabel }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val alpha = if (enabled) 1f else .5f
        Text(symbol, modifier = Modifier.clearAndSetSemantics {}, color = tint.copy(alpha = alpha), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(3.dp))
        Text(label, color = colors.fg.copy(alpha = alpha), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(detail, color = colors.fg2.copy(alpha = alpha), fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun Destination(title: String, detail: String, colors: QaLensOverlayColors, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp)) {
        Text(title, color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(detail, color = colors.fg2, fontSize = 11.sp, lineHeight = 14.sp)
    }
}

private fun openControlRoom(context: Context) {
    runCatching {
        context.startActivity(
            Intent(context, QaLensControlActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
