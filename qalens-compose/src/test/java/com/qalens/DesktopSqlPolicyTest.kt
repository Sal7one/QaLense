package com.qalens

import org.junit.Assert.*
import org.junit.Test

class DesktopSqlPolicyTest {
    @Test fun acceptsBoundedReadsAndCtes() {
        assertEquals("SELECT * FROM (SELECT 42) LIMIT 101", DesktopSqlPolicy.bounded(" SELECT 42; "))
        assertTrue(DesktopSqlPolicy.bounded("WITH x AS (SELECT 1) SELECT * FROM x").endsWith("LIMIT 101"))
    }
    @Test fun rejectsWritesPragmasAttachmentsAndOversizedCommands() {
        for (query in listOf("", "DELETE FROM users", "UPDATE users SET a=0", "INSERT INTO users VALUES(1)",
            "REPLACE INTO users VALUES(1)", "PRAGMA journal_mode=OFF", "ATTACH 'file' AS other", "VACUUM", "SELECT " + "x".repeat(4096))) {
            assertThrows(IllegalArgumentException::class.java) { DesktopSqlPolicy.bounded(query) }
        }
    }
}
