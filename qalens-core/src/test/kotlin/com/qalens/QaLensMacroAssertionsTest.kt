package com.qalens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QaLensMacroAssertionsTest {

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private fun node(
        id: String,
        testTag: String? = null,
        qaName: String? = null,
        text: List<String> = emptyList(),
        role: String? = null
    ) = InspectNode(id = id, testTag = testTag, qaName = qaName, text = text, role = role)

    private fun net(
        status: Int = 200,
        latency: Long = 100,
        error: String? = null,
        url: String = "https://api.x.com/v1/thing"
    ) = NetworkEvent(method = "GET", url = url, status = status, latencyMs = latency, error = error)

    // ── parseMacroLine: all verbs ────────────────────────────────────────────

    @Test
    fun parseExistsBareSelector() {
        assertEquals(MacroStep.AssertExists("Submit"), parseMacroLine("assert exists Submit"))
    }

    @Test
    fun parseExistsStripsTagPrefix() {
        assertEquals(MacroStep.AssertExists("Submit"), parseMacroLine("assert exists tag:Submit"))
    }

    @Test
    fun parseLabel() {
        assertEquals(MacroStep.AssertLabel("submit", "Login"), parseMacroLine("assert label submit Login"))
    }

    @Test
    fun parseRoute() {
        assertEquals(MacroStep.AssertRoute("/home"), parseMacroLine("assert route /home"))
    }

    @Test
    fun parseNetworkNoErrors() {
        assertEquals(MacroStep.AssertNoNetworkErrors, parseMacroLine("assert network no-errors"))
    }

    @Test
    fun parseNetworkNoSlowDefaultThreshold() {
        assertEquals(MacroStep.AssertNoSlowNetwork(2000), parseMacroLine("assert network no-slow"))
    }

    @Test
    fun parseNetworkNoSlowCustomThreshold() {
        assertEquals(MacroStep.AssertNoSlowNetwork(1500), parseMacroLine("assert network no-slow 1500"))
    }

    @Test
    fun parseIsCaseInsensitive() {
        assertEquals(MacroStep.AssertExists("Submit"), parseMacroLine("ASSERT EXISTS Submit"))
        assertEquals(MacroStep.AssertNoNetworkErrors, parseMacroLine("Assert Network No-Errors"))
        assertEquals(MacroStep.AssertRoute("/x"), parseMacroLine("assert ROUTE /x"))
    }

    @Test
    fun parseUnknownReturnsNull() {
        assertNull(parseMacroLine("assert frobnicate"))
        assertNull(parseMacroLine("tap Submit"))
        assertNull(parseMacroLine("assert network"))
        assertNull(parseMacroLine("assert label onlytag"))
        assertNull(parseMacroLine(""))
        assertNull(parseMacroLine("assert exists"))
    }

    // ── parseMacroScript: if / then / else ───────────────────────────────────

    @Test
    fun parseScriptIfThenElseThreeLine() {
        val steps = parseMacroScript(
            listOf(
                "if exists Button",
                "assert route /expected",
                "else assert route /fallback"
            )
        )
        assertEquals(1, steps.size)
        val ifStep = steps[0] as MacroStep.If
        assertEquals(MacroCondition.Exists("Button"), ifStep.condition)
        assertEquals(MacroStep.AssertRoute("/expected"), ifStep.thenStep)
        assertEquals(MacroStep.AssertRoute("/fallback"), ifStep.elseStep)
    }

    @Test
    fun parseScriptIfThenElseFourLine() {
        val steps = parseMacroScript(
            listOf(
                "if route /home",
                "assert exists Submit",
                "else",
                "assert exists Cancel"
            )
        )
        assertEquals(1, steps.size)
        val ifStep = steps[0] as MacroStep.If
        assertEquals(MacroCondition.RouteEquals("/home"), ifStep.condition)
        assertEquals(MacroStep.AssertExists("Submit"), ifStep.thenStep)
        assertEquals(MacroStep.AssertExists("Cancel"), ifStep.elseStep)
    }

    @Test
    fun parseScriptIfWithoutElse() {
        val steps = parseMacroScript(
            listOf(
                "if exists Button",
                "assert route /expected"
            )
        )
        assertEquals(1, steps.size)
        val ifStep = steps[0] as MacroStep.If
        assertEquals(MacroStep.AssertRoute("/expected"), ifStep.thenStep)
        assertNull(ifStep.elseStep)
    }

    @Test
    fun parseScriptSkipsBlanksAndUnknownLines() {
        val steps = parseMacroScript(
            listOf(
                "",
                "assert exists Foo",
                "garbage line",
                "",
                "assert route /bar"
            )
        )
        assertEquals(
            listOf(MacroStep.AssertExists("Foo"), MacroStep.AssertRoute("/bar")),
            steps
        )
    }

    // ── Engine: AssertLabel ──────────────────────────────────────────────────

    @Test
    fun assertLabelPassesWhenNodeTextContains() {
        val state = QaLensUiState(nodes = listOf(node("1", testTag = "submit", text = listOf("Login"))))
        val result = MacroEngine.execute(listOf(MacroStep.AssertLabel("tag:submit", "Login")), state)
        assertTrue(result.allPassed)
        assertTrue(result.steps[0].passed)
    }

    @Test
    fun assertLabelBareTagMatchesQaName() {
        val state = QaLensUiState(nodes = listOf(node("1", qaName = "submit", text = listOf("Login"))))
        val result = MacroEngine.execute(listOf(MacroStep.AssertLabel("submit", "Login")), state)
        assertTrue(result.allPassed)
    }

    @Test
    fun assertLabelFailsWhenTextMissing() {
        val state = QaLensUiState(nodes = listOf(node("1", testTag = "submit", text = listOf("Cancel"))))
        val result = MacroEngine.execute(listOf(MacroStep.AssertLabel("tag:submit", "Login")), state)
        assertFalse(result.allPassed)
        assertFalse(result.steps[0].passed)
    }

    @Test
    fun assertLabelFailsWhenNodeMissing() {
        val state = QaLensUiState(nodes = emptyList())
        val result = MacroEngine.execute(listOf(MacroStep.AssertLabel("tag:submit", "Login")), state)
        assertFalse(result.allPassed)
    }

    // ── Engine: AssertNoNetworkErrors ────────────────────────────────────────

    @Test
    fun assertNoNetworkErrorsPassesWhenClean() {
        val state = QaLensUiState(networkEvents = listOf(net(status = 200)))
        assertTrue(MacroEngine.execute(listOf(MacroStep.AssertNoNetworkErrors), state).allPassed)
    }

    @Test
    fun assertNoNetworkErrorsFailsOn5xx() {
        val state = QaLensUiState(networkEvents = listOf(net(status = 500)))
        assertFalse(MacroEngine.execute(listOf(MacroStep.AssertNoNetworkErrors), state).allPassed)
    }

    @Test
    fun assertNoNetworkErrorsFailsOnTransportError() {
        val state = QaLensUiState(networkEvents = listOf(net(status = 0, error = "SocketTimeoutException")))
        assertFalse(MacroEngine.execute(listOf(MacroStep.AssertNoNetworkErrors), state).allPassed)
    }

    // ── Engine: AssertNoSlowNetwork ──────────────────────────────────────────

    @Test
    fun assertNoSlowNetworkPassesWhenUnderThreshold() {
        val state = QaLensUiState(networkEvents = listOf(net(status = 200, latency = 1500)))
        assertTrue(MacroEngine.execute(listOf(MacroStep.AssertNoSlowNetwork()), state).allPassed)
    }

    @Test
    fun assertNoSlowNetworkFailsWhenOverThreshold() {
        val state = QaLensUiState(networkEvents = listOf(net(status = 200, latency = 2500)))
        assertFalse(MacroEngine.execute(listOf(MacroStep.AssertNoSlowNetwork()), state).allPassed)
    }

    @Test
    fun assertNoSlowNetworkIgnoresFailedRequests() {
        // A failed request is not a "successful" request, so it is never slow.
        val state = QaLensUiState(networkEvents = listOf(net(status = 500, latency = 5000)))
        assertTrue(MacroEngine.execute(listOf(MacroStep.AssertNoSlowNetwork()), state).allPassed)
    }

    @Test
    fun assertNoSlowNetworkCustomThreshold() {
        val state = QaLensUiState(networkEvents = listOf(net(status = 200, latency = 1200)))
        assertFalse(MacroEngine.execute(listOf(MacroStep.AssertNoSlowNetwork(1000)), state).allPassed)
        assertTrue(MacroEngine.execute(listOf(MacroStep.AssertNoSlowNetwork(1500)), state).allPassed)
    }

    // ── Engine: MacroCondition.NoSlowRequests ────────────────────────────────

    @Test
    fun conditionNoSlowRequestsTrueWhenFast() {
        val state = QaLensUiState(networkEvents = listOf(net(status = 200, latency = 100)))
        val step = MacroStep.If(
            condition = MacroCondition.NoSlowRequests(2000),
            thenStep = MacroStep.AssertExists("ThenBranch"),
            elseStep = MacroStep.AssertExists("ElseBranch")
        )
        val result = MacroEngine.execute(listOf(step), state)
        assertTrue(result.steps[0].message.contains("ThenBranch"))
    }

    @Test
    fun conditionNoSlowRequestsFalseWhenSlow() {
        val state = QaLensUiState(networkEvents = listOf(net(status = 200, latency = 2500)))
        val step = MacroStep.If(
            condition = MacroCondition.NoSlowRequests(2000),
            thenStep = MacroStep.AssertExists("ThenBranch"),
            elseStep = MacroStep.AssertExists("ElseBranch")
        )
        val result = MacroEngine.execute(listOf(step), state)
        assertTrue(result.steps[0].message.contains("ElseBranch"))
    }

    // ── MacroResult.failures ─────────────────────────────────────────────────

    @Test
    fun failuresContainsOnlyFailedAssertions() {
        val state = QaLensUiState(nodes = emptyList(), screen = ScreenSnapshot(route = "/login"))
        val steps = listOf(
            MacroStep.AssertExists("Missing"),
            MacroStep.Tap("Button"),
            MacroStep.AssertRoute("/home"),
            MacroStep.AssertExists("AlsoMissing")
        )
        val result = MacroEngine.execute(steps, state)
        assertEquals(3, result.failures.size)
        assertTrue(result.failures.all { it.step is MacroStep.AssertExists || it.step is MacroStep.AssertRoute })
        assertFalse(result.failures.any { it.step is MacroStep.Tap })
    }

    @Test
    fun failuresEmptyWhenAllPass() {
        val state = QaLensUiState(nodes = listOf(node("1", qaName = "Submit")))
        val result = MacroEngine.execute(listOf(MacroStep.AssertExists("Submit")), state)
        assertTrue(result.allPassed)
        assertTrue(result.failures.isEmpty())
    }

    @Test
    fun assertNetworkFailureNotInFailures() {
        val state = QaLensUiState(networkEvents = emptyList())
        val result = MacroEngine.execute(listOf(MacroStep.AssertNetwork("api.x.com", 200)), state)
        assertFalse(result.allPassed)
        assertTrue(result.failures.isEmpty())
    }

    // ── toTimelineEvents ─────────────────────────────────────────────────────

    @Test
    fun toTimelineEventsPerFailure() {
        val state = QaLensUiState(nodes = emptyList(), screen = ScreenSnapshot(route = "/login"))
        val result = MacroEngine.execute(
            listOf(
                MacroStep.AssertExists("Missing"),
                MacroStep.AssertRoute("/home")
            ),
            state
        )
        val events = result.toTimelineEvents(1234L)
        assertEquals(2, events.size)
        assertTrue(events.all { it.kind == TimelineKind.ASSERTION && it.isError })
        assertTrue(events.all { it.timestampMillis == 1234L })
        assertTrue(events[0].title.startsWith("Assertion failed: "))
        assertTrue(events[0].title.contains("Missing"))
        assertTrue(events[1].title.contains("/home"))
        assertTrue(events.all { it.detail != null })
    }

    @Test
    fun toTimelineEventsSinglePassingEvent() {
        val state = QaLensUiState(nodes = listOf(node("1", qaName = "Submit")))
        val result = MacroEngine.execute(listOf(MacroStep.AssertExists("Submit")), state)
        val events = result.toTimelineEvents(1234L)
        assertEquals(1, events.size)
        val e = events[0]
        assertEquals(TimelineKind.ASSERTION, e.kind)
        assertFalse(e.isError)
        assertEquals("All macro assertions passed", e.title)
    }

    @Test
    fun toTimelineEventsEmptyWhenNoSteps() {
        val result = MacroResult(allPassed = true, steps = emptyList(), errorCount = 0)
        assertTrue(result.toTimelineEvents(0L).isEmpty())
    }

    // ── TimelineMerger ASSERTION mapping ─────────────────────────────────────

    @Test
    fun timelineMergerMapsAssertionEvents() {
        val events = listOf(
            QaEvent(timestampMillis = 10, type = QaEventType.EVENT, message = "assertion ✕ login button missing", tag = "qalens.assertion"),
            QaEvent(timestampMillis = 20, type = QaEventType.EVENT, message = "assertion ✓ route home"),
            QaEvent(timestampMillis = 30, type = QaEventType.LOG, message = "ASSERTION ✕ slow network"),
            QaEvent(timestampMillis = 40, type = QaEventType.EVENT, message = "some unrelated event", tag = "qalens.assertion")
        )
        val tl = TimelineMerger.merge(events, emptyList(), QaLensConfig())
        assertEquals(4, tl.size)
        assertEquals(TimelineKind.ASSERTION, tl[0].kind)
        assertTrue(tl[0].isError)
        assertEquals(TimelineKind.ASSERTION, tl[1].kind)
        assertFalse(tl[1].isError)
        assertEquals(TimelineKind.ASSERTION, tl[2].kind)
        assertTrue(tl[2].isError)
        assertEquals(TimelineKind.ASSERTION, tl[3].kind)
        assertFalse(tl[3].isError)
    }

    // ── QaLensAnalysis.digest ────────────────────────────────────────────────

    private fun coverage() = QaLensAnalysis.Coverage(
        hasFrames = false,
        hasVideo = false,
        networkInterceptorInstalled = true,
        networkCount = 0,
        logCount = 0,
        stateCount = 0
    )

    @Test
    fun digestAddsAssertionFailureAnomalyAndStat() {
        val json = QaLensAnalysis.digest(
            coverage = coverage(),
            startMillis = 0L,
            endMillis = 1000L,
            network = emptyList(),
            events = emptyList(),
            timeline = emptyList(),
            stateSamples = emptyList(),
            classification = null,
            config = QaLensConfig(),
            assertionFailures = 2
        )
        assertTrue(json.contains("assertion_failed"))
        assertTrue(json.contains("2 macro assertion(s) failed"))
        assertTrue(json.contains("assertionFailures"))
    }

    @Test
    fun digestOmitsAssertionDataWhenZero() {
        val json = QaLensAnalysis.digest(
            coverage = coverage(),
            startMillis = 0L,
            endMillis = 1000L,
            network = emptyList(),
            events = emptyList(),
            timeline = emptyList(),
            stateSamples = emptyList(),
            classification = null,
            config = QaLensConfig()
        )
        assertFalse(json.contains("assertion_failed"))
        assertFalse(json.contains("assertionFailures"))
    }
}
