package com.qalens

/** Restrict captured tracks to one recording; older crashes/bookmarks must not taint a new session. */
object RecordingWindow {
    fun slice(state: QaLensUiState, startMillis: Long, endMillis: Long): QaLensUiState {
        require(endMillis >= startMillis)
        val window = startMillis..endMillis
        return state.copy(
            events = state.events.filter { it.timestampMillis in window },
            networkEvents = state.networkEvents.filter { it.timestampMillis in window },
            crashes = state.crashes.filter { it.timestampMillis in window },
            frameMetrics = state.frameMetrics.filter { it.timestampMillis in window },
            connectivityTransitions = state.connectivityTransitions.filter { it.timestampMillis in window },
            memorySamples = state.memorySamples.filter { it.timestampMillis in window },
            bookmarks = state.bookmarks.filter { it.timestampMillis in window }
        )
    }
}
