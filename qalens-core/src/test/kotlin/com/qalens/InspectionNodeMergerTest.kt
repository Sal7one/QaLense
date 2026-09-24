package com.qalens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InspectionNodeMergerTest {
    @Test fun repeatedTagsMatchTheirOwnBounds() {
        val first = node("root-1", QaRect(0, 0, 100, 40))
        val second = node("root-2", QaRect(0, 50, 100, 90))
        val hints = listOf(
            node("hint-2", QaRect(0, 50, 100, 90), NodeSource.MANUAL_QA_TAG).copy(qaName = "Second"),
            node("hint-1", QaRect(0, 0, 100, 40), NodeSource.MANUAL_QA_TAG).copy(qaName = "First")
        )
        val merged = InspectionNodeMerger.merge(listOf(first, second), hints)
        assertEquals(setOf("root-1", "root-2"), merged.map { it.id }.toSet())
        assertEquals(2, merged.size)
        assertEquals("First", merged.first { it.id == "root-1" }.qaName)
        assertEquals("Second", merged.first { it.id == "root-2" }.qaName)
    }

    @Test fun unmatchedHintsRemainVisibleAndHiddenHintsNeverLeak() {
        val semantics = node("root", QaRect(0, 0, 100, 40)).copy(hiddenFromReports = true)
        val unmatched = node("hint-other", QaRect(0, 50, 100, 90), NodeSource.MANUAL_QA_TAG)
        val hidden = node("hint-hidden", QaRect(0, 0, 100, 40), NodeSource.MANUAL_QA_TAG)
        val merged = InspectionNodeMerger.merge(listOf(semantics), listOf(unmatched, hidden))
        assertFalse(merged.any { it.id == "root" })
        assertTrue(merged.any { it.id == "hint-other" })
    }

    private fun node(id: String, bounds: QaRect, source: NodeSource = NodeSource.SEMANTICS) =
        InspectNode(id = id, testTag = "repeated", bounds = bounds, source = source)
}
