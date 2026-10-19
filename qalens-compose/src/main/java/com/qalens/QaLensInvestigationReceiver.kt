package com.qalens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** Manifest-private, same-package replay handoff. No pairing, host Activity, file or model work. */
class QaLensInvestigationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.qalens.action.SEND_INVESTIGATION" || !isOrderedBroadcast) return
        if (active.incrementAndGet() > 2) {
            active.decrementAndGet(); resultCode = 3; resultData = "A handoff is already finishing; retry"; return
        }
        val pending = goAsync()
        val document = try { intent.getStringExtra("document") } catch (_: Exception) { null }
        scope.launch {
            try {
                if (document == null) { pending.resultCode = 3; pending.resultData = "Invalid investigation" }
                else {
                    val queued = QaLensLocalBridge.queueInvestigation(document)
                    pending.resultCode = if (queued) 1 else 2
                    pending.resultData = if (queued) "Queued for approved PC" else "No approved bridge is listening"
                }
            } catch (_: Exception) {
                pending.resultCode = 3; pending.resultData = "Invalid or oversized investigation"
            } finally { active.decrementAndGet(); pending.finish() }
        }
    }
    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val active = AtomicInteger()
    }
}
