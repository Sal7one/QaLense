package com.qalens.replay

import java.net.InetAddress
import java.net.URI
import java.util.Locale

/** Deliberately no arbitrary DNS, proxy, redirect, credentials or cloud endpoint support. */
internal class LocalModelEndpoint private constructor(val root: String) {
    fun openAi(path: String): String = "$root/v1/$path"
    fun ollama(path: String): String = "$root/api/$path"

    companion object {
        private val embeddingModels = Regex("embedding|(^|[-_/:])embed([-_/:]|$)|nomic-embed|bge[-_]|e5[-_]|rerank", RegexOption.IGNORE_CASE)
        fun parse(input: String): LocalModelEndpoint {
            require(input.length <= 2048) { "Local model URL is too long." }
            val uri = try { URI(input.trim()) } catch (_: Exception) {
                throw IllegalArgumentException("Enter a local model URL such as http://10.0.2.2:1234 on an emulator, or your PC's private IP on a phone.")
            }
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            require(scheme == "http" || scheme == "https") { "Use an http:// or https:// local model URL." }
            require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "URL credentials, query parameters and fragments are not supported. Use the optional API key field."
            }
            require(uri.rawPath.orEmpty() in setOf("", "/", "/v1", "/v1/", "/api", "/api/")) {
                "Use the model server's root URL, /v1 or /api."
            }
            val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.lowercase(Locale.ROOT)
                ?: throw IllegalArgumentException("A literal private IP address or localhost is required.")
            require(isLocal(host)) { "Only localhost, a loopback address or a literal private LAN IP is supported." }
            require(uri.port == -1 || uri.port in 1..65535) { "Invalid model server port." }
            // Pin localhost: a machine's hosts/DNS configuration must not redirect evidence externally.
            val target = if (host == "localhost") "127.0.0.1" else host
            return LocalModelEndpoint(URI(scheme, null, target, uri.port, null, null, null).toASCIIString())
        }

        private fun isLocal(host: String): Boolean {
            if (host == "localhost") return true
            if (':' in host) {
                if (!Regex("[0-9a-f:]+").matches(host)) return false
                val bytes = runCatching { InetAddress.getByName(host).address }.getOrNull() ?: return false
                // IPv4-mapped, link-local and scoped IPv6 addresses are intentionally unsupported.
                if (bytes.size != 16) return false
                return bytes.take(15).all { it.toInt() == 0 } && bytes[15].toInt() == 1 ||
                    (bytes[0].toInt() and 0xfe) == 0xfc
            }
            val parts = host.split('.')
            if (parts.size != 4 || parts.any { !Regex("0|[1-9][0-9]{0,2}").matches(it) }) return false
            val octets = parts.map { it.toIntOrNull() ?: return false }
            if (octets.any { it !in 0..255 }) return false
            return octets[0] == 127 || octets[0] == 10 ||
                octets[0] == 192 && octets[1] == 168 || octets[0] == 172 && octets[1] in 16..31
        }

        fun isChatModel(id: String): Boolean = id.isNotBlank() && id.length <= 256 && id.none { it.code < 32 || it.code == 127 } &&
            !embeddingModels.containsMatchIn(id)
    }
}
