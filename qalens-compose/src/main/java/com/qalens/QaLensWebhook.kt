package com.qalens

import android.content.Context
import com.qalens.android.QaLensPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32

/**
 * Ships a ".sal" recording to the configured webhook so a backend (typically AI analysis over the
 * logs/network/video tracks) can process it and return a verdict.
 *
 * Contract sent to the backend:
 *  - multipart/form-data POST, one part: "file" (the .sal, application/zip)
 *  - headers: the configured auth header, X-QaLens-App/-Version/-Env/-Device/-Platform,
 *    X-QaLens-Sal-Name/-Size, and X-QaLens-Digest (one-line JSON triage summary so the backend
 *    can route/prioritize WITHOUT unzipping)
 *  - query params: app, version, env, platform, device, createdAt (when "attach metadata" is on)
 *    plus the user's raw extra params
 *
 * Recordings larger than [CHUNK_THRESHOLD] use a chunked/resumable upload instead: POST
 * /webhook/chunk/start (idempotent by name+size+digest), then one POST per 1MB chunk with a crc32,
 * then POST /webhook/chunk/<id>/finalize to assemble + run the normal ingest path. Already-received
 * chunks are skipped via GET /webhook/chunk/<id>/status, so a dropped connection can resume where it
 * left off. Transient upload failures are parked in an offline retry queue (see [drainQueue]).
 *
 * The backend's HTTP status + response body (truncated) are surfaced live in the Control Room.
 * Dependency-free on purpose: HttpURLConnection on a bounded background pool — hosts aren't forced
 * onto any HTTP client. Debug/QA tooling only.
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

    /** Upload resilience (R7-lite): total tries + backoff between them. */
    private const val MAX_ATTEMPTS = 3
    private val RETRY_BACKOFF_MS = longArrayOf(1_500L, 3_000L)

    /** Small files go multipart as before; anything larger switches to chunked/resumable. */
    private const val CHUNK_THRESHOLD = 2_000_000L
    private const val CHUNK_SIZE = 1_000_000

    /** Offline retry queue cap (oldest entries evicted first). */
    private const val MAX_QUEUE = 20

    // ── Bounded executor ────────────────────────────────────────────────────
    // Fixed pool of 2 daemon threads + a bounded queue of 8. Overflow is dropped
    // with a log line (AbortPolicy) rather than blocking the caller or growing the pool.
    private val threadCounter = AtomicInteger(0)
    private val executor: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS,
            LinkedBlockingQueue<Runnable>(8),
            ThreadFactory { r ->
                Thread(r, "qalens-webhook-" + threadCounter.incrementAndGet()).apply { isDaemon = true }
            },
            ThreadPoolExecutor.AbortPolicy()
        )
    }

    private val connectionLock = Any()
    private val connections = mutableSetOf<HttpURLConnection>()
    private val jobEpoch = ThreadLocal<Long>()
    private val jobConnections = ThreadLocal<MutableList<HttpURLConnection>>()
    private val inFlight = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    fun stop() {
        statesMutable.update { states -> states.mapValues { (_, state) ->
            if (state is UploadState.Uploading) UploadState.Failed("SDK disabled") else state
        } }
        val active = synchronized(connectionLock) { connections.toList().also { connections.clear() } }
        Thread({ active.forEach { runCatching { it.disconnect() } } }, "qalens-upload-cancel").apply { isDaemon = true; start() }
    }

    private fun activeJob(): Boolean = QaLens.config.value.enabled && jobEpoch.get() == QaLens.captureEpoch

    private fun submit(key: String, onRejected: () -> Unit, block: () -> Unit) {
        val epoch = QaLens.captureEpoch
        try {
            executor.execute(Runnable {
                jobEpoch.set(epoch)
                jobConnections.set(mutableListOf())
                try { if (activeJob()) block() }
                finally {
                    jobConnections.get().orEmpty().forEach { conn ->
                        runCatching { conn.disconnect() }
                        synchronized(connectionLock) { connections.remove(conn) }
                    }
                    jobConnections.remove()
                    jobEpoch.remove()
                    inFlight.remove(key)
                }
            })
        } catch (e: RejectedExecutionException) {
            inFlight.remove(key)
            onRejected()
            QaLens.log("Webhook queue full")
        }
    }

    fun upload(context: Context, info: RecordingInfo) {
        val app = context.applicationContext
        drainQueue(app)
        uploadEntry(app, info.path, info.name, skipDrain = true)
    }

    /**
     * Re-uploads everything parked in the offline retry queue. Entries are removed once the backend
     * accepts (2xx) or permanently rejects (4xx) them, and kept when the failure was a transport
     * error. Callers pass [Context.getApplicationContext].
     */
    internal fun drainQueue(context: Context) {
        val app = context.applicationContext
        if (QaLensPrefs.webhookUrl(app).isBlank()) return
        val pending = readQueue(app)
        if (pending.isEmpty()) return
        QaLens.log("Webhook draining ${pending.size} queued upload(s)")
        for (p in pending.filter { it.destination == QaLensPrefs.webhookUrl(app) }) {
            uploadEntry(app, p.path, p.name, skipDrain = true)
        }
    }

    /** Upload one recording. [skipDrain] avoids infinite recursion when called from [drainQueue]. */
    private fun uploadEntry(context: Context, path: String, name: String, skipDrain: Boolean) {
        if (!QaLens.config.value.enabled) return
        if (!skipDrain) drainQueue(context)
        val url = QaLensPrefs.webhookUrl(context)
        if (url.isBlank()) { QaLens.log("Webhook URL not configured"); return }
        val file = File(path)
        if (!file.exists()) {
            removePending(context, path)
            setState(path, UploadState.Failed("file no longer exists"))
            return
        }

        val endpoint = buildUrl(context, url)
        val settings = requestSettings(context)
        if (!inFlight.add(path)) return
        setState(path, UploadState.Uploading)
        submit(path, onRejected = {
            setState(path, UploadState.Failed("Upload queue full; queued for retry"))
            enqueuePending(context, path, name, url)
        }) {
            if (!QaLens.config.value.enabled) { setState(path, UploadState.Failed("SDK disabled")); return@submit }
            var failure: Throwable? = null
            val result = runCatching { sendRecording(settings, endpoint, file, name) }
                .onFailure { failure = it }
            val state = result.fold(
                onSuccess = { (code, body) -> UploadState.Done(code, body.take(600)) },
                onFailure = { UploadState.Failed(it.message ?: it.javaClass.simpleName) }
            )
            if (!activeJob()) return@submit
            setState(path, state)
            when (state) {
                is UploadState.Done -> {
                    // Permanent outcome: drop any queued copy. 5xx is transient → keep it queued.
                    if (QaLensUploadPolicy.retryable(state.code)) enqueuePending(context, path, name, url)
                    else removePending(context, path)
                    QaLens.log("Webhook ${if (state.success) "OK" else "rejected"} (${state.code}) for ${name}" +
                        state.body.takeIf { it.isNotBlank() }?.let { ": ${it.take(120)}" }.orEmpty())
                }
                is UploadState.Failed -> {
                    if (failure is IOException && (failure !is HttpFailure || QaLensUploadPolicy.retryable((failure as HttpFailure).code))) enqueuePending(context, path, name, url)
                    else removePending(context, path)
                    QaLens.log("Webhook upload failed for ${name}: ${state.error}")
                }
                else -> Unit
            }
        }
    }

    /** Metadata-only ping (no file) so QA can validate the endpoint before shipping a recording. */
    fun test(context: Context) {
        val app = context.applicationContext
        val url = QaLensPrefs.webhookUrl(app)
        if (url.isBlank()) { QaLens.log("Webhook URL not configured"); return }
        if (!QaLens.config.value.enabled) return
        val endpoint = buildUrl(app, url)
        val settings = requestSettings(app)
        if (!inFlight.add(TEST_KEY)) return
        setState(TEST_KEY, UploadState.Uploading)
        submit(TEST_KEY, onRejected = { setState(TEST_KEY, UploadState.Failed("Upload queue full")) }) {
            val result = runCatching {
                val conn = open(settings, endpoint)
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.doOutput = true
                conn.outputStream.use { it.write("""{"qalens":"webhook-test"}""".toByteArray()) }
                readResponse(conn)
            }
            if (!activeJob()) return@submit
            setState(TEST_KEY, result.fold(
                onSuccess = { (code, body) -> UploadState.Done(code, body.take(600)) },
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
        val endpoint = base.substringBefore('#')
        if (parts.isEmpty()) return endpoint
        return endpoint + (if (endpoint.contains('?')) "&" else "?") + parts.joinToString("&")
    }

    private data class RequestSettings(val headers: Map<String, String>)
    private class HttpFailure(val code: Int) : IOException("Upload rejected (HTTP $code)")
    private fun requestSettings(context: Context): RequestSettings {
        val state = QaLens.state.value
        val headers = linkedMapOf("User-Agent" to "QaLens-Android", "X-QaLens-App" to state.device.appName,
            "X-QaLens-Version" to state.device.appVersion, "X-QaLens-Platform" to "android ${state.device.androidVersion}")
        state.device.environment?.let { headers["X-QaLens-Env"] = it }
        headers["X-QaLens-Device"] = "${state.device.manufacturer} ${state.device.deviceModel}"
        QaLensPrefs.userName(context).takeIf { it.isNotBlank() }?.let { headers["X-QaLens-User"] = it }
        val name = QaLensPrefs.webhookHeaderName(context)
        val value = QaLensPrefs.webhookHeaderValue(context)
        if (name.isNotBlank() && value.isNotBlank()) headers[name] = value
        return RequestSettings(headers)
    }
    private fun open(context: RequestSettings, url: String): HttpURLConnection {
        check(activeJob()) { "SDK disabled" }
        require(QaLensUploadPolicy.origin(url) != null) { "Invalid webhook URL" }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false // credentials never follow a destination redirect
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        context.headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
        synchronized(connectionLock) {
            check(activeJob()) { "SDK disabled" }
            connections += conn
            jobConnections.get()?.add(conn)
        }
        return conn
    }

    /** Dispatch: small files use the unchanged multipart path; large ones use chunked/resumable. */
    private fun sendRecording(context: RequestSettings, url: String, file: File, name: String): Pair<Int, String> =
        if (file.length() <= CHUNK_THRESHOLD) {
            postWithRetry(context, url, file, name)
        } else {
            chunkedUpload(context, url, file, name)
        }

    /** Upload path retry (R7-lite): retry exceptions + 5xx up to [MAX_ATTEMPTS] with backoff.
     *  4xx is a permanent rejection and returns immediately; a 5xx after the last try still surfaces
     *  as [UploadState.Done] with the code/body, so Control Room display is unchanged. */
    private fun postWithRetry(context: RequestSettings, url: String, file: File, name: String): Pair<Int, String> {
        var attempt = 0
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            val response = try {
                post(context, url, file)
            } catch (e: Exception) {
                if (!activeJob() || attempt == MAX_ATTEMPTS) throw e
                QaLens.log("Webhook attempt ${attempt + 1}/$MAX_ATTEMPTS for $name after ${e.message ?: e.javaClass.simpleName}")
                Thread.sleep(RETRY_BACKOFF_MS[attempt - 1])
                continue
            }
            if (QaLensUploadPolicy.retryable(response.first) && attempt < MAX_ATTEMPTS) {
                QaLens.log("Webhook attempt ${attempt + 1}/$MAX_ATTEMPTS for $name after HTTP ${response.first}")
                Thread.sleep(RETRY_BACKOFF_MS[attempt - 1])
                continue
            }
            return response
        }
        throw IllegalStateException("webhook retry loop exhausted")
    }

    private fun post(context: RequestSettings, url: String, file: File): Pair<Int, String> {
        val boundary = "----qalens-${System.currentTimeMillis()}"
        val conn = open(context, url)
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.setRequestProperty("X-QaLens-Sal-Name", file.name)
        conn.setRequestProperty("X-QaLens-Sal-Size", file.length().toString())
        // Triage header sourced from the file's own analysis.json (accurate for old recordings
        // too) — backends can route/prioritize without unzipping the upload.
        triageDigest(file)?.let { conn.setRequestProperty("X-QaLens-Digest", it) }
        // Stream — recordings can be tens of MB; don't buffer them in memory.
        conn.setFixedLengthStreamingMode(multipartLength(boundary, file))

        conn.outputStream.use { out ->
            out.write(partHeader(boundary, file).toByteArray())
            file.inputStream().use { it.copyTo(out) }
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        return readResponse(conn)
    }

    /** The one-line JSON triage summary (analysis.json "stats") the backend keys digest on. */
    private fun triageDigest(file: File): String? =
        runCatching {
            java.util.zip.ZipFile(file).use { zip ->
                zip.getEntry("analysis.json")?.let { entry ->
                    val text = zip.getInputStream(entry).buffered().use { source ->
                        source.mark(2)
                        val gzip = source.read() == 0x1f && source.read() == 0x8b
                        source.reset()
                        val decoded = if (gzip) java.util.zip.GZIPInputStream(source) else source
                        decoded.bufferedReader().use { reader ->
                            val chars = CharArray(4096)
                            val text = StringBuilder()
                            while (true) {
                                val count = reader.read(chars)
                                if (count < 0) break
                                require(text.length + count <= 1_048_576) { "Digest exceeds limit" }
                                text.append(chars, 0, count)
                            }
                            text.toString()
                        }
                    }
                    org.json.JSONObject(text).optJSONObject("stats")?.toString()
                }
            }
        }.getOrNull()

    private fun partHeader(boundary: String, file: File): String =
        "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n" +
            "Content-Type: application/zip\r\n\r\n"

    private fun multipartLength(boundary: String, file: File): Long =
        partHeader(boundary, file).toByteArray().size +
            file.length() +
            "\r\n--$boundary--\r\n".toByteArray().size

    // ── Chunked / resumable upload (R7) ──────────────────────────────────────

    /** Retry helper for the chunked flow: retries exceptions + 409 (mismatch) + 5xx with backoff. */
    private fun httpWithRetry(name: String, what: String, block: () -> Pair<Int, String>): Pair<Int, String> {
        var attempt = 0
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            val response = try {
                block()
            } catch (e: Exception) {
                if (!activeJob() || attempt == MAX_ATTEMPTS) throw e
                QaLens.log("Webhook $what attempt ${attempt + 1}/$MAX_ATTEMPTS for $name after ${e.message ?: e.javaClass.simpleName}")
                Thread.sleep(RETRY_BACKOFF_MS[attempt - 1])
                continue
            }
            if ((response.first == 409 || QaLensUploadPolicy.retryable(response.first)) && attempt < MAX_ATTEMPTS) {
                QaLens.log("Webhook $what attempt ${attempt + 1}/$MAX_ATTEMPTS for $name after HTTP ${response.first}")
                Thread.sleep(RETRY_BACKOFF_MS[attempt - 1])
                continue
            }
            return response
        }
        throw IllegalStateException("webhook retry loop exhausted")
    }

    /** Derive a chunk-endpoint URL from the (query-carrying) webhook URL. */
    private fun chunkUrl(base: String, suffix: String): String {
        val qIdx = base.indexOf('?')
        val path = (if (qIdx >= 0) base.substring(0, qIdx) else base).trimEnd('/')
        val query = if (qIdx >= 0) base.substring(qIdx) else ""
        return path + suffix + query
    }

    private fun chunkedUpload(context: RequestSettings, url: String, file: File, name: String): Pair<Int, String> {
        val size = file.length()
        val chunkCount = ((size + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()
        val digest = triageDigest(file).orEmpty()
        val uploadId = startChunkUpload(context, url, file.name, size, chunkCount, digest)
        val received = chunkStatus(context, url, uploadId)
        for (i in 0 until chunkCount) {
            if (i in received) continue
            uploadChunkWithRetry(context, url, uploadId, i, readChunk(file, i), name)
        }
        return finalizeChunkUpload(context, url, uploadId, file.name, size, digest)
    }

    private fun startChunkUpload(context: RequestSettings, url: String, name: String, size: Long, chunkCount: Int, digest: String): String {
        val (code, body) = httpWithRetry(name, "chunk-start") {
            val conn = open(context, chunkUrl(url, "/chunk/start"))
            conn.requestMethod = "POST"
            conn.doOutput = false
            conn.setRequestProperty("X-QaLens-Sal-Name", name)
            conn.setRequestProperty("X-QaLens-Sal-Size", size.toString())
            conn.setRequestProperty("X-QaLens-Chunk-Count", chunkCount.toString())
            if (digest.isNotBlank()) conn.setRequestProperty("X-QaLens-Digest", digest)
            readResponse(conn)
        }
        if (code !in 200..299) throw HttpFailure(code)
        val uploadId = runCatching { org.json.JSONObject(body).optString("uploadId") }
            .getOrNull().orEmpty()
        if (!uploadId.matches(Regex("[A-Za-z0-9_-]{1,200}"))) throw IOException("Invalid chunk uploadId")
        return uploadId
    }

    /** Best-effort: ask which chunks the backend already has so a resume can skip them. */
    private fun chunkStatus(context: RequestSettings, url: String, uploadId: String): Set<Int> {
        val resp = runCatching {
            val conn = open(context, chunkUrl(url, "/chunk/$uploadId/status"))
            conn.requestMethod = "GET"
            readResponse(conn)
        }.getOrNull() ?: return emptySet()
        if (resp.first !in 200..299) return emptySet()
        return runCatching {
            val arr = org.json.JSONObject(resp.second).optJSONArray("received")
            if (arr == null) emptySet() else {
                val out = mutableSetOf<Int>()
                for (i in 0 until arr.length()) out += arr.getInt(i)
                out
            }
        }.getOrDefault(emptySet())
    }

    private fun uploadChunkWithRetry(context: RequestSettings, url: String, uploadId: String, index: Int, chunk: ByteArray, name: String) {
        val (code, body) = httpWithRetry(name, "chunk ${index + 1}") {
            val conn = open(context, chunkUrl(url, "/chunk/$uploadId/$index"))
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setRequestProperty("X-QaLens-Chunk-Size", chunk.size.toString())
            conn.setRequestProperty("X-QaLens-Chunk-Crc32", crc32Hex(chunk))
            conn.setFixedLengthStreamingMode(chunk.size.toLong())
            conn.outputStream.use { it.write(chunk) }
            readResponse(conn)
        }
        if (code !in 200..299) throw HttpFailure(code)
    }

    private fun finalizeChunkUpload(context: RequestSettings, url: String, uploadId: String, name: String, size: Long, digest: String): Pair<Int, String> =
        httpWithRetry(name, "finalize") {
            val conn = open(context, chunkUrl(url, "/chunk/$uploadId/finalize"))
            conn.requestMethod = "POST"
            conn.doOutput = false
            conn.setRequestProperty("X-QaLens-Sal-Name", name)
            conn.setRequestProperty("X-QaLens-Sal-Size", size.toString())
            if (digest.isNotBlank()) conn.setRequestProperty("X-QaLens-Digest", digest)
            readResponse(conn)
        }

    private fun readChunk(file: File, index: Int): ByteArray {
        val offset = index.toLong() * CHUNK_SIZE
        val length = minOf(CHUNK_SIZE.toLong(), file.length() - offset).toInt()
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val buf = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = raf.read(buf, read, length - read)
                if (n < 0) throw IOException("chunk read EOF for $index")
                read += n
            }
            return buf
        }
    }

    private fun crc32Hex(data: ByteArray): String {
        val crc = CRC32()
        crc.update(data)
        return java.lang.Long.toHexString(crc.value)
    }

    // ── Offline retry queue (persisted JSON under QaLensPrefs) ───────────────

    private data class PendingUpload(val path: String, val name: String, val createdAt: Long, val destination: String)

    private fun readQueue(context: Context): List<PendingUpload> {
        val raw = QaLensPrefs.webhookQueue(context)
        val out = mutableListOf<PendingUpload>()
        runCatching {
            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val path = o.optString("path")
                if (path.isBlank()) continue
                val name = o.optString("name").ifBlank { path.substringAfterLast('/') }
                out += PendingUpload(path, name, o.optLong("createdAt", 0L), o.optString("destination"))
            }
        }
        return out
    }

    private fun writeQueue(context: Context, list: List<PendingUpload>) {
        val arr = org.json.JSONArray()
        for (p in list) {
            arr.put(org.json.JSONObject()
                .put("path", p.path)
                .put("name", p.name)
                .put("createdAt", p.createdAt).put("destination", p.destination))
        }
        QaLensPrefs.setWebhookQueue(context, arr.toString())
    }

    private fun enqueuePending(context: Context, path: String, name: String, destination: String) {
        synchronized(queueLock) {
            val current = readQueue(context).toMutableList()
            if (current.any { it.path == path }) return
            current += PendingUpload(path, name, System.currentTimeMillis(), destination)
            while (current.size > MAX_QUEUE) current.removeAt(0)
            writeQueue(context, current)
        }
        QaLens.log("Webhook queued for retry")
    }

    private fun removePending(context: Context, path: String) {
        synchronized(queueLock) {
            val current = readQueue(context)
            val filtered = current.filter { it.path != path }
            if (filtered.size != current.size) writeQueue(context, filtered)
        }
    }

    private val queueLock = Any()

    private fun readResponse(conn: HttpURLConnection): Pair<Int, String> = try {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { reader ->
            val text = StringBuilder()
            val chars = CharArray(4096)
            while (text.length < 65_536) {
                val count = reader.read(chars, 0, minOf(chars.size, 65_536 - text.length))
                if (count < 0) break
                text.append(chars, 0, count)
            }
            text.toString()
        }.orEmpty()
        code to body
    } finally {
        conn.disconnect()
        synchronized(connectionLock) { connections.remove(conn) }
        jobConnections.get()?.remove(conn)
    }
}
