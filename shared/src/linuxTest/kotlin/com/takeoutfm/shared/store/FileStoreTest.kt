@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.takeoutfm.shared.store

import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.mkdtemp
import platform.posix.remove
import platform.posix.rmdir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileStoreTest {
    private fun tempDir(): String = memScoped {
        mkdtemp("/tmp/takeout-store-XXXXXX".cstr.getPointer(this))!!.toKString()
    }

    @Test
    fun valuesSurviveANewStoreOnTheSameFile() {
        val dir = tempDir()
        try {
            FileStore(dir, "prefs").apply {
                putStrings(mapOf("a" to "1", "quote" to "say \"hi\"\nnext"))
                remove("a")
            }
            val reopened = FileStore(dir, "prefs")
            assertNull(reopened.getString("a"))
            assertEquals("say \"hi\"\nnext", reopened.getString("quote"))
        } finally {
            remove("$dir/prefs.json")
            rmdir(dir)
        }
    }
}
