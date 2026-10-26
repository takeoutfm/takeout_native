package com.takeoutfm.watch.tile

import android.content.ComponentName
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.TypeBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.takeoutfm.media.MusicPlayer
import com.takeoutfm.watch.MainActivity
import com.takeoutfm.watch.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The TakeoutFM tile: what's playing, a play/pause button, and a tap on the title opens the
 * app. Refreshed after play/pause so the button follows.
 */
@AndroidEntryPoint
class PlayerTileService : TileService() {
    @Inject lateinit var player: MusicPlayer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onTileRequest(request: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> = scope.future {
        // A tile can be the first thing in a fresh process: wait for the player's state.
        withTimeoutOrNull(3_000) { player.awaitConnected() }
        tile(request)
    }

    private fun tile(request: RequestBuilders.TileRequest): TileBuilders.Tile {
        val state = player.state.value
        val toggled = request.currentState.lastClickableId == ToggleId
        if (toggled) {
            player.playPause()
            scope.launch {
                delay(1_500)
                getUpdater(this@PlayerTileService).requestUpdate(PlayerTileService::class.java)
            }
        }
        val item = state.current
        // Just tapped: show what it's about to be (the player answers a moment later).
        val playing = if (toggled) !state.playWhenReady else state.playWhenReady
        val title = item?.let { state.streamTitle ?: it.title } ?: getString(R.string.tap_to_open)
        val artist = item?.artist.orEmpty()

        val open = ModifiersBuilders.Clickable.Builder()
            .setId("open")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(ComponentName(this, MainActivity::class.java).className)
                            .build()
                    )
                    .build()
            )
            .build()
        val toggle = ModifiersBuilders.Clickable.Builder()
            .setId(ToggleId)
            .setOnClick(ActionBuilders.LoadAction.Builder().build())
            .build()

        val layout = LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .addContent(text(getString(R.string.app_name), 12f, 0xFFA8C7FA.toInt()))
                    .addContent(spacer(6f))
                    .addContent(
                        LayoutElementBuilders.Column.Builder()
                            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(open).build())
                            .addContent(text(title, 16f, 0xFFFFFFFF.toInt(), maxLines = 2))
                            .addContent(text(artist, 12f, 0xFFBDBDBD.toInt()))
                            .build()
                    )
                    .addContent(spacer(10f))
                    .apply {
                        if (item != null) {
                            addContent(
                                LayoutElementBuilders.Box.Builder()
                                    .setWidth(dp(52f))
                                    .setHeight(dp(52f))
                                    .setModifiers(
                                        ModifiersBuilders.Modifiers.Builder()
                                            .setClickable(toggle)
                                            .setBackground(
                                                ModifiersBuilders.Background.Builder()
                                                    .setColor(argb(0xFFA8C7FA.toInt()))
                                                    .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(26f)).build())
                                                    .build()
                                            )
                                            .build()
                                    )
                                    .addContent(
                                        LayoutElementBuilders.Image.Builder()
                                            .setResourceId(if (playing) PauseIcon else PlayIcon)
                                            .setWidth(dp(28f))
                                            .setHeight(dp(28f))
                                            .build()
                                    )
                                    .build()
                            )
                        }
                    }
                    .build()
            )
            .build()

        return TileBuilders.Tile.Builder()
            .setResourcesVersion(ResourcesVersion)
            .setFreshnessIntervalMillis(60_000)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            .build()
    }

    override fun onTileResourcesRequest(request: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(
            ResourceBuilders.Resources.Builder()
                .setVersion(ResourcesVersion)
                .addIdToImageMapping(PlayIcon, image(R.drawable.ic_play))
                .addIdToImageMapping(PauseIcon, image(R.drawable.ic_pause))
                .build()
        )

    private fun image(res: Int) = ResourceBuilders.ImageResource.Builder()
        .setAndroidResourceByResId(ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(res).build())
        .build()

    private fun text(value: String, size: Float, color: Int, maxLines: Int = 1) =
        LayoutElementBuilders.Text.Builder()
            .setText(TypeBuilders.StringProp.Builder(value).build())
            .setMaxLines(maxLines)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(androidx.wear.protolayout.DimensionBuilders.sp(size))
                    .setColor(argb(color))
                    .build()
            )
            .build()

    private fun spacer(height: Float) = LayoutElementBuilders.Spacer.Builder().setHeight(dp(height)).build()

    private companion object {
        const val ToggleId = "toggle"
        const val PlayIcon = "play"
        const val PauseIcon = "pause"
        const val ResourcesVersion = "1"
    }
}
