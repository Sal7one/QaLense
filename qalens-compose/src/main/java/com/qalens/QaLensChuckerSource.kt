package com.qalens

import android.content.Context

/**
 * Uses Chucker as the NETWORK SOURCE for QaLens instead of QaLensOkHttpInterceptor.
 *
 * When [QaLensConfig.networkFromChucker] is true, QaLens registers a reflection proxy
 * implementing Chucker's public `TransactionListener` with Chucker's singleton collector
 * (`Chucker.getCollector(context).registerListener(...)`). Every transaction Chucker already
 * collected — because the app installed ChuckerInterceptor on its OkHttpClient — is converted
 * into a redaction-aware [NetworkEvent] and pushed through the normal QaLens pipeline
 * (Network tab, classifier, timeline, `.sal` network track, analysis digest).
 *
 * Why reflection: Chucker stays a compileOnly-optional dependency — QaLens never forces it on
 * hosts, and if it is absent [ensureRegistered] returns false and QaLens logs a clear warning
 * (the QaLens interceptor remains available as the fallback source).
 *
 * No double-counting: while this source is active, [QaLensOkHttpInterceptor] is a pass-through.
 * Teams that already run Chucker get the whole evidence pipeline from ONE inspector.
 */
internal object QaLensChuckerSource {

    @Volatile private var registered = false

    /** True when Chucker's public API classes are on the classpath. */
    fun isAvailable(): Boolean = runCatching {
        Class.forName("com.chuckerteam.chucker.api.Chucker")
        Class.forName("com.chuckerteam.chucker.api.TransactionListener")
        Class.forName("com.chuckerteam.chucker.api.ChuckerTransaction")
        true
    }.getOrDefault(false)

    /**
     * Registers the listener once. Idempotent. Returns true when the source is (or becomes)
     * active; false when Chucker is unavailable or registration failed.
     */
    fun ensureRegistered(context: Context): Boolean {
        if (registered) return true
        if (!isAvailable()) {
            QaLens.log("networkFromChucker=true but Chucker is not on the classpath — " +
                "falling back to QaLensOkHttpInterceptor for network capture")
            return false
        }
        return runCatching {
            val chuckerClass = Class.forName("com.chuckerteam.chucker.api.Chucker")
            val getCollector = chuckerClass.getMethod("getCollector", Context::class.java)
            val collector = getCollector.invoke(null, context.applicationContext)
            val listenerClass = Class.forName("com.chuckerteam.chucker.api.TransactionListener")
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass)
            ) { _, method, args ->
                if (method.name == "onTransactionCollected" && args != null && args.isNotEmpty()) {
                    runCatching { mapTransaction(args[0]) }.onFailure {
                        QaLens.log("Chucker transaction mapping failed: " + it.message)
                    }
                }
                null
            }
            val register = collector.javaClass.methods
                .firstOrNull { it.name == "registerListener" }
            if (register == null) {
                QaLens.log("Chucker collector has no registerListener — older Chucker?")
                return@runCatching false
            }
            register.invoke(collector, proxy)
            registered = true
            QaLens.markNetworkAvailable()
            QaLens.log("Network source: Chucker (TransactionListener) — " +
                "QaLensOkHttpInterceptor is pass-through")
            true
        }.onFailure {
            QaLens.log("networkFromChucker=true but listener registration failed: " + it.message)
        }.getOrDefault(false)
    }

    /** Converts a ChuckerTransaction into a NetworkEvent. Reflection-only — no hard dep. */
    private fun mapTransaction(tx: Any) {
        fun str(name: String): String? = runCatching {
            tx.javaClass.getMethod(name).invoke(tx)?.toString()
        }.getOrNull()
        fun num(name: String): Long = runCatching {
            (tx.javaClass.getMethod(name).invoke(tx) as? Number)?.toLong() ?: 0L
        }.getOrDefault(0L)
        fun statusCode(): Int = runCatching {
            val cls = tx.javaClass
            (cls.methods.firstOrNull { it.name == "getStatusCode" }?.invoke(tx) as? Number)?.toInt()
                ?: (cls.methods.firstOrNull { it.name == "getResponseCode" }?.invoke(tx) as? Number)?.toInt()
                ?: 0
        }.getOrDefault(0)
        fun timestampMillis(): Long = runCatching {
            (tx.javaClass.getMethod("getRequestDate").invoke(tx) as? java.util.Date)?.time
                ?: System.currentTimeMillis()
        }.getOrDefault(System.currentTimeMillis())

        val method = str("getMethod") ?: return
        val url = str("getUrl") ?: return
        val throwable = runCatching {
            (tx.javaClass.getMethod("getThrowable").invoke(tx) as? Throwable)?.javaClass?.simpleName
        }.getOrNull()

        QaLens.logNetwork(
            NetworkEvent(
                timestampMillis = timestampMillis(),
                method = method,
                url = url,
                status = statusCode(),
                latencyMs = num("getTookMs"),
                requestBodyBytes = num("getRequestContentLength"),
                responseBodyBytes = num("getResponseContentLength"),
                error = throwable,
                connectivity = QaLens.currentConnectivity()
            )
        )
    }
}
