package com.takeoutfm.wear

import android.app.Application
import androidx.wear.tiles.TileService
import com.takeoutfm.media.MusicPlayer
import com.takeoutfm.wear.tile.NowPlayingComplicationService
import com.takeoutfm.wear.tile.PlayerTileService
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@HiltAndroidApp
class WatchApp : Application() {
    @Inject lateinit var player: MusicPlayer

    override fun onCreate() {
        super.onCreate()
        // Tiles and complications show their last value until asked for a new one: ask whenever
        // what's playing or play/pause changes.
        MainScope().launch {
            player.state
                .map { Triple(it.current?.title, it.streamTitle, it.playWhenReady) }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    TileService.getUpdater(this@WatchApp).requestUpdate(PlayerTileService::class.java)
                    NowPlayingComplicationService.requestUpdate(this@WatchApp)
                }
        }
    }
}
