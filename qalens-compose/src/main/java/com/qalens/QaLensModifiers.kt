package com.qalens

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import java.util.UUID

val QaNameKey = SemanticsPropertyKey<String>("QaLensName")
var SemanticsPropertyReceiver.qaLensName by QaNameKey

val QaHiddenFromReportsKey = SemanticsPropertyKey<Boolean>("QaLensHiddenFromReports")
var SemanticsPropertyReceiver.qaLensHiddenFromReports by QaHiddenFromReportsKey

fun Modifier.qaTag(
    tag: String,
    hiddenFromReports: Boolean = false
): Modifier = composed {
    val id = remember(tag) { "manual:${tag}:${UUID.randomUUID()}" }
    val density = LocalDensity.current

    DisposableEffect(id) {
        onDispose { QaLens.unregisterManualNode(id) }
    }

    this
        .semantics {
            testTag = tag
            if (hiddenFromReports) qaLensHiddenFromReports = true
        }
        .onGloballyPositioned { coordinates ->
            val bounds: Rect = coordinates.boundsInWindow()
            QaLens.registerManualNode(
                InspectNode(
                    id = id,
                    testTag = tag,
                    bounds = QaRect(
                        left = bounds.left.toInt(),
                        top = bounds.top.toInt(),
                        right = bounds.right.toInt(),
                        bottom = bounds.bottom.toInt()
                    ),
                    widthDp = with(density) { bounds.width.toDp().value },
                    heightDp = with(density) { bounds.height.toDp().value },
                    source = NodeSource.MANUAL_QA_TAG,
                    hiddenFromReports = hiddenFromReports
                )
            )
        }
}

fun Modifier.qaName(name: String): Modifier = semantics { qaLensName = name }

fun Modifier.qaHiddenFromReports(): Modifier = semantics { qaLensHiddenFromReports = true }

fun Modifier.qaContentDescription(value: String): Modifier = semantics { contentDescription = value }
