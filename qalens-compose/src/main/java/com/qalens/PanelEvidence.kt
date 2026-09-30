package com.qalens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Immutable input: overlay flags, memory ticks and upload changes cannot invalidate evidence. */
internal data class PanelEvidenceInput(
    val snapshot: InspectionSnapshot,
    val network: List<NetworkEvent>,
    val config: QaLensConfig,
    val flags: Map<String, Boolean>,
    val networkAvailable: Boolean,
    val contract: ContractResult?,
    val data: Map<String, Map<String, String>>,
    val frames: List<FrameMetricsSample>,
    val attachments: List<EvidenceAttachment> = emptyList()
) {
    fun build(): EvidenceBundle = EvidenceBuilder.build(
        snapshot.copy(generatedAtMillis = System.currentTimeMillis()), network, config, flags, attachments,
        networkInterceptorInstalled = networkAvailable,
        expectedEnvironment = config.expectedEnvironment,
        observedHost = network.lastOrNull()?.let { runCatching { java.net.URL(it.url).host }.getOrNull() },
        slowThresholdMs = config.slowNetworkThresholdMs,
        contractResult = contract, dataSources = data, frameMetrics = frames
    )

    companion object {
        fun capture(state: QaLensUiState, config: QaLensConfig,
            attachments: List<EvidenceAttachment> = emptyList()) = PanelEvidenceInput(
            InspectionSnapshot(state.screen, state.device, state.nodes, state.warnings,
                state.testTags, state.events, state.selectedNode, generatedAtMillis = 0),
            state.networkEvents, config, state.featureFlags, state.networkAvailable,
            state.contractResult, state.dataSources, state.frameMetrics, attachments
        )
    }
}

/** One worker per visible tab. Conflation handles a continuous log stream without restart starvation. */
@Composable
internal fun panelEvidence(state: QaLensUiState): State<EvidenceBundle?> {
    val config by QaLens.config.collectAsState()
    return backgroundPanelState(PanelEvidenceInput.capture(state, config,
        QaLens.evidenceAttachments()), null) { it.build() }
}

@Composable
internal fun <Input, Output> backgroundPanelState(
    input: Input, initial: Output, compute: (Input) -> Output
): State<Output> {
    val latest = rememberUpdatedState(input)
    val computation = rememberUpdatedState(compute)
    return produceState(initial) {
        snapshotFlow { latest.value }.conflate().collect { input ->
            val build = computation.value
            value = withContext(Dispatchers.Default) { build(input) }
            delay(250)
        }
    }
}

/** Report formatting/redaction stays off main; only the clipboard operation returns to main. */
@Composable
internal fun backgroundCopier(context: Context): (String, () -> String) -> Unit {
    val scope = rememberCoroutineScope()
    val busy = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    return { label, build ->
        if (busy.compareAndSet(false, true)) scope.launch {
            try {
                val text = withContext(Dispatchers.Default) { build() }
                val bounded = if (text.length > 200_000) text.take(200_000) +
                    "\n[Clipboard output truncated. Export a recording for the full evidence.]" else text
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(label, bounded))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                QaLens.pushError(ErrorKind.EXPORT, "Could not copy $label: ${failure.message}")
            } finally {
                busy.set(false)
            }
        }
    }
}
