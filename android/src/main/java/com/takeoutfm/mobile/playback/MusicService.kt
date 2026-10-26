package com.takeoutfm.mobile.playback

import com.takeoutfm.media.TakeoutMusicService
import com.takeoutfm.media.TakeoutPlayerFactory
import com.takeoutfm.media.auto.TakeoutBrowseTree
import com.takeoutfm.media.offline.TakeoutMediaCache
import com.takeoutfm.mobile.MainActivity
import com.takeoutfm.shared.listen.ListenReporter
import com.takeoutfm.shared.music.MusicHistory
import com.takeoutfm.shared.music.StreamHistory
import com.takeoutfm.shared.progress.TakeoutProgress
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** The phone app's background music service ([TakeoutMusicService] with Hilt's singletons). */
@AndroidEntryPoint
class MusicService : TakeoutMusicService() {
    @Inject public override lateinit var playerFactory: TakeoutPlayerFactory
    @Inject public override lateinit var listenReporter: ListenReporter
    @Inject public override lateinit var history: MusicHistory
    @Inject public override lateinit var streamHistory: StreamHistory
    @Inject public override lateinit var progress: TakeoutProgress
    @Inject public override lateinit var mediaCache: TakeoutMediaCache
    @Inject public override lateinit var browseTree: TakeoutBrowseTree
    override val sessionActivity = MainActivity::class.java
}
