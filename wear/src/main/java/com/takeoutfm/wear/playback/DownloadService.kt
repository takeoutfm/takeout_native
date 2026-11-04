package com.takeoutfm.wear.playback

import com.takeoutfm.media.offline.TakeoutDownloadService
import com.takeoutfm.media.offline.TakeoutMediaCache
import com.takeoutfm.wear.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Downloads to the watch for offline listening. */
@AndroidEntryPoint
class DownloadService : TakeoutDownloadService() {
    @Inject public override lateinit var mediaCache: TakeoutMediaCache
    override val smallIcon = R.drawable.ic_download
}
