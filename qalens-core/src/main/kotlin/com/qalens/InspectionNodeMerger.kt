package com.qalens

/** Reconcile optional qaTag layout hints with Compose semantics without collapsing repeated tags. */
object InspectionNodeMerger {
    fun merge(semantics: List<InspectNode>, manual: List<InspectNode>): List<InspectNode> {
        val byId = linkedMapOf<String, InspectNode>()
        val hiddenRegions = semantics.filter { it.hiddenFromReports }.map { it.bounds }
        semantics.forEach { byId[it.id] = it }
        manual.filterNot { hint ->
            hint.hiddenFromReports || hiddenRegions.any { it.contains(hint.bounds.centerX.toFloat(), hint.bounds.centerY.toFloat()) }
        }.forEach { hint ->
            val match = hint.testTag?.let { tag ->
                byId.values.asSequence()
                    .filter { it.source != NodeSource.MANUAL_QA_TAG && it.testTag == tag }
                    .map { it to overlap(it.bounds, hint.bounds) }
                    .filter { it.second > 0L }
                    .maxByOrNull { it.second }
                    ?.first
            }
            if (match == null) {
                byId[hint.id] = hint
            } else {
                byId[match.id] = match.copy(
                    qaName = hint.qaName ?: match.qaName,
                    hiddenFromReports = hint.hiddenFromReports || match.hiddenFromReports,
                    source = NodeSource.MANUAL_QA_TAG
                )
            }
        }
        return byId.values
            .filter { it.bounds.width > 0 && it.bounds.height > 0 && !it.hiddenFromReports }
            .sortedWith(compareBy<InspectNode> { it.bounds.top }.thenBy { it.bounds.left })
    }

    private fun overlap(a: QaRect, b: QaRect): Long =
        (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0).toLong() *
            (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0).toLong()
}
