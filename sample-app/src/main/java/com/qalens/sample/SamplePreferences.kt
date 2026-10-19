package com.qalens.sample

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stands in for a DataStore preferences store (same way SampleBackend stands in for OkHttp). It
 * exposes a [StateFlow] of allowed values; QaLens.observeDataStoreValues() provides a live preview
 * and recording snapshots while emitting value-free change events.
 */
object SamplePreferences {
    private val _flow = MutableStateFlow(
        mapOf("theme" to "system", "biometrics" to "true", "onboarded" to "true")
    )
    val flow: StateFlow<Map<String, String>> = _flow.asStateFlow()

    val current: Map<String, String> get() = _flow.value

    fun setTheme(dark: Boolean) {
        _flow.value = _flow.value + ("theme" to if (dark) "dark" else "light")
    }
}
