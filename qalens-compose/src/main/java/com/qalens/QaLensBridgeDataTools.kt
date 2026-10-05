package com.qalens

import android.os.CancellationSignal
import android.os.SystemClock
import com.qalens.android.AppSalQuery
import com.qalens.android.QaLensAppSal
import kotlinx.coroutines.*
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Private desktop data protocol. No disk reads, SQL or JSON work runs on main. */
internal object QaLensBridgeDataTools {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class Read(
        val id: String = UUID.randomUUID().toString(),
        val signal: CancellationSignal = CancellationSignal(),
        var phase: String = "running", var reason: String? = null,
        var result: QaLensDataTools.DesktopResult? = null, var error: String? = null,
        var errorPolicy: QaLensConfig? = null,
        var ended: Long = 0
    )
    private data class ReadView(val id: String, val phase: String, val error: String?,
        val result: QaLensDataTools.DesktopResult?, val policy: QaLensConfig)
    private var current: Read? = null
    private var expiry: Job? = null
    private var policy: QaLensConfig? = null
    private var policyRevision = 0L

    @Synchronized fun policyId(expected: QaLensConfig = QaLens.config.value): Long {
        if (expected != QaLens.config.value) throw QaLensBridgeFailure(409, "Privacy settings changed; refresh")
        if (policy != QaLens.config.value) { policy = QaLens.config.value; policyRevision++ }
        return policyRevision
    }

    @Synchronized fun stop() {
        val old = current; current = null
        expiry?.cancel(); expiry = null
        old?.reason = "Desktop disconnected"
        // CancellationSignal can wait for its listener; never do that on a host main thread.
        if (old != null) scope.launch { old.signal.cancel() }
    }

    private fun context() = QaLens.appContext ?: throw QaLensBridgeFailure(503, "No app context")
    private fun text(input: JSONObject, field: String, max: Int): String =
        (input.opt(field) as? String)?.takeIf { it.isNotBlank() && it.length <= max }
            ?: throw QaLensBridgeFailure(400, "Supply $field up to $max characters")
    private fun fileId(name: String): String = MessageDigest.getInstance("SHA-256")
        .digest(name.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun database(input: JSONObject): String {
        val id = text(input, "database", 128)
        return QaLensDataTools.databases(context()).firstOrNull { fileId(it) == id }
            ?: throw QaLensBridgeFailure(404, "Database unavailable; rescan")
    }

    fun values(): Map<String, Any?> {
        val config = QaLens.config.value
        val sources = QaLens.state.value.dataSources
        var fieldsLeft = 300; var charsLeft = 100_000; var omittedFields = 0
        val visible = sources.entries.take(30).associate { (name, values) ->
            val safe = DataValuePreview.sanitize(values, config)
            val shown = linkedMapOf<String, String>()
            safe.forEach { (key, value) ->
                if (fieldsLeft > 0 && charsLeft >= key.length + value.length) {
                    shown[key] = value; fieldsLeft--; charsLeft -= key.length + value.length
                } else omittedFields++
            }
            omittedFields += (values.size - safe.size).coerceAtLeast(0)
            config.redact(name).take(200) to shown
        }
        val statuses = QaLens.dataStoreValueStatus.value.entries.take(30).associate { (name, status) ->
            config.redact(name).take(200) to mapOf("phase" to status.phase.name.lowercase(), "updatedAtMillis" to status.updatedAtMillis)
        }
        return mapOf("ok" to true, "policyId" to policyId(), "dataSources" to visible, "sourceStatus" to statuses,
            "omittedSources" to (sources.size - visible.size).coerceAtLeast(0), "omittedFields" to omittedFields,
            "coverage" to "Cached decoded fields chosen by the host; bounded read-only previews").also { policyId(config) }
    }

    fun data(input: JSONObject): Map<String, Any?> {
        val context = context(); val config = QaLens.config.value
        return when (input.opt("action")) {
            "files" -> mapOf("ok" to true, "policyId" to policyId(),
                "preferences" to QaLensDataTools.sharedPrefsFiles(context).filterNot { it.startsWith("qalens", true) }.take(50).map {
                    mapOf("id" to fileId(it), "name" to config.redact(it).take(200)) },
                "dataStore" to QaLensDataTools.dataStoreFiles(context).take(50).map { (name, bytes) ->
                    mapOf("name" to config.redact(name).take(200), "size" to bytes) },
                "coverage" to "DataStore names/sizes only. Binary does not mean encrypted; connect the host's decoded Flow for values.")
            "preferences" -> {
                val id = text(input, "id", 128)
                val name = QaLensDataTools.sharedPrefsFiles(context).firstOrNull { fileId(it) == id && !it.startsWith("qalens", true) }
                    ?: throw QaLensBridgeFailure(404, "Preferences unavailable")
                val file = java.io.File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")
                val directory = java.io.File(context.applicationInfo.dataDir, "shared_prefs").canonicalFile
                if (file.name != "$name.xml" || file.canonicalFile.parentFile != directory)
                    throw QaLensBridgeFailure(400, "Preferences path unavailable")
                val raw = QaLensDataTools.readSharedPrefs(context, name)
                val encrypted = raw.keys.any { it.startsWith("__androidx_security_crypto_encrypted_prefs_") }
                mapOf("ok" to true, "policyId" to policyId(), "name" to config.redact(name), "encrypted" to encrypted,
                    "values" to if (encrypted) emptyMap() else DataValuePreview.sanitize(raw, config),
                    "coverage" to if (encrypted) "Encrypted preferences; connect the app's decoded fields to Live app values." else "Explicit preference snapshot; not a live DataStore hook")
            }
            else -> throw QaLensBridgeFailure(400, "Use files or preferences")
        }.also { policyId(config) }
    }

    private fun queryId(query: AppSalQuery): String = MessageDigest.getInstance("SHA-256")
        .digest(listOf(query.name, query.db, query.sql).joinToString("") { "${it.length}:$it" }.toByteArray())
        .joinToString("") { "%02x".format(it) }

    fun catalog(): Map<String, Any?> {
        val context = context(); val config = QaLens.config.value
        return mapOf("ok" to true, "policyId" to policyId(), "databases" to QaLensDataTools.databases(context).take(100).map {
                mapOf("id" to fileId(it), "name" to config.redact(it).take(200)) },
            "saved" to QaLensAppSal.queries(context).take(50).map { query ->
                val sql = config.redact(query.sql).take(DesktopSqlPolicy.MAX_SQL)
                mapOf("id" to queryId(query), "name" to config.redact(query.name).take(100),
                    "database" to fileId(query.db), "databaseName" to config.redact(query.db).take(200), "sql" to sql, "masked" to (sql != query.sql))
            }, "readOnly" to true, "maxRows" to 100, "maxColumns" to 30, "timeoutSeconds" to 10).also { policyId(config) }
    }

    fun sql(input: JSONObject, sessionActive: () -> Boolean = { QaLens.config.value.enabled }): Map<String, Any?> = when (input.opt("action")) {
        "start" -> {
            val database = database(input); val sql = text(input, "sql", DesktopSqlPolicy.MAX_SQL)
            try { DesktopSqlPolicy.bounded(sql) } catch (e: IllegalArgumentException) { throw QaLensBridgeFailure(400, e.message.orEmpty()) }
            if (database !in QaLensDataTools.databases(context())) throw QaLensBridgeFailure(404, "Database unavailable; rescan")
            val read = synchronized(this) {
                if (!sessionActive()) throw QaLensBridgeFailure(503, "Bridge stopped")
                if (current?.phase in listOf("running", "cancelling")) throw QaLensBridgeFailure(409, "Cancel or wait for the current query")
                expiry?.cancel(); expiry = null
                Read().also { current = it }
            }
            scope.launch {
                val timer = launch {
                    delay(10_000)
                    synchronized(this@QaLensBridgeDataTools) {
                        if (current === read && read.phase == "running") { read.reason = "Query exceeded ten seconds"; read.phase = "cancelling" }
                    }
                    read.signal.cancel()
                }
                try {
                    if (!sessionActive()) read.signal.cancel()
                    val result = QaLensDataTools.desktopQuery(context(), database, sql, read.signal)
                    synchronized(this@QaLensBridgeDataTools) {
                        if (current === read) { read.result = if (read.reason == null && sessionActive()) result else null; read.phase = if (read.reason == null && sessionActive()) "complete" else "cancelled" }
                    }
                } catch (e: Exception) {
                    val errorPolicy = QaLens.config.value
                    val message = errorPolicy.redact(e.message ?: "Database cannot be read with Android SQLite. Encrypted/custom databases need host snapshots.").take(500)
                    synchronized(this@QaLensBridgeDataTools) {
                        if (current === read) {
                            read.phase = if (read.reason != null || read.signal.isCanceled) "cancelled" else "error"
                            read.error = message
                            read.errorPolicy = errorPolicy
                        }
                    }
                } finally {
                    timer.cancel()
                    synchronized(this@QaLensBridgeDataTools) {
                        read.ended = SystemClock.elapsedRealtime()
                        if (current === read) expiry = scope.launch { delay(300_000); synchronized(this@QaLensBridgeDataTools) { if (current === read) current = null } }
                    }
                }
            }
            mapOf("ok" to true, "id" to read.id, "phase" to "running")
        }
        "status", "cancel" -> {
            // Copy immutable, already-masked result references under a short lock. Redaction/JSON
            // must not hold the monitor needed by stop(), which can run on the host main thread.
            val view = synchronized(this) {
                val read = current?.takeIf { it.id == text(input, "id", 128) && (it.ended == 0L || SystemClock.elapsedRealtime() - it.ended < 300_000) }
                    ?: throw QaLensBridgeFailure(404, "Query expired; run it again")
                if (input.opt("action") == "cancel" && read.phase == "running") {
                    read.reason = "Query cancelled"; read.phase = "cancelling"; scope.launch { read.signal.cancel() }
                }
                val config = QaLens.config.value
                if (read.result?.policy?.let { it != config } == true || read.errorPolicy?.let { it != config } == true) {
                    read.result = null; read.phase = "error"; read.error = "Privacy settings changed; rescan and run the query again"; read.errorPolicy = config
                }
                ReadView(read.id, read.phase, read.reason ?: read.error, read.result, config)
            }
            mapOf("ok" to true, "id" to view.id, "phase" to view.phase, "policyId" to policyId(view.policy),
                "error" to view.error?.let { view.policy.redact(it).take(500) },
                "result" to view.result?.let { mapOf("columns" to it.columns, "rows" to it.rows,
                    "durationMs" to it.durationMs, "limited" to it.limited, "omittedColumns" to it.omittedColumns) }).also { policyId(view.policy) }
        }
        "save", "delete" -> {
            val context = context()
            val config = QaLens.config.value
            // Validate/redact outside the preferences monitor also used by Control Room.
            val candidate = if (input.opt("action") == "save") {
                val query = AppSalQuery(text(input, "name", 100), database(input), text(input, "sql", DesktopSqlPolicy.MAX_SQL))
                try { DesktopSqlPolicy.bounded(query.sql) } catch (e: IllegalArgumentException) { throw QaLensBridgeFailure(400, e.message.orEmpty()) }
                if (config.redact(query.sql) != query.sql) throw QaLensBridgeFailure(400, "Saved SQL contains masked text; remove sensitive literals first")
                query
            } else null
            synchronized(QaLensAppSal) {
                val queries = QaLensAppSal.queries(context)
                val updated = if (candidate != null) {
                    if (queries.size >= 50) throw QaLensBridgeFailure(409, "Fifty saved queries already exist; remove one first")
                    if (queries.any { it.name == candidate.name }) throw QaLensBridgeFailure(409, "Name already exists; choose a new query name")
                    queries + candidate
                } else {
                    val id = text(input, "id", 128)
                    if (queries.none { queryId(it) == id }) throw QaLensBridgeFailure(409, "Saved query changed; rescan before deleting")
                    queries.filterNot { queryId(it) == id }
                }
                if (!sessionActive()) throw QaLensBridgeFailure(503, "Bridge stopped")
                policyId(config)
                QaLensAppSal.setQueries(context, updated)
            }
            catalog()
        }
        else -> throw QaLensBridgeFailure(400, "Use start, status, cancel, save or delete")
    }
}
