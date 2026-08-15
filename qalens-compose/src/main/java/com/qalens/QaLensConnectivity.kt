package com.qalens

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build

import java.util.concurrent.atomic.AtomicReference

/**
 * Observes the device's network connectivity (WiFi/Cellular/Offline transitions + signal strength)
 * via [ConnectivityManager.NetworkCallback]. Feeds transitions into the QaLens timeline and exposes
 * [current] so the [com.qalens.QaLensOkHttpInterceptor] can stamp each [com.qalens.NetworkEvent]
 * with the connectivity at request time — letting [com.qalens.BugClassifier] distinguish a server
 * 500 from a device-that-lost-WiFi.
 *
 * Started by [com.qalens.QaLens.install]. No-op below API 24 (NetworkCallback exists but signal
 * strength reporting is limited); the callback still fires for available/lost on 23+.
 */
object QaLensConnectivity {

    private val currentRef = AtomicReference<ConnectivitySnapshot?>(null)
    @Volatile private var started = false
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** The latest connectivity snapshot, or null if [start] hasn't been called / no network yet. */
    fun current(): ConnectivitySnapshot? = currentRef.get()

    fun start(context: Context) {
        if (started) return
        started = true
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                publish(context, cm, network, "Network available")
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                // Fires on gain/loss of WiFi/cell + signal changes. We snapshot + publish on type changes.
                publish(context, cm, network, null)
            }
            override fun onLost(network: Network) {
                val snap = ConnectivitySnapshot(type = ConnectivityType.OFFLINE)
                currentRef.set(snap)
                QaLens.appendConnectivity(snap)
                QaLens.event("connectivity", "Network lost — device is offline")
            }
        }
        callback = cb
        runCatching { cm.registerNetworkCallback(req, cb) }.onFailure {
            QaLens.pushError(ErrorKind.OTHER, "Connectivity observer failed to start: ${it.message}")
        }
    }

    fun stop(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        started = false
    }

    private fun publish(context: Context, cm: ConnectivityManager, network: Network, message: String?) {
        val snap = snapshot(cm, network) ?: return
        val previous = currentRef.getAndSet(snap)
        // Only emit a timeline event when the *type* changed (avoid spamming on every signal bar delta).
        val typeChanged = previous?.type != snap.type
        QaLens.appendConnectivity(snap)
        if (typeChanged) {
            val desc = "${snap.type.name.lowercase()}" +
                (if (snap.strengthBars > 0) " · ${snap.strengthBars} bars" else "") +
                (if (snap.hasVpn) " · VPN" else "") +
                (if (snap.isMetered) " · metered" else "")
            QaLens.event("connectivity", message ?: "Connectivity now $desc")
            // Back online: flush any recordings parked in the offline retry queue.
            if (previous?.type == ConnectivityType.OFFLINE) {
                QaLensWebhook.drainQueue(context.applicationContext)
            }
        }
    }

    private fun snapshot(cm: ConnectivityManager, network: Network): ConnectivitySnapshot? {
        val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull() ?: return null
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ConnectivityType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ConnectivityType.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ConnectivityType.ETHERNET
            else -> ConnectivityType.UNKNOWN
        }
        val strength = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // SignalStrength is transport-specific; use the 0..4 bars heuristic from the capability
            // down/upload bandwidth when available (API 29+). Fall back to 0.
            runCatching {
                val up = caps.linkUpstreamBandwidthKbps
                val down = caps.linkDownstreamBandwidthKbps
                when {
                    down >= 1_000 -> 4
                    down >= 500 -> 3
                    down >= 100 -> 2
                    down > 0 -> 1
                    else -> 0
                }
            }.getOrDefault(0)
        } else 0
        val hasVpn = runCatching { caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).not() }.getOrDefault(false)
        val isMetered = runCatching { caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED).not() }.getOrDefault(false)
        return ConnectivitySnapshot(type = type, strengthBars = strength, hasVpn = hasVpn, isMetered = isMetered)
    }
}
