package com.qalens.replay

import org.json.JSONArray
import org.json.JSONObject

internal data class InsightsQaStep(val action: String, val evidenceIds: List<String>)

internal data class InsightsQaReport(
    val title: String,
    val steps: List<InsightsQaStep>,
    val expectedResult: String,
    val expectedSource: String,
    val actualResult: String,
    val actualSource: String
) {
    fun json(): JSONObject = JSONObject().put("title", title)
        .put("steps", JSONArray(steps.map { JSONObject().put("action", it.action).put("evidenceIds", JSONArray(it.evidenceIds)) }))
        .put("expectedResult", expectedResult).put("expectedSource", expectedSource)
        .put("actualResult", actualResult).put("actualSource", actualSource)
}

/** Deterministic QA formatting after grounding; model-authored steps/expected/actual are never used. */
internal object InsightsQaReportBuilder {
    const val EXPECTED_MISSING = "Not supplied; confirm expected behavior"
    const val ACTUAL_MISSING = "Not established by selected evidence"
    const val STEPS_MISSING = "No selected captured actions, navigation or screens establish reproduction steps."
    const val PLAYER_STEPS_MISSING = "No current QaLens-player action trace was captured; archived app actions are not replay steps."

    fun attach(report: InsightsReport, evidence: InsightsEvidence, apiKey: String = ""): InsightsReport {
        val bundle = JSONObject(evidence.json)
        val tester = bundle.optJSONObject("qaContext")
        fun safe(value: String, limit: Int): String =
            (if (apiKey.isEmpty()) value else value.replace(apiKey, "[REDACTED]"))
                .replace(Regex("[\\u0000-\\u0008\\u000b\\u000c\\u000e-\\u001f]"), "").trim().take(limit)
        val expected = safe(tester?.opt("expectedResult") as? String ?: "", 1600)
        val actual = safe(tester?.opt("actualResult") as? String ?: "", 1600)
        val playerTarget = bundle.optJSONObject("investigation")?.optString("target") == "qalens-player"
        val items = bundle.getJSONArray("items")
        val steps = if (playerTarget) emptyList() else (0 until items.length()).map(items::getJSONObject)
            .filter { item -> item.optString("kind") == "timeline" && item.optString("id") in evidence.itemTimes &&
                item.optJSONObject("details")?.optString("kind")?.uppercase() in setOf("ACTION", "NAVIGATION", "SCREEN") }
            .sortedWith(compareBy({ it.optLong("tMs") }, { it.optString("id") }))
            .mapNotNull { item -> safe(item.optString("summary"), 1200).takeIf(String::isNotBlank)
                ?.let { InsightsQaStep(it, listOf(item.getString("id"))) } }.take(12)
        val observed = safe(report.observations.joinToString("\n") { it.text }, 2400)
        val qa = InsightsQaReport(safe(report.summary, 400), steps,
            expected.ifBlank { EXPECTED_MISSING }, if (expected.isBlank()) "not-provided" else "tester",
            actual.ifBlank { observed.ifBlank { ACTUAL_MISSING } },
            when { actual.isNotBlank() -> "tester"; observed.isNotBlank() -> "captured-evidence"; else -> "not-established" })
        val document = JSONObject(report.json).put("qaReport", qa.json())
        val encoded = document.toString()
        require(encoded.length <= InsightsEvidenceBuilder.TEXT_LIMIT) { "The QA-formatted report exceeds the 48,000-character limit. No results were accepted." }
        val readable = document.toString(2).takeIf { it.length <= InsightsEvidenceBuilder.TEXT_LIMIT } ?: encoded
        return report.copy(json = readable, qaReport = qa, qaMarkdown = markdown(qa, playerTarget))
    }

    private fun markdown(qa: InsightsQaReport, playerTarget: Boolean): String {
        fun escaped(value: String): String = value.replace("\\", "\\\\")
            .replace(Regex("([`*_{}\\[\\]<>#!|])"), "\\\\$1")
        return buildString {
            append("# ").append(escaped(qa.title.replace('\n', ' '))).append("\n\n")
            append("QA draft from reviewed evidence. Verify before filing; model wording and citations do not prove a cause.\n\n")
            append("## Steps\n\n")
            if (qa.steps.isEmpty()) append(if (playerTarget) PLAYER_STEPS_MISSING else STEPS_MISSING).append("\n")
            else qa.steps.forEachIndexed { index, step ->
                append(index + 1).append(". ").append(escaped(step.action.replace('\n', ' ')))
                append(" (").append(step.evidenceIds.joinToString(", ") { "`$it`" }).append(")\n")
            }
            append("\n## Expected result\n\n").append(escaped(qa.expectedResult))
            append("\n\nSource: ").append(if (qa.expectedSource == "tester") "Tester reported" else "Not supplied").append(".\n")
            append("\n## Actual result\n\n").append(escaped(qa.actualResult))
            append("\n\nSource: ").append(when (qa.actualSource) { "tester" -> "Tester reported"; "captured-evidence" -> "Model interpretation of cited evidence"; else -> "Not established" }).append(". ")
            append(if (qa.actualSource == "captured-evidence") "Verify model wording against the original evidence; citations do not prove every claim."
                else if (qa.actualSource == "tester") "Tester reports are not captured proof." else "More evidence is needed.")
            append("\n")
        }
    }
}
