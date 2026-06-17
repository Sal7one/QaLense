package com.qalens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier

/**
 * Counts how many times this composable recomposes and surfaces it in the QaLens panel
 * under the Device tab → "Recompose Counts". Debounced at 500 ms so it doesn't flood state.
 *
 * Usage:
 *   Box(Modifier.qaLensRecompose("MyCard")) { ... }
 */
@Composable
fun Modifier.qaLensRecompose(name: String): Modifier {
    SideEffect { QaLens.trackRecompose(name) }
    return this
}
