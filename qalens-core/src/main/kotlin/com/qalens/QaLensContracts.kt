package com.qalens

/**
 * A live, optional quality checklist for a critical screen. Apps register a contract once; QaLens
 * validates it against the current semantics tree / warnings / network whenever that screen is
 * active. Screens without a contract produce no noise.
 */
data class ScreenContract(
    val screen: String,
    val rules: List<ContractRule>
)

sealed class ContractRule {
    abstract val description: String

    data class RequiresTag(val tag: String) : ContractRule() {
        override val description get() = "has test tag '$tag'"
    }
    data class RequiresAnyTag(val tags: List<String>) : ContractRule() {
        override val description get() = "has one of tags ${tags.joinToString(", ")}"
    }
    data class RequiresLabel(val label: String) : ContractRule() {
        override val description get() = "shows label '$label'"
    }
    data class RequiresRoute(val route: String) : ContractRule() {
        override val description get() = "route is '$route'"
    }
    object RequiresNoCriticalA11y : ContractRule() {
        override val description get() = "no critical accessibility issues"
    }
    object RequiresNoFailedNetwork : ContractRule() {
        // Honest naming: QaLens only sees completed calls, so it cannot prove a request is still
        // in flight. This checks that no captured call on this screen failed.
        override val description get() = "no failed network calls"
    }
}

data class ContractRuleResult(val description: String, val passed: Boolean, val detail: String? = null)

data class ContractResult(val screen: String, val results: List<ContractRuleResult>) {
    val total: Int get() = results.size
    val passCount: Int get() = results.count { it.passed }
    val passed: Boolean get() = results.all { it.passed }
}

/** Fluent builder behind `QaLens.contract("Checkout") { ... }`. */
class ScreenContractBuilder(private val screen: String) {
    private val rules = mutableListOf<ContractRule>()

    fun requiresTag(tag: String) { rules += ContractRule.RequiresTag(tag) }
    fun requiresAnyTag(vararg tags: String) { rules += ContractRule.RequiresAnyTag(tags.toList()) }
    fun requiresLabel(label: String) { rules += ContractRule.RequiresLabel(label) }
    fun requiresRoute(route: String) { rules += ContractRule.RequiresRoute(route) }
    fun requiresNoCriticalAccessibilityWarnings() { rules += ContractRule.RequiresNoCriticalA11y }
    fun requiresNoFailedNetwork() { rules += ContractRule.RequiresNoFailedNetwork }

    fun build(): ScreenContract = ScreenContract(screen, rules.toList())
}

object ScreenContractValidator {

    /** True when [contract] applies to the currently active screen. */
    fun appliesTo(contract: ScreenContract, screen: ScreenSnapshot): Boolean {
        val target = contract.screen.trim()
        return screen.screenName.equals(target, ignoreCase = true) ||
            screen.route?.equals(target, ignoreCase = true) == true ||
            screen.route?.substringBefore("/")?.substringBefore("?")?.equals(target, ignoreCase = true) == true
    }

    fun validate(
        contract: ScreenContract,
        nodes: List<InspectNode>,
        warnings: List<QaWarning>,
        screen: ScreenSnapshot,
        network: List<NetworkEvent>
    ): ContractResult {
        val results = contract.rules.map { rule ->
            when (rule) {
                is ContractRule.RequiresTag -> {
                    val ok = nodes.any { it.testTag == rule.tag }
                    ContractRuleResult(rule.description, ok)
                }
                is ContractRule.RequiresAnyTag -> {
                    val ok = nodes.any { it.testTag in rule.tags }
                    ContractRuleResult(rule.description, ok)
                }
                is ContractRule.RequiresLabel -> {
                    val ok = nodes.any { node ->
                        val haystack = (node.text + node.contentDescription + listOfNotNull(node.qaName, node.label))
                        haystack.any { it.contains(rule.label, ignoreCase = true) }
                    }
                    ContractRuleResult(rule.description, ok)
                }
                is ContractRule.RequiresRoute -> {
                    val ok = ScenarioMatcher.matches(rule.route, screen.route, screen.screenName)
                    ContractRuleResult(rule.description, ok, detail = screen.route)
                }
                ContractRule.RequiresNoCriticalA11y -> {
                    val critical = warnings.count { it.severity == WarningSeverity.CRITICAL }
                    ContractRuleResult(rule.description, critical == 0,
                        detail = if (critical > 0) "$critical critical issue(s)" else null)
                }
                ContractRule.RequiresNoFailedNetwork -> {
                    val failed = network.count { it.isError }
                    ContractRuleResult(rule.description, failed == 0,
                        detail = if (failed > 0) "$failed failed call(s)" else null)
                }
            }
        }
        return ContractResult(contract.screen, results)
    }
}
