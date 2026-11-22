package com.takeoutfm.media

import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import com.takeoutfm.shared.listen.parseStreamTitle

/**
 * The player the media session shows (the notification, Android Auto, the lock screen): a live
 * stream's song (its ICY "Artist - Title", put in the item's extras by the service's
 * StreamTitles) as title and artist, with the station as album (RADIO-4). Only the combined
 * metadata changes, not the items, so the app's own queue still sees the station.
 */
@OptIn(UnstableApi::class)
internal class StreamTitlePlayer(player: Player) : ForwardingPlayer(player) {

    // The session reads the metadata from the event's argument, so rewrite that too. Each
    // listener keeps one wrapper, so removeListener finds it.
    private val wrappers = mutableMapOf<Player.Listener, Player.Listener>()

    override fun getMediaMetadata(): MediaMetadata = withStreamTitle(super.getMediaMetadata())

    override fun addListener(listener: Player.Listener) {
        super.addListener(wrappers.getOrPut(listener) { TitledListener(listener) })
    }

    override fun removeListener(listener: Player.Listener) {
        wrappers.remove(listener)?.let { super.removeListener(it) }
    }

    private fun withStreamTitle(metadata: MediaMetadata): MediaMetadata {
        val item = currentMediaItem ?: return metadata
        val song = item.mediaMetadata.extras?.getString(StreamTitleKey)?.takeIf { it.isNotBlank() }
            ?: return metadata
        val station = item.mediaMetadata.title?.toString().orEmpty()
        val (artist, title) = parseStreamTitle(song) ?: (station to song)
        return metadata.buildUpon()
            .setTitle(title)
            .setDisplayTitle(title)
            .setArtist(artist)
            .setSubtitle(artist)
            .setAlbumTitle(station)
            .build()
    }

    /**
     * Passes every event on, the metadata with the stream's song. Every method is spelled out:
     * `Player.Listener by listener` would not forward them, as they're Java default methods.
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private inner class TitledListener(private val l: Player.Listener) : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = l.onMediaMetadataChanged(withStreamTitle(mediaMetadata))
        override fun onEvents(player: Player, events: Player.Events) = l.onEvents(player, events)
        override fun onTimelineChanged(timeline: Timeline, reason: Int) = l.onTimelineChanged(timeline, reason)
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = l.onMediaItemTransition(mediaItem, reason)
        override fun onTracksChanged(tracks: Tracks) = l.onTracksChanged(tracks)
        override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) = l.onPlaylistMetadataChanged(mediaMetadata)
        override fun onIsLoadingChanged(isLoading: Boolean) = l.onIsLoadingChanged(isLoading)
        override fun onLoadingChanged(isLoading: Boolean) = l.onLoadingChanged(isLoading)
        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) = l.onAvailableCommandsChanged(availableCommands)
        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) = l.onTrackSelectionParametersChanged(parameters)
        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) = l.onPlayerStateChanged(playWhenReady, playbackState)
        override fun onPlaybackStateChanged(playbackState: Int) = l.onPlaybackStateChanged(playbackState)
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = l.onPlayWhenReadyChanged(playWhenReady, reason)
        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) = l.onPlaybackSuppressionReasonChanged(playbackSuppressionReason)
        override fun onIsPlayingChanged(isPlaying: Boolean) = l.onIsPlayingChanged(isPlaying)
        override fun onRepeatModeChanged(repeatMode: Int) = l.onRepeatModeChanged(repeatMode)
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = l.onShuffleModeEnabledChanged(shuffleModeEnabled)
        override fun onPlayerError(error: PlaybackException) = l.onPlayerError(error)
        override fun onPlayerErrorChanged(error: PlaybackException?) = l.onPlayerErrorChanged(error)
        override fun onPositionDiscontinuity(reason: Int) = l.onPositionDiscontinuity(reason)
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) =
            l.onPositionDiscontinuity(oldPosition, newPosition, reason)
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = l.onPlaybackParametersChanged(playbackParameters)
        override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) = l.onSeekBackIncrementChanged(seekBackIncrementMs)
        override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) = l.onSeekForwardIncrementChanged(seekForwardIncrementMs)
        override fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) = l.onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs)
        override fun onAudioSessionIdChanged(audioSessionId: Int) = l.onAudioSessionIdChanged(audioSessionId)
        override fun onAudioAttributesChanged(audioAttributes: AudioAttributes) = l.onAudioAttributesChanged(audioAttributes)
        override fun onVolumeChanged(volume: Float) = l.onVolumeChanged(volume)
        override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = l.onSkipSilenceEnabledChanged(skipSilenceEnabled)
        override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) = l.onDeviceInfoChanged(deviceInfo)
        override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) = l.onDeviceVolumeChanged(volume, muted)
        override fun onVideoSizeChanged(videoSize: VideoSize) = l.onVideoSizeChanged(videoSize)
        override fun onSurfaceSizeChanged(width: Int, height: Int) = l.onSurfaceSizeChanged(width, height)
        override fun onRenderedFirstFrame() = l.onRenderedFirstFrame()
        override fun onCues(cues: List<Cue>) = l.onCues(cues)
        override fun onCues(cueGroup: CueGroup) = l.onCues(cueGroup)
        override fun onMetadata(metadata: Metadata) = l.onMetadata(metadata)
    }
}
