package com.qalens

import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.ForwardingSink
import okio.buffer

/** Optional body observation, isolated from Android so stream preservation can be tested on the JVM. */
internal object QaLensBodyPreview {
    fun request(request: Request, config: QaLensConfig): Pair<String?, Request> = try {
        val body = request.body
        if (body == null || body.isOneShot() || body.isDuplex()) null to request
        else {
            val length = body.contentLength()
            if (length <= 0 || length > BODY_CAPTURE_CAP) null to request
            else if (!isTextish(body.contentType())) "<binary $length bytes>" to request
            else {
                val buffer = Buffer()
                // contentLength() is advisory: enforce the cap on actual writes too.
                val bounded = object : ForwardingSink(buffer) {
                    override fun write(source: Buffer, byteCount: Long) {
                        check(byteCount <= BODY_CAPTURE_CAP - buffer.size) { "Body preview limit exceeded" }
                        super.write(source, byteCount)
                    }
                }.buffer()
                body.writeTo(bounded)
                bounded.emit()
                val bytes = buffer.readByteArray()
                val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                val preview = config.redact(truncateCapturedBody(String(bytes, charset)))
                preview to request.newBuilder().method(request.method, bytes.toRequestBody(body.contentType())).build()
            }
        }
    } catch (_: Exception) { null to request }

    fun response(response: Response, config: QaLensConfig): String? = try {
        val body = response.body
        if (body == null) null
        // Never pre-read an open-ended stream (SSE/chunked/unknown length). Peek only finite,
        // bounded bodies; larger bodies remain metadata-only and stream directly to the host.
        else if (body.contentLength() !in 0..BODY_CAPTURE_CAP.toLong()) null
        else if (body.contentType()?.subtype.equals("event-stream", ignoreCase = true)) null
        else if (!isTextish(body.contentType())) "<binary body>"
        else config.redact(truncateCapturedBody(response.peekBody((BODY_CAPTURE_CAP + 1).toLong()).string()))
    } catch (_: Exception) { null }

    private fun isTextish(mediaType: MediaType?): Boolean {
        if (mediaType == null) return false
        val subtype = mediaType.subtype.lowercase()
        return mediaType.type.equals("text", true) || (mediaType.type.equals("application", true) &&
            (subtype in setOf("json", "xml", "x-www-form-urlencoded") || subtype.endsWith("+json") || subtype.endsWith("+xml")))
    }
}
