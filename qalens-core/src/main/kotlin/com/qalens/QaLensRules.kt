package com.qalens

object QaLensRules {
    fun evaluate(nodes: List<InspectNode>, config: QaLensConfig): List<InspectNode> {
        val screenWarnings = evaluateScreen(nodes)
        return nodes.map { node ->
            val nodeWarnings = evaluateNode(node, config)
            node.copy(warnings = nodeWarnings + screenWarnings.filter { it.nodeId == node.id })
        }
    }

    fun flattenWarnings(nodes: List<InspectNode>): List<QaWarning> =
        nodes.flatMap { it.warnings }
            .distinctBy { listOf(it.id, it.nodeId, it.title, it.description).joinToString("|") }
            .sortedWith(compareBy<QaWarning> { it.severity.ordinal }.thenBy { it.title })

    private fun evaluateNode(node: InspectNode, config: QaLensConfig): List<QaWarning> = buildList {
        if (node.isClickable && !node.hasHumanLabel) {
            add(
                QaWarning(
                    id = "A11Y_CLICKABLE_MISSING_LABEL",
                    severity = WarningSeverity.CRITICAL,
                    title = "Clickable element has no human label",
                    description = "Add visible text, contentDescription, or qaName so QA and accessibility tools can identify it.",
                    nodeId = node.id
                )
            )
        }

        if (node.isClickable && (node.widthDp < config.touchTargetMinDp || node.heightDp < config.touchTargetMinDp)) {
            add(
                QaWarning(
                    id = "A11Y_SMALL_TOUCH_TARGET",
                    severity = WarningSeverity.WARNING,
                    title = "Touch target below ${config.touchTargetMinDp.toInt()}dp",
                    description = "Actual size: ${node.widthDp.toInt()}dp × ${node.heightDp.toInt()}dp.",
                    nodeId = node.id
                )
            )
        }

        val weakLabel = node.label.trim().lowercase()
        if (node.isClickable && weakLabel in config.nonDescriptiveLabels) {
            add(
                QaWarning(
                    id = "A11Y_WEAK_LABEL",
                    severity = WarningSeverity.WARNING,
                    title = "Weak label: \"$weakLabel\"",
                    description = "Use a descriptive label like Recharge, Close, Open settings, or Pay bill.",
                    nodeId = node.id
                )
            )
        }

        if (config.requireTestTagsForClickable && node.isClickable && node.testTag.isNullOrBlank()) {
            add(
                QaWarning(
                    id = "QA_CLICKABLE_MISSING_TEST_TAG",
                    severity = WarningSeverity.INFO,
                    title = "Clickable element missing test tag",
                    description = "Add Modifier.testTag(...) or Modifier.qaTag(...) to improve QA reporting and test locator stability.",
                    nodeId = node.id
                )
            )
        }
    }

    private fun evaluateScreen(nodes: List<InspectNode>): List<QaWarning> = buildList {
        val duplicateTags = nodes
            .mapNotNull { node -> node.testTag?.takeIf { it.isNotBlank() }?.let { it to node.id } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }

        duplicateTags.forEach { (tag, ids) ->
            ids.forEach { id ->
                add(
                    QaWarning(
                        id = "QA_DUPLICATE_TEST_TAG",
                        severity = WarningSeverity.WARNING,
                        title = "Duplicate test tag",
                        description = "The test tag \"$tag\" appears ${ids.size} times on this screen.",
                        nodeId = id
                    )
                )
            }
        }

        val duplicateContentDescriptions = nodes
            .flatMap { node -> node.contentDescription.map { it.trim() to node.id } }
            .filter { it.first.isNotBlank() }
            .groupBy({ it.first.lowercase() }, { it.second })
            .filterValues { it.size > 1 }

        duplicateContentDescriptions.forEach { (label, ids) ->
            ids.forEach { id ->
                add(
                    QaWarning(
                        id = "A11Y_DUPLICATE_CONTENT_DESCRIPTION",
                        severity = WarningSeverity.INFO,
                        title = "Repeated content description",
                        description = "The label \"$label\" appears ${ids.size} times. This may be fine for list items, but QA should verify context.",
                        nodeId = id
                    )
                )
            }
        }
    }
}
