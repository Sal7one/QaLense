package com.qalens

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One SDK-owned pairing surface shared by Control Room and both overlay layouts. */
@Composable
internal fun QaLensPcInspectorControls(colors: QaLensOverlayColors, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val status by QaLensLocalBridge.status.collectAsState()
    val pairing by QaLensLocalBridge.pairing.collectAsState()
    val config by QaLens.config.collectAsState()
    var portText by remember(pairing?.port) { mutableStateOf((pairing?.port ?: 8766).toString()) }
    var showToken by remember(pairing?.token) { mutableStateOf(false) }
    var feedback by remember(pairing?.token) { mutableStateOf<String?>(null) }
    val active = pairing != null
    fun start() {
        val port = portText.toIntOrNull()
        if (port == null || port !in 1024..65535) { feedback = "Use a device port from 1024 to 65535."; return }
        runCatching { QaLensLocalBridge.startPairing(port) }
            .onFailure { feedback = "Could not start pairing. Check the port and try again." }
    }

    Column(modifier.fillMaxWidth().qaHiddenFromReports(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("PC inspector", color = colors.fg, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(status, color = if (active) colors.ok else colors.fg2, fontSize = 12.sp)
        Text("Read the Compose tree, inspect components and run actions from your PC. Connect using adb and the QaLens desktop app.",
            color = colors.fg2, fontSize = 11.sp)
        Text("App: ${context.packageName}", color = colors.fg2, fontSize = 11.sp)
        if (!active) {
            Text("Device port", color = colors.fg2, fontSize = 11.sp)
            BasicTextField(portText, { if (it.length <= 5 && it.all(Char::isDigit)) portText = it },
                modifier = Modifier.fillMaxWidth().background(colors.well).padding(10.dp)
                    .semantics { contentDescription = "PC inspector device port" },
                singleLine = true, textStyle = TextStyle(color = colors.fg, fontSize = 13.sp, textDirection = TextDirection.Ltr),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Text("Only start for an authorized QA session. Nothing pairs automatically.", color = colors.fg2, fontSize = 11.sp)
            Action("Start PC inspector", colors, config.enabled) { start() }
            if (!config.enabled) Text("QaLens is disabled in this app.", color = colors.err, fontSize = 11.sp)
        } else {
            Text("Device port: ${pairing?.port} · USB/adb loopback only", color = colors.fg2, fontSize = 11.sp)
            Action("Copy pairing token", colors) {
                pairing?.token?.let { token ->
                    runCatching {
                        val clip = ClipData.newPlainText("QaLens PC pairing", token)
                        if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply {
                            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                        }
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                    }.onSuccess { feedback = "Token copied. Paste it into the PC connection form." }
                        .onFailure { feedback = "Could not copy. Show the token and enter it on your PC." }
                }
            }
            Action(if (showToken) "Hide pairing token" else "Show pairing token", colors) { showToken = !showToken }
            if (showToken) Text(pairing?.token.orEmpty(), color = colors.fg, fontSize = 12.sp,
                style = TextStyle(textDirection = TextDirection.Ltr))
            Action("New pairing token", colors) { start() }
            Action("Stop PC inspector", colors) { QaLens.stopLocalBridge() }
            Text("Tokens stay in memory. A new token disconnects the previous pairing; Stop or disabling QaLens revokes access.",
                color = colors.fg2, fontSize = 11.sp)
        }
        feedback?.let { Text(it, color = colors.fg2, fontSize = 11.sp) }
    }
}

@Composable
private fun Action(label: String, colors: QaLensOverlayColors, enabled: Boolean = true, onClick: () -> Unit) {
    Text(label, color = if (enabled) colors.accent else colors.fg3, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).background(colors.accentWash)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 14.dp))
}
