package com.qalens

import android.content.Context
import com.qalens.android.QaLensPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * Ships a `.sal` recording to the configured webhook so a backend (typically AI analysis over the
 * logs/network/video tracks) can process it and return a verdict.
 *
 * Contract sent to the backend:
 *  - multipart/form-data POST, one part: `file` (the .sal, application/zip)
 *  - headers: the configured auth header, `X-QaLens-App/-Version/-Env/-Device/-Platform`,
 *    `X-QaLens-Sal-Name/-Size`, and `X-QaLens-Digest` (one-line JSON triage summary so the backend
 *    can route/prioritize WITHOUT unzipping)
 *  - query params: `app, version, env, platform, device, createdAt` (when "attach metadata" is on)
 *    plus the user's raw extra params
 *
 * The backend's HTTP status + response body (truncated) are surfaced live in the Control Room.
 * Dependency-free on purpose: HttpURLConnection on a background thread — hosts aren't forced onto
 * any HTTP client. Debug/QA tooling only.
 */
internal object QaLensWebhook {

    sealed class UploadState {
        data object Uploading : UploadState()
        data class Done(val code: Int, val body: String) : UploadState() {
            val success: Boolean get() = code in 200..299
        }
        data class Failed(val error: String) : UploadState()
    }

    /** Keyed by recording file path. */
    private val statesMutable = MutableStateFlow<Map<String, UploadState>>(emptyMap())
    val states: StateFlow<Map<String, UploadState>> = statesMutable.asStateFlow()

    fun upload(context: Context, info: RecordingInfo) {
        val app = context.applicationContext
        val url = QaLensPrefs.webhookUrl(app)
        if (url.isBlank()) { QaLens.log("Webhook URL not configured"); return }
        val file = File(info.path)
        if (!file.exists()) { setState(info.path, UploadState.Failed("file no longer exists")); return }

        setState(info.path, UploadState.Uploading)
        thread(name = "qalens-webhook") {
            val result = runCatching { post(app, buildUrl(app, url), file) }
            val state = result.fold(
                onSuccess = { (code, body) -> UploadState.Done(code, body) },
                onFailure = { UploadState.Failed(it.message ?: it.javaClass.simpleName) }
            )
            setState(info.path, state)
            when (state) {
                is UploadState.Done ->
                    QaLens.log("Webhook ${if (state.success) "OK" else "rejected"} (${state.code}) for ${info.name}" +
                        state.body.takeIf { it.isNotBlank() }?.let { ": ${it.take(120)}" }.orEmpty())
                is UploadState.Failed -> QaLens.log("Webhook upload failed for ${info.name}: ${state.error}")
                else -> Unit
            }
        }
    }

    /** Metadata-only ping (no file) so QA can validate the endpoint before shipping a recording. */
    fun test(context: Context) {
        val app = context.applicationContext
        val url = QaLensPrefs.webhookUrl(app)
        if (url.isBlank()) { QaLens.log("Webhook URL not configured"); return }
        setState(TEST_KEY, UploadState.Uploading)
        thread(name = "qalens-webhook-test") {
            val result = runCatching {
                val conn = open(app, buildUrl(app, url))
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.doOutput = true
                conn.outputStream.use { it.write("""{"qalens":"webhook-test"}""".toByteArray()) }
                readResponse(conn)
            }
            setState(TEST_KEY, result.fold(
                onSuccess = { (code, body) -> UploadState.Done(code, body) },
                onFailure = { UploadState.Failed(it.message ?: it.javaClass.simpleName) }
            ))
        }
    }

    const val TEST_KEY = "__webhook_test__"

    private fun setState(key: String, state: UploadState) =
        statesMutable.update { it + (key to state) }

    // ── HTTP plumbing ────────────────────────────────────────────────────────

    private fun buildUrl(context: Context, base: String): String {
        val s = QaLens.state.value
        val parts = mutableListOf<String>()
        if (QaLensPrefs.webhookIncludeMeta(context)) {
            fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
            parts += "app=${enc(s.device.appName)}"
            parts += "version=${enc(s.device.appVersion)}"
            s.device.environment?.let { parts += "env=${enc(it)}" }
            parts += "platform=android"
            parts += "device=${enc("${s.device.manufacturer} ${s.device.deviceModel}")}"
            parts += "createdAt=${System.currentTimeMillis()}"
            // Active QA profile → the backend knows WHO uploaded (shared test phones).
            QaLensPrefs.userName(context).takeIf { it.isNotBlank() }?.let { parts += "user=${enc(it)}" }
        }
        QaLensPrefs.webhookParams(context).takeIf { it.isNotBlank() }?.let { parts += it }
        if (parts.isEmpty()) return base
        return base + (if (base.contains('?')) "&" else "?") + parts.joinToString("&")
    }

    private fun open(context: Context, url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "QaLens-Android")
        val headerName = QaLensPrefs.webhookHeaderName(context)
        val headerValue = QaLensPrefs.webhookHeaderValue(context)
        if (headerName.isNotBlank() && headerValue.isNotBlank()) {
            conn.setRequestProperty(headerName, headerValue)
        }
        val s = QaLens.state.value
        conn.setRequestProperty("X-QaLens-App", s.device.appName)
        conn.setRequestProperty("X-QaLens-Version", s.device.appVersion)
        s.device.environment?.let { conn.setRequestProperty("X-QaLens-Env", it) }
        conn.setRequestProperty("X-QaLens-Device", "${s.device.manufacturer} ${s.device.deviceModel}")
        conn.setRequestProperty("X-QaLens-Platform", "android ${s.device.androidVersion}")
        QaLensPrefs.userName(context).takeIf { it.isNotBlank() }
            ?.let { conn.setRequestProperty("X-QaLens-User", it) }
        return conn
    }

    private fun post(context: Context, url: String, file: File): Pair<Int, String> {
        val boundary = "----qalens-${System.currentTimeMillis()}"
        val conn = open(context, url)
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.setRequestProperty("X-QaLens-Sal-Name", file.name)
        conn.setRequestProperty("X-QaLens-Sal-Size", file.length().toString())
        // Triage header sourced from the file's own analysis.json (accurate for old recordings
        // too) — backends can route/prioritize without unzipping the upload.
        runCatching {
            java.util.zip.ZipFile(file).use { zip ->
                zip.getEntry("analysis.json")?.let { entry ->
                    val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                    org.json.JSONObject(text).optJSONObject("stats")?.toString()
                }
            }
        }.getOrNull()?.let { conn.setRequestProperty("X-QaLens-Digest", it) }
        // Stream — recordings can be tens of MB; don't buffer them in memory.
        conn.setFixedLengthStreamingMode(multipartLength(boundary, file))

        conn.outputStream.use { out ->
            out.write(partHeader(boundary, file).toByteArray())
            file.inputStream().use { it.copyTo(out) }
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        return readResponse(conn)
    }

    private fun partHeader(boundary: String, file: File): String =
        "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n" +
            "Content-Type: application/zip\r\n\r\n"

    private fun multipartLength(boundary: String, file: File): Long =
        partHeader(boundary, file).toByteArray().size +
            file.length() +
            "\r\n--$boundary--\r\n".toByteArray().size

    private fun readResponse(conn: HttpURLConnection): Pair<Int, String> {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = runCatching {
            stream?.bufferedReader()?.use { it.readText() }.orEmpty().take(600)
        }.getOrDefault("")
        conn.disconnect()
        return code to body
    }
}
