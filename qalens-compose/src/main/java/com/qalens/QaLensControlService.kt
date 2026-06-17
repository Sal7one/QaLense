package com.qalens

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * Manifest-declared command service for QaLens — the always-reachable entry point for the
 * notification and Control Room actions (start/stop recording, toggle panel, panic restore).
 *
 * This replaces the old activity-registered BroadcastReceiver, which was unregistered whenever the
 * host activity was destroyed — leaving the notification's Stop button dead mid-recording. A
 * manifest-declared service is resolvable for the entire life of the process, so Stop always works.
 *
 * It is a plain started service (no foreground promotion, no extra permissions): each command is
 * handled synchronously on the main thread and the service stops itself immediately.
 */
class QaLensControlService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_PANEL -> QaLens.togglePanel()
            ACTION_TOGGLE_RECORD -> QaLens.toggleRecording()
            ACTION_STOP_RECORD -> QaLens.stopRecording()
            ACTION_PANIC -> QaLens.panicRestore()
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    companion object {
        const val ACTION_TOGGLE_PANEL = "com.qalens.action.TOGGLE_PANEL"
        const val ACTION_TOGGLE_RECORD = "com.qalens.action.TOGGLE_RECORD"
        const val ACTION_STOP_RECORD = "com.qalens.action.STOP_RECORD"
        const val ACTION_PANIC = "com.qalens.action.PANIC_RESTORE"

        fun intent(context: Context, action: String): Intent =
            Intent(context, QaLensControlService::class.java).setAction(action)
    }
}
