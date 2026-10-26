package com.takeoutfm.mobile.playback

import com.takeoutfm.media.TakeoutMusicService
import com.takeoutfm.media.TakeoutPlayerFactory
import com.takeoutfm.mobile.MainActivity
import com.takeoutfm.shared.listen.ListenReporter
import com.takeoutfm.shared.music.StreamHistory
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** The phone app's background music service ([TakeoutMusicService] with Hilt's singletons). */
@AndroidEntryPoint
class MusicService : TakeoutMusicService() {
    @Inject public override lateinit var playerFactory: TakeoutPlayerFactory
    @Inject public override lateinit var listenReporter: ListenReporter
    @Inject public override lateinit var streamHistory: StreamHistory
    override val sessionActivity = MainActivity::class.java
}
