package com.qalens.replay

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class LocalInsightsTest {
    @Test fun localEndpointsPinLoopbackAndRejectExternalOrAmbiguousAddresses() {
        assertEquals("http://127.0.0.1:11434/v1/models", LocalModelEndpoint.parse("http://localhost:11434/api/").openAi("models"))
        for (address in listOf("10.0.2.2", "172.16.0.1", "172.31.255.1", "192.168.4.8", "127.0.0.1"))
            assertTrue(LocalModelEndpoint.parse("http://$address:1234/v1").root.endsWith(":1234"))
        assertEquals("https://[::1]:1234", LocalModelEndpoint.parse("https://[::1]:1234").root)
        assertEquals("http://[fd12::42]:11434", LocalModelEndpoint.parse("http://[fd12::42]:11434").root)
        for (url in listOf("http://example.com", "http://localhost.evil", "http://172.32.0.1", "http://8.8.8.8", "http://169.254.169.254",
            "http://2130706433", "http://127.1", "http://0177.0.0.1", "http://127.0.0.1@8.8.8.8", "http://user:pass@localhost",
            "http://localhost?key=secret", "http://localhost#x", "file:///tmp/model", "http://localhost/private/path", "http://[fe80::1]",
            "http://[::ffff:127.0.0.1]", "http://localhost:0", "http://localhost:65536")) {
            try { LocalModelEndpoint.parse(url); fail("Accepted $url") } catch (_: IllegalArgumentException) { }
        }
        assertFalse(LocalModelEndpoint.isChatModel("text-embedding-nomic-embed-text-v1.5"))
        assertFalse(LocalModelEndpoint.isChatModel("bge-large-en"))
        assertTrue(LocalModelEndpoint.isChatModel("qalens-synthetic-triage"))
    }

    @Test fun fullTrackPositionsSurviveFilteringAndContextRetainsObservedState() {
        val root = Files.createTempDirectory("qalens-insights-test").toFile()
        try {
            root.resolve("logs.json").writeText(JSONArray().put(JSONObject().put("ts", 1999).put("message", "old"))
                .put(JSONObject().put("ts", 1060).put("message", "PlaybackException synthetic"))
                .put(JSONObject().put("ts", "not-a-time").put("message", "invalid")).toString())
            root.resolve("state.json").writeText(JSONArray().put(JSONObject().put("ts", 1049).put("screen", "Player")
                .put("dataSources", JSONObject().put("player", JSONObject().put("playing", "true").put("apiToken", "SECRET"))))
                .put(JSONObject().put("ts", 1061).put("screen", "Error")).toString())
            root.resolve("network.json").writeText(JSONArray().put(JSONObject().put("ts", 1059).put("method", "GET")
                .put("url", "https://user:password-secret@synthetic.test/media?token=secret-query").put("status", 503)
                .put("responseBodyPreview", "redacted synthetic preview")).toString())
            root.resolve("analysis.json").writeText(JSONObject().put("coverage", JSONObject().put("networkCaptureEnabled", false))
                .put("anomalies", JSONArray().put(JSONObject().put("tMs", 60).put("title", "Synthetic anomaly"))).toString())
            val session = session(root)
            val evidence = InsightsEvidenceBuilder.build(session, 60, "What failed?", 5_000)
            val doc = JSONObject(evidence.json)
            assertEquals("qalens-insights-evidence/1", doc.getString("schema"))
            assertEquals(60, doc.getJSONObject("recording").getLong("focusMs"))
            assertEquals(60L, evidence.itemTimes["logs:1"])
            assertEquals(49L, evidence.itemTimes["state:0"])
            assertTrue(evidence.itemTimes.containsKey("anomalies:0"))
            assertFalse(evidence.itemTimes.containsKey("logs:2"))
            assertFalse(evidence.json.contains("SECRET"))
            assertFalse(evidence.json.contains("password-secret"))
            assertFalse(evidence.json.contains("secret-query"))
            assertTrue(evidence.json.contains("redacted synthetic preview"))
            assertEquals(1, doc.getJSONObject("omissions").getInt("invalidTimestamp"))
            assertFalse(doc.getJSONObject("coverage").getJSONObject("media").getBoolean("pixelsSent"))
        } finally { root.deleteRecursively() }
    }

    @Test fun endlessLogsAreBoundedWithoutDiscardingTheFailureOrNearestValues() {
        val root = Files.createTempDirectory("qalens-insights-budget").toFile()
        try {
            val logs = JSONArray()
            repeat(10_000) { index -> logs.put(JSONObject().put("ts", 1000L + index).put("message", "x".repeat(800))) }
            // BoundedArchive text limit also applies before evidence selection.
            root.resolve("logs.json").writeText(logs.toString())
            root.resolve("network.json").writeText(JSONArray().put(JSONObject().put("ts", 6000).put("method", "GET")
                .put("url", "https://synthetic.test/video").put("status", 500).put("error", "Synthetic timeout")).toString())
            root.resolve("state.json").writeText(JSONArray().put(JSONObject().put("ts", 5999).put("screen", "Player"))
                .put(JSONObject().put("ts", 6001).put("screen", "Retry")).toString())
            val evidence = InsightsEvidenceBuilder.build(session(root, 12_000), 5_000, "test")
            assertTrue(evidence.json.length <= 48_000)
            assertTrue(evidence.itemTimes.size <= 300)
            assertTrue(evidence.itemTimes.containsKey("network:0"))
            assertTrue(evidence.itemTimes.containsKey("state:0"))
            assertTrue(evidence.itemTimes.containsKey("state:1"))
            assertTrue(evidence.omitted > 9000)
            assertTrue(JSONObject(evidence.json).getJSONObject("omissions").getInt("contextLimit") > 0)
        } finally { root.deleteRecursively() }
    }

    @Test fun errorLogFloodReservesIndependentFailuresValuesAndActionsWithinBothBudgets() {
        val root = Files.createTempDirectory("qalens-insights-error-flood").toFile()
        try {
            val logs = JSONArray()
            repeat(2000) { index -> logs.put(JSONObject().put("ts", 53_000L + index % 20)
                .put("message", "ERROR retry failed " + "x".repeat(500))) }
            root.resolve("logs.json").writeText(logs.toString())
            root.resolve("network.json").writeText(JSONArray()
                .put(JSONObject().put("ts", 101_000).put("status", 503).put("url", "https://synthetic.test/another-episode"))
                .put(JSONObject().put("ts", 51_000).put("method", "GET").put("status", 503).put("url", "https://synthetic.test/video")).toString())
            root.resolve("crashes.json").writeText(JSONArray()
                .put(JSONObject().put("ts", 101_000).put("throwable", "UnrelatedException"))
                .put(JSONObject().put("ts", 50_000).put("throwable", "SyntheticPlaybackException")).toString())
            root.resolve("state.json").writeText(JSONArray()
                .put(JSONObject().put("ts", 1000).put("screen", "Launch"))
                .put(JSONObject().put("ts", 41_000).put("screen", "Player").put("dataSources", JSONObject().put("player", JSONObject().put("buffering", true))))
                .put(JSONObject().put("ts", 55_000).put("screen", "Retry")).toString())
            root.resolve("connectivity.json").writeText(JSONArray().put(JSONObject().put("ts", 1000).put("type", "WIFI")).toString())
            root.resolve("timeline.json").writeText(JSONArray().put(JSONObject().put("ts", 44_000).put("title", "Play pressed").put("kind", "ACTION")).toString())
            root.resolve("performance.json").writeText(JSONArray().put(JSONObject().put("ts", 45_000).put("totalMs", 12.0)).toString())
            root.resolve("memory.json").writeText(JSONArray().put(JSONObject().put("ts", 46_000).put("freeKb", 20_000)).toString())
            val evidence = InsightsEvidenceBuilder.build(session(root, 120_000), 52_000, "Player buffering bug", 10_000)
            val reserved = setOf("network:1", "crashes:1", "state:1", "state:2", "connectivity:0", "timeline:0", "performance:0", "memory:0", "logs:0")
            assertTrue("Error logs displaced an independent source: ${evidence.itemTimes.keys}", evidence.itemTimes.keys.containsAll(reserved))
            assertFalse(evidence.itemTimes.containsKey("network:0"))
            assertFalse(evidence.itemTimes.containsKey("crashes:0"))
            assertFalse(evidence.itemTimes.containsKey("state:0"))
            assertTrue(evidence.json.length <= InsightsEvidenceBuilder.TEXT_LIMIT)
            assertTrue(evidence.itemTimes.size <= InsightsEvidenceBuilder.ITEM_LIMIT)
            assertTrue(JSONObject(evidence.json).getJSONObject("omissions").getJSONObject("byKind").getJSONObject("logs").getInt("omitted") >= 1700)
            assertTrue(evidence.json.contains("Latest preceding captured state sample"))
            val image = InsightsEvidenceBuilder.withImage(evidence, RecordingInsightImage(52_000, byteArrayOf(1, 2, 3), "recording-frame", false))
            assertTrue("Image metadata displaced reserved text evidence", image.itemTimes.keys.containsAll(reserved))
            assertTrue(image.json.length <= InsightsEvidenceBuilder.TEXT_LIMIT)
        } finally { root.deleteRecursively() }
    }

    @Test fun wideNestedValuesHavePerItemNodeAndDetailLimitsWithHonestOmissions() {
        val root = Files.createTempDirectory("qalens-insights-wide-values").toFile()
        try {
            val values = JSONObject()
            repeat(40) { group ->
                values.put("provider$group", JSONObject().apply { repeat(30) { put("value$it", "synthetic".repeat(100)) } })
            }
            root.resolve("state.json").writeText(JSONArray().put(JSONObject().put("ts", 53_000).put("screen", "Player")
                .put("dataSources", values)).toString())
            root.resolve("network.json").writeText(JSONArray().put(JSONObject().put("ts", 53_100).put("status", 503)
                .put("method", "GET").put("url", "https://synthetic.test/video?token=hidden")
                .put("responseBodyPreview", "Synthetic unavailable").put("largeCapturedHeaders", values))
                .put(JSONObject().put("ts", 53_200).put("status", 200).put("error", JSONObject.NULL).put("method", "GET")
                    .put("url", "https://synthetic.test/healthy").put("aaa", JSONArray().apply {
                        repeat(16) { put(JSONArray().apply { repeat(16) { put(JSONArray().apply { repeat(16) { put(0) } }) } }) }
                    })).toString())
            val evidence = InsightsEvidenceBuilder.build(session(root, 120_000), 52_000, "Wide captured values")
            val doc = JSONObject(evidence.json)
            val items = doc.getJSONArray("items")
            for (index in 0 until items.length()) assertTrue(items.getJSONObject(index).getJSONObject("details").toString().length <= 1800)
            val state = (0 until items.length()).map(items::getJSONObject).single { it.getString("kind") == "state" }.getJSONObject("details")
            val network = (0 until items.length()).map(items::getJSONObject).single { it.getString("id") == "network:0" }.getJSONObject("details")
            val healthy = (0 until items.length()).map(items::getJSONObject).single { it.getString("id") == "network:1" }.getJSONObject("details")
            assertTrue(state.getBoolean("truncated"))
            assertEquals("Player", state.getString("screen"))
            assertEquals(503, network.getInt("status"))
            assertEquals("GET", network.getString("method"))
            assertEquals(200, healthy.getInt("status"))
            assertTrue(healthy.has("error") && healthy.isNull("error"))
            assertEquals("GET", healthy.getString("method"))
            assertEquals("https://synthetic.test/healthy", healthy.getString("url"))
            assertFalse(evidence.json.contains("token=hidden"))
            assertTrue(doc.getJSONObject("omissions").getInt("detailsTruncated") > 0)
            assertTrue(evidence.json.length <= InsightsEvidenceBuilder.TEXT_LIMIT)
        } finally { root.deleteRecursively() }
    }

    @Test fun unknownCitationsCannotBecomeCapturedFactsAndUnsupportedCausesAreLowConfidence() {
        val response = JSONObject().put("schema", "qalens-insights-report/1").put("summary", "Model assessment")
            .put("observations", JSONArray().put(JSONObject().put("text", "Invented observation").put("evidenceIds", JSONArray().put("network:99")))
                .put(JSONObject().put("text", "Request failed").put("evidenceIds", JSONArray().put("network:0"))))
            .put("hypotheses", JSONArray().put(JSONObject().put("title", "Decoder problem").put("confidence", "high")
                .put("reasoning", "Not recorded").put("evidenceIds", JSONArray().put("logs:99")).put("nextChecks", JSONArray())))
            .put("missingEvidence", JSONArray()).put("recommendedChecks", JSONArray())
        val report = InsightsReportParser.parse("```json\n$response\n```", setOf("network:0"))
        assertEquals(listOf("Request failed"), report.observations.map { it.text })
        assertEquals("low", report.hypotheses.single().confidence)
        assertTrue(report.hypotheses.single().reasoning.startsWith("Unverified hypothesis."))
        assertTrue(report.groundingWarnings.isNotEmpty())
        assertFalse(report.json.contains("network:99"))
        assertTrue(report.missingEvidence.any { it.contains("Video pixels") })
        try { InsightsReportParser.parse("{\"schema\":\"wrong\"}", emptySet()); fail("Accepted wrong schema") }
        catch (_: IllegalArgumentException) { }
        try { InsightsReportParser.parse("{\"schema\":\"qalens-insights-report/1\",\"summary\":\"Missing sections\"}", emptySet()); fail("Accepted missing report sections") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun selectedWindowExcludesOtherIncidentsButRetainsLabeledPrecedingStateAndConnectivity() {
        val root = Files.createTempDirectory("qalens-insights-window").toFile()
        try {
            root.resolve("logs.json").writeText(JSONArray().put(JSONObject().put("ts", 101_000).put("message", "Error in a different episode"))
                .put(JSONObject().put("ts", 53_000).put("message", "Player buffering error at focus")).toString())
            root.resolve("state.json").writeText(JSONArray().put(JSONObject().put("ts", 41_000).put("screen", "Player"))
                .put(JSONObject().put("ts", 110_000).put("screen", "Different flow later")).toString())
            root.resolve("connectivity.json").writeText(JSONArray().put(JSONObject().put("ts", 1000).put("type", "WIFI")).toString())
            val evidence = InsightsEvidenceBuilder.build(session(root, 120_000), 52_000, "Selected bug", 10_000)
            assertFalse(evidence.itemTimes.containsKey("logs:0"))
            assertFalse(evidence.itemTimes.containsKey("state:1"))
            assertTrue(evidence.itemTimes.containsKey("logs:1"))
            assertEquals(40_000L, evidence.itemTimes["state:0"])
            assertEquals(0L, evidence.itemTimes["connectivity:0"])
            assertTrue(evidence.json.contains("Latest preceding captured state sample outside the selected window"))
            assertTrue(evidence.json.contains("Latest preceding captured connectivity sample outside the selected window"))
        } finally { root.deleteRecursively() }
    }

    @Test fun savedVideoImageMappingHonorsConsentPrerollAndLegacyEndAlignment() {
        assertEquals(2500L, RecordingInsightImagePolicy.videoPosition(10_000, 20_000, 12_000, 5000, 4500))
        assertEquals(500L, RecordingInsightImagePolicy.videoPosition(10_000, 20_000, null, 5000, 5500))
        for (focus in listOf(0L, 1000L, 7000L, 9500L)) {
            try { RecordingInsightImagePolicy.videoPosition(10_000, 20_000, 12_000, 5000, focus); fail("Accepted a moment without video coverage") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun optionalImageAndPlayerRuntimePreserveSeparateProvenanceAndTextBudget() {
        val root = Files.createTempDirectory("qalens-insights-runtime").toFile()
        try {
            root.resolve("logs.json").writeText(JSONArray().put(JSONObject().put("ts", 1100).put("message", "Captured host log")).toString())
            val runtime = InsightsRuntime(1_800_000_000_000, 100, mapOf("lastPlayerError" to "Synthetic decoder failure", "playing" to false))
            val text = InsightsEvidenceBuilder.build(session(root), 100, "Is the QaLens player broken?", target = "qalens-player", runtime = runtime)
            val investigation = JSONObject(text.json).getJSONObject("investigation")
            assertEquals("qalens-player", investigation.getString("target"))
            assertEquals("current-player", investigation.getJSONObject("runtime").getString("source"))
            assertEquals(1_800_000_000_000, investigation.getJSONObject("runtime").getLong("observedAtMillis"))
            assertTrue(text.itemTimes.containsKey("player:runtime"))
            assertFalse(JSONObject(text.json).getJSONArray("items").toString().contains("player:runtime"))
            val image = RecordingInsightImage(75, byteArrayOf(1, 2, 3), "recording-frame", false)
            val attached = InsightsEvidenceBuilder.withImage(text, image)
            assertEquals(75L, attached.itemTimes["images:0"])
            assertTrue(JSONObject(attached.json).getJSONObject("coverage").getJSONObject("media").getBoolean("pixelsSent"))
            assertTrue(JSONObject(attached.json).getJSONObject("coverage").getJSONObject("media").isNull("pixelsAnalyzed"))
            assertFalse(JSONObject(attached.json).getJSONArray("images").getJSONObject(0).has("data"))
            assertTrue(attached.json.length <= 48_000)
            try { InsightsEvidenceBuilder.withImage(text, image.copy(tMs = -1)); fail("Accepted an out-of-recording image citation") }
            catch (_: IllegalArgumentException) { }
            try { InsightsEvidenceBuilder.withImage(text, image.copy(bytes = ByteArray(128 * 1024 + 1))); fail("Accepted image over 128 KiB") }
            catch (_: IllegalArgumentException) { }
            val recorded = InsightsEvidenceBuilder.build(session(root), 100, "Host app", runtime = runtime)
            assertFalse(recorded.itemTimes.containsKey("player:runtime"))
        } finally { root.deleteRecursively() }
    }

    @Test fun providerKeysAreMaskedOnlyInNormalizedHumanTextWithoutBreakingProtocol() {
        val report = JSONObject().put("schema", "qalens-insights-report/1").put("summary", "Provider echo secret\"quoted")
            .put("observations", JSONArray().put(JSONObject().put("text", "Echo secret\"quoted").put("evidenceIds", JSONArray().put("network:0"))))
            .put("hypotheses", JSONArray()).put("missingEvidence", JSONArray()).put("recommendedChecks", JSONArray())
        val normalized = InsightsReportParser.parse(report.toString(), setOf("network:0"), apiKey = "secret\"quoted")
        assertFalse(normalized.json.contains("secret"))
        assertEquals("network:0", normalized.observations.single().evidenceIds.single())
        assertEquals("qalens-insights-report/1", JSONObject(normalized.json).getString("schema"))
        val shortKey = InsightsReportParser.parse(report.toString(), setOf("network:0"), apiKey = "a")
        assertEquals("qalens-insights-report/1", JSONObject(shortKey.json).getString("schema"))
    }

    @Test fun malformedInnerReportFieldsAndExpandedReportsAreRejectedBeforeSuccess() {
        fun canonical() = JSONObject().put("schema", "qalens-insights-report/1").put("summary", "Synthetic report")
            .put("observations", JSONArray().put(JSONObject().put("text", "Captured failure").put("evidenceIds", JSONArray().put("network:0"))))
            .put("hypotheses", JSONArray().put(JSONObject().put("title", "Possible service failure").put("confidence", "medium")
                .put("reasoning", "Captured HTTP status").put("evidenceIds", JSONArray().put("network:0")).put("nextChecks", JSONArray().put("Check server"))))
            .put("missingEvidence", JSONArray()).put("recommendedChecks", JSONArray())
        val invalid = listOf(
            canonical().apply { getJSONArray("observations").put(0, "not an observation object") },
            canonical().apply { getJSONArray("observations").getJSONObject(0).put("text", 503) },
            canonical().apply { getJSONArray("observations").getJSONObject(0).put("evidenceIds", JSONArray().put(1)) },
            canonical().apply { getJSONArray("hypotheses").getJSONObject(0).remove("nextChecks") },
            canonical().apply { getJSONArray("hypotheses").getJSONObject(0).put("confidence", "certain") },
            canonical().apply { getJSONArray("hypotheses").getJSONObject(0).put("reasoning", JSONObject().put("unsupported", true)) },
            canonical().apply { getJSONArray("missingEvidence").put(JSONObject().put("not", "text")) },
            canonical().put("summary", "x".repeat(4001)),
            canonical().apply { getJSONArray("hypotheses").getJSONObject(0).put("nextChecks", JSONArray().apply { repeat(11) { put("Too many") } }) }
        )
        for (report in invalid) {
            try { InsightsReportParser.parse(report.toString(), setOf("network:0")); fail("Accepted malformed inner report") }
            catch (_: IllegalArgumentException) { }
        }
        try { InsightsReportParser.parse("x".repeat(48_001), emptySet()); fail("Accepted an oversized report") }
        catch (_: IllegalArgumentException) { }
        val expanded = canonical().put("summary", "a".repeat(1000))
            .put("observations", JSONArray().apply { repeat(20) { put(JSONObject().put("text", "a".repeat(600)).put("evidenceIds", JSONArray().put("network:0"))) } })
            .put("hypotheses", JSONArray().apply { repeat(10) { put(JSONObject().put("title", "a".repeat(100)).put("confidence", "medium")
                .put("reasoning", "a".repeat(200)).put("evidenceIds", JSONArray().put("network:0"))
                .put("nextChecks", JSONArray().apply { repeat(5) { put("a".repeat(30)) } })) } })
            .put("missingEvidence", JSONArray().apply { repeat(20) { put("a".repeat(40)) } })
            .put("recommendedChecks", JSONArray().apply { repeat(20) { put("a".repeat(40)) } })
        assertTrue(expanded.toString().length < 48_000)
        try { InsightsReportParser.parse(expanded.toString(), setOf("network:0"), apiKey = "a"); fail("Accepted an oversized normalized report") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun qaFormatUsesTesterContextAndOnlySelectedCapturedActionsRatherThanModelSteps() {
        val root = Files.createTempDirectory("qalens-qa-format").toFile()
        try {
            root.resolve("timeline.json").writeText(JSONArray()
                .put(JSONObject().put("ts", 1090).put("kind", "ERROR").put("title", "Failed stream"))
                .put(JSONObject().put("ts", 1080).put("kind", "ACTION").put("title", "Press **Play** [unsafe](https://evil.test)"))
                .put(JSONObject().put("ts", 1070).put("kind", "NAVIGATION").put("title", "Open player"))
                .put(JSONObject().put("ts", 1075).put("kind", "SCREEN").put("title", "Player visible")).toString())
            root.resolve("network.json").writeText(JSONArray().put(JSONObject().put("ts", 1090).put("status", 503)).toString())
            val evidence = InsightsEvidenceBuilder.build(session(root), 100, "What failed?",
                expectedResult = "Video starts after Play", actualResult = "Video stays buffering")
            val raw = canonicalQaModel().put("qaReport", JSONObject().put("title", "Invented bug")
                .put("steps", JSONArray().put("Click an uncaptured reset button")))
            val report = InsightsQaReportBuilder.attach(InsightsReportParser.parse(raw.toString(), evidence.itemTimes.keys), evidence)
            val qa = checkNotNull(report.qaReport)
            assertEquals("Synthetic playback assessment", qa.title)
            assertEquals(listOf("timeline:2", "timeline:3", "timeline:1"), qa.steps.map { it.evidenceIds.single() })
            assertEquals("tester", qa.expectedSource)
            assertEquals("Video starts after Play", qa.expectedResult)
            assertEquals("tester", qa.actualSource)
            assertEquals("Video stays buffering", qa.actualResult)
            assertFalse(report.json.contains("uncaptured reset"))
            assertTrue(report.qaMarkdown.contains("Source: Tester reported"))
            assertTrue(report.qaMarkdown.contains("\\*\\*Play\\*\\*"))
            assertTrue(report.qaMarkdown.contains("\\[unsafe\\]"))
            assertEquals(qa.title, JSONObject(report.json).getJSONObject("qaReport").getString("title"))
            val transfer = JSONObject(PcInvestigationSender.document(evidence, report))
            assertEquals(JSONObject(evidence.json).toString(), transfer.getJSONObject("bundle").toString())
            assertEquals("Video stays buffering", transfer.getJSONObject("report").getJSONObject("qaReport").getString("actualResult"))
            assertFalse(JSONObject(PcInvestigationSender.document(evidence, null)).has("report"))
        } finally { root.deleteRecursively() }
    }

    @Test fun qaMissingContextAndPlayerTargetNeverInventExpectedBehaviorOrReplayClicks() {
        val root = Files.createTempDirectory("qalens-qa-missing").toFile()
        try {
            root.resolve("timeline.json").writeText(JSONArray().put(JSONObject().put("ts", 1080).put("kind", "ACTION").put("title", "Host Play pressed")).toString())
            root.resolve("network.json").writeText(JSONArray().put(JSONObject().put("ts", 1090).put("status", 503)).toString())
            val evidence = InsightsEvidenceBuilder.build(session(root), 100, "", expectedResult = "  ")
            assertFalse(JSONObject(evidence.json).has("qaContext"))
            val withObservation = InsightsQaReportBuilder.attach(InsightsReportParser.parse(canonicalQaModel().toString(), evidence.itemTimes.keys), evidence)
            assertEquals(InsightsQaReportBuilder.EXPECTED_MISSING, withObservation.qaReport?.expectedResult)
            assertEquals("not-provided", withObservation.qaReport?.expectedSource)
            assertEquals("captured-evidence", withObservation.qaReport?.actualSource)
            assertEquals("HTTP 503 was observed", withObservation.qaReport?.actualResult)
            assertTrue(withObservation.qaMarkdown.contains("Model interpretation of cited evidence"))
            val noObservations = canonicalQaModel().put("observations", JSONArray())
            val missing = InsightsQaReportBuilder.attach(InsightsReportParser.parse(noObservations.toString(), evidence.itemTimes.keys), evidence)
            assertEquals("not-established", missing.qaReport?.actualSource)
            assertEquals(InsightsQaReportBuilder.ACTUAL_MISSING, missing.qaReport?.actualResult)
            val player = InsightsEvidenceBuilder.build(session(root), 100, "", target = "qalens-player")
            val replay = InsightsQaReportBuilder.attach(InsightsReportParser.parse(canonicalQaModel().toString(), player.itemTimes.keys), player)
            assertTrue(checkNotNull(replay.qaReport).steps.isEmpty())
            assertTrue(replay.qaMarkdown.contains(InsightsQaReportBuilder.PLAYER_STEPS_MISSING))
            val noActions = evidence.copy(json = JSONObject(evidence.json).put("items", JSONArray()).toString(), itemTimes = emptyMap())
            val absent = InsightsQaReportBuilder.attach(InsightsReportParser.parse(noObservations.toString(), emptySet()), noActions)
            assertTrue(checkNotNull(absent.qaReport).steps.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun qaContextStepsAndExportKeepBoundsAndSecretsDoNotAlterEvidenceIds() {
        val root = Files.createTempDirectory("qalens-qa-bounds").toFile()
        try {
            root.resolve("timeline.json").writeText(JSONArray().apply {
                repeat(20) { index -> put(JSONObject().put("ts", 1100L + (19 - index)).put("kind", "ACTION").put("title", "Action $index")) }
            }.toString())
            root.resolve("network.json").writeText(JSONArray().put(JSONObject().put("ts", 1090).put("status", 503)).toString())
            val evidence = InsightsEvidenceBuilder.build(session(root), 100, "",
                expectedResult = "e".repeat(2000), actualResult = "key\"quoted " + "a".repeat(2000))
            val context = JSONObject(evidence.json).getJSONObject("qaContext")
            assertEquals(1600, context.getString("expectedResult").length)
            assertEquals(1600, context.getString("actualResult").length)
            assertTrue(evidence.json.length <= 48_000)
            val report = InsightsQaReportBuilder.attach(InsightsReportParser.parse(canonicalQaModel().toString(), evidence.itemTimes.keys), evidence, "key\"quoted")
            val qa = checkNotNull(report.qaReport)
            assertEquals(12, qa.steps.size)
            assertEquals("timeline:19", qa.steps.first().evidenceIds.single())
            assertFalse(report.json.contains("key\\\"quoted"))
            assertTrue(qa.actualResult.startsWith("[REDACTED]"))
            assertTrue(report.json.length <= 48_000)
            // The transport budget is UTF-8 bytes, not the number of Kotlin characters.
            val expanded = evidence.copy(json = JSONObject(evidence.json).put("question", "界".repeat(46_000)).toString())
            val hugeReport = report.copy(json = JSONObject(report.json).put("summary", "界".repeat(46_000)).toString())
            try { PcInvestigationSender.document(expanded, hugeReport); fail("Accepted a UTF-8 handoff over 256 KiB") }
            catch (_: IllegalArgumentException) { }
        } finally { root.deleteRecursively() }
    }

    private fun canonicalQaModel(): JSONObject = JSONObject().put("schema", "qalens-insights-report/1").put("summary", "Synthetic playback assessment")
        .put("observations", JSONArray().put(JSONObject().put("text", "HTTP 503 was observed").put("evidenceIds", JSONArray().put("network:0"))))
        .put("hypotheses", JSONArray()).put("missingEvidence", JSONArray()).put("recommendedChecks", JSONArray())

    private fun session(root: java.io.File, duration: Long = 1000): PlayerSession = PlayerSession(root, "Synthetic player", 1000, 1000 + duration,
        2, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null, "", null)
}
