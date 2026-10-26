package com.takeoutfm.shared.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals

class MigrationTest {
    @Test
    fun aVersionOneDatabaseGainsStreamHistory() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // A version 1 database (before stream history), then the migrations.
        listOf(
            "CREATE TABLE progress (etag TEXT NOT NULL PRIMARY KEY, position INTEGER NOT NULL, duration INTEGER NOT NULL, date TEXT NOT NULL, saved_at INTEGER NOT NULL, pending INTEGER NOT NULL DEFAULT 1)",
            "INSERT INTO progress VALUES ('e1', 60, 3600, '2026-10-25T00:00:00Z', 1, 1)",
        ).forEach { driver.execute(null, it, 0) }
        TakeoutDb.Schema.migrate(driver, 1, TakeoutDb.Schema.version)

        val db = TakeoutDb(driver)
        db.streamHistoryQueries.insert("BAGeL Radio", "", "Beck - Loser", 1)
        assertEquals(1, db.streamHistoryQueries.all().executeAsList().size)
        assertEquals(listOf("e1"), db.progressQueries.all().executeAsList().map { it.etag }) // kept
        assertEquals(2L, TakeoutDb.Schema.version)
    }
}
