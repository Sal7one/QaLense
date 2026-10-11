package com.qalens.replay

import org.json.JSONObject

/** An immutable snapshot of the QaLens player, not an event from the app's saved recording. */
internal data class InsightsRuntime(val observedAtMillis: Long, val recordingPositionMs: Long, val details: Map<String, Any?>) {
    fun json(): JSONObject = JSONObject().put("id", "player:runtime").put("source", "current-player").put("client", "android")
        .put("observedAtMillis", observedAtMillis).put("recordingPositionMs", recordingPositionMs)
        .put("details", JSONObject(details.mapValues { (_, value) -> if (value is String) value.take(600) else value ?: JSONObject.NULL }))
}
