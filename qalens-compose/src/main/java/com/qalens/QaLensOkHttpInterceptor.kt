package com.qalens

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Add to your OkHttpClient to see every request in the QaLens Network tab and feed the timeline,
 * bug classifier, and network-health score:
 *
 *   OkHttpClient.Builder().addInterceptor(QaLensOkHttpInterceptor()).build()
 *
 * Privacy: only metadata is captured (method, URL, status, latency, sizes, failure class).
 * Request/response **bodies are never read**, and URLs are redaction-aware at export time.
 */
class QaLensOkHttpInterceptor : Interceptor {

    init {
        // Constructing the interceptor is the signal that network capture is wired up, so the
        // Network tab and evidence completeness report it as available even before the first call.
        QaLens.markNetworkAvailable()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val start = System.currentTimeMillis()
        return try {
            val resp = chain.proceed(req)
            QaLens.logNetwork(
                NetworkEvent(
                    timestampMillis = start,
                    method = req.method,
                    url = req.url.toString(),
                    status = resp.code,
                    latencyMs = System.currentTimeMillis() - start,
                    requestBodyBytes = req.body?.contentLength()?.coerceAtLeast(0) ?: 0,
                    responseBodyBytes = resp.body?.contentLength()?.coerceAtLeast(0) ?: 0
                )
            )
            resp
        } catch (e: Exception) {
            QaLens.logNetwork(
                NetworkEvent(
                    timestampMillis = start,
                    method = req.method,
                    url = req.url.toString(),
                    latencyMs = System.currentTimeMillis() - start,
                    error = e.javaClass.simpleName
                )
            )
            throw e
        }
    }
}
