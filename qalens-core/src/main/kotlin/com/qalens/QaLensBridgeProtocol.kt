package com.qalens

import java.io.InputStream
import java.security.MessageDigest

class QaLensBridgeFailure(val status: Int, override val message: String) : Exception(message)
data class QaLensBridgeRequest(val method: String, val path: String, val body: String)

/** Small, bounded HTTP/1.1 subset. No keep-alive, chunking, browser access or unauthenticated reads. */
object QaLensBridgeProtocol {
    fun read(input: InputStream, token: String): QaLensBridgeRequest {
        val header = StringBuilder()
        while (!header.endsWith("\r\n\r\n")) {
            val byte = input.read()
            if (byte < 0) throw QaLensBridgeFailure(400, "Incomplete headers")
            if (header.length >= 8_192) throw QaLensBridgeFailure(413, "Headers too large")
            header.append(byte.toChar())
        }
        val lines = header.toString().split("\r\n")
        val start = lines[0].split(' ')
        if (start.size != 3 || start[2] !in listOf("HTTP/1.1", "HTTP/1.0")) throw QaLensBridgeFailure(400, "Invalid request")
        val headers = mutableMapOf<String, String>()
        lines.drop(1).filter(String::isNotBlank).forEach { line ->
            val split = line.indexOf(':')
            if (split <= 0) throw QaLensBridgeFailure(400, "Invalid header")
            val key = line.substring(0, split).trim().lowercase()
            if (headers.put(key, line.substring(split + 1).trim()) != null) throw QaLensBridgeFailure(400, "Duplicate header")
        }
        if (headers.containsKey("origin")) throw QaLensBridgeFailure(403, "Use the PC bridge; direct browser access is disabled")
        if (!MessageDigest.isEqual(headers["authorization"].orEmpty().toByteArray(), "Bearer $token".toByteArray()))
            throw QaLensBridgeFailure(401, "Invalid bridge token")
        if (headers.containsKey("transfer-encoding")) throw QaLensBridgeFailure(400, "Chunking is unsupported")
        val size = headers["content-length"]?.toIntOrNull() ?: if (headers.containsKey("content-length")) -1 else 0
        if (size < 0) throw QaLensBridgeFailure(400, "Invalid content length")
        if (size > 16_384) throw QaLensBridgeFailure(413, "Command too large")
        if (start[0] == "POST" && !headers["content-type"].orEmpty().startsWith("application/json"))
            throw QaLensBridgeFailure(415, "Commands require application/json")
        val body = ByteArray(size)
        var read = 0
        while (read < size) {
            val count = input.read(body, read, size - read)
            if (count < 0) throw QaLensBridgeFailure(400, "Incomplete body")
            read += count
        }
        return QaLensBridgeRequest(start[0], start[1], body.toString(Charsets.UTF_8))
    }
}
