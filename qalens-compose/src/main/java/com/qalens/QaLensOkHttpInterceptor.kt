package com.qalens

import okhttp3.Interceptor
import okhttp3.Response

/** Metadata-only by default. Optional bounded body previews never consume one-shot/duplex requests. */
class QaLensOkHttpInterceptor : Interceptor {
    init {
        val config = QaLens.config.value
        if (config.enabled && config.captureNetwork && !config.networkFromChucker) QaLens.markNetworkAvailable()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val config = QaLens.config.value
        if (!config.enabled || !config.captureNetwork || config.networkFromChucker) return chain.proceed(chain.request())
        val timestamp = System.currentTimeMillis()
        val start = System.nanoTime()
        val connectivity = QaLens.currentConnectivity()
        val (preview, request) = if (config.captureNetworkBodies) QaLensBodyPreview.request(chain.request(), config)
                               else null to chain.request()
        val response = try { chain.proceed(request) } catch (error: Exception) {
            runCatching {
                QaLens.logNetwork(NetworkEvent(timestampMillis = timestamp, method = request.method,
                    url = request.url.toString(), latencyMs = (System.nanoTime() - start) / 1_000_000,
                    error = error.javaClass.simpleName, connectivity = connectivity, requestBodyPreview = preview))
            }
            throw error
        }
        // Observation failures must never turn a successful HTTP response into an app failure.
        val latency = (System.nanoTime() - start) / 1_000_000
        runCatching {
            QaLens.logNetwork(NetworkEvent(timestampMillis = timestamp, method = request.method,
                url = request.url.toString(), status = response.code, latencyMs = latency,
                requestBodyBytes = runCatching { request.body?.contentLength()?.coerceAtLeast(0) ?: 0 }.getOrDefault(0),
                responseBodyBytes = runCatching { response.body?.contentLength()?.coerceAtLeast(0) ?: 0 }.getOrDefault(0),
                connectivity = connectivity, requestBodyPreview = preview,
                responseBodyPreview = if (config.captureNetworkBodies) QaLensBodyPreview.response(response, config) else null))
        }
        return response
    }
}
