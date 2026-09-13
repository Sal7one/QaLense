package com.qalens

object QaLensUploadPolicy {
    fun origin(url: String): String? = runCatching {
        val uri = java.net.URI(url.trim())
        val scheme = uri.scheme?.lowercase()
        require(scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null)
        val port = if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80
        "$scheme://${uri.host.lowercase()}:$port"
    }.getOrNull()
    fun sameOrigin(first: String, second: String): Boolean =
        origin(first)?.let { it == origin(second) } ?: false
    fun retryable(code: Int): Boolean = code == 408 || code == 429 || code in 500..599
}
