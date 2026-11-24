# Video track choices — design

How each app picks and remembers audio and subtitle tracks. Requirements:
[../requirements/video-tracks.md](../requirements/video-tracks.md).

## Shared

- `:shared` `video/AudioTrackRanking.kt`: `bestAudioTrack(candidates, preferredLanguage)` and
  `normalizeLanguage` ("eng", "en-US" → "en"). Tests: `AudioTrackRankingTest`.
- `video/AudioTrackLabel.kt`: `audioTrackLabel` ("Dolby Digital · Surround 5.1" over
  "English"). Linux reaches both through the `audioTracks` call (`linuxMain` `AudioTracks.kt`).

## Phone (`:android`, `:android-media`)

- Tracks: `:android-media` `video/Tracks.kt`. `selectBestAudioTrack(player, language)` sets an
  override; `applySubtitlePreference(player, language)` sets Media3's preferred text language,
  or disables text for "" (off), before the title loads; once its tracks are known,
  `selectSubtitleTrack(player, language)` selects the track the shared `pickSubtitleTrack`
  chooses (Media3 alone may take a forced one), with Media3's `SELECTION_FLAG_FORCED`. Tests:
  `SubtitleSelectionTest` (instrumented).
- Picks are made in Media3's own menus (the `PlayerView` settings and subtitle buttons), so
  `TrackPicks` listens for `onTrackSelectionParametersChanged` and keeps the audio and subtitle
  language of an override that changed, or "" for off. What the app sets itself goes through
  `TrackPicks.auto { … }` and isn't counted (**TRACKS-7**). Tests: `android/src/androidTest`
  `TrackPicksTest` (instrumented: Media3 needs Android).
- Saved in `PlaySettings.audioLanguage` / `subtitleLanguage`; used by `VideoPlayerActivity` and
  `LivePlayerActivity`.

## TV (`:tv`)

- Its own pickers (`videoPlayer/components/TrackSelection.kt`), so a pick is always the
  viewer's: `TakeoutSettings.setAudioLanguage` / `setSubtitleLanguage` (null never, "" off).
- `VideoPlayerScreen` and `LivePlayerScreen` apply them with the same `Tracks.kt` helpers.

## Mac/iOS (`apple/`)

- `AudioTrackRanking.swift`: VLC's tracks as the shared ranking's candidates; the audio
  language in `UserDefaults` `audioLanguage`.
- `VideoPlayer.swift` (`VLCMediaPlayer` extension): `rememberSubtitle(index)` and
  `applySubtitlePreference()`, the subtitle language in `UserDefaults` `subtitleLanguage`
  (normalised); applied when playback starts, and again until VLC lists the subtitles (they can
  come late). `LivePlayer.swift` uses the same.

## Linux (`linux/`)

- One `Settings` object (`Main.qml` `videoSettings`, category `video`: `audioLanguage`,
  `subtitlesChosen`, `subtitleLanguage`, with `pickSubtitle` and `chooseSubtitles`) shared by
  `VideoPlayer.qml` and `LivePlayer.qml`, so a pick reaches the other player at once (two
  `Settings` objects only read the file when created).
- `VideoView` (`src/VideoView.cpp`, mpv rendering into a Qt Quick FBO) lists the tracks with
  what the ranking needs (subtitles with mpv's `forced` flag); `chooseSubtitles` asks the core's
  `subtitleTrack` call (`linuxMain` `SubtitleTracks.kt`: the shared `pickSubtitleTrack`, both
  languages normalised so "eng" finds "en"); `sid=auto` leaves the file's default; reopening a title passes the
  selected `aid` / `sid` to `loadfile`.
- Every mpv call from the UI thread is asynchronous: a blocking one can wait on the video
  output, which waits on the UI thread to draw, and the window freezes (2026-11-13/14). Its
  `audio-display=no` skips a song's cover art, which would wait for a frame the hidden live
  player never draws.

## tofm

Videos open in mpv, a program of its own (`tui/Video.cpp`), which picks tracks itself.
