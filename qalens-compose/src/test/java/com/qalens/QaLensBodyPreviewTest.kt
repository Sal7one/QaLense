package com.qalens

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSink
import org.junit.Assert.*
import org.junit.Test

class QaLensBodyPreviewTest {
    private val config = QaLensConfig()
    private val json = "application/json".toMediaType()
    private fun request(body: RequestBody) = Request.Builder().url("https://example.com").post(body).build()

    @Test fun oneShotAndDuplexBodiesAreNotTouched() {
        for (duplex in listOf(false, true)) {
            val body = object : RequestBody() {
                override fun contentType() = json
                override fun contentLength() = 10L
                override fun isOneShot() = !duplex
                override fun isDuplex() = duplex
                override fun writeTo(sink: BufferedSink) { error("Body must not be observed") }
            }
            val original = request(body)
            val result = QaLensBodyPreview.request(original, config)
            assertNull(result.first)
            assertSame(original, result.second)
        }
    }

    @Test fun capturedRequestPreservesBytesAndRedactsPreview() {
        val bytes = "{\"email\":\"qa@example.com\"}".toByteArray()
        val (preview, rebuilt) = QaLensBodyPreview.request(request(bytes.toRequestBody(json)), config)
        assertFalse(preview!!.contains("qa@example.com"))
        val sent = Buffer()
        rebuilt.body!!.writeTo(sent)
        assertArrayEquals(bytes, sent.readByteArray())
    }

    @Test fun dishonestContentLengthCannotGrowPreviewPastCap() {
        var written = 0
        val body = object : RequestBody() {
            override fun contentType() = json
            override fun contentLength() = 1L
            override fun writeTo(sink: BufferedSink) {
                repeat(1000) { sink.write(ByteArray(8192)); written++ }
            }
        }
        val original = request(body)
        val result = QaLensBodyPreview.request(original, config)
        assertNull(result.first)
        assertSame(original, result.second)
        assertTrue(written < 10)
    }

    @Test fun responsePeekLeavesOriginalReadable() {
        val text = "{\"email\":\"qa@example.com\"}"
        val response = Response.Builder().request(Request.Builder().url("https://example.com").build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(text.toResponseBody(json)).build()
        assertFalse(QaLensBodyPreview.response(response, config)!!.contains("qa@example.com"))
        assertEquals(text, response.body!!.string())
    }

    @Test fun largeResponsePreviewIsBounded() {
        val text = "x".repeat(BODY_CAPTURE_CAP * 2)
        val response = Response.Builder().request(Request.Builder().url("https://example.com").build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(text.toResponseBody(json)).build()
        assertNull(QaLensBodyPreview.response(response, config))
        assertEquals(text, response.body!!.string())
    }
    @Test fun streamingResponsesAreNeverRead() {
        for ((type, length) in listOf("text/event-stream" to 42L, "application/json" to -1L)) {
            val body = object : ResponseBody() {
                override fun contentType() = type.toMediaType()
                override fun contentLength() = length
                override fun source(): okio.BufferedSource = error("Stream was touched")
            }
            val response = Response.Builder().request(Request.Builder().url("https://example.test").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
            assertNull(QaLensBodyPreview.response(response, config))
        }
    }

}
