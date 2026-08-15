package com.qalens

/**
 * Extracted from the `QaLens` orchestrator (A3). Owns the *pure* analysis pipeline: given the
 * current observed state + config + registered contracts/data-source providers, it computes the
 * derived slices (score, classification, build safety, feature flags, screen quality, contract
 * result, data sources) without touching any Android type or `StateFlow`.
 *
 * This is the testable core of `runAnalysis()` — it can be unit-tested without an Android emulator
 * by feeding it fixture `QaLensUiState`s + `QaLensConfig`s and asserting the derived slices.
 *
 * The `QaLens` object still owns the *when* (debouncing, main-thread confinement) and the
 * *where* (reading from + writing to `uiStateMutable`); this class owns the *what*.
 *
 * @param contracts the registered screen contracts (name → contract)
 * @param dataSourceProviders the registered data-source entries (provider + per-source redaction)
 * @param featureFlagProvider the live feature-flag provider (null if none)
 * @param onError called when a provider throws (so the facade can surface it via pushError)
 */

/** A registered data-source snapshot provider + its per-source redaction policy (B16). */
internal data class DataSourceEntry(
    val provider: () -> Map<String, String>,
    val redactKeys: Set<String> = emptySet(),
    val redactPatterns: List<Regex> = emptyList(),
    val redactAll: Boolean = false
)

internal class AnalysisEngine(
    private val contracts: Map<String, ScreenContract>,
    private val dataSourceProviders: Map<String, DataSourceEntry>,
    private val featureFlagProvider: (() -> Map<String, Boolean>)?,
    private val onError: (ErrorKind, String) -> Unit
) {
    /** The last screen key seen — used to detect new visits for [ScreenQualityStore]. Mutable. */
    @Volatile private var lastScreenKey: String? = null

    /**
     * Compute all derived analysis slices from the current state. Pure (no side effects beyond
     * [onError] for failing providers + [lastScreenKey] mutation for visit tracking).
     */
    data class AnalysisResult(
        val score: ReleaseReadinessScore,
        val classification: BugClassification,
        val buildSafety: BuildSafetyStatus,
        val featureFlags: Map<String, Boolean>,
        val screenQuality: Map<String, ScreenQualitySnapshot>,
        val contractResult: ContractResult?,
        val dataSources: Map<String, Map<String, String>>
    )

    fun analyze(state: QaLensUiState, config: QaLensConfig, previousScreenQuality: Map<String, ScreenQualitySnapshot>): AnalysisResult {
        val flags = resolveFeatureFlags(config)
        val observedHost = state.networkEvents.lastOrNull()?.let { hostOf(it.url) }
        val buildSafety = BuildSafetyCheck.check(state.device, config.expectedEnvironment, observedHost)
        val score = ReleaseReadinessEngine.score(
            warnings = state.warnings,
            screen = state.screen,
            network = state.networkEvents,
            buildSafetyIssues = buildSafety.issues,
            slowThresholdMs = config.slowNetworkThresholdMs,
            frameMetrics = state.frameMetrics
        )
        val classification = BugClassifier.classify(
            network = state.networkEvents,
            warnings = state.warnings,
            screenHistory = state.screen.history,
            slowThresholdMs = config.slowNetworkThresholdMs,
            buildSafetyIssues = buildSafety.issues
        )
        val key = ScreenQualityStore.keyFor(state.screen)
        val newVisit = key != lastScreenKey
        lastScreenKey = key
        val screenQuality = ScreenQualityStore.record(previousScreenQuality, state.screen, score, newVisit)

        val contractResult = contracts.values
            .firstOrNull { ScreenContractValidator.appliesTo(it, state.screen) }
            ?.let { ScreenContractValidator.validate(it, state.nodes, state.warnings, state.screen, state.networkEvents) }

        val dataSources = resolveDataSources(config)

        return AnalysisResult(
            score = score,
            classification = classification,
            buildSafety = buildSafety,
            featureFlags = flags,
            screenQuality = screenQuality,
            contractResult = contractResult,
            dataSources = dataSources
        )
    }

    private fun resolveDataSources(cfg: QaLensConfig): Map<String, Map<String, String>> =
        dataSourceProviders.mapNotNull { (name, entry) ->
            val snapshot = runCatching { entry.provider() }
                .onFailure { onError(ErrorKind.DATA_SOURCE, "Data source '$name' failed: ${it.message}") }
                .getOrNull() ?: return@mapNotNull null
            // B16: per-source redaction on top of the global rules.
            name to snapshot.mapValues { (key, value) ->
                when {
                    key in entry.redactKeys -> "[REDACTED]"
                    entry.redactAll -> "[REDACTED]"
                    entry.redactPatterns.any { it.containsMatchIn(value) } -> "[REDACTED]"
                    else -> cfg.redact(value)  // global rules as backstop
                }
            }
        }.toMap()

    private fun resolveFeatureFlags(cfg: QaLensConfig): Map<String, Boolean> {
        val provided = runCatching { featureFlagProvider?.invoke() }
            .onFailure { onError(ErrorKind.DATA_SOURCE, "Feature flag provider failed: ${it.message}") }
            .getOrNull()
            .orEmpty()
        return cfg.featureFlags + provided
    }

    private fun hostOf(url: String): String? = runCatching { java.net.URL(url).host }.getOrNull()
}
