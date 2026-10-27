package com.takeoutfm.watch.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.WearUnsuitableOutputPlaybackSuppressionResolverListener
import com.takeoutfm.media.TakeoutMusicService
import com.takeoutfm.media.TakeoutPlayerFactory
import com.takeoutfm.media.offline.TakeoutMediaCache
import com.takeoutfm.shared.listen.ListenReporter
import com.takeoutfm.shared.music.MusicHistory
import com.takeoutfm.shared.music.StreamHistory
import com.takeoutfm.shared.progress.TakeoutProgress
import com.takeoutfm.watch.BuildConfig
import com.takeoutfm.watch.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** The watch's background music service ([TakeoutMusicService] with Hilt's singletons). */
@AndroidEntryPoint
class MusicService : TakeoutMusicService() {
    @Inject public override lateinit var playerFactory: TakeoutPlayerFactory
    @Inject public override lateinit var listenReporter: ListenReporter
    @Inject public override lateinit var history: MusicHistory
    @Inject public override lateinit var streamHistory: StreamHistory
    @Inject public override lateinit var progress: TakeoutProgress
    @Inject public override lateinit var mediaCache: TakeoutMediaCache
    override val sessionActivity = MainActivity::class.java

    /**
     * On a watch, music should play to headphones, not the speaker: until one's connected the
     * player holds playback ([onPlayerCreated] opens the system's output picker). Debug builds
     * skip this so the emulator (no Bluetooth) can play.
     */
    @OptIn(UnstableApi::class)
    override fun configure(builder: ExoPlayer.Builder): ExoPlayer.Builder =
        builder.setSuppressPlaybackOnUnsuitableOutput(!BuildConfig.DEBUG)

    /** Held for want of headphones: the system's output picker, to connect some. */
    @OptIn(UnstableApi::class)
    override fun onPlayerCreated(player: ExoPlayer) {
        player.addListener(WearUnsuitableOutputPlaybackSuppressionResolverListener(this))
    }
}
