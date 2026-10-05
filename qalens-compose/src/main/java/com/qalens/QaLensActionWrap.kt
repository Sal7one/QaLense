package com.qalens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.constrainHeight

/** Small button groups using stable Compose UI APIs. Experimental FlowRow changed its binary
 * signature after 1.7; calling that version from an SDK crashes hosts on newer Foundation. */
@Composable
internal fun QaLensActionWrap(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gap = 6.dp.roundToPx()
        val children = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val positions = ArrayList<Pair<Int, Int>>(children.size)
        var x = 0
        var y = 0
        var rowHeight = 0
        var width = 0
        children.forEach { child ->
            if (x > 0 && x + child.width > constraints.maxWidth) {
                y += rowHeight + gap
                x = 0
                rowHeight = 0
            }
            positions += x to y
            width = maxOf(width, x + child.width)
            x += child.width + gap
            rowHeight = maxOf(rowHeight, child.height)
        }
        layout(constraints.constrainWidth(width), constraints.constrainHeight(y + rowHeight)) {
            children.forEachIndexed { index, child ->
                val (left, top) = positions[index]
                child.placeRelative(left, top)
            }
        }
    }
}
