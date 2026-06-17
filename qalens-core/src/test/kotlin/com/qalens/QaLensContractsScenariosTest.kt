package com.qalens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QaLensContractsScenariosTest {

    private fun node(tag: String? = null, text: String = "", cd: String = "") = InspectNode(
        id = "n_${tag ?: text}",
        testTag = tag,
        text = if (text.isNotBlank()) listOf(text) else emptyList(),
        contentDescription = if (cd.isNotBlank()) listOf(cd) else emptyList(),
        bounds = QaRect(0, 0, 10, 10)
    )

    private fun net(status: Int) = NetworkEvent(method = "POST", url = "https://x/y", status = status)

    // ── Scenario matching ─────────────────────────────────────────────────────

    @Test
    fun scenarioMatchesExactRoute() {
        assertTrue(ScenarioMatcher.matches("accounts", "accounts", null))
    }

    @Test
    fun scenarioMatchesPathPrefix() {
        assertTrue(ScenarioMatcher.matches("account", "account/2", null))
    }

    @Test
    fun scenarioMatchesScreenName() {
        assertTrue(ScenarioMatcher.matches("Profile", null, "Profile"))
    }

    @Test
    fun scenarioRejectsWrongRoute() {
        assertFalse(ScenarioMatcher.matches("accounts", "settings", "Settings"))
    }

    // ── Contract validation ─────────────────────────────────────────────────────

    private val screen = ScreenSnapshot(screenName = "Transfer", route = "transfer/1")

    private fun contract() = ScreenContractBuilder("Transfer").apply {
        requiresTag("transfer.confirm")
        requiresLabel("Confirm Transfer")
        requiresNoCriticalAccessibilityWarnings()
        requiresNoFailedNetwork()
    }.build()

    @Test
    fun contractAppliesToMatchingScreen() {
        assertTrue(ScreenContractValidator.appliesTo(contract(), screen))
        assertFalse(ScreenContractValidator.appliesTo(contract(), ScreenSnapshot(screenName = "Home", route = "home")))
    }

    @Test
    fun contractPassesWhenAllSatisfied() {
        val result = ScreenContractValidator.validate(
            contract(),
            nodes = listOf(node(tag = "transfer.confirm", text = "Confirm Transfer")),
            warnings = emptyList(),
            screen = screen,
            network = listOf(net(200))
        )
        assertTrue(result.passed)
        assertEquals(result.total, result.passCount)
    }

    @Test
    fun contractFailsOnMissingTagAndFailedNetwork() {
        val result = ScreenContractValidator.validate(
            contract(),
            nodes = listOf(node(text = "Confirm Transfer")), // no tag
            warnings = listOf(QaWarning("X", WarningSeverity.CRITICAL, "bad", "d")),
            screen = screen,
            network = listOf(net(500))
        )
        assertFalse(result.passed)
        // tag fail + critical a11y fail + failed network fail = 3 failures, label passes
        assertEquals(1, result.passCount)
    }
}
