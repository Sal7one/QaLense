package com.qalens

/**
 * The dashboard is a newest-first window, independent of the recording journal. Keep just one
 * scheduled UI publication during a flood instead of putting one Runnable on main per log line.
 */
internal class DashboardEventBuffer {
    private val pending = ArrayDeque<QaEvent>()
    private var characters = 0

    @Synchronized fun add(event: QaEvent, requestedLimit: Int) {
        val bounded = event.copy(message = preview(event.message), tag = event.tag?.let(::preview))
        pending.addLast(bounded)
        characters += size(bounded)
        trimPending(limit(requestedLimit))
    }

    @Synchronized fun clear() { pending.clear(); characters = 0 }

    @Synchronized fun drain(): List<QaEvent> = pending.toList().also { clear() }

    private fun trimPending(maxEntries: Int) {
        while (pending.size > maxEntries || characters > MAX_CHARACTERS) {
            characters -= size(pending.removeFirst())
        }
    }

    companion object {
        const val MAX_ENTRIES = 10_000
        const val MAX_CHARACTERS = 1_048_576
        const val MAX_MESSAGE_CHARACTERS = 16_384
        fun limit(requested: Int): Int = requested.coerceIn(20, MAX_ENTRIES)
        fun preview(text: String): String = if (text.length <= MAX_MESSAGE_CHARACTERS) text
            else text.take(MAX_MESSAGE_CHARACTERS) + "… [dashboard preview truncated]"
        private fun size(event: QaEvent): Int = event.message.length + (event.tag?.length ?: 0)

        fun append(previous: List<QaEvent>, batch: List<QaEvent>, requestedLimit: Int): List<QaEvent> {
            val combined = previous + batch
            var start = combined.size
            var characters = 0
            val maxEntries = limit(requestedLimit)
            while (start > 0 && combined.size - start < maxEntries) {
                val next = size(combined[start - 1])
                if (characters + next > MAX_CHARACTERS) break
                characters += next
                start--
            }
            return combined.subList(start, combined.size).toList()
        }
    }
}

/** Newest observations awaiting the next dashboard publication; recording owns its own history. */
internal class DashboardQueue<T>(private val maxEntries: Int) {
    private val pending = ArrayDeque<T>()
    @Synchronized fun add(value: T) {
        pending.addLast(value)
        while (pending.size > maxEntries) pending.removeFirst()
    }
    @Synchronized fun clear() = pending.clear()
    @Synchronized fun drain(): List<T> = pending.toList().also { clear() }
}
