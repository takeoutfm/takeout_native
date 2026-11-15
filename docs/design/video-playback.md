# Video playback — design

Requirements: [../requirements/video-playback.md](../requirements/video-playback.md).

- `:shared` `progress/TakeoutProgress`: offsets by ETag (the `progress` table), pending until
  the server has them, `resumePositionMs`, `latest()`, `report`, `sync`; `ProgressRules`
  (resume and finished rules); `video/TakeoutVideoRepository` for details, next / previous
  episode ids and Continue Watching.

| App | Player |
|---|---|
| Phone | `ui/video/VideoPlayerActivity` (its own task, for picture-in-picture): Media3 `PlayerView`, ExoPlayer from `:android-media` `TakeoutPlayerFactory` (media token through a `ResolvingDataSource`, decoder fallback); reads downloads from the cache, never writes |
| TV | `presentation/screens/videoPlayer/` (`RememberPlayer.kt`: the TV's player options), its own controls and pickers (`components/`) |
| Mac/iOS | `VideoPlayer.swift` `VideoPlayerModel` on VLCKit / MobileVLCKit (MKV); `macOS/VideoWindow.swift`, `iOS/VideoPlayerScreen.swift`; streams through `StreamProxy` |
| Linux | `src/VideoView` (its own mpv rendering into a Qt Quick framebuffer; `hwdec=auto-safe`; follows Takeout's redirect once and asks again when the signed URL gets old, keeping the tracks; every UI-thread mpv call async) and `qml/VideoPlayer.qml` |
| tofm | `tui/Video.cpp`: mpv as a program of its own; the media token in a config file only the user can read (`--include`, deleted once read), progress through mpv's JSON IPC; another player (Settings) gets downloads only |
