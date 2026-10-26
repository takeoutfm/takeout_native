package com.takeoutfm.media.offline

import android.app.Notification
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler

/**
 * Runs downloads in the background with a progress notification. Each app subclasses it
 * (declared in its manifest) to supply its [TakeoutMediaCache] and notification icon.
 */
@OptIn(UnstableApi::class)
abstract class TakeoutDownloadService : DownloadService(
    NotificationId,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    ChannelId,
    com.takeoutfm.media.R.string.downloads_channel,
    0,
) {
    protected abstract val mediaCache: TakeoutMediaCache
    protected abstract val smallIcon: Int

    private val notifications by lazy { DownloadNotificationHelper(this, ChannelId) }

    override fun getDownloadManager(): DownloadManager = mediaCache.downloads

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification =
        notifications.buildProgressNotification(this, smallIcon, null, null, downloads, notMetRequirements)

    private companion object {
        const val NotificationId = 4401
        const val ChannelId = "downloads"
    }
}
