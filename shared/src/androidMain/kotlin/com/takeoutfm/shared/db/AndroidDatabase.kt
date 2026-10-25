package com.takeoutfm.shared.db

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

object AndroidDatabase {
    /** The app's on-device database ("takeout.db"). */
    fun open(context: Context): TakeoutDb =
        TakeoutDb(AndroidSqliteDriver(TakeoutDb.Schema, context, "takeout.db"))
}
