package com.takeoutfm.shared.db

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration

object AppleDatabase {
    /** The app's on-device database, "takeout.db" in [directory] (which must exist). */
    fun open(directory: String): TakeoutDb = TakeoutDb(
        NativeSqliteDriver(
            TakeoutDb.Schema,
            "takeout.db",
            onConfiguration = { it.copy(extendedConfig = DatabaseConfiguration.Extended(basePath = directory)) },
        )
    )
}
