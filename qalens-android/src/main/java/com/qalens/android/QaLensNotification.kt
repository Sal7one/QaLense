package com.qalens.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object QaLensNotification {

    private const val CHANNEL_ID   = "qalens_debug_tools"
    const val NOTIFICATION_ID = 0x4151   // "QA" in hex

    // Handled by com.qalens.QaLensControlService (manifest-declared in qalens-compose, referenced by
    // name so qalens-android needs no dependency on it). A service target — unlike the old
    // activity-registered receiver — stays resolvable for the whole process lifetime, so the Stop
    // action can never go dead mid-recording.
    private const val CONTROL_SERVICE = "com.qalens.QaLensControlService"
    private const val CONTROL_ACTIVITY = "com.qalens.QaLensControlActivity"
    const val ACTION_TOGGLE   = "com.qalens.action.TOGGLE_PANEL"
    const val ACTION_RECORD   = "com.qalens.action.TOGGLE_RECORD"

    fun show(context: Context, recording: Boolean = false) {
        ensureChannel(context)
        if (!hasPermission(context)) return

        fun service(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
            context,
            requestCode,
            Intent(action).setClassName(context, CONTROL_SERVICE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Tapping the notification body opens the Control Room — works even when the host app's
        // overlay is hidden or broken, which is the whole point of a command & control surface.
        val controlRoom = PendingIntent.getActivity(
            context,
            2,
            Intent().setClassName(context, CONTROL_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val recordTitle = if (recording) "■ Stop Recording" else "● Record"

        // Several QaLens-instrumented apps can sit in the shade at once — identify WHICH app this
        // notification controls (launcher label + package), not just "QaLens Active".
        val appLabel = runCatching {
            context.applicationInfo.loadLabel(context.packageManager).toString()
        }.getOrDefault(context.packageName)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle(if (recording) "QaLens · Recording $appLabel" else "QaLens · $appLabel")
            .setContentText(
                if (recording) "${context.packageName}  ·  capturing session  ·  Stop below"
                else "${context.packageName}  ·  tap for Control Room"
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(controlRoom)
            .addAction(0, recordTitle, service(ACTION_RECORD, 1))
            .addAction(0, "Panel", service(ACTION_TOGGLE, 0))
            .setSilent(true)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    fun dismiss(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "QaLens Debug Tools", NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = "Persistent overlay control for QA sessions"
                    setShowBadge(false)
                }
        )
    }
}
