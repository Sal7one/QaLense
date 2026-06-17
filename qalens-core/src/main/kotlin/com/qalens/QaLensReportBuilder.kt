package com.qalens

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object QaLensReportBuilder {
    fun build(snapshot: InspectionSnapshot, config: QaLensConfig): String {
        fun r(value: String?): String = config.redact(value.orEmpty())
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            .format(Date(snapshot.generatedAtMillis))

        return buildString {
            appendLine("QaLens QA Report")
            appendLine("Generated: $date")
            appendLine()

            appendLine("== App / Build ==")
            appendLine("App: ${r(snapshot.device.appName)}")
            appendLine("Version: ${r(snapshot.device.appVersion)} (${snapshot.device.versionCode})")
            appendLine("Variant: ${r(snapshot.device.buildVariant)}")
            appendLine("Environment: ${r(snapshot.device.environment)}")
            appendLine("Git SHA: ${r(snapshot.device.gitSha)}")
            appendLine("Build Number: ${r(snapshot.device.buildNumber)}")
            appendLine("User Type: ${r(snapshot.device.userType)}")
            appendLine()

            appendLine("== Device ==")
            appendLine("Model: ${r(snapshot.device.manufacturer)} ${r(snapshot.device.deviceModel)}")
            appendLine("Android: ${r(snapshot.device.androidVersion)} / SDK ${snapshot.device.sdkVersion}")
            appendLine("Screen: ${snapshot.device.screenWidthDp}dp x ${snapshot.device.screenHeightDp}dp")
            appendLine("Density: ${snapshot.device.density}")
            appendLine("Font Scale: ${snapshot.device.fontScale}")
            appendLine("Layout: ${if (snapshot.device.isRtl) "RTL" else "LTR"}")
            appendLine()

            appendLine("== Screen ==")
            appendLine("Activity: ${r(snapshot.screen.activityName)}")
            appendLine("Fragment: ${r(snapshot.screen.fragmentName)}")
            appendLine("Screen: ${r(snapshot.screen.screenName)}")
            appendLine("Route: ${r(snapshot.screen.route)}")
            appendLine("History: ${snapshot.screen.history.joinToString(" > ") { r(it) }}")
            appendLine()

            appendLine("== Selected Component ==")
            val selected = snapshot.selectedNode
            if (selected == null) {
                appendLine("None")
            } else {
                appendNode(selected, config)
            }
            appendLine()

            appendLine("== Warnings (${snapshot.warnings.size}) ==")
            if (snapshot.warnings.isEmpty()) appendLine("None")
            snapshot.warnings.forEach { warning ->
                appendLine("[${warning.severity}] ${warning.title}")
                appendLine("- Rule: ${warning.id}")
                appendLine("- Node: ${warning.nodeId ?: "screen"}")
                appendLine("- ${r(warning.description)}")
            }
            appendLine()

            appendLine("== Visible Test Tags (${snapshot.testTags.size}) ==")
            if (snapshot.testTags.isEmpty()) appendLine("None")
            snapshot.testTags.forEach { appendLine("- ${r(it)}") }
            appendLine()

            appendLine("== Visible Components (${snapshot.nodes.size}) ==")
            snapshot.nodes.filterNot { it.hiddenFromReports }.take(120).forEach { node ->
                appendLine("- ${r(node.label)} [${node.id}] tag=${r(node.testTag)} source=${node.source}")
            }
            if (snapshot.nodes.size > 120) appendLine("... truncated ${snapshot.nodes.size - 120} components")
            appendLine()

            appendLine("== Events (${snapshot.events.size}) ==")
            snapshot.events.takeLast(80).forEach { event ->
                appendLine("- ${event.timestampMillis} [${event.type}] ${r(event.tag)} ${r(event.message)}")
            }
        }
    }

    private fun StringBuilder.appendNode(node: InspectNode, config: QaLensConfig) {
        fun r(value: String?): String = config.redact(value.orEmpty())
        appendLine("Id: ${r(node.id)}")
        appendLine("Label: ${r(node.label)}")
        appendLine("QA Name: ${r(node.qaName)}")
        appendLine("Test Tag: ${r(node.testTag)}")
        appendLine("Text: ${node.text.joinToString { r(it) }}")
        appendLine("Content Description: ${node.contentDescription.joinToString { r(it) }}")
        appendLine("Role: ${r(node.role)}")
        appendLine("State: ${r(node.stateDescription)}")
        appendLine("Enabled: ${node.isEnabled}")
        appendLine("Clickable: ${node.isClickable}")
        appendLine("Focusable: ${node.isFocusable}")
        appendLine("Selected: ${node.isSelected}")
        appendLine("Bounds: ${node.bounds}")
        appendLine("Size: ${node.widthDp.toInt()}dp x ${node.heightDp.toInt()}dp")
        appendLine("Source: ${node.source}")
    }
}
