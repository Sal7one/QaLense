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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qalens.android.QaLensAppSal

private val MBg = Color(0xF80E1322)
private val MText = Color.White
private val MMuted = Color(0xFF93A1B5)
private val MGreen1 = Color(0xFF22C55E); private val MGreen2 = Color(0xFF15803D)
private val MRed1 = Color(0xFFF87171);  private val MRed2 = Color(0xFFB91C1C)
private val MBlue1 = Color(0xFF60A5FA); private val MBlue2 = Color(0xFF3730A3)
private val MAmber1 = Color(0xFFFBBF24); private val MAmber2 = Color(0xFFB45309)
private val MPurple1 = Color(0xFFC084FC); private val MPurple2 = Color(0xFF7C3AED)
private val MTeal1 = Color(0xFF2DD4BF);  private val MTeal2 = Color(0xFF0F766E)

/**
 * The QA-minimal panel — what testers (not developers) see when they open QaLens, if the
 * "QA Minimal" style is selected in the Control Room (or via `.appsal`). Big colorful actions,
 * one-tap macros, zero settings. The full developer panel stays one tap away.
 */
@Composable
internal fun QaLensMinimalPanel(
    modifier: Modifier = Modifier,
    state: QaLensUiState,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    // Top 5, most-recently-used first — the login macro you ran this morning is always on top.
    val macros = remember { QaLensAppSal.recentMacros(context, limit = 5) }
    val failed = state.networkEvents.count { it.isError }

    Column(
        modifier
            .width(310.dp)
            .background(MBg, RoundedCornerShape(22.dp))
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).background(if (state.isRecording) MRed1 else MGreen1, CircleShape))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("QA Mode", color = MText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(state.screen.displayName, color = MMuted, fontSize = 11.sp)
            }
            Text("✕", color = MMuted, fontSize = 16.sp,
                modifier = Modifier.clickable(onClick = onClose).padding(6.dp))
        }

        Spacer(Modifier.height(10.dp))

        // Status chips — tappable: they switch to the full panel so QA can dig into the details.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusChip(
                if (failed > 0) "$failed failed calls →" else "network ok",
                if (failed > 0) MRed1 else MGreen1
            ) { if (failed > 0) QaLens.setPanelMinimal(false) }
            StatusChip(
                if (state.warnings.isEmpty()) "0 warnings" else "${state.warnings.size} warnings →",
                if (state.warnings.isEmpty()) MGreen1 else MAmber1
            ) { if (state.warnings.isNotEmpty()) QaLens.setPanelMinimal(false) }
        }

        Spacer(Modifier.height(14.dp))

        // Big record button
        BigAction(
            label = if (state.isRecording) "■  STOP & SHARE RECORDING" else "●  RECORD MY SESSION",
            sub = if (state.isRecording) "recording…" else "captures screen + everything happening",
            c1 = if (state.isRecording) MRed1 else MGreen1,
            c2 = if (state.isRecording) MRed2 else MGreen2
        ) { QaLens.toggleRecording() }

        Spacer(Modifier.height(10.dp))

        // Action grid — screenshot/mark save straight to Photos (no share sheet in the way).
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) {
                SquareAction("📷", "Screenshot", MBlue1, MBlue2) {
                    onClose(); QaLens.takeScreenshot(share = false)
                }
            }
            Box(Modifier.weight(1f)) {
                SquareAction("⭐", "Mark moment", MAmber1, MAmber2) { onClose(); QaLens.markMoment() }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) {
                // Toggles: while tag mode is on this becomes the off switch (the on-screen
                // TAG MODE pill also exits). Only entering closes the sheet.
                if (state.isTagMode) {
                    SquareAction("🏷", "Hide tags", MRed1, MRed2) { QaLens.setTagMode(false) }
                } else {
                    SquareAction("🏷", "Show tags", MGreen1, MTeal2) {
                        onClose(); QaLens.setTagMode(true)
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                SquareAction("📋", "Copy bug report", MPurple1, MPurple2) {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("QaLens Bug", QaLens.buildJiraReport()))
                    QaLens.log("Bug report copied")
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) {
                SquareAction("📱", "Copy device info", MBlue1, MPurple2) {
                    val d = state.device
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("QaLens Device",
                        "${d.appName} ${d.appVersion} (${d.buildVariant})" +
                            (d.environment?.let { " · $it" } ?: "") +
                            (d.gitSha?.let { " · git $it" } ?: "") + "\n" +
                            "${d.manufacturer} ${d.deviceModel} · Android ${d.androidVersion} (SDK ${d.sdkVersion})\n" +
                            "screen ${d.screenWidthDp}×${d.screenHeightDp}dp · font ×${d.fontScale} · " +
                            (if (d.isRtl) "RTL" else "LTR") + " · screen: ${state.screen.displayName}"))
                    QaLens.log("Device info copied")
                }
            }
            Box(Modifier.weight(1f)) {
                SquareAction("🛠", "Control Room", MTeal1, MTeal2) {
                    runCatching {
                        context.startActivity(
                            Intent(context, QaLensControlActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }
        }

        // Macros
        if (macros.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("MACROS · RECENT FIRST", color = MMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            macros.forEach { macro ->
                Row(
                    Modifier.fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(12.dp))
                        .clickable { onClose(); QaLensMacros.run(macro) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("▶", color = MTeal1, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(macro.name, color = MText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("${macro.steps.size} steps", color = MMuted, fontSize = 10.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Footer links
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Full panel →", color = MBlue1, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { QaLens.setPanelMinimal(false) }.padding(6.dp))
            Text("Watch mode", color = MMuted, fontSize = 12.sp,
                modifier = Modifier.clickable { QaLens.setWatchMode(true) }.padding(6.dp))
        }
    }
}

@Composable
private fun StatusChip(label: String, tint: Color, onTap: () -> Unit = {}) {
    Text(
        label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(tint.copy(alpha = 0.14f), CircleShape)
            .clickable(onClick = onTap)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun BigAction(label: String, sub: String, c1: Color, c2: Color, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.linearGradient(listOf(c1, c2)), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(sub, color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp)
    }
}

@Composable
private fun SquareAction(emoji: String, label: String, c1: Color, c2: Color, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.linearGradient(listOf(c1.copy(alpha = 0.22f), c2.copy(alpha = 0.22f))), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(emoji, fontSize = 20.sp)
        Spacer(Modifier.height(4.dp))
        Text(label, color = MText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}
