package com.qalens

data class BuildSafetyStatus(
    val issues: List<String>,
    val observedHost: String? = null
) {
    val isSafe: Boolean get() = issues.isEmpty()

    /** One-line build banner, e.g. "STAGING · v6.12.0 · build 8421 · git a91f22c". */
    fun banner(device: DeviceSnapshot): String = buildList {
        device.environment?.takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }
        device.appVersion.takeIf { it.isNotBlank() }?.let { add("v$it") }
        device.buildNumber?.takeIf { it.isNotBlank() }?.let { add("build $it") }
        device.gitSha?.takeIf { it.isNotBlank() }?.let { add("git ${it.take(7)}") }
    }.joinToString("  ·  ").ifBlank { "build metadata not configured" }
}

/**
 * Flags missing or suspicious build metadata so QA never spends time testing the wrong build.
 * Pure logic — the observed host is supplied by the network interceptor when available.
 */
object BuildSafetyCheck {

    private val devHostMarkers = listOf("localhost", "127.0.0.1", "10.0.2.2", "dev.", ".dev", "-dev", "staging-local")

    fun check(
        device: DeviceSnapshot,
        expectedEnvironment: String? = null,
        observedHost: String? = null
    ): BuildSafetyStatus {
        val issues = mutableListOf<String>()

        if (device.gitSha.isNullOrBlank()) issues += "Git SHA is missing — configure QaLens.gitSha."
        if (device.buildVariant.isBlank()) issues += "Build variant is unknown — configure QaLens.buildVariant."
        if (device.environment.isNullOrBlank()) issues += "Environment is not configured — set QaLens.environment."
        if (device.appVersion.isBlank()) issues += "App version is missing."

        val env = device.environment
        if (!expectedEnvironment.isNullOrBlank() && !env.isNullOrBlank() &&
            !env.equals(expectedEnvironment, ignoreCase = true)
        ) {
            issues += "Environment is '$env' but the expected release profile is '$expectedEnvironment'."
        }

        if (!observedHost.isNullOrBlank() && !env.isNullOrBlank()) {
            val looksDev = devHostMarkers.any { observedHost.contains(it, ignoreCase = true) }
            val envIsHigher = env.contains("stag", true) || env.contains("prod", true) || env.contains("release", true)
            if (looksDev && envIsHigher) {
                issues += "Backend host '$observedHost' looks DEV/local while environment is '$env'."
            }
        }

        return BuildSafetyStatus(issues = issues, observedHost = observedHost)
    }
}
