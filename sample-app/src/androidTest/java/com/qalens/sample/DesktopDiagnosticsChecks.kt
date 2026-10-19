package com.qalens.sample

import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.qalens.NetworkEvent
import com.qalens.QaLens
import com.qalens.QaLensControlActivity
import com.qalens.android.QaLensAppSal
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Real WAL SQLite / decoded Preferences DataStore, through the authenticated SDK protocol. */
internal class DesktopDiagnosticsChecks(private val test: Instrumentation) {
    private val context = test.targetContext
    private val source = "Desktop settings fixture"
    private val database = "qa-desktop-data.db"
    private val plain = "app-desktop-plain"
    private val encrypted = "app-desktop-envelope"
    private val port = 18766
    private val token = "synthetic-desktop-diagnostics-0123456789"
    private fun await(message: String, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < end) { if (predicate()) return; Thread.sleep(100) }
        error(message)
    }
    private fun request(path: String, command: JSONObject? = null, auth: String = token): Pair<Int, JSONObject> {
        val c = URL("http://127.0.0.1:$port/v1/$path").openConnection() as HttpURLConnection
        c.connectTimeout = 2_000; c.readTimeout = 5_000; c.setRequestProperty("Authorization", "Bearer $auth")
        if (command != null) {
            c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(command.toString().toByteArray()) }
        }
        return try { val code = c.responseCode; code to JSONObject((if (code < 400) c.inputStream else c.errorStream).use { String(it.readBytes()) }) }
        finally { c.disconnect() }
    }
    private fun sql(action: String, vararg fields: Pair<String, Any>) = request("sql", JSONObject().put("action", action).apply { fields.forEach { put(it.first, it.second) } })
    private fun read(db: String, query: String): String = sql("start", "database" to db, "sql" to query).let {
        check(it.first == 200) { it.toString() }; it.second.getString("id")
    }
    private fun complete(id: String): JSONObject {
        var response = JSONObject()
        await("Read did not finish") {
            response = sql("status", "id" to id).second
            response.optString("phase") !in listOf("running", "cancelling")
        }
        return response
    }
    private fun fixture(block: ((String) -> Unit) -> Unit) {
        val original = QaLens.config.value; val originalQueries = QaLensAppSal.queries(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File(context.filesDir, "datastore/qa-desktop-fixture.preferences_pb").apply { parentFile!!.mkdirs() }
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val theme = stringPreferencesKey("theme"); val secret = stringPreferencesKey("accessToken")
        test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        test.runOnMainSync { QaLens.configure { enabled = true; captureLogs = true; captureNetwork = true; captureNetworkBodies = true; addRedaction("desktop-sensitive") }; QaLens.closePanel() }
        val db = context.openOrCreateDatabase(database, 0, null)
        try {
            db.enableWriteAheadLogging()
            db.execSQL("CREATE TABLE IF NOT EXISTS notes (id INTEGER, label TEXT, accessToken TEXT, payload BLOB)")
            db.execSQL("DELETE FROM notes")
            db.beginTransaction()
            try {
                repeat(250) { db.execSQL("INSERT INTO notes VALUES (?, ?, ?, ?)", arrayOf(it, "fixture-row-$it", "never-show-sql-token", byteArrayOf(1, 2, 3))) }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            context.getSharedPreferences(plain, 0).edit().putString("theme", "prefs-day").putString("password", "never-show-prefs-token").commit()
            context.getSharedPreferences(encrypted, 0).edit().putString("__androidx_security_crypto_encrypted_prefs_key_keyset__", "synthetic-envelope")
                .putString("opaque", "never-show-ciphertext").commit()
            runBlocking { store.edit { it[theme] = "day"; it[secret] = "never-show-datastore-token" } }
            QaLens.observeDataStoreValues(source, store.data) { mapOf("theme" to it[theme].orEmpty(), "accessToken" to it[secret].orEmpty()) }
            await("Decoded initial value absent") { QaLens.state.value.dataSources[source]?.get("theme") == "day" }
            val update: (String) -> Unit = { value -> runBlocking { store.edit { it[theme] = value } } }
            block(update)
        } finally {
            test.runOnMainSync {
                QaLens.stopLocalBridge(); QaLens.stopObservingDataStore(source)
                QaLens.configure { enabled = original.enabled; captureLogs = original.captureLogs; captureNetwork = original.captureNetwork;
                    captureNetworkBodies = original.captureNetworkBodies; redactionRules = original.redactionRules }
            }
            QaLensAppSal.setQueries(context, originalQueries)
            scope.cancel(); db.close(); context.deleteDatabase(database); file.delete()
            for (name in listOf(plain, encrypted)) { context.getSharedPreferences(name, 0).edit().clear().commit(); if (Build.VERSION.SDK_INT >= 24) context.deleteSharedPreferences(name) }
        }
    }

    fun run() = fixture { update ->
        test.runOnMainSync { QaLens.startLocalBridge(token, port) }
        await("Bridge did not start") { runCatching { request("data").first == 200 }.getOrDefault(false) }
        check(request("sql", auth = "invalid").first == 401)
        val data = request("data").second
        check(data.getJSONObject("dataSources").getJSONObject(source).getString("theme") == "day")
        check(!data.toString().contains("never-show-datastore-token"))
        test.startActivitySync(Intent(context, QaLensControlActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        update("night")
        await("Desktop values stalled with Control Room foreground") { request("data").second.getJSONObject("dataSources").getJSONObject(source).getString("theme") == "night" }
        check(request("data").second.getJSONObject("sourceStatus").getJSONObject(source).getString("phase") == "live")

        QaLens.log("[ERROR] Desktop fixture desktop-sensitive")
        QaLens.logNetwork(NetworkEvent(method = "GET", url = "https://fixture.invalid/desktop", status = 503,
            latencyMs = 650, error = "desktop-sensitive", requestBodyPreview = "desktop-sensitive", responseBodyPreview = "synthetic-body"))
        await("Live log absent") { request("events").second.toString().contains("Desktop fixture") }
        val observations = request("events").second
        check(!observations.toString().contains("desktop-sensitive"))
        val network = observations.getJSONArray("network").let { list -> (0 until list.length()).map { list.getJSONObject(it) }.first { it.getString("url").contains("/desktop") } }
        check(network.getInt("status") == 503 && network.getLong("time") > 0 && network.getLong("durationMs") == 650L)
        check(network.getString("responsePreview") == "synthetic-body")

        val files = request("data", JSONObject().put("action", "files")).second
        val prefs = files.getJSONArray("preferences")
        fun prefId(name: String) = (0 until prefs.length()).map { prefs.getJSONObject(it) }.first { it.getString("name") == name }.getString("id")
        check((0 until prefs.length()).none { prefs.getJSONObject(it).getString("name").startsWith("qalens", true) })
        val readableReply = request("data", JSONObject().put("action", "preferences").put("id", prefId(plain)))
        check(readableReply.first == 200) { "Preference reply: $readableReply" }
        val readable = readableReply.second
        check(readable.getJSONObject("values").getString("theme") == "prefs-day" && !readable.toString().contains("never-show-prefs-token"))
        val envelope = request("data", JSONObject().put("action", "preferences").put("id", prefId(encrypted))).second
        check(envelope.getBoolean("encrypted") && envelope.getJSONObject("values").length() == 0 && !envelope.toString().contains("never-show-ciphertext"))
        check(files.toString().contains("qa-desktop-fixture.preferences_pb"))
        check(request("data", JSONObject().put("action", "preferences").put("id", "../../qalens_prefs")).first == 404)

        val catalog = request("sql").second
        val databases = catalog.getJSONArray("databases")
        val db = (0 until databases.length()).map { databases.getJSONObject(it) }.first { it.getString("name") == database }.getString("id")
        val result = complete(read(db, "SELECT id, label, accessToken, payload FROM notes ORDER BY id"))
        check(result.getString("phase") == "complete") { result.toString() }
        val preview = result.getJSONObject("result")
        check(preview.getJSONArray("rows").length() == 100 && preview.getBoolean("limited"))
        check(!result.toString().contains("never-show-sql-token") && result.toString().contains("[binary value]"))
        for (query in listOf("UPDATE notes SET label='changed'", "DELETE FROM notes", "PRAGMA user_version=1", "ATTACH '/tmp/other' AS other", "DROP TABLE notes"))
            check(sql("start", "database" to db, "sql" to query).first == 400)
        for (query in listOf("SELECT * FROM notes; DELETE FROM notes", "WITH x AS (SELECT 1) DELETE FROM notes"))
            check(complete(read(db, query)).getString("phase") == "error")
        check(complete(read(db, "SELECT COUNT(*) AS count FROM notes")).getJSONObject("result").getJSONArray("rows").getJSONArray(0).getString(0) == "250")
        check(sql("start", "database" to "../../other", "sql" to "SELECT 1").first == 404)

        val savedName = "Desktop read fixture"
        check(sql("save", "name" to savedName, "database" to db, "sql" to "SELECT 42 AS answer").first == 200)
        check(QaLensAppSal.queries(context).any { it.name == savedName && it.db == database })
        check(sql("save", "name" to savedName, "database" to db, "sql" to "SELECT 42").first == 409)
        val saved = request("sql").second.getJSONArray("saved").let { list -> (0 until list.length()).map { list.getJSONObject(it) }.first { it.getString("name") == savedName } }
        check(sql("delete", "id" to saved.getString("id")).first == 200)
        check(QaLensAppSal.queries(context).none { it.name == savedName })
        check(sql("delete", "id" to saved.getString("id")).first == 409)

        val slow = "WITH RECURSIVE seq(n) AS (VALUES(1) UNION ALL SELECT n+1 FROM seq WHERE n<1000000000) SELECT SUM(n) FROM seq"
        val running = read(db, slow)
        check(sql("start", "database" to db, "sql" to "SELECT 1").first == 409)
        val start = SystemClock.elapsedRealtime(); check(request("events").first == 200)
        check(SystemClock.elapsedRealtime() - start < 2_000) { "SQL blocked live observations" }
        val mainStart = SystemClock.elapsedRealtime(); test.runOnMainSync { check(QaLens.config.value.enabled) }
        check(SystemClock.elapsedRealtime() - mainStart < 1_500) { "SQL blocked the main thread" }
        check(sql("cancel", "id" to running).first == 200)
        check(complete(running).getString("phase") == "cancelled")
        val timed = complete(read(db, slow))
        check(timed.getString("phase") == "cancelled" && timed.getString("error").contains("ten seconds"))

        val old = read(db, "SELECT 'allowed-value' AS value"); check(complete(old).getString("phase") == "complete")
        val oldPolicy = request("data").second.getLong("policyId")
        test.runOnMainSync { QaLens.configure { addRedaction("allowed-value") } }
        check(sql("status", "id" to old).second.getString("phase") == "error")
        check(request("data").second.getLong("policyId") != oldPolicy)
        val stopped = read(db, slow)
        test.runOnMainSync { QaLens.configure { enabled = false } }
        check(runCatching { request("data").first == 200 }.getOrDefault(false).not())
        test.runOnMainSync { QaLens.configure { enabled = true }; QaLens.startLocalBridge(token, port) }
        await("Bridge did not resume") { runCatching { request("data").first == 200 }.getOrDefault(false) }
        check(sql("status", "id" to stopped).first == 404)
        check(complete(read(db, "SELECT 42 AS answer")).getString("phase") == "complete")
    }

    /** Browser fixture owns a real DataStore; normal app remains interactive, no test SDK code. */
    fun holdGui(seconds: Int) = fixture { update ->
        val end = SystemClock.uptimeMillis() + seconds.coerceIn(1, 600) * 1_000L
        var count = 0
        while (SystemClock.uptimeMillis() < end) {
            update(if (count++ % 2 == 0) "night" else "day")
            QaLens.log("${if (count % 3 == 0) "[ERROR]" else "[INFO]"} Desktop fixture update $count")
            QaLens.logNetwork(NetworkEvent(method = "GET", url = "https://fixture.invalid/desktop/$count", status = if (count % 3 == 0) 503 else 200, latencyMs = 300))
            Thread.sleep(8_000)
        }
    }
}
