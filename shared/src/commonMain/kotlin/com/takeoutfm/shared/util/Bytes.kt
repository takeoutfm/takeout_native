package com.takeoutfm.shared.util

import kotlin.math.roundToLong

private const val KB = 1L shl 10
private const val MB = 1L shl 20
private const val GB = 1L shl 30

/** Bytes as "12 MB" / "1.4 GB" (no `String.format` in common code). */
fun formatBytes(bytes: Long): String = when {
    bytes >= GB -> (bytes * 10.0 / GB).roundToLong().let { "${it / 10}.${it % 10} GB" }
    bytes >= MB -> "${bytes / MB} MB"
    else -> "${(bytes / KB).coerceAtLeast(1)} KB"
}
