package com.qalens

import kotlin.test.*

class QaLensBridgeProtocolTest {
    private val token = "synthetic-token-0123456789"
    private fun request(headers: String = "Authorization: Bearer $token\r\n", body: String = "", verb: String = "GET") =
        "$verb /v1/snapshot HTTP/1.1\r\n${headers}\r\n$body".byteInputStream()

    @Test fun authenticatedReadsAndUtf8Commands() {
        assertEquals("/v1/snapshot", QaLensBridgeProtocol.read(request(), token).path)
        val body = "{\"text\":\"مرحبا\"}"
        val headers = "Authorization: Bearer $token\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\n"
        assertEquals(body, QaLensBridgeProtocol.read(request(headers, body, "POST"), token).body)
    }
    @Test fun rejectsUnauthenticatedAndBrowserRequestsBeforeReadingBody() {
        assertEquals(401, assertFailsWith<QaLensBridgeFailure> { QaLensBridgeProtocol.read(request(""), token) }.status)
        assertEquals(403, assertFailsWith<QaLensBridgeFailure> { QaLensBridgeProtocol.read(request("Authorization: Bearer $token\r\nOrigin: http://evil.example\r\n"), token) }.status)
    }
    @Test fun boundsHeadersBodiesAndRejectsSmuggling() {
        for (header in listOf("Content-Length: 16385", "Transfer-Encoding: chunked", "Content-Length: -1", "Content-Length: 2\r\nContent-Length: 2")) {
            assertFailsWith<QaLensBridgeFailure> { QaLensBridgeProtocol.read(request("Authorization: Bearer $token\r\n$header\r\n"), token) }
        }
        assertEquals(413, assertFailsWith<QaLensBridgeFailure> { QaLensBridgeProtocol.read(request("X-Long: ${"a".repeat(8192)}\r\n"), token) }.status)
    }
    @Test fun truncatedRequestsAndWrongContentTypeFailClearly() {
        assertEquals(400, assertFailsWith<QaLensBridgeFailure> { QaLensBridgeProtocol.read("GET / HTTP/1.1".byteInputStream(), token) }.status)
        assertEquals(400, assertFailsWith<QaLensBridgeFailure> { QaLensBridgeProtocol.read(request("Authorization: Bearer $token\r\nContent-Length: 4\r\n", "x"), token) }.status)
        assertEquals(415, assertFailsWith<QaLensBridgeFailure> { QaLensBridgeProtocol.read(request(verb = "POST"), token) }.status)
    }
}
