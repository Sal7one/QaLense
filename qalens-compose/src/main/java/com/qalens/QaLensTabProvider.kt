package com.qalens

import androidx.compose.runtime.Composable

/**
 * Extension point for apps to register custom tabs in the QaLens inspector panel (B3).
 *
 * Every app has domain-specific things QA needs to see (queue depths, A/B variants, cache state)
 * that don't fit the flat key-value [QaLens.registerDataSource] model. A composable tab API lets
 * apps build rich, interactive surfaces without forking QaLens.
 *
 * Register via `QaLens.registerTab(MyTabProvider())`. The tab appears after the built-in tabs in
 * the inspector panel. In release builds (`qalens-noop`), `registerTab` is a no-op — the provider
 * is constructible so app code compiles, but `Content` is never invoked.
 *
 * ```kotlin
 * class BackendHealthTab : QaLensTabProvider {
 *     override val title = "Backend Health"
 *     @Composable override fun Content(state: QaLensUiState, config: QaLensConfig) {
 *         // Render your backend dashboard using state.networkEvents, state.screen, etc.
 *     }
 * }
 * QaLens.registerTab(BackendHealthTab())
 * ```
 */
interface QaLensTabProvider {
    val title: String

    @Composable
    fun Content(state: QaLensUiState, config: QaLensConfig)
}
