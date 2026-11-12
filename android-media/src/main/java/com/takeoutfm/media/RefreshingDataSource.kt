package com.takeoutfm.media

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener

/**
 * Opens through [inner] (which goes straight to a kept storage URL), and when storage refuses
 * it (400 or 403: expired, or a clock off), forgets it in [redirects] and opens once more with a
 * fresh redirect from Takeout.
 */
@OptIn(UnstableApi::class)
class RefreshingDataSource(
    private val inner: DataSource,
    private val redirects: RedirectCache,
) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) = inner.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long = try {
        inner.open(dataSpec)
    } catch (e: HttpDataSource.InvalidResponseCodeException) {
        if (e.responseCode != 400 && e.responseCode != 403) throw e
        redirects.forget(dataSpec.uri.toString())
        inner.close()
        inner.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = inner.read(buffer, offset, length)

    override fun getUri(): Uri? = inner.uri

    override fun getResponseHeaders(): Map<String, List<String>> = inner.responseHeaders

    override fun close() = inner.close()

    class Factory(private val inner: DataSource.Factory, private val redirects: RedirectCache) : DataSource.Factory {
        override fun createDataSource(): DataSource = RefreshingDataSource(inner.createDataSource(), redirects)
    }
}
