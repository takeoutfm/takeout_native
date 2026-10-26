package com.takeoutfm.mobile.playback

import com.takeoutfm.media.offline.TakeoutDownloadService
import com.takeoutfm.media.offline.TakeoutMediaCache
import com.takeoutfm.mobile.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** The phone app's background downloads ([TakeoutDownloadService] with Hilt's cache). */
@AndroidEntryPoint
class DownloadService : TakeoutDownloadService() {
    @Inject public override lateinit var mediaCache: TakeoutMediaCache
    override val smallIcon = R.drawable.ic_download
}
