package com.qalens

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * QA-facing data tooling: raw SQL into the app's own SQLite/Room databases (Room DBs are plain
 * SQLite files, so this needs NO Room dependency), plus SharedPreferences / DataStore visibility.
 *
 * Writes report exactly how many rows got affected, and every execution drops a breadcrumb into
 * the QaLens timeline — so anything QA changed is part of the session evidence (and any `.sal`
 * being recorded at the time). Debug builds only, the app's own sandbox only.
 */
internal object QaLensDataTools {

    private const val MAX_ROWS = 100
    private const val MAX_CELL = 80

    data class QueryResult(
        val columns: List<String> = emptyList(),
        val rows: List<List<String>> = emptyList(),
        val rowsAffected: Int = -1,        // -1 = was a read
        val totalRows: Int = 0,            // before the MAX_ROWS cap
        val durationMs: Long = 0,
        val error: String? = null
    )

    /** The app's SQLite databases (journal/wal/shm side-files filtered out). */
    fun databases(context: Context): List<String> =
        context.databaseList()
            .filterNot { it.endsWith("-journal") || it.endsWith("-shm") || it.endsWith("-wal") }
            .sorted()

    fun runQuery(context: Context, dbName: String, sql: String): QueryResult {
        val trimmed = sql.trim().trimEnd(';')
        if (trimmed.isBlank()) return QueryResult(error = "Empty query")
        val dbFile = context.getDatabasePath(dbName)
        if (!dbFile.exists()) return QueryResult(error = "Database not found: $dbName")

        val start = System.currentTimeMillis()
        return try {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                val head = trimmed.substringBefore(' ').lowercase()
                if (head in setOf("select", "pragma", "with", "explain")) {
                    db.rawQuery(trimmed, null).use { c ->
                        val cols = c.columnNames.toList()
                        val rows = mutableListOf<List<String>>()
                        var total = 0
                        while (c.moveToNext()) {
                            total++
                            if (rows.size < MAX_ROWS) {
                                rows += cols.indices.map { i ->
                                    (runCatching { c.getString(i) }.getOrNull() ?: "NULL").take(MAX_CELL)
                                }
                            }
                        }
                        QaLens.breadcrumb("SQL read [$dbName]: ${trimmed.take(80)} → $total rows")
                        QueryResult(cols, rows, -1, total, System.currentTimeMillis() - start)
                    }
                } else {
                    // Write path — report exactly what got affected.
                    val affected = when (head) {
                        "update", "delete" -> db.compileStatement(trimmed).use { it.executeUpdateDelete() }
                        "insert", "replace" -> db.compileStatement(trimmed).use {
                            if (it.executeInsert() >= 0) 1 else 0
                        }
                        else -> { db.execSQL(trimmed); 0 }
                    }
                    QaLens.breadcrumb("SQL write [$dbName]: ${trimmed.take(80)} → $affected rows affected")
                    QueryResult(rowsAffected = affected, durationMs = System.currentTimeMillis() - start)
                }
            }
        } catch (e: Exception) {
            QueryResult(error = e.message ?: e.javaClass.simpleName, durationMs = System.currentTimeMillis() - start)
        }
    }

    // ── SharedPreferences ─────────────────────────────────────────────────────

    /** SharedPreferences file names (without .xml). */
    fun sharedPrefsFiles(context: Context): List<String> =
        File(context.applicationInfo.dataDir, "shared_prefs")
            .listFiles { f -> f.name.endsWith(".xml") }
            ?.map { it.name.removeSuffix(".xml") }
            ?.sorted()
            ?: emptyList()

    fun readSharedPrefs(context: Context, name: String): Map<String, String> =
        runCatching {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all
                .mapValues { (_, v) -> v.toString().take(120) }
                .toSortedMap()
        }.getOrDefault(emptyMap())

    // ── DataStore ─────────────────────────────────────────────────────────────

    /**
     * DataStore files (name + size). Values are protobuf — not parseable without a dependency;
     * live values come through the app's `QaLens.observeDataStore`/`registerDataSource` hooks.
     */
    fun dataStoreFiles(context: Context): List<Pair<String, Long>> =
        File(context.filesDir, "datastore")
            .listFiles()
            ?.map { it.name to it.length() }
            ?.sortedBy { it.first }
            ?: emptyList()
}
