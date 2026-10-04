package com.qalens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The manifest requires DUMP on the sender: an authorized adb shell can offer pairing,
 * ordinary applications cannot. Offering never starts a listener or grants access. */
class QaLensPcPairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.qalens.action.CANCEL_PC_PAIRING") {
            QaLensPcPairing.cancel(intent.getStringExtra("token")); return
        }
        if (intent.action != "com.qalens.action.REQUEST_PC_PAIRING") return
        QaLens.rememberApplication(context.applicationContext as? android.app.Application ?: return)
        resultCode = QaLensPcPairing.offer(intent.getStringExtra("token"), intent.getIntExtra("port", 8766))
    }
}

/** Credentials and expiry are process memory only, never Activity extras or saved state. */
internal object QaLensPcPairing {
    class Request internal constructor(internal val token: String, val port: Int, internal val expires: Long)
    private val handler = Handler(Looper.getMainLooper())
    private val mutableRequest = MutableStateFlow<Request?>(null)
    val request = mutableRequest.asStateFlow()

    @Synchronized fun offer(token: String?, port: Int): Int {
        if (!QaLens.config.value.enabled) return 3
        if (token == null || !token.matches(Regex("[A-Za-z0-9_-]{24,128}")) || port !in 1024..65535) return 4
        // Keep a visible request stable; repeated shell requests cannot swap what QA approves.
        if (mutableRequest.value?.expires?.let { it > SystemClock.elapsedRealtime() } == true) return 2
        val offered = Request(token, port, SystemClock.elapsedRealtime() + 120_000)
        mutableRequest.value = offered
        handler.postDelayed({ synchronized(this) { if (mutableRequest.value === offered) clear() } }, 120_000)
        return 1
    }

    @Synchronized fun clear() { mutableRequest.value = null; handler.removeCallbacksAndMessages(null) }
    @Synchronized fun cancel(token: String?) { if (token != null && mutableRequest.value?.token == token) clear() }

    fun approve(offered: Request): Boolean {
        synchronized(this) {
            if (mutableRequest.value !== offered || offered.expires <= SystemClock.elapsedRealtime() || !QaLens.config.value.enabled) {
                clear(); return false
            }
            clear()
        }
        QaLens.startLocalBridge(offered.token, offered.port)
        return true
    }
}
