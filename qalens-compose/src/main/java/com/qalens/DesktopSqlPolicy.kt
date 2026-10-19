package com.qalens

/** Desktop accepts result-producing reads only; the database connection also enforces read-only. */
internal object DesktopSqlPolicy {
    const val MAX_SQL = 4_096
    fun bounded(sql: String): String {
        require(sql.length <= MAX_SQL) { "SQL exceeds 4,096 characters" }
        val query = sql.trim().removeSuffix(";").trimEnd()
        val head = query.takeWhile { it.isLetter() }.lowercase()
        require(head in setOf("select", "with")) { "Desktop SQL is read-only. Use SELECT or WITH … SELECT." }
        // Wrapping rejects multi-statements, PRAGMAs, ATTACH and WITH … DELETE/UPDATE.
        // A connection opened OPEN_READONLY provides a second, independent write barrier.
        return "SELECT * FROM ($query) LIMIT 101"
    }
}
