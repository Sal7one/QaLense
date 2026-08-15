package com.qalens

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSink

/**
 * Add to your OkHttpClient to see every request in the QaLens Network tab and feed the timeline,
 * bug classifier, and network-health score:
 *
 *   OkHttpClient.Builder().addInterceptor(QaLensOkHttpInterceptor()).build()
 *
 * Privacy: by default only metadata is captured (method, URL, status, latency, sizes, failure
 * class) — request/response bodies are never read and URLs are redaction-aware at export time.
 *
 * R8 (opt-in): when [QaLensConfig.captureNetworkBodies] is enabled, a preview of the request and
 * response bodies is captured for text-ish content types only (JSON/XML/forms), truncated to
 * [BODY_CAPTURE_CAP] bytes, and folded through [QaLensRedactor] before storage. Binary bodies get a
 * `<binary N bytes>` placeholder. Every read is guarded by runCatching so a capture failure
 * degrades to null and never breaks the app's request pipeline.
 */
class QaLensOkHttpInterceptor : Interceptor {

    init {
        // Constructing the interceptor is the signal that network capture is wired up, so the
        // Network tab and evidence completeness report it as available even before the first call.
        // With captureNetwork=false or Chucker-as-source, this interceptor is a pure pass-through
        // and must NOT claim availability (analysis.json.coverage tells the truth instead).
        val cfg0 = QaLens.config.value
        if (cfg0.captureNetwork && !cfg0.networkFromChucker) QaLens.markNetworkAvailable()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val cfg = QaLens.config.value
        // Feature flags: captureNetwork=false → zero observation; networkFromChucker=true →
        // Chucker's TransactionListener is the source (see QaLensChuckerSource) — logging here
        // would double-count every request.
        if (!cfg.captureNetwork || cfg.networkFromChucker) {
            return chain.proceed(chain.request())
        }

        val start = System.currentTimeMillis()
        // B5: stamp the connectivity at request time so the classifier can tell a server 500
        // from a device-that-lost-WiFi. currentConnectivity() is a cheap volatile read.
        val connectivity = QaLens.currentConnectivity()
        val captureBodies = cfg.captureNetworkBodies

        // R8: when body capture is on, buffer the request body (and rebuild the request from the
        // buffered bytes so the app is unaffected). When off, the body is never touched.
        var req = chain.request()
        var requestBodyPreview: String? = null
        if (captureBodies) {
            val (preview, rebuilt) = captureRequestBody(req)
            requestBodyPreview = preview
            req = rebuilt
        }

        return try {
            val resp = chain.proceed(req)
            val responseBodyPreview = if (captureBodies) captureResponseBody(resp) else null
            QaLens.logNetwork(
                NetworkEvent(
                    timestampMillis = start,
                    method = req.method,
                    url = req.url.toString(),
                    status = resp.code,
                    latencyMs = System.currentTimeMillis() - start,
                    requestBodyBytes = req.body?.contentLength()?.coerceAtLeast(0) ?: 0,
                    responseBodyBytes = resp.body?.contentLength()?.coerceAtLeast(0) ?: 0,
                    connectivity = connectivity,
                    requestBodyPreview = requestBodyPreview,
                    responseBodyPreview = responseBodyPreview
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
                    error = e.javaClass.simpleName,
                    connectivity = connectivity,
                    requestBodyPreview = requestBodyPreview
                )
            )
            throw e
        }
    }

    /**
     * Buffer a bounded (1..[BODY_CAPTURE_CAP] bytes) request body, redact a preview, and return a
     * rebuilt [Request] carrying the same bytes so the app's call is untouched. Returns `(null, req)`
     * for anything we intentionally skip (no body, too large, binary, or any failure).
     */
    private fun captureRequestBody(req: Request): Pair<String?, Request> {
        return try {
            val body = req.body ?: return null to req
            val len = body.contentLength()
            if (len <= 0 || len > BODY_CAPTURE_CAP) return null to req
            val contentType = body.contentType()
            if (!isTextish(contentType)) return "<binary " + len + " bytes>" to req

            val buffer = Buffer()
            body.writeTo(buffer)
            val bytes = buffer.readByteArray()
            val raw = String(bytes, Charsets.UTF_8)
            val preview = QaLensRedactor.redact(
                truncateCapturedBody(raw),
                QaLens.config.value.redactionRules
            )
            val rebuiltBody = object : RequestBody() {
                override fun contentType(): MediaType? = contentType
                override fun contentLength(): Long = bytes.size.toLong()
                override fun writeTo(sink: BufferedSink) { sink.write(bytes) }
            }
            preview to req.newBuilder().method(req.method, rebuiltBody).build()
        } catch (e: Exception) {
            null to req
        }
    }

    /**
     * Capture a redacted preview of the response body via [ResponseBody.peekBody] — this does
     * NOT consume the original stream, so the app reads the full response as normal.
     */
    private fun captureResponseBody(resp: Response): String? {
        return try {
            val body = resp.body ?: return null
            val contentType = body.contentType()
            if (!isTextish(contentType)) {
                val len = body.contentLength()
                if (len >= 0) "<binary " + len + " bytes>" else "<binary body>"
            } else {
                val raw = peekBodyText(body, (BODY_CAPTURE_CAP + 1).toLong())
                QaLensRedactor.redact(
                    truncateCapturedBody(raw),
                    QaLens.config.value.redactionRules
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Read up to [byteCount] bytes from [body] WITHOUT consuming the original stream — the OkHttp 3
     * `peekBody` equivalent (removed in OkHttp 4.x): buffer the source, clone its buffer, read the clone.
     */
    private fun peekBodyText(body: ResponseBody, byteCount: Long): String {
        val source = body.source()
        source.request(byteCount)
        val cloned = source.buffer.clone()
        val clipped = if (cloned.size > byteCount) {
            Buffer().apply { write(cloned, byteCount) }
        } else {
            cloned
        }
        return clipped.readUtf8()
    }

    private fun isTextish(mediaType: MediaType?): Boolean {
        if (mediaType == null) return false
        val type = mediaType.type.lowercase()
        val subtype = mediaType.subtype.lowercase()
        return type == "text" ||
            (type == "application" && (
                subtype == "json" || subtype == "xml" ||
                subtype == "x-www-form-urlencoded" ||
                subtype.endsWith("+json") || subtype.endsWith("+xml")
            ))
    }
}
