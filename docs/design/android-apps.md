# The Android apps — design

Where things are in the phone and TV apps (the watch follows the phone's pattern in `wear/`).
The shared core and `:android-media` are in [architecture.md](architecture.md); each area's
design has the details per platform. The TV's screens and their behaviour:
`tv/TAKEOUT_PORT.md`.

## Phone (`android/src/main/java/com/takeoutfm/android/`)

- `di/AppModule.kt` provides the shared core and `:android-media` (as the TV's `TakeoutModule`),
  plus `PlaySettings` (`:shared` `settings/`, Flutter's defaults). `MainActivity` refreshes the
  library and resends offline progress/listens on start.
- `ui/App.kt`: sign-in gate (`ui/signin/`: password + passcode via `POST /api/token`, or a
  device code). `ui/main/MainScaffold.kt`: `NavigationSuiteScaffold` (bottom bar when the width
  is compact, else a rail), one `NavController` per `Tab` (Home · Artists · Recent · Radio · Now
  Playing, icons only) so each keeps its back stack; re-tapping a tab pops to its root, and at
  the root Home cycles the media type and Artists the artist type. Routes in `TabHost.kt`.
  Play/pause FAB (with progress ring) when the music player has a queue.
- `ui/home/`: the media bar (one icon per type the library index has; tapping the shown one
  cycles its lists) over `ui/common/TileGrid` (adaptive columns by max tile width), with the
  Flutter menu (Reload, Favorite Tracks, Playlists, Settings, About, Logout).
- Music (`ui/music/`): artist, album, search (server query syntax, music + video results),
  playlists and queue pages are in every tab's graph (`TabHost.musicPages`, `MusicNav`).
  `MainViewModel` holds all playback actions (play album/mix/station/playlist/Recently Played,
  play next / add to queue — one track only; the server's track playlist goes on with similar
  tracks), track favorites and the snackbar messages. Now Playing (`PlayerScreen.kt`) has the
  queue sheet and live-stream layout; the bottom-bar layout shows the FAB, the rail layout a
  persistent `MiniPlayer` instead (as the Flutter app in landscape). The manifest allows
  cleartext http (radio streams, home servers).
- Video (`ui/video/`): `VideoScreen` (movie / series / episode from the shared
  `TakeoutVideoRepository`), person and genre pages (`VideoNav`), and `VideoPlayerActivity`
  (its own task so picture-in-picture works; Media3 `PlayerView`; track helpers from
  `:android-media` `video/Tracks.kt`, shared with the TV). Progress goes through the shared
  `TakeoutProgress` — playing a title on the emulator changes the user's real progress.
- Podcasts: shared `podcast/TakeoutPodcastRepository` (ids `ps-`/`pe-`; progress keyed by the
  episode GUID). Episodes play in the music service as `QueueItem(podcast = true)`:
  `PodcastProgress` saves their position, `ListenTracker` skips them. Subscribe changes the
  user's real subscriptions.
- Android Auto: `TakeoutMusicService` is a `MediaLibraryService` browsing `:android-media`
  `auto/TakeoutBrowseTree` (the phone provides it; the TV gives none). Test the tree with
  `./gradlew :android:assembleDebug :android:assembleDebugAndroidTest`, `adb install -r` both
  APKs (`-t` for the test one), then
  `adb shell am instrument -w com.takeoutfm.android.debug.test/androidx.test.runner.AndroidJUnitRunner`
  — not `connectedAndroidTest`, which uninstalls the app and its sign-in.
- Theme: Settings → Theme (System / Light / Dark, `PlaySettings.theme`); `MainActivity` applies
  it, status bar icons included.
- Voice: `voice/VoiceRequests` turns `MEDIA_PLAY_FROM_SEARCH` and `com.takeoutfm.action.*`
  intents into server search refs; `VoiceHandler` (called from `MainActivity`) plays them via
  `TakeoutApi.resolveRef`, which rewrites the user's server-side current playlist. Test with
  `adb shell "am start -a com.takeoutfm.action.PLAY_ARTIST -e artist 'Beck' -n com.takeoutfm.android.debug/com.takeoutfm.android.MainActivity"`.
- Offline: `:android-media` `offline/TakeoutMediaCache` (cache keyed by ETag; playback reads it,
  writes only with Playback Caching on; Media3 downloads into the same cache) and
  `TakeoutDownloadService` (the app's `playback/DownloadService`). `offline/CachePurge` keeps
  Playback Caching within Settings → Cache size (the shared `offline/CachePolicy`, [downloads-and-cache.md](downloads-and-cache.md)): only entries marked `DownloadInfo.cached` (and partly streamed tracks) are removed,
  never the viewer's downloads; video downloads first get room (`makeRoom`) or ask. Offline browsing: the shared
  `api/DbResponseCache` (set as `TakeoutApi.responseCache` in `AppModule`) answers GETs that
  can't reach the server with the last response, and reuses recent library responses (an hour;
  podcast episode lists 15 min; never progress, activity, favorites, playlists, search) until
  Reload or an edit (`TakeoutApi.forgetKept`). The phone emulator goes offline with
  `adb shell svc wifi disable; adb shell svc data disable`. `MainViewModel` adds/removes
  downloads (`downloadVideo` for a movie or episode: `DownloadInfo.videoId`); `DownloadsScreen`
  lists them (a video opens `VideoPlayerActivity`, which reads downloads from the cache but never
  writes to it); `DownloadBadge` shows ✓ / progress. The emulator has well under 1 GB free:
  test video downloads with a small title (The Chronicles of Riddick: Dark Fury, 284 MB). The TV doesn't
  pass a cache, so it always streams.

## TV (`tv/`)

- **Identity:** `applicationId`, package and namespace are `com.takeoutfm.tv`. Paths below are
  under `tv/src/main/java/com/takeoutfm/tv/`.
- **Data flow:** JetStream's screens talk to the `MovieRepository` interface, bound (Hilt, in
  `JetStreamApplication.kt`) to `data/repositories/TakeoutMovieRepository.kt`, which maps the
  shared `TakeoutVideoRepository`'s entities (`VideoCard`, `VideoDetails`, …) onto JetStream's
  (`Movie`, `MovieDetails`, …) and adds the TV's display formatting (durations, dollars, "—").
  All server logic (caching, search, seasons, next episode, Continue Watching) lives in the
  shared repository. Features JetStream lacks (people, episodes, favorites, Continue
  Watching, recommendations, search results) are extra methods on `TakeoutMovieRepository`,
  injected directly where needed. Keep server access inside the `data/` layer.
- **Shared core (`:shared`, `com.takeoutfm.shared.*`):**
  - `api/`: `TakeoutApi` (Ktor with the OkHttp engine, bearer access token, refresh on 401,
    JSON decoded off the main thread), `TakeoutSession` (tokens; streams use the separate
    *media* token, only for URLs on the signed-in server; also the user's name from `/api/me`,
    which `TakeoutLibrary.refresh` asks for and every app's settings shows as "Connected as"
    — null offline before it's known or on servers without it), `TakeoutUrls` (full server URLs for
    locations, images and release covers).
  - `library/TakeoutLibrary`: the `/api/index` library index + recommendation sets — decides
    which tabs exist and which requests are made; saved on device and refreshed on
    launch/foreground/sign-in/Home.
  - `music/`: `TakeoutMusicRepository` and the music entities.
  - `video/`: `TakeoutVideoRepository` (movies, TV, people, search, Continue Watching via a
    `ProgressSource`, which `TakeoutProgress` implements) and the video entities.
  - `listen/`: `ListenBrainzAccount` (Ktor), the listen rules, and `ListenSession` (the same
    rules as `ListenTracker`, fed events by players that aren't Media3 — the Mac's);
    `progress/`, `util/`, `model/`.
  - `store/KeyValueStore`: how shared classes save values; `AndroidStores` (androidMain) gives
    plain and EncryptedSharedPreferences-backed ones.
  - Shared classes have no Hilt annotations: `data/takeout/TakeoutModule.kt` provides them,
    using the same preferences files as before the move (`takeout_session`,
    `takeout_library`, `listenbrainz`) so saved state carries over. HTTP code is tested with
    Ktor's `MockEngine`.
  - **Database** (SQLDelight, `src/commonMain/sqldelight/…/db/*.sq`, file `takeout.db`):
    `progress/TakeoutProgress` (watch progress keyed by media ETag, pending until the server
    has it), `listen/ListenReporter` (unsent plays), `music/MusicHistory` (Recently Played).
    `music/StreamHistory` (songs heard on streams, recorded by `TakeoutMusicService`). Writes go
    through `DbWriter` (one at a time, in order, off the caller's thread). Schema changes need
    a `<version>.sqm` migration (currently v3; `MigrationTest`). Store tests use an in-memory JDBC driver (`testDb()`).
    `data/takeout/LegacyImport` moves pre-database SharedPreferences JSON in once.
- **`data/takeout/`:** `TakeoutSettings` (per-device prefs), `TakeoutModule` (Hilt wiring for the
  shared core). 
- **IDs:** one navigable id space — movies `m-<id>`, TV series `s-<id>`, episodes `e-<id>`;
  `getMovieDetails()` dispatches on the prefix. Progress offsets carry only an ETag, so titles are
  found by matching ETags against cached `/api/movies` and `/api/tv`.
- **Navigation:** `presentation/App.kt` (sign-in gate + top-level NavHost: details, player,
  person, genre pages); `presentation/screens/dashboard/` (tab bar built from `TakeoutLibrary`,
  including one tab per recommendation set).
- **Player:** `TakeoutPlayerFactory` (`:android-media`) sets up ExoPlayer for Takeout streams
  (media token via `ResolvingDataSource`, decoder fallback, debug `EventLogger`), shared by video
  and music; `presentation/screens/videoPlayer/` — `RememberPlayer.kt` adds the video-specific
  options; track pickers live in `components/`, the best-audio-track ranking in
  `:android-media` `video/`.
- **Shared UI:** `presentation/common/TileGrid.kt` / `TileRow.kt` (generic 6-column grid and
  card row; `PosterGrid.kt` and `MoviesRow.kt` wrap them for movies), `Skeletons.kt` (loading
  states), `presentation/utils/FocusMemory.kt` (return focus to the item that was opened).
- **Shared data helpers** (in `:shared`): `TakeoutUrls`, and `listFlow` / `sortKey` /
  `sortedByTitle` in `util/Lists.kt`.
- **Music:** a separate half of the app, switched with the TAKEOUT logo (`LibraryMode` in
  `TakeoutSettings`). Data in `:shared` `music/` (`TakeoutMusicRepository`, entities; ids
  `ar-`/`al-`/`t-`) and `MusicHistory` (Recently Played); screens in
  `presentation/screens/music/`. Music types are deliberately not `Movie`; the plan for the
  remaining phases is in `TAKEOUT_PORT.md` → Music.
- **Music playback:** `playback/MusicService` (the TV's Hilt subclass of `:android-media`'s
  `TakeoutMusicService`, a background `MediaSessionService`) plays; the UI only talks to
  `MusicPlayer` (`:android-media`, a `MediaController` singleton with `NowPlaying` state),
  provided in `TakeoutModule`.
  The mini player is an overlay in `App.kt`. Video playback stays screen-scoped.
- **Play reporting:** `ListenReporter` (`:shared`; Takeout activity + ListenBrainz, per the
  Music settings, with an on-device retry queue), `ListenBrainzAccount` (`:shared`, encrypted
  token), `data/listen/TokenHandoff` (one-time local web page + QR for entering the token from a phone).
  `ListenTracker` (`:android-media`) decides when a track counts. Don't test with real accounts: the
  settings change the user's real Takeout stats / ListenBrainz profile.
