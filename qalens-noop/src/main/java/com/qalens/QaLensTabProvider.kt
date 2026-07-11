package com.qalens

import androidx.compose.runtime.Composable

/**
 * Release twin of [QaLensTabProvider] (B3). Identical interface so app code compiles unchanged
 * against `qalens-noop`; `Content` is never invoked in release.
 */
interface QaLensTabProvider {
    val title: String

    @Composable
    fun Content(state: QaLensUiState, config: QaLensConfig)
}
