package com.qalens

/** Recording-owned history. UI eviction/clearing never changes these tracks.
 *
 * Keeps the earliest observations within independent entry and estimated-size budgets, so a noisy
 * track cannot evict another track's evidence. Size accounting includes UTF-16 text and allowances
 * for objects; it is not a measurement of JVM heap usage. Export still applies configured redaction.
 * All admission, annotation edits and close operations share a lock. Late callbacks are ignored.
 */
class RecordingEvidenceStore(private val startMillis: Long, private val limits: Map<String, Limit> = emptyMap()) {
    data class Limit(val entries: Int, val estimatedBytes: Long) {
        init { require(entries >= 0 && estimatedBytes >= 0) }
    }
    data class TrackCoverage(
        val observed: Long, val retained: Int, val dropped: Long, val removed: Long,
        val estimatedBytes: Long, val limit: Limit
    ) {
        fun asMap(): Map<String, Any> = mapOf(
            "observed" to observed, "retained" to retained, "dropped" to dropped, "removed" to removed,
            "estimatedBytes" to estimatedBytes, "maxEntries" to limit.entries,
            "maxEstimatedBytes" to limit.estimatedBytes
        )
    }
    data class Retention(val tracks: Map<String, TrackCoverage>, val droppedFrameCallbacks: Long) {
        val truncated: Boolean get() = tracks.values.any { it.dropped > 0 }
        fun notes(): List<String> = tracks.filterValues { it.dropped > 0 }.map { (name, c) ->
            "Recording $name limit reached: ${c.dropped} observations omitted; ${c.retained} retained. Conclusions cover retained evidence only."
        } + if (droppedFrameCallbacks > 0) listOf(
            "Android reported $droppedFrameCallbacks dropped frame-metrics callbacks during recording; performance statistics are partial."
        ) else emptyList()
        fun asMap(): Map<String, Any> = mapOf(
            "policy" to "keep-earliest", "truncated" to truncated,
            "droppedFrameCallbacks" to droppedFrameCallbacks,
            "tracks" to tracks.mapValues { it.value.asMap() }
        )
    }
    data class Snapshot(val state: QaLensUiState, val stateSamples: List<StateSample>, val retention: Retention) {
        fun applyTo(ui: QaLensUiState): QaLensUiState = ui.copy(
            events = state.events, networkEvents = state.networkEvents, crashes = state.crashes,
            frameMetrics = state.frameMetrics, connectivityTransitions = state.connectivityTransitions,
            memorySamples = state.memorySamples, bookmarks = state.bookmarks
        )
    }
    private class Track<T>(val limit: Limit) {
        val items = mutableListOf<Pair<T, Long>>()
        var observed = 0L
        var dropped = 0L
        var removed = 0L
        var bytes = 0L
        fun add(value: T, size: Long) {
            observed++
            if (items.size >= limit.entries || size > limit.estimatedBytes - bytes) { dropped++; return }
            items += value to size
            bytes += size
        }
        fun remove(predicate: (T) -> Boolean) {
            val iterator = items.iterator()
            while (iterator.hasNext()) {
                val (value, size) = iterator.next()
                if (predicate(value)) { iterator.remove(); bytes -= size; removed++ }
            }
        }
        fun values(): List<T> = items.map { it.first }
        fun coverage() = TrackCoverage(observed, items.size, dropped, removed, bytes, limit)
    }
    private fun limit(name: String, entries: Int, mib: Long = 2) = limits[name] ?: Limit(entries, mib * 1024 * 1024)
    private val logs = Track<QaEvent>(limit("logs", 10_000))
    private val network = Track<NetworkEvent>(limit("network", 2_000, 8))
    private val crashes = Track<QaLensCrash>(limit("crashes", 100))
    private val performance = Track<FrameMetricsSample>(limit("performance", 40_000, 8))
    private val connectivity = Track<ConnectivitySnapshot>(limit("connectivity", 1_000))
    private val memory = Track<MemorySample>(limit("memory", 2_000))
    private val marks = Track<Bookmark>(limit("marks", 1_000))
    private val state = Track<StateSample>(limit("state", 1_000))
    private var closed: Snapshot? = null
    private var droppedCallbacks = 0L
    private fun accepts(timestamp: Long) = closed == null && timestamp >= startMillis
    private fun text(vararg values: String?): Long = values.sumOf { if (it == null) 0L else 48L + it.length.toLong() * 2 }

    @Synchronized fun event(value: QaEvent) {
        if (accepts(value.timestampMillis)) logs.add(value, 128 + text(value.message, value.tag))
    }
    @Synchronized fun network(value: NetworkEvent) {
        if (accepts(value.timestampMillis)) network.add(value, 256 + text(value.method, value.url,
            value.error, value.requestBodyPreview, value.responseBodyPreview))
    }
    @Synchronized fun crash(value: QaLensCrash) {
        if (accepts(value.timestampMillis) && crashes.items.none { it.first == value })
            crashes.add(value, 128 + text(value.thread, value.throwable, value.stackTrace, value.screen, value.route, value.lastNetworkSummary))
    }
    @Synchronized fun frame(value: FrameMetricsSample) {
        if (accepts(value.timestampMillis)) performance.add(value, 128)
    }
    @Synchronized fun droppedFrames(count: Int) {
        if (closed == null) droppedCallbacks += count.coerceAtLeast(0).toLong()
    }
    @Synchronized fun connectivity(value: ConnectivitySnapshot) {
        if (accepts(value.timestampMillis)) connectivity.add(value, 128)
    }
    @Synchronized fun memory(value: MemorySample) {
        if (accepts(value.timestampMillis)) memory.add(value, 128 + text(value.trimLevel))
    }
    @Synchronized fun bookmark(value: Bookmark) {
        if (accepts(value.timestampMillis)) marks.add(value, 128 + text(value.id, value.label))
    }
    @Synchronized fun removeBookmark(id: String) { if (closed == null) marks.remove { it.id == id } }
    @Synchronized fun clearBookmarks() { if (closed == null) marks.remove { true } }
    @Synchronized fun state(value: StateSample) {
        if (!accepts(value.timestampMillis)) return
        val size = 128 + text(value.screenName, value.route) +
            value.featureFlags.entries.sumOf { 64 + text(it.key) } +
            value.dataSources.entries.sumOf { (name, entries) ->
                128 + text(name) + entries.entries.sumOf { 64 + text(it.key, it.value) }
            }
        // Copy host-owned maps; subsequent host mutation must not rewrite captured evidence.
        if (state.items.size >= state.limit.entries || size > state.limit.estimatedBytes - state.bytes) {
            state.observed++; state.dropped++; return
        }
        state.add(value.copy(featureFlags = value.featureFlags.toMap(),
            dataSources = value.dataSources.mapValues { it.value.toMap() }), size)
    }
    /** Freezes evidence at stop, before potentially slow video finalization / archive writing. */
    @Synchronized fun close(): Snapshot {
        closed?.let { return it }
        val tracks = linkedMapOf("logs" to logs, "network" to network, "crashes" to crashes,
            "performance" to performance, "connectivity" to connectivity, "memory" to memory,
            "marks" to marks, "state" to state)
        val snapshot = Snapshot(QaLensUiState(events = logs.values(), networkEvents = network.values(),
            crashes = crashes.values(), frameMetrics = performance.values(), connectivityTransitions = connectivity.values(),
            memorySamples = memory.values(), bookmarks = marks.values()), state.values(),
            Retention(tracks.mapValues { it.value.coverage() }, droppedCallbacks))
        closed = snapshot
        tracks.values.forEach { it.items.clear() }
        return snapshot
    }
}
