package com.qalens

internal data class PanelLogInput(val events: List<QaEvent>, val level: QaEventType?, val filter: String)
internal data class PanelLogRows(val events: List<QaEvent>, val rows: List<Pair<QaEvent, Int>>) {
    companion object {
        val EMPTY = PanelLogRows(emptyList(), emptyList())
        fun build(input: PanelLogInput): PanelLogRows {
            val filtered = input.events.asReversed().filter { event ->
                (input.level == null || event.type == input.level) &&
                    (input.filter.isBlank() || event.message.contains(input.filter, ignoreCase = true) ||
                        event.tag?.contains(input.filter, ignoreCase = true) == true)
            }
            val rows = mutableListOf<Pair<QaEvent, Int>>()
            filtered.forEach { event ->
                val last = rows.lastOrNull()
                if (last != null && last.first.message == event.message &&
                    last.first.type == event.type && last.first.tag == event.tag) {
                    rows[rows.lastIndex] = last.first to (last.second + 1)
                } else rows += event to 1
            }
            return PanelLogRows(filtered, rows)
        }
    }
}
