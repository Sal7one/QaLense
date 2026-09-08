package com.qalens

import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Response

/** Metadata-only by default. Safe to place beside Chucker, logging or tracing interceptors. */
class QaLensOkHttpInterceptor internal constructor(
    private val currentConfig: () -> QaLensConfig,
    private val record: (NetworkEvent) -> Unit,
    private val connectivity: () -> ConnectivitySnapshot?
) : Interceptor {
    constructor() : this({ QaLens.config.value }, QaLens::logNetwork, QaLens::currentConnectivity) {
        QaLens.markNetworkSource("OkHttp")
    }

    private class ObservedCall(val call: Call)

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val config = runCatching(currentConfig).getOrNull()
        if (config == null || !config.enabled || !config.captureNetwork) return chain.proceed(original)
        // Accidental duplicate installation (including application + network layers) must not
        // count a single call twice. Identity scopes the marker to this Call, not a reused Request.
        if (original.tag(ObservedCall::class.java)?.call === chain.call()) return chain.proceed(original)
        val tagged = original.newBuilder().tag(ObservedCall::class.java, ObservedCall(chain.call())).build()
        val timestamp = System.currentTimeMillis()
        val start = System.nanoTime()
        val connection = runCatching(connectivity).getOrNull()
        val (preview, request) = if (config.captureNetworkBodies) QaLensBodyPreview.request(tagged, config)
                               else null to tagged
        val response = try { chain.proceed(request) } catch (error: Exception) {
            runCatching {
                record(NetworkEvent(timestampMillis = timestamp, method = request.method,
                    url = request.url.toString(), latencyMs = (System.nanoTime() - start) / 1_000_000,
                    error = error.javaClass.simpleName, connectivity = connection, requestBodyPreview = preview))
            }
            throw error
        }
        val latency = (System.nanoTime() - start) / 1_000_000
        // Observation failures never change the response or failure seen by the host application.
        runCatching {
            record(NetworkEvent(timestampMillis = timestamp, method = request.method,
                url = request.url.toString(), status = response.code, latencyMs = latency,
                requestBodyBytes = runCatching { request.body?.contentLength()?.coerceAtLeast(0) ?: 0 }.getOrDefault(0),
                responseBodyBytes = runCatching { response.body?.contentLength()?.coerceAtLeast(0) ?: 0 }.getOrDefault(0),
                connectivity = connection, requestBodyPreview = preview,
                responseBodyPreview = if (config.captureNetworkBodies) QaLensBodyPreview.response(response, config) else null))
        }
        return response
    }
}
