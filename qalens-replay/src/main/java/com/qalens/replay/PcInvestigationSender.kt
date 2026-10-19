package com.qalens.replay

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/** An optional same-package handoff. Standalone replay never depends on the active SDK facade. */
internal object PcInvestigationSender {
    const val ACTION = "com.qalens.action.SEND_INVESTIGATION"
    const val RECEIVER = "com.qalens.QaLensInvestigationReceiver"
    const val SCHEMA = "qalens-investigation-transfer/1"
    const val BYTE_LIMIT = 256 * 1024

    fun document(evidence: InsightsEvidence, report: InsightsReport?): String {
        val bundle = JSONObject(evidence.json)
        evidence.image?.let { image ->
            require(image.bytes.size in 1..RecordingInsightImagePolicy.BYTE_LIMIT) { "The reviewed still exceeds the image limit." }
            // Exact reviewed metadata and exact saved bytes, without recapture/reconstruction.
            val metadata = bundle.getJSONArray("images").getJSONObject(0)
            require(metadata.getString("id") == image.id && metadata.getLong("tMs") == image.tMs) { "Review the selected image again before sending." }
            metadata.put("data", Base64.encodeToString(image.bytes, Base64.NO_WRAP))
        }
        val transfer = JSONObject().put("schema", SCHEMA).put("bundle", bundle)
        report?.let { transfer.put("report", JSONObject(it.json)) }
        val document = transfer.toString()
        require(document.toByteArray(Charsets.UTF_8).size <= BYTE_LIMIT) {
            "This investigation exceeds the 256 KiB PC handoff limit. Remove the optional image or select a smaller evidence window, then review again."
        }
        return document
    }

    suspend fun send(context: Context, evidence: InsightsEvidence, report: InsightsReport?): String {
        val document = withContext(Dispatchers.IO) { document(evidence, report) }
        return withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (!continuation.isActive) return
                        val message = when (resultCode) {
                            1 -> "Queued for the approved PC. Selected text, any reviewed still and the available report were sent; the PC must review before saving or analyzing."
                            2 -> "No approved PC bridge is listening. Connect and approve PC access first, then send again. No pairing or model request was started."
                            3 -> "The reviewed investigation could not be queued. Remove the optional image or narrow the evidence, then review and retry."
                            else -> "This app has no active investigation handoff receiver. Integrate the matching active debug SDK and connect the PC bridge, then retry."
                        }
                        continuation.resume(message)
                    }
                }
                try {
                    context.applicationContext.sendOrderedBroadcast(Intent(ACTION)
                        .setComponent(ComponentName(context.packageName, RECEIVER)).putExtra("document", document),
                        null, callback, Handler(Looper.getMainLooper()), 0, null, null)
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resume("The app could not hand off this investigation. Check the active debug SDK and approved PC bridge, then retry.")
                }
            }
        } ?: "The PC handoff did not finish in time. Check the approved bridge, then send again explicitly. A queued copy may still be waiting for PC review."
    }
}
