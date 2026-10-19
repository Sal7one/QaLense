package com.qalens.replay

import org.json.JSONArray
import org.json.JSONObject

internal data class InsightObservation(val text: String, val evidenceIds: List<String>)
internal data class InsightHypothesis(val title: String, val confidence: String, val reasoning: String,
                                      val evidenceIds: List<String>, val nextChecks: List<String>)
internal data class InsightsReport(val summary: String, val observations: List<InsightObservation>,
                                   val hypotheses: List<InsightHypothesis>, val missingEvidence: List<String>,
                                   val recommendedChecks: List<String>, val groundingWarnings: List<String>,
                                   val json: String, val qaReport: InsightsQaReport? = null,
                                   val qaMarkdown: String = "")

/** Model text is data. It cannot invoke SDK methods, URLs, shell commands or actions. */
internal object InsightsReportParser {
    const val SCHEMA = "qalens-insights-report/1"
    fun parse(text: String, knownIds: Set<String>, imageProvided: Boolean = false, apiKey: String = ""): InsightsReport {
        require(text.length <= 48_000) { "The model report exceeds the 48,000-character limit." }
        val trimmed = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val raw = try { JSONObject(trimmed) } catch (_: Exception) {
            throw IllegalArgumentException("The model did not return a JSON report. Choose a chat/instruct model that follows JSON instructions.")
        }
        require(raw.optString("schema") == SCHEMA) { "The model returned an unsupported report schema. Try a chat/instruct model." }
        require(raw.opt("summary") is String && raw.optString("summary").isNotBlank() &&
            listOf("observations", "hypotheses", "missingEvidence", "recommendedChecks").all { raw.opt(it) is JSONArray }) {
            "The model omitted required report sections. Choose a chat/instruct model that follows the report schema."
        }
        fun validText(value: Any?, max: Int): Boolean = value is String && value.isNotBlank() && value.length <= max
        fun validList(value: Any?, count: Int, length: Int): Boolean = value is JSONArray && value.length() <= count &&
            (0 until value.length()).all { value.opt(it) is String && (value.opt(it) as String).length <= length }
        val sourceObservations = raw.getJSONArray("observations")
        val sourceHypotheses = raw.getJSONArray("hypotheses")
        require(validText(raw.opt("summary"), 4000) && sourceObservations.length() <= 30 && sourceHypotheses.length() <= 10 &&
            validList(raw.opt("missingEvidence"), 30, 1200) && validList(raw.opt("recommendedChecks"), 20, 1200) &&
            (0 until sourceObservations.length()).all { index ->
                sourceObservations.optJSONObject(index)?.let { item -> validText(item.opt("text"), 2400) &&
                    validList(item.opt("evidenceIds"), 20, 64) } == true
            } && (0 until sourceHypotheses.length()).all { index ->
                sourceHypotheses.optJSONObject(index)?.let { item -> validText(item.opt("title"), 400) &&
                    validText(item.opt("reasoning"), 2400) && item.opt("confidence") in setOf("low", "medium", "high") &&
                    validList(item.opt("evidenceIds"), 20, 64) && validList(item.opt("nextChecks"), 10, 1200) } == true
            }) { "The model report does not match qalens-insights-report/1. No results were accepted. Choose a chat/instruct model that follows the report schema." }
        val warnings = mutableListOf<String>()
        fun cleanText(value: String): String = if (apiKey.isEmpty()) value else value.replace(apiKey, "[REDACTED]")
        fun ids(value: JSONObject): List<String> {
            val supplied = strings(value.optJSONArray("evidenceIds"), 30, 80)
            val unknown = supplied.count { it !in knownIds }
            if (unknown > 0) warnings += "$unknown unknown evidence reference(s) were removed."
            return supplied.filter { it in knownIds }.distinct()
        }
        val observations = objects(sourceObservations, 20).mapNotNull { value ->
            val cited = ids(value)
            val body = cleanText(value.optString("text").trim()).take(1200)
            if (body.isBlank()) null
            else if (cited.isEmpty()) { warnings += "An observation with no captured evidence was omitted."; null }
            else InsightObservation(body, cited)
        }
        val hypotheses = objects(sourceHypotheses, 10).mapNotNull { value ->
            val title = cleanText(value.optString("title").trim()).take(200)
            if (title.isBlank()) return@mapNotNull null
            val cited = ids(value)
            val supplied = value.optString("confidence").lowercase()
            val confidence = if (cited.isEmpty() || supplied !in setOf("low", "medium", "high")) "low" else supplied
            val reasoning = cleanText(value.optString("reasoning").trim()).take(2000)
            if (cited.isEmpty()) warnings += "'$title' has no captured evidence and is unverified."
            InsightHypothesis(title, confidence,
                if (cited.isEmpty()) "Unverified hypothesis. $reasoning" else reasoning,
                cited, strings(value.optJSONArray("nextChecks"), 10, 600).map { cleanText(it).take(600) })
        }
        val missing = (strings(raw.optJSONArray("missingEvidence"), 20, 600).map { cleanText(it).take(600) } +
            if (imageProvided) "Only one saved still was provided. It cannot establish playback motion or audio; the provider's actual image analysis is unverified."
            else "Video pixels and audio were not sent. The model cannot confirm what the player displayed.").distinct()
        val checks = strings(raw.optJSONArray("recommendedChecks"), 20, 600).map { cleanText(it).take(600) }
        // Summary is a model conclusion, never presented as a captured observation or causal proof.
        val summary = cleanText(raw.optString("summary").trim()).take(2000).ifBlank { "The model did not provide a summary." }
        val clean = JSONObject().put("schema", SCHEMA).put("summary", summary)
            .put("observations", JSONArray(observations.map { JSONObject().put("text", it.text).put("evidenceIds", JSONArray(it.evidenceIds)) }))
            .put("hypotheses", JSONArray(hypotheses.map { JSONObject().put("title", it.title).put("confidence", it.confidence)
                .put("reasoning", it.reasoning).put("evidenceIds", JSONArray(it.evidenceIds)).put("nextChecks", JSONArray(it.nextChecks)) }))
            .put("missingEvidence", JSONArray(missing)).put("recommendedChecks", JSONArray(checks))
            .put("groundingWarnings", JSONArray(warnings.distinct()))
        val encoded = clean.toString()
        require(encoded.length <= 48_000) { "The normalized model report exceeds the 48,000-character limit. No results were accepted." }
        val readable = clean.toString(2).takeIf { it.length <= 48_000 } ?: encoded
        return InsightsReport(summary, observations, hypotheses, missing, checks, warnings.distinct(), readable)
    }
    private fun strings(array: JSONArray?, limit: Int, length: Int): List<String> =
        if (array == null) emptyList() else (0 until minOf(array.length(), limit)).mapNotNull {
            (array.opt(it) as? String)?.trim()?.take(length)?.takeIf(String::isNotEmpty)
        }
    private fun objects(array: JSONArray?, limit: Int): List<JSONObject> =
        if (array == null) emptyList() else (0 until minOf(array.length(), limit)).mapNotNull(array::optJSONObject)
}
