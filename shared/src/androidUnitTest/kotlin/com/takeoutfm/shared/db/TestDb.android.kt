package com.takeoutfm.shared.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

actual fun testDb(): TakeoutDb =
    TakeoutDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { TakeoutDb.Schema.create(it) })
