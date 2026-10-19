package com.qalens

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QaLensInvestigationTransferTest {
    @Test fun approvedQueueIsBoundedNonConsumingAcknowledgedAndAccessExpires() {
        var clock = 100L
        val queue = InvestigationTransferQueue { clock }
        val ids = (0 until 5).map { queue.enqueue(document("Symptom $it").toString()) }
        val inbox = JSONObject(queue.inbox())
        assertEquals(4, inbox.getJSONArray("transfers").length())
        assertEquals(1, inbox.getInt("dropped"))
        assertEquals(ids[1], inbox.getJSONArray("transfers").getJSONObject(0).getString("id"))
        assertEquals(4, JSONObject(queue.inbox()).getJSONArray("transfers").length())
        assertEquals("Symptom 4", inbox.getJSONArray("transfers").getJSONObject(3).getJSONObject("document")
            .getJSONObject("bundle").getJSONObject("qaContext").getString("actualResult"))
        queue.acknowledge(listOf(ids[1], "unknown")); queue.acknowledge(listOf(ids[1]))
        assertEquals(3, JSONObject(queue.inbox()).getJSONArray("transfers").length())
        clock += 5 * 60_000
        assertEquals(0, JSONObject(queue.inbox()).getJSONArray("transfers").length())
        assertEquals(4, JSONObject(queue.inbox()).getInt("dropped"))
        val oldId = queue.enqueue(document().toString())
        queue.clear()
        val freshId = queue.enqueue(document().toString())
        assertNotEquals(oldId.substringBefore(':'), freshId.substringBefore(':'))
        assertEquals(0, JSONObject(queue.inbox()).getInt("dropped"))
    }

    @Test fun canonicalHandoffPreservesContextIdsAndRejectsAmbiguousPrivateOrMalformedPayloads() {
        val valid = document()
        valid.put("report", JSONObject().put("schema", "qalens-insights-report/1").put("summary", "Synthetic observed buffering")
            .put("observations", JSONArray().put(JSONObject().put("text", "Play was pressed").put("evidenceIds", JSONArray().put("timeline:7"))))
            .put("hypotheses", JSONArray()).put("missingEvidence", JSONArray()).put("recommendedChecks", JSONArray()))
        assertEquals(valid.toString(), QaLensInvestigationDocuments.validate(valid.toString()).toString())
        val invalid = listOf(
            JSONObject(valid.toString()).put("apiKey", "must not transfer"),
            JSONObject(valid.toString()).apply { getJSONObject("bundle").put("settings", JSONObject().put("key", "private")) },
            JSONObject(valid.toString()).apply { getJSONObject("bundle").getJSONObject("qaContext").put("expectedResult", 1) },
            JSONObject(valid.toString()).apply { getJSONObject("bundle").getJSONObject("qaContext").put("other", "ambiguous") },
            JSONObject(valid.toString()).apply { getJSONObject("bundle").getJSONObject("qaContext").put("actualResult", "x".repeat(1601)) },
            JSONObject(valid.toString()).apply { getJSONObject("bundle").getJSONArray("items").getJSONObject(0).put("tMs", -1) },
            JSONObject(valid.toString()).apply { getJSONObject("bundle").getJSONArray("items").getJSONObject(0).put("kind", "network") },
            JSONObject(valid.toString()).apply { getJSONObject("bundle").getJSONArray("items").put(getJSONObject("bundle").getJSONArray("items").getJSONObject(0)) },
            JSONObject(valid.toString()).apply { getJSONObject("bundle").getJSONObject("recording").put("focusMs", 200) },
            JSONObject(valid.toString()).apply { getJSONObject("report").remove("hypotheses") },
            JSONObject(valid.toString()).apply { getJSONObject("report").getJSONArray("observations").getJSONObject(0).put("evidenceIds", JSONArray().put("timeline:999")) },
            JSONObject(valid.toString()).put("schema", "unexpected")
        )
        for (value in invalid) {
            try { QaLensInvestigationDocuments.validate(value.toString()); fail("Accepted malformed handoff") }
            catch (_: Exception) { }
        }
        try { QaLensInvestigationDocuments.validate("界".repeat(100_000)); fail("Accepted excessive UTF-8 bytes") }
        catch (_: IllegalArgumentException) { }
        try { QaLensInvestigationDocuments.validate("[".repeat(17) + "]".repeat(17)); fail("Accepted excessive parser recursion") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun reviewedImageByteMetadataIsAcceptedUnchangedAndMustMatchDecodedPayload() {
        val image = JSONObject().put("id", "images:0").put("tMs", 50).put("mediaType", "image/jpeg")
            .put("data", "AAEC").put("source", "recording-frame").put("approximate", false).put("bytes", 3)
        val before = image.toString()
        QaLensInvestigationDocuments.validateImageMetadata(image, 100.0, decodedSize = 3)
        assertEquals(before, image.toString())
        for (invalid in listOf(
            JSONObject(before).put("bytes", 2), JSONObject(before).put("bytes", "3"),
            JSONObject(before).put("unexpected", "extra"), JSONObject(before).put("tMs", 101),
            JSONObject(before).put("mediaType", "image/svg+xml"))) {
            try { QaLensInvestigationDocuments.validateImageMetadata(invalid, 100.0, 3); fail("Accepted invalid reviewed image metadata") }
            catch (_: IllegalArgumentException) { }
        }
    }

    private fun document(actual: String = "Buffering remains"): JSONObject = JSONObject().put("schema", "qalens-investigation-transfer/1")
        .put("bundle", JSONObject().put("schema", "qalens-insights-evidence/1")
            .put("recording", JSONObject().put("name", "Synthetic app").put("t0", 1000).put("durationMs", 100)
                .put("focusMs", 50).put("windowStartMs", 0).put("windowEndMs", 100))
            .put("question", "Why does Play fail?").put("coverage", JSONObject()).put("omissions", JSONObject())
            .put("qaContext", JSONObject().put("expectedResult", "Video starts").put("actualResult", actual))
            .put("items", JSONArray().put(JSONObject().put("id", "timeline:7").put("kind", "timeline").put("tMs", 49)
                .put("summary", "Play pressed").put("details", JSONObject().put("kind", "ACTION")))))
}
