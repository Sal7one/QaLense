package com.qalens

/** A file produced for the bundle (screenshot). [available] is false when capture failed. */
data class EvidenceAttachment(
    val kind: String,
    val path: String?,
    val available: Boolean,
    val note: String? = null
)

/** Which evidence subsystems contributed, so reports can be honest about gaps. */
data class EvidenceCompleteness(
    val hasScreenMeta: Boolean,
    val hasNetwork: Boolean,
    val hasNavigation: Boolean,
    val hasScreenshot: Boolean,
    val hasFeatureFlags: Boolean,
    val hasTimeline: Boolean
) {
    private val all = listOf(hasScreenMeta, hasNetwork, hasNavigation, hasScreenshot, hasFeatureFlags, hasTimeline)
    val percent: Int get() = if (all.isEmpty()) 0 else (all.count { it } * 100) / all.size

    val missing: List<String> get() = buildList {
        if (!hasScreenMeta) add("screen name/route (use QaLensNavHost or setScreen)")
        if (!hasNetwork) add("network capture (add QaLensOkHttpInterceptor)")
        if (!hasNavigation) add("navigation history")
        if (!hasScreenshot) add("screenshot (capture failed or not taken)")
        if (!hasFeatureFlags) add("feature flags (configure flags or a provider)")
        if (!hasTimeline) add("timeline events")
    }
}

/**
 * The complete in-memory evidence bundle assembled in one tap. Nothing here is persisted or
 * uploaded; it is built on demand from observed state and discarded unless the user exports it.
 */
data class EvidenceBundle(
    val generatedAtMillis: Long,
    val snapshot: InspectionSnapshot,
    val timeline: List<TimelineEvent>,
    val repro: ReproSteps,
    val classification: BugClassification,
    val score: ReleaseReadinessScore,
    val buildSafety: BuildSafetyStatus,
    val featureFlags: Map<String, Boolean>,
    val networkEvents: List<NetworkEvent>,
    val completeness: EvidenceCompleteness,
    val attachments: List<EvidenceAttachment>,
    val networkAvailable: Boolean,
    val contractResult: ContractResult? = null,
    val dataSources: Map<String, Map<String, String>> = emptyMap()
)

/**
 * Assembles an [EvidenceBundle] from everything QaLens has observed. Pure: callers pass in the
 * raw state, this orchestrates the core engines. Redaction happens at *export* time in
 * [QaLensReports] (the bundle itself holds the already-redaction-aware snapshot/timeline).
 */
object EvidenceBuilder {

    fun build(
        snapshot: InspectionSnapshot,
        network: List<NetworkEvent>,
        config: QaLensConfig,
        featureFlags: Map<String, Boolean>,
        attachments: List<EvidenceAttachment> = emptyList(),
        networkInterceptorInstalled: Boolean = network.isNotEmpty(),
        expectedEnvironment: String? = null,
        observedHost: String? = null,
        slowThresholdMs: Long = 2000L,
        contractResult: ContractResult? = null,
        dataSources: Map<String, Map<String, String>> = emptyMap(),
        nowMs: Long = System.currentTimeMillis()
    ): EvidenceBundle {
        val timeline = TimelineMerger.merge(snapshot.events, network, config)
        val repro = ReproStepGenerator.generate(timeline)
        val buildSafety = BuildSafetyCheck.check(snapshot.device, expectedEnvironment, observedHost)
        val score = ReleaseReadinessEngine.score(
            warnings = snapshot.warnings,
            screen = snapshot.screen,
            network = network,
            buildSafetyIssues = buildSafety.issues,
            slowThresholdMs = slowThresholdMs
        )
        val classification = BugClassifier.classify(
            network = network,
            warnings = snapshot.warnings,
            screenHistory = snapshot.screen.history,
            slowThresholdMs = slowThresholdMs,
            buildSafetyIssues = buildSafety.issues
        )

        val completeness = EvidenceCompleteness(
            hasScreenMeta = !snapshot.screen.screenName.isNullOrBlank() || !snapshot.screen.route.isNullOrBlank(),
            hasNetwork = networkInterceptorInstalled,
            hasNavigation = snapshot.screen.history.isNotEmpty(),
            hasScreenshot = attachments.any { it.available },
            hasFeatureFlags = featureFlags.isNotEmpty(),
            hasTimeline = timeline.isNotEmpty()
        )

        return EvidenceBundle(
            generatedAtMillis = nowMs,
            snapshot = snapshot,
            timeline = timeline,
            repro = repro,
            classification = classification,
            score = score,
            buildSafety = buildSafety,
            featureFlags = featureFlags,
            networkEvents = network,
            completeness = completeness,
            attachments = attachments,
            networkAvailable = networkInterceptorInstalled,
            contractResult = contractResult,
            dataSources = dataSources
        )
    }
}
