package com.qalens.sample

import android.app.Instrumentation
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.qalens.QaLens

/** Test APK only: real editable/touch/scroll UI and temporary HD opt-in for desktop checks. */
internal class ScrcpyDesktopChecks(private val runner: Instrumentation) {
    fun hold(seconds: Int) {
        val original = QaLens.config.value
        val activity = runner.startActivitySync(Intent(runner.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        runner.runOnMainSync {
            QaLens.configure { enabled = true; allowUnmaskedVideo = true }
            QaLens.closePanel(); QaLens.setInspectMode(false)
            activity.setContent {
                MaterialTheme {
                    var text by remember { mutableStateOf("initial") }
                    var taps by remember { mutableStateOf(0) }
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 70.dp, bottom = 100.dp)) {
                        Text("Scrcpy desktop fixture", Modifier.testTag("scrcpy.title"))
                        Text("Taps: $taps", Modifier.height(60.dp).testTag("scrcpy.tap").clickable { taps++ })
                        OutlinedTextField(text, { text = it }, Modifier.testTag("scrcpy.input"))
                        repeat(60) { Text("Scrollable fixture row $it", Modifier.height(48.dp).testTag("scrcpy.row.$it")) }
                    }
                }
            }
            QaLens.startLocalBridge("synthetic-scrcpy-desktop-0123456789", 18766)
        }
        try { Thread.sleep(seconds.coerceIn(1, 600) * 1000L) }
        finally {
            runner.runOnMainSync {
                QaLens.stopRecording(); QaLens.stopLocalBridge(); QaLens.setInspectMode(false); QaLens.closePanel()
                QaLens.configure { enabled = original.enabled; allowUnmaskedVideo = original.allowUnmaskedVideo }
            }
            runner.startActivitySync(Intent(runner.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
    }
}
