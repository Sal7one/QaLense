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
        val totalRows: Int = 0,            // rows returned, capped at MAX_ROWS
        val durationMs: Long = 0,
        val error: String? = null
    )

    data class DesktopResult(
        val columns: List<String>, val rows: List<List<String>>, val durationMs: Long,
        val limited: Boolean, val omittedColumns: Int, val policy: QaLensConfig
    )

    /** A distinct connection: PC access can never use the Control Room write path. Runs on IO. */
    fun desktopQuery(context: Context, dbName: String, sql: String,
        cancellation: android.os.CancellationSignal): DesktopResult {
        val query = DesktopSqlPolicy.bounded(sql)
        require(dbName in databases(context)) { "Database unavailable; rescan databases" }
        val file = context.getDatabasePath(dbName)
        val directory = context.getDatabasePath("qalens-path-check.db").parentFile?.canonicalFile
        require(file.name == dbName && file.isFile && file.canonicalFile.parentFile == directory) { "Database path is unavailable" }
        val start = android.os.SystemClock.elapsedRealtime()
        cancellation.throwIfCanceled()
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            check(db.isReadOnly) { "Read-only database unavailable" }
            db.rawQuery(query, null, cancellation).use { cursor ->
                val config = QaLens.config.value
                val rawColumns = cursor.columnNames.take(30)
                val columns = rawColumns.map { config.redact(it).take(200) }
                val rows = mutableListOf<List<String>>()
                var remaining = 60_000
                var limited = false
                while (cursor.moveToNext()) {
                    cancellation.throwIfCanceled()
                    if (rows.size == 100 || remaining <= 0) { limited = true; break }
                    val values = rawColumns.indices.map { i ->
                        val raw = when (cursor.getType(i)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> "NULL"
                            android.database.Cursor.FIELD_TYPE_BLOB -> "[binary value]"
                            else -> cursor.getString(i).orEmpty()
                        }
                        // Mask using the original column name before any preview truncation.
                        val safe = DataValuePreview.sanitize(mapOf(rawColumns[i] to raw), config).values.first()
                        val budget = minOf(512, remaining).coerceAtLeast(0)
                        val cell = if (safe.length > budget) { limited = true; safe.take(budget) + "…" } else safe
                        remaining -= minOf(budget, safe.length)
                        cell
                    }
                    rows += values
                }
                QaLens.breadcrumb("Desktop SQL read: ${rows.size} preview rows")
                DesktopResult(columns, rows, android.os.SystemClock.elapsedRealtime() - start,
                    limited, (cursor.columnCount - columns.size).coerceAtLeast(0), config)
            }
        }
    }

    /** The app's SQLite databases (journal/wal/shm side-files filtered out). */
    fun databases(context: Context): List<String> =
        context.databaseList()
            .filterNot { it.endsWith("-journal") || it.endsWith("-shm") || it.endsWith("-wal") }
            .sorted()

    fun runQuery(context: Context, dbName: String, sql: String, cancellation: android.os.CancellationSignal = android.os.CancellationSignal()): QueryResult {
        val trimmed = sql.trim().trimEnd(';')
        if (trimmed.isBlank()) return QueryResult(error = "Empty query")
        val dbFile = context.getDatabasePath(dbName)
        if (!dbFile.exists()) return QueryResult(error = "Database not found: $dbName")

        val start = System.currentTimeMillis()
        return try {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                val head = trimmed.takeWhile { !it.isWhitespace() }.lowercase()
                if (head in setOf("select", "pragma", "with", "explain")) {
                    // SQLiteCursor may count the entire result while filling its first window.
                    // Limit in SQL as well as in the consumer loop so huge reads stop in SQLite.
                    val bounded = if (head == "select" || head == "with") "SELECT * FROM ($trimmed) LIMIT $MAX_ROWS" else trimmed
                    db.rawQuery(bounded, null, cancellation).use { c ->
                        val cols = c.columnNames.toList()
                        val rows = mutableListOf<List<String>>()
                        var total = 0
                        while (rows.size < MAX_ROWS && c.moveToNext()) {
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
                    cancellation.throwIfCanceled()
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
                // Redact full values in the caller before truncating their displayed preview.
                .mapValues { (_, v) -> v.toString() }
                .toSortedMap()
        }.getOrDefault(emptyMap())

    // ── DataStore ─────────────────────────────────────────────────────────────

    /**
     * DataStore files (name + size). The app owns its serializer and any encryption;
     * decoded values come through its `QaLens.observeDataStoreValues` hook or cached provider.
     */
    fun dataStoreFiles(context: Context): List<Pair<String, Long>> =
        File(context.filesDir, "datastore")
            .listFiles()
            ?.map { it.name to it.length() }
            ?.sortedBy { it.first }
            ?: emptyList()
}
