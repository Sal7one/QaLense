package com.qalens

/** A dependency-free adapter target for Ktor, Cronet, Apollo, custom transports and test harnesses.
 * Report each completed request once. This interface never reads a body or executes a request.
 */
fun interface QaLensNetworkSink {
    fun record(event: NetworkEvent)
}

/** One capture boundary shared by native interceptors and third-party adapters. */
object QaLensNetworkCapture {
    fun sanitize(event: NetworkEvent, config: QaLensConfig): NetworkEvent? {
        if (!config.enabled || !config.captureNetwork) return null
        fun body(value: String?): String? = if (!config.captureNetworkBodies) null
            else value?.let { config.redact(truncateCapturedBody(it)) }
        return event.copy(
            method = config.redact(event.method),
            url = QaLensRedactor.redactUrl(event.url, config.redactionRules),
            error = event.error?.let(config::redact),
            latencyMs = event.latencyMs.coerceAtLeast(0),
            requestBodyBytes = event.requestBodyBytes.coerceAtLeast(0),
            responseBodyBytes = event.responseBodyBytes.coerceAtLeast(0),
            requestBodyPreview = body(event.requestBodyPreview),
            responseBodyPreview = body(event.responseBodyPreview)
        )
    }

    fun sourceName(name: String): String {
        val clean = name.trim()
        require(clean.length in 1..64 && clean.all { it.isLetterOrDigit() || it in " ._-/" }) {
            "Use a short integration name (1–64 letters, digits, spaces, '.', '_', '-', '/'), not a URL or identifier."
        }
        return clean
    }
}

/** Setup diagnostics describe declared integrations, not proof that every request was observed. */
object QaLensIntegrationDiagnostics {
    fun report(config: QaLensConfig, state: QaLensUiState): String = buildString {
        appendLine("QaLens integration check")
        appendLine("SDK: ${if (config.enabled) "enabled" else "disabled"}")
        appendLine("Android lifecycle: ${if (state.isInstalled) "installed" else "not installed"}")
        appendLine("Network capture: ${if (config.captureNetwork) "enabled" else "disabled"}")
        appendLine("Declared network sources: ${state.networkSources.sorted().joinToString().ifEmpty { "none" }}")
        appendLine("Network rows in dashboard: ${state.networkEvents.size}")
        appendLine("Automatic logs: ${if (config.captureLogs) "enabled" else "disabled"}")
        appendLine("Logs in dashboard: ${state.events.size}")
        appendLine("Body previews: ${if (config.captureNetworkBodies) "opted in; bounded and text-redacted" else "off"}")
        if (config.enabled && config.captureNetwork && state.networkSources.isEmpty())
            appendLine("ACTION: attach QaLensOkHttpInterceptor to the client that executes requests, or use QaLens.networkSink for another transport.")
        if (config.networkFromChucker)
            appendLine("ACTION: networkFromChucker is unsupported. Keep both ChuckerInterceptor and QaLensOkHttpInterceptor; Chucker has no public transaction listener.")
        appendLine("A declared source is not proof of traffic. Exercise a real request and check its network row.")
        appendLine("Screenshots mask known sensitive Compose regions; custom pixels and full-display video are not masked automatically.")
    }
}
