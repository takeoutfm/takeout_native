package com.takeoutfm.shared.db

import app.cash.sqldelight.driver.native.inMemoryDriver

actual fun testDb(): TakeoutDb = TakeoutDb(inMemoryDriver(TakeoutDb.Schema))
