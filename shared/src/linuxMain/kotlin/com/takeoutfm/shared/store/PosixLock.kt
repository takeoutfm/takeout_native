@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.takeoutfm.shared.store

import kotlinx.cinterop.alloc
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock

/** A mutex for code that can't suspend (the stores are called from any thread). Never freed. */
internal class PosixLock {
    private val mutex = nativeHeap.alloc<pthread_mutex_t>().also { pthread_mutex_init(it.ptr, null) }

    inline fun <T> locked(block: () -> T): T {
        pthread_mutex_lock(mutex.ptr)
        try {
            return block()
        } finally {
            pthread_mutex_unlock(mutex.ptr)
        }
    }
}
