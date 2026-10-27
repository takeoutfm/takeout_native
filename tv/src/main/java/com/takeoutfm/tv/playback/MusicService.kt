package com.takeoutfm.tv.playback

import com.takeoutfm.media.TakeoutMusicService
import com.takeoutfm.media.TakeoutPlayerFactory
import com.takeoutfm.shared.listen.ListenReporter
import com.takeoutfm.shared.music.MusicHistory
import com.takeoutfm.shared.progress.TakeoutProgress
import com.takeoutfm.tv.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** The TV app's background music service ([TakeoutMusicService] with Hilt's singletons). */
@AndroidEntryPoint
class MusicService : TakeoutMusicService() {
    @Inject public override lateinit var playerFactory: TakeoutPlayerFactory
    @Inject public override lateinit var listenReporter: ListenReporter
    @Inject public override lateinit var history: MusicHistory

    /** Podcast episodes' positions (shared with the phone), saved as they play. */
    @Inject public override lateinit var progress: TakeoutProgress
    override val sessionActivity = MainActivity::class.java
}
