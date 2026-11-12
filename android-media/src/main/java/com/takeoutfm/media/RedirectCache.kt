package com.takeoutfm.media

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Where Takeout's `/location` URLs redirect, kept a while. A stream's `/location` answers with a
 * redirect to a presigned storage URL, and ExoPlayer opens a file several times to start it (the
 * header, an MKV's index near the end, the position to play from) and again on each seek; each
 * open went back to Takeout for a fresh redirect, a round trip before every read (and slower
 * tuning between channels). Now the first open follows the redirect and later ones go straight
 * to storage, until shortly before the presigned URL expires (it says when, in its X-Amz-Date
 * and X-Amz-Expires) and never for more than [maxReuseMs], so a seek after a long pause asks
 * Takeout again.
 *
 * [follow] asks for [uri] with [authorization] and returns where it redirects, or null when it
 * doesn't (a file served directly): those aren't kept.
 */
class RedirectCache(
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxReuseMs: Long = MaxReuseMs,
    private val follow: (uri: String, authorization: String) -> String? = ::followRedirect,
) {
    private class Entry(val target: String, val untilMs: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** Where [uri] redirects now, asking only when what's kept is missing or about to expire. */
    @Throws(IOException::class)
    fun target(uri: String, authorization: String): String? {
        val now = clock()
        entries[uri]?.takeIf { it.untilMs > now }?.let { return it.target }
        val target = follow(uri, authorization) ?: return null
        val until = minOf(now + maxReuseMs, expiresAt(target)?.minus(MarginMs) ?: Long.MAX_VALUE)
        if (until > now) entries[uri] = Entry(target, until)
        // what's expired
        if (entries.size > MaxEntries) entries.entries.removeIf { it.value.untilMs <= now }
        return target
    }

    /** Forgets [uri]'s target, so the next open asks again (storage refused it). */
    fun forget(uri: String) {
        entries.remove(uri)
    }

    companion object {
        const val MaxReuseMs = 10 * 60 * 1000L

        /** A target is used no later than this before its URL says it expires. */
        const val MarginMs = 60 * 1000L
        private const val MaxEntries = 64
        private val amzDate = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

        /** When a presigned S3 URL stops working (epoch ms), or null if it doesn't say. */
        fun expiresAt(url: String): Long? {
            val query = runCatching { URI(url).rawQuery }.getOrNull() ?: return null
            val params = query.split('&').associate { p ->
                val i = p.indexOf('=')
                if (i < 0) p to "" else p.substring(0, i) to p.substring(i + 1)
            }
            val date = params["X-Amz-Date"] ?: return null
            val seconds = params["X-Amz-Expires"]?.toLongOrNull() ?: return null
            val signed = runCatching { LocalDateTime.parse(date, amzDate).toInstant(ZoneOffset.UTC) }.getOrNull()
                ?: return null
            return signed.toEpochMilli() + seconds * 1000
        }

        /** Asks for [uri] without following its redirect: the target, or null if it isn't one. */
        @Throws(IOException::class)
        fun followRedirect(uri: String, authorization: String): String? {
            val connection = URL(uri).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = TimeoutMs
                connection.readTimeout = TimeoutMs
                connection.setRequestProperty("Authorization", authorization)
                // only the redirect is wanted, not the file
                connection.setRequestProperty("Range", "bytes=0-0")
                val code = connection.responseCode
                if (code !in 300..399) return null
                val location = connection.getHeaderField("Location") ?: return null
                return URI(uri).resolve(location).toString()
            } finally {
                connection.disconnect()
            }
        }

        private const val TimeoutMs = 15_000
    }
}
