# Music playback — design

Requirements: [../requirements/music-playback.md](../requirements/music-playback.md).

## Shared (`:shared` `music/`)

- `TakeoutMusicRepository`: the queues (`albumQueue`, `artistQueue`, `stationQueue`,
  `playlistQueue`, `trackQueue`, `favoritesQueue`, …) as `MusicQueue` (title, items,
  start index and position, the server `location`).
- `QueueItem`: title, artist, album, cover, stream `uri`, ETag (a podcast episode's GUID),
  `podcast`.
- `MusicHistory`: Recently Played, the `played_queue` table (50 entries; `progress` saves where
  one got to). `StreamHistory`: songs heard on streams.
- `PlaySettings.repeatAll` (`RepeatSetting`).

## Per platform

| App | Player | System controls |
|---|---|---|
| Phone | `:android-media` `TakeoutMusicService` (a `MediaLibraryService`, Media3 ExoPlayer from `TakeoutPlayerFactory`); the UI talks to `MusicPlayer` (a `MediaController`, `NowPlaying` state); actions in `MainViewModel` | Media3's session and notification; Android Auto browses `auto/TakeoutBrowseTree` |
| TV | the TV's `playback/MusicService` subclass; `MusicPlayer` | Media3's session |
| Watch | the same `:android-media` service | `tile/PlayerTileService`, a complication |
| Mac/iOS | `AudioPlayer` (AVFoundation) | `MPNowPlayingInfoCenter`, `MPRemoteCommandCenter` |
| Linux, tofm | `linux/src/core/Player` (libmpv), shared by both | `Mpris` (bus name per app) |
