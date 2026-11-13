# Takeout Android — plan

A native Android (phone/tablet) client for the TakeoutFM server, written in Kotlin with Jetpack
Compose. It brings the feature set of the Flutter app (`takeout_app`, `com.takeoutfm.mobile`)
to a native app, built on the architecture proven in the Android TV app (`takeout_tv`), and
shares code with it through a common Kotlin module that is designed from the start to become a
Kotlin Multiplatform (KMP) module for future native iOS and macOS apps.

Status: **phases 0–8 done**; phase 9 (Wear OS) in progress (see section 5). Decisions are recorded in section 7; items
still marked ❓ need an answer before the phase that uses them.

---

## 1. Goals

1. **Feature parity with the Flutter app** (section 4 is the checklist), then go beyond it
   where the TV app already did better (offline progress sync, focus/navigation polish,
   live-stream handling, local Recently Played).
2. **One Kotlin codebase for the Takeout domain**: API, models, play queues, progress, listen
   reporting, offline cache — shared by the TV, phone, watch and Mac apps, so a fix lands in
   all of them.
3. **Native everywhere**: Compose on Android, Media3 for playback, Android Auto and Assistant
   integration; later SwiftUI + AVFoundation on iOS/macOS on top of the same shared core.
4. **Same server, no server changes required** for parity (the TV app already speaks the whole
   API surface needed, apart from podcasts/activity/playlist editing endpoints, which exist).

Also in scope: a **full native Wear OS app** on the same shared core (phase 9), replacing the
Flutter `takeout_watch`.

Non-goals for v1: Linux/Windows desktop, a UI shared across platforms (Compose Multiplatform
for iOS) — see section 7.

---

## 2. Recommended architecture

### 2.1 Modules

```
takeout_native/                      (this repo: one Gradle build — see 2.3)
├── shared/            KMP: commonMain + androidMain (+ iosMain/macosMain later)
│   ├── api/           Ktor client, auth + token refresh, media-token URLs, all endpoints
│   ├── model/         API models (PascalCase JSON), spiff, domain entities
│   ├── domain/        play queues, listen rules, progress merge/sync, history, search query,
│   │                  sort keys, cover-art URL rules, ICY title parsing, library index
│   └── data/          repositories + caches (JSON TTL cache, offsets, downloads index,
│                      Recently Played), settings, secure token storage (expect/actual)
├── android-media/     Android library: Media3 player factory (media token, decoder fallback),
│                      MusicService (MediaLibraryService), MusicPlayer (MediaController),
│                      ListenTracker, downloads (Media3 DownloadManager), Android Auto browse
│                      tree, video player helpers (track pickers, best-audio ranking)
├── android-ui/        Android library: shared Compose pieces that don't depend on TV vs phone
│                      Material (image loading, palette colours, formatting, focus helpers)
├── tv/                the existing takeout_tv app (Compose for TV), moved in
├── android/           the new phone/tablet app (Compose Material 3)
├── wear/              the native Wear OS app (Compose for Wear OS, Media3)
└── apple/             later: Xcode project (SwiftUI) consuming `shared` as an XCFramework
```

What goes where:

| Concern | Module | Why |
|---|---|---|
| Endpoints, JSON models, auth/refresh | `shared` (commonMain) | Identical on every platform. |
| Rules: listen (4 min / half), stream-song listens, progress resume/finished, Continue Watching window, best-match title parsing, A–Z sort keys, release grouping (albums/singles/other) | `shared` | Pure logic, already unit-tested in `takeout_tv`. |
| Offline caches & sync queues (progress, listens, Recently Played, JSON TTL cache) | `shared` (SQLDelight, see 7.9) | Same behaviour offline on all apps. |
| Secure token store, settings store | `shared` expect/actual | EncryptedSharedPreferences / Keychain. |
| Playback, media session, downloads, Auto | `android-media` | Media3 is Android-only; Apple uses AVFoundation. |
| Screens | per app | TV (`androidx.tv.material3`, D-pad), phone (Material 3, touch) and watch (Wear Compose, rotary) differ too much to share whole screens. |

### 2.2 Why KMP for the shared core (yes, it adds value)

- **Worth it**: the part that's identical everywhere is large — ~40 endpoints, every model,
  queue/spiff handling, the listen/progress/offline rules, caching. That's where bugs come from
  (we fixed several in `takeout_tv`: progress lost offline, stream listens, sort keys), and
  KMP lets iOS/macOS get those fixes for free.
- **Low cost now**: write `shared` as KMP from day one but ship only the Android targets.
  The constraint is just "no Android imports in commonMain": Ktor instead of OkHttp directly
  (Ktor uses OkHttp on Android), kotlinx.serialization (already used), kotlinx-datetime instead
  of `java.time` in common code, SQLDelight and multiplatform settings instead of
  SharedPreferences.
- **Not shared**: UI and playback. SwiftUI and AVFoundation (VLCKit for MKV video) give the
  best native result on Apple; Compose Multiplatform for iOS is possible later but not recommended for a media app
  that needs native players, Now Playing, CarPlay and Siri.
- **Swift ergonomics**: use SKIE (or KMP-NativeCoroutines) so Flows and sealed classes are
  natural in Swift; keep the shared API small (repositories + models).

### 2.3 Repository layout — decided: monorepo

**A monorepo** (this repo), with `takeout_tv`'s full history: the TV app is the `:tv`
module (`tv/`), its benchmarks `:tv-benchmark`. Shared-module changes and all apps then change in one commit
and one build.

### 2.4 Android app stack

- Kotlin, Jetpack Compose, Material 3 with **dynamic colour** (Material You) and light/dark
  following the system — as the Flutter app.
- Navigation: Navigation Compose (type-safe routes), **one back stack per bottom tab** (the
  Flutter app keeps a Navigator per tab).
- DI: Hilt in the apps; `shared` exposes plain constructors/factories (no Hilt in common code).
- Images: Coil 3 (KMP-capable) with a disk cache; Palette for cover-derived colours.
- Playback: Media3 ExoPlayer + `MediaLibraryService` (needed for Android Auto browsing).
- Downloads and playback caching: Media3 `SimpleCache` keyed by track ETag, `CacheDataSource`
  for playback, `DownloadManager` + `DownloadService` for downloads (see 4.11), with the
  shared downloads index and a live per-track cache-status flow for UI icons.
- Window size classes: bottom bar on phones, NavigationRail on tablets/landscape (the Flutter
  app switches to a rail + persistent mini player in landscape).
- Min SDK 28 (as TV), target latest. Wear OS: min SDK 30 (Wear OS 3+).

### 2.5 Identity — decided

- **Application ID**: `com.takeoutfm.android` (package too), matching the module names; it
  installs beside the Flutter app (`com.takeoutfm.mobile`) rather than replacing it (changed
  2026-11-03 from reusing the Flutter id). Debug builds use an `applicationIdSuffix = ".debug"`.
- Wear OS: `com.takeoutfm.wear` (package too), beside the Flutter watch app (`com.takeoutfm.watch`).
- Kotlin package: `com.takeoutfm.*` for all new code (the TV app still uses JetStream's
  `com.google.jetstream`; rename it when moving into the monorepo).

---

## 3. What comes from `takeout_tv`

Already written, tested on devices, and extracted into `shared`/`android-media` in phase 0:

- `TakeoutApi` (bearer + media token, refresh on 401, decoding off the main thread),
  `TakeoutSession` (encrypted tokens, and the user from `/api/me`), `TakeoutUrls`, `TakeoutLibrary` (index + home, saved),
  `TakeoutSettings`.
- Music data layer: `MusicApiModels`, `TakeoutMusicRepository` (home, artists, artist/album
  details, release grouping, queues from spiff playlists, favorites, radio, playlists),
  `MusicHistory` (Recently Played, 50 queues, resume position).
- `TakeoutProgress` with the **offline progress cache** (pending queue, resend on start /
  foreground, newest-by-date merge).
- Play reporting: `ListenReporter` (Takeout activity + ListenBrainz, retry queue),
  `ListenTracker` (play-time based, stream songs judged on ICY title change),
  `ListenBrainzAccount`; `TokenHandoff` (QR/phone hand-off — on a phone just paste instead).
- Playback: `TakeoutPlayerFactory`, `MusicService` (background, ICY → metadata), `MusicPlayer`
  (`NowPlaying` state), audio-track ranking, subtitle/audio pickers.
- Rules with unit tests: listen rules, stream titles, progress selection/merge, QueueRef,
  sort keys, cover URLs, mapping.

Everything TV-specific stays in `tv/`: Compose-for-TV screens, focus memory/return, the
dashboard top bar, D-pad handling.

---

## 4. Feature checklist (Flutter app → native)

### 4.1 Sign-in and account
- [ ] Login with **host, user, password and optional passcode** (2FA).
- [ ] **Device-code sign-in** (TV app's flow) as an alternative.
- [ ] **Link code**: link another device (the Flutter "Link" page) — enter the code shown on a
      TV/new device.
- [ ] Logout; token refresh; connectivity re-check on resume.
- [ ] About (version, source link).

### 4.2 Navigation and home
- [ ] Bottom navigation: **Home · Artists · Recent · Radio · Now Playing** (icons only, as now).
- [ ] Per-tab back stacks; re-tapping the current tab pops to its root; **repeated Home taps
      cycle the media type** (music → movies → TV → podcasts), **repeated Artists taps cycle the
      artist type**.
- [ ] Home **media bar** to switch media type (only types the library has — `/api/index`).
- [ ] Home menu: Reload, Recent Tracks, Favorite Tracks, Activity, Playlists, Settings,
      Downloads, Link, Logout, About.
- [ ] Landscape/tablet: NavigationRail + content + **persistent mini player**.
- [ ] Floating play/pause button (hidden on Now Playing), kept as in the Flutter app.

### 4.3 Music
- [ ] Home grids: Recently Added, New Releases (cover grid with title/artist).
- [ ] All artists (A–Z) with artist-type cycling.
- [ ] Artist page: header with artwork, Shuffle / Popular / Radio, releases, singles, popular
      tracks, similar artists, **Want List**, favorite artist.
- [ ] Release page: tracks grouped by **disc**, similar releases, **Tracks Like This**,
      play/shuffle, download, add to playlist.
- [ ] Track lists with cover tiles; long-press/overflow actions (**play next**, add to queue,
      add to playlist, favorite, download) — play next inserts after the current item in the
      Media3 queue and the local queue copy.
- [ ] Favorites: tracks, artists, **favorite artist tracks** playlist.

### 4.4 Movies and TV
- [ ] Movies grid (added / new / recommended), genres grid and genre pages.
- [ ] Movie details: cast & crew (directors first, as TV), related, **Resume / Play from start**,
      progress "N min left".
- [ ] Person page: starring, starring shows, directing, writing, bio.
- [ ] TV series grid, series → seasons → episodes, episode details, Recently Watched.
- [ ] Native video player: audio & subtitle pickers (embedded tracks only), best audio track,
      aspect ratio kept, progress saved (with the offline cache), **picture-in-picture** (auto-enter on Home /
      leaving the app while playing, play/pause action in the PiP window).
- [x] **Live channels** (the server's `/api/guide`, shown only when the index has `HasGuide`;
      server design in the Go project's `CHANNELS.md`). Shared in `:shared` `guide/`: the
      repository, `LiveGuide` (reloaded when stale), `tune` (what a live player plays now: a
      program from its offset, break music fading out when cut off, or the up-next slate),
      grid helpers, and `GuideLoader` (the guide in 6-hour windows fetched as they scroll into
      view, merged, with the schedule's horizon found up front for a day row). TV: the Live
      tab's grid and live player (`tv/TAKEOUT_PORT.md`). Phone: Home's Live type (On now list;
      tap again for the Guide: a detail panel for the selected program with Watch live and
      Details, a day row to the horizon, and the grid, loading as it scrolls, titles of
      programs that began off the left edge kept in view) and `ui/live/LivePlayerActivity` (landscape; channels change
      from the guide; Media3's standard controls as in the video player, through a player
      without seek or speed commands, so the time bar can't be dragged and the gear has only
      Audio; the channel, program and what's next over the top; choices followed by language
      to the next program; pause puts you behind live until the program ends or Jump to live;
      picture-in-picture on leaving the app while a channel plays, following the schedule,
      with an up-next slate in the small window during breaks). Linux:
      the sidebar's Live page (On Now, and Guide as on the phone; click selects, double-click
      tunes or opens details; the keyboard as the TV's remote: Left/Right by program, Up/Down
      by channel at the same time without scrolling sideways, Page Up/Down by day, Home to
      now, Enter tunes or opens details) and `qml/LivePlayer.qml` (channels change from
      the guide; laid out as `VideoPlayer.qml`, the program's progress in the seek bar's place,
      Audio, Subtitles and Full screen at the right of the buttons, the best audio track picked
      by the shared ranking; click or Space pauses, L jumps to live), through the `guideReload`, `guideEnsure` and `tune` calls
      (the `guide/LiveViews.kt` types, shared with Apple). Apple (2026-11-12): the Live page in
      the Mac/iPad sidebar and on the iPhone's Home (On Now; Guide with the detail panel, day
      row and grid, cut-off titles kept at the left edge as on Android; ← → ↑ ↓, Home and
      Return on the Mac) and `LivePlayerModel` on VLC (the Mac's one Live window, iOS full
      screen; slate, break music fade, pause behind live, Jump to Live, best audio track,
      subtitles followed by language), through `SwiftCalls.guideReload` / `guideEnsure` /
      `tune` (`apple/README.md`). Live playback doesn't write watch progress. None planned for
      Wear OS: video doesn't fit a watch.

### 4.5 Podcasts
- [ ] Series grid (all / **subscribed**), subscribe / unsubscribe.
- [ ] Series details, episode list with progress ("N min left", Complete), episode details.
- [ ] Download / delete episodes; resume position (offsets).

### 4.6 Radio
- [ ] Stations by type: **Genres, Decades, Other, Streams** (live), recently used first.
- [ ] Live player: stream title from ICY ("Now: Artist – Title" → title/artist lines),
      **Stream History** of songs heard.
- [ ] **Custom stations** (a Flutter TODO, wanted in v1): create from a search query, list with
      the other stations, delete. **Blocked on the server**: `apiRadioPost` exists in
      `takeout/internal/server/api.go` but `server.go` registers no `POST /api/radio` route
      (and there's no delete); needs that server change first.

### 4.7 Playlists
- [ ] Playlists list; **create**, **add to playlist**, delete, open (spiff details).
- [ ] **Sync Playlist** (offline copy).

### 4.8 Search
- [ ] Search across artists, releases, tracks, stations, movies, series, episodes.
- [ ] Query syntax help: `text`, `artist:name`, `guitar:person` (role search).
- [ ] Voice search (in-app recognizer, as TV) and **Assistant "play X on Takeout"**.

### 4.9 Activity, history and stats
- [ ] **Activity**: track stats by period (Recent, Last/This Week, Last/This Month, …) for
      artists, releases, tracks, with counts.
- [ ] Recent Tracks (server history).
- [ ] Local **Recent** tab: recently played queues (Recently Played from TV) and stream history;
      delete history.

### 4.10 Player
- [ ] Now Playing: large cover, **cover-derived background colour** (Palette) or artist
      background, title/artist/album, seek bar, **previous · −10 s · play/pause · +30 s · next**,
      repeat, queue sheet (swipe up), favorite.
- [ ] Mini player (bottom, above the nav bar) with swipe/tap to open.
- [ ] Live-stream layout (LIVE indicator, play/pause only).
- [ ] Media session, notification controls, lock screen, Bluetooth/headset buttons.

### 4.11 Offline and downloads
- [ ] Download releases, playlists and podcast episodes; Downloads page (per item and
      delete-all with "frees N of storage").
- [ ] **Playback caching**: auto-download while playing (the Flutter app's
      `LockCachingAudioSource` + "add queue to Downloads"). Native design:
      - The player reads through a Media3 `CacheDataSource` over a `SimpleCache` (in
        `TakeoutPlayerFactory`, behind the media-token resolver), so streamed bytes are kept
        and later plays are local and work offline.
      - With Playback Caching on, nothing is fetched ahead: a track that playback has cached
        whole is added as a `DownloadRequest` (`:android-media` `offline/PlayedDownloads`,
        in the music service), which completes from the cache, so it shows on the Downloads
        page under the queue it played in. Partly played tracks keep their chunks for
        playback but aren't listed. (The Flutter app downloaded the whole queue; changed
        2026-10-26 at the user's request.)
      - **Cache key = the track's ETag** (custom `CacheKeyFactory`): the stream URL redirects
        to a signed, expiring URL, so URL keys would never hit.
      - Downloads cache uses no eviction (`NoOpCacheEvictor`). **With Playback Caching off,
        nothing is cached**: playback streams without writing to any cache (it still *reads*
        tracks already downloaded, so they play offline).
      - **No caching for live streams** (no ETag / no length) — they always stream.
      - Chunked storage inside the app's cache folder is fine (playback only; not exported).
- [ ] **Cached indicator**: a per-ETag `CacheStatus` flow (`NotCached` / `Partial(n%)` /
      `Cached`) in `android-media`:
      - fully cached = `cache.getCachedBytes(etag, 0, size) == size`, with `size` from the
        spiff entry (as the Flutter app uses it);
      - downloads add state and progress (`Download.state`, `percentDownloaded`);
      - updates live from `Cache.Listener` span events and `DownloadManager.Listener`.
      UI: ✓ (or offline icon) on cached tracks, a progress ring while downloading, "N of M
      cached" / ✓ for albums, playlists and episodes; nothing for live streams.
- [x] Offline playback from downloads. Mobile-data limits (streaming / downloads / artwork) are
      **not wanted** (decided 2026-10-26: the old Flutter settings are dropped).
- [ ] JSON response cache with TTL so browsing works offline.
- [ ] Offline progress + listen queues (from TV).

### 4.12 Settings
- [ ] Playback caching; Track Activity (report plays to Takeout); ListenBrainz token +
      enable (paste on phone; validate).

### 4.13 Platform integration
- [ ] **Android Auto**: browse music, podcasts, radio (recent stations first), search.
- [ ] **Assistant / intents**: `MEDIA_PLAY_FROM_SEARCH` and the app actions the Flutter app
      declares — PLAY_ARTIST, PLAY_ARTIST_SONG, PLAY_ARTIST_ALBUM, PLAY_ARTIST_RADIO,
      PLAY_ARTIST_POPULAR_SONGS, PLAY_ALBUM, PLAY_SONG, PLAY_RADIO, PLAY_SEARCH, PLAYER_PLAY,
      PLAYER_PAUSE, PLAYER_NEXT, PLAY_MOVIE.
- [ ] Keyboard / D-pad navigation (Chromebooks, tablets with keyboards).
- [ ] Localization via string resources (English first; the ARB strings map 1:1).

### 4.14 Design elements to keep
- Material 3 + dynamic colour, system light/dark.
- Collapsing app bars with artwork headers (Flutter "sliver" pages).
- Cover-art grids sized by max tile width (cover vs poster aspect), titles under tiles.
- Palette-derived colours from artwork; circle buttons; chips for filters (activity periods);
  person avatars; relative times ("3 days ago"); HTML bios rendered.

---

## 5. Phases

Each phase ends with a usable build, tests passing, and a device check (phone + tablet/foldable).

0. **Shared core** (no visible change to TV)
   - [x] Create the monorepo with `takeout_tv`'s history (`:tv`, `:tv-benchmark`); rename its
         package to `com.takeoutfm.tv`.
   - [x] `shared` KMP module (Android target only), used by `:tv`: API models
         (`shared.model`), listen rules (`shared.listen`), progress rules on kotlinx-datetime
         (`shared.progress`), URL rules (`TakeoutPaths`), sort keys / `listFlow`
         (`shared.util`); their tests run from `commonTest`.
   - [x] `TakeoutApi` on Ktor (OkHttp engine on Android, `expect` engine per platform) and
         `TakeoutSession` (with a `KeyValueStore`; EncryptedSharedPreferences on
         Android) in `shared.api`, with `MockEngine` tests for refresh/sign-out.
   - [x] `KeyValueStore` (plain + encrypted on Android); `TakeoutUrls`, `TakeoutLibrary`,
         `ListenBrainzAccount` (Ktor) and the whole music data layer (`TakeoutMusicRepository`
         + entities) in `shared`.
   - [x] Shared video domain (`shared.video`): entities and `TakeoutVideoRepository` (search,
         series → seasons → episodes, next episode, directors-first credits, Continue
         Watching through a `ProgressSource`); the TV app maps them onto JetStream's
         `Movie`/`MovieDetails`.
   - [x] SQLDelight in `shared` (`TakeoutDb`): `TakeoutProgress`, `ListenReporter` and
         `MusicHistory` moved in on top of it; the TV app imports its old SharedPreferences
         JSON once (`LegacyImport`).
   - [x] `android-media`: `TakeoutPlayerFactory`, abstract `TakeoutMusicService` (each app
         subclasses it with its Hilt singletons), `MusicPlayer`, `ListenTracker`, audio-track
         ranking.
   - [x] Re-verify the TV app: each step on the emulator; the finished phase 0 release on the
         Chromecast (2026-10-25: signed in, Recently Played and progress migrated, music and
         video Home, Continue Watching).
1. **App skeleton** — done (2026-10-25): `:android` (`com.takeoutfm.android`, versionCode 3000);
   login (password + passcode, device code with "get a new code"), dynamic theme, bottom bar
   / rail (rail whenever the width isn't compact), per-tab stacks, Home with media bar
   (music: added/new; movies: added/new/recommended/favorites; TV: added), read-only Artists
   (all/favorite), Recent, Radio (streams first), Now Playing placeholder, FAB, Settings
   (Track Activity, ListenBrainz paste + validate), About. Opening titles shows "next update".
2. **Music browse + background playback** — done (2026-10-25): artist pages (background,
   Shuffle / Popular / Radio, popular tracks, releases, singles, similar, ♥), release pages
   (discs, Play / Shuffle, similar), search (music + video; server query syntax), background
   playback (`MediaSessionService`; `MediaLibraryService` comes with Android Auto in phase 7),
   FAB / mini player, Now Playing with queue sheet, repeat, −10/+30, ♥, live layout, play next
   / add to queue, favorite tracks, playlists (read), notification permission.
3. **Radio, history, activity** — done (2026-10-25) except custom stations (server, see 4.6):
   stations and live streams (phase 2), Recent tab with Recently Played + Stream History
   (shared `StreamHistory`, SQLDelight `stream_song`, schema v2 with `1.sqm`) and Clear, Recent
   Tracks (`/api/activity/tracks/recent`), Activity (artists / releases / tracks × Recent,
   Last Week, Last Month, This Month, with counts; Play / Shuffle), play reporting via the
   shared `ListenReporter`.
4. **Video** — done (2026-10-25): Home's Movies (added / new / continue watching /
   recommended / genres / favorites) and TV Shows (added / all); details for movies, series
   (seasons → episodes, Play / Resume picks the next episode) and episodes; person and genre
   pages; `VideoPlayerActivity` on Media3 `PlayerView` (embedded audio/subtitle + speed
   settings, best audio track, −10/+30, resume / play from start, progress saved through the
   shared offline cache, next episode at the end, its own media session, picture-in-picture).
   The track logic moved to `:android-media` `video/Tracks.kt` for both apps.
   Movie trailers (2026-10-27): the movie view's `Trailers` (official YouTube trailers the
   server syncs from TMDB) as `VideoDetails.trailers`, main trailer first; a Trailers row of
   YouTube thumbnails (`i.ytimg.com`) last on the page, opened in the YouTube app on both the
   phone and the TV. Not for series (the server syncs none).
   Episode pages (2026-10-27): the episode's name, a link to its series, "S1E2 · air date ·
   49 min · ★", guest stars & crew (the episode view's `Cast`/`Directing`/`Writing`, now read by
   the shared repository as `VideoDetails.episode` / cast), Previous / Next episode and a
   More from Season N row; the series page's episode rows open the page, ▶ plays.
5. **Podcasts** — done (2026-10-25): Home's Podcasts (all / subscribed, shown when the index
   has podcasts), podcast pages (Subscribe, description, episodes with date, "N min left" /
   Complete), playback through the music service with progress saved by GUID (shared
   `TakeoutPodcastRepository`, `:android-media` `PodcastProgress`; episodes aren't listens),
   resume. Downloads come with phase 6. Episode pages (2026-10-27, `PodcastEpisodeScreen`):
   art, podcast link, date and progress, Play / Resume and Play from start, Download, Mark as
   played / unplayed (`TakeoutPodcastRepository.playedMark`: the known length, else a token
   one, since the length isn't known before playing), show notes with tappable links
   (`AnnotatedString.fromHtml`), Previous (older) / Next (newer); podcast rows open the page,
   ▶ plays.
6. **Offline** — mostly done (2026-10-25): `:android-media` `offline/TakeoutMediaCache`
   (one `SimpleCache` in `files/media`, keyed by ETag via the media items' custom cache key,
   `NoOpCacheEvictor`; playback reads it always and writes only while Playback Caching is on;
   live streams stay out via ExoPlayer's `FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN` — not
   `FLAG_IGNORE_CACHE_FOR_UNSET_LENGTH_REQUESTS`, which bypassed the cache for all playback
   until 2026-10-26; video isn't cached), Media3
   `DownloadManager` + `TakeoutDownloadService` (the app's `DownloadService`), downloads of
   releases, playlists, favorite tracks and podcast episodes, Playback Caching listing tracks
   it cached whole as downloads (off by default, as Flutter; nothing fetched ahead), ✓ / progress badges, the Downloads page
   (groups, play offline, delete one / all with sizes).
   Offline browsing (2026-10-25): shared `api/ResponseCache` (`DbResponseCache`, SQLDelight
   `http_cache`, schema v3 via `2.sqm`): every GET's last response is kept, and a request that
   can't reach the server answers from it (refusals still fail); cleared on sign-out. Phone
   only for now (the TV keeps its own saved index). Checked on the emulator with Wi-Fi and
   data off.
   No mobile-data limits (decided 2026-10-26).
7. **Platform** — done (2026-10-26): Android Auto, Assistant intents/app actions, playlist
   editing, link code.
   - [x] Android Auto: `TakeoutMusicService` is a `MediaLibraryService`; `:android-media`
         `auto/TakeoutBrowseTree` gives Recent, New releases, Added (short titles: the car's
         tabs are narrow), Radio (streams first, grid), Playlists, subscribed Podcasts (grid →
         episodes with played / unplayed marks) and Artists (Recent: last 5 played, Favorite,
         All → Popular / Shuffle / Radio mixes and releases), search, and expands a
         played item (or car voice "play …") into its whole queue. Podcast episodes get back
         10 s / forward 30 s instead of previous / next in the notification and the car
         (`TakeoutMusicService.PodcastButtons`). Manifest: library service
         filters + `automotive_app_desc`. Tested with a Media3 `MediaBrowser` instrumented test
         (`android/src/androidTest/…/BrowseTreeTest`) against the server, and in the user's car
         (2026-10-26; sideloaded builds need Android Auto's "Unknown sources").
   - [x] Assistant (2026-10-25): `MEDIA_PLAY_FROM_SEARCH` (artist / album / song focus, or the
         server's best match; empty = resume) and the Flutter app's `com.takeoutfm.action.*`
         intents with the same server search refs (`voice/VoiceRequests`, unit-tested;
         `VoiceHandler`). Refs resolve through the user's server playlist
         (`TakeoutApi.resolveRef`, `PATCH /api/playlist`), as Flutter does. Like Flutter there's
         no `shortcuts.xml`, so Assistant reaches the custom actions only by explicit intent.
   - [x] Playlist editing (2026-10-25): create, delete, add a track or a whole release ("Add to
         playlist" picker, with New playlist), remove a track — shared `TakeoutApi`
         `createPlaylist` / `addToPlaylist` (server refs `/music/tracks/{id}`,
         `/music/releases/{id}/tracks`) / `removeFromPlaylist` / `deletePlaylist`, mock-tested.
   - [x] Link (2026-10-25): approve another device's code with this account (`POST /api/link`,
         shared `TakeoutApi.link`, Home menu → Link).
8. **Polish & release** — done (2026-10-26):
   - [x] Signing: release builds use `android/key.properties` (same keys as the Flutter app's,
         not committed; `.gitignore` covers keys) or fall back to the debug key; target SDK 36
         like the Flutter app.
   - [x] Tablets / foldables (checked at 1600×2560 and 2560×1600): rail, wider grids; page
         headers (artist, video) are 16:9 but capped at 360dp (`headerSize()`).
   - [x] Accessibility: no unlabeled tappable elements on the main screens (uiautomator
         scan); artwork is decorative (titles beside it are read); at 2× font, app bar titles
         are one line with the subtitle beside, and the album title moves under the cover.
         Home's title still truncates at 2× because of the five icons.
   - [x] Startup: `profileinstaller` ships the libraries' baseline profiles; no app-specific
         generated profile (a generator run can't sign in).
   - [x] Upgrade over the Flutter app (2026-10-26): `android/key.properties` points at the
         Flutter keystore; the signed release has the same certificate (SHA-256 8d25f6b6…) and
         installed over Flutter 0.26.0 (versionCode 2600 → 3000) as an update on the emulator,
         then started at sign-in (Flutter's tokens aren't migrated). Note: Flutter's minSdk was
         24, ours is 28, so Android 7–8 devices won't get the update.
9. **Wear OS**: a standalone watch app (`wear/`) on `shared` + `android-media`:
   - sign-in by link code / device code from the phone or TV (no typing on the watch);
   - browse Recent, Radio, favorites, playlists and downloads with Wear Compose Material 3
     (scrolling lists, rotary input);
   - Media3 playback to Bluetooth headphones with an output switcher prompt when none is
     connected, Now Playing with volume via the crown, live streams with the LIVE badge;
   - downloads to the watch for offline listening (same ETag-keyed cache, Wi-Fi only by
     default), listen reporting and offline queues as on the phone;
   - a Tile (recent queues / play-pause) and a complication.
   Controlling playback on the phone needs nothing extra: Wear OS mirrors the phone's media
   session.
   - [x] Module `wear/` (`com.takeoutfm.wear`, `.debug` for debug builds; versionCode 3000 over
         Flutter's 2600; release key shared with the phone): Wear Compose Material (1.4) home
         list, Recent, New Music, Artists, album/artist pages, Playlists (+ Favorites), Radio,
         Podcasts, Downloads, Settings; Now Playing with progress ring, LIVE badge and the
         stream's song, crown volume. `TakeoutMusicService.configure()` lets the watch
         suppress playback on the speaker (release builds only, so the emulator can play), and
         `onPlayerCreated()` adds Media3's `WearUnsuitableOutputPlaybackSuppressionResolverListener`
         (`media3-ui`), which opens the output picker — suppression alone shows nothing.
   - [x] Tile (title + play/pause, refreshed when the player changes) and complication.
   - [x] Now Playing complication (2026-10-28, `tile/NowPlayingComplicationService`): long text
         (title, artist as its title line) and short text, with a play / pause icon; a stream's
         song from its ICY title; "Not playing" ("Takeout" in short text). Pushed from
         `WatchApp` when the song or play/pause changes (no polling). Checked on the emulator
         on the Utility face.
   - [x] Checked on the `Wear_Round_API_36` emulator (2026-10-26): browse, album play, radio
         (LIVE), tile play/pause.
   - [x] Real watch (Galaxy Watch, 2026-10-26): release installed over the Flutter app;
         browsing, the output picker when no headphones are connected, and bezel volume on
         Now Playing (it needs `rememberActiveFocusRequester()`: a plain `requestFocus()` on
         first composition is dropped inside `SwipeDismissableNavHost`).
   - [x] Faster streaming (2026-10-27, `playback/StreamingNetwork`): near its phone a watch
         routes everything over Bluetooth, slow to start songs (2–19 s measured). While the
         app is open or playing (released 30 s after), it requests a *validated* Wi‑Fi — or
         mobile data too with Settings → Stream over mobile data (off by default) — and binds
         the process to it. Unvalidated Wi‑Fi is ignored: on the user's watch the router blocks
         its Wi‑Fi (Android's check gets a 404 and a cut-off TLS handshake), and binding to it
         failed every request. Speed-up not yet measured on a working Wi‑Fi.
   - [ ] Not yet checked: downloads and offline play, the complication;
         downloads aren't limited to Wi-Fi yet.
10. **Apple — macOS first** (started 2026-10-27; `apple/`, see `apple/README.md`). A native
   SwiftUI app, no Compose UI. Work happens on Linux (the user pulls on their MacBook to build):
   - [x] `shared` gets `macosArm64` (static `Shared` framework) and `appleMain`: Darwin Ktor
         engine, NFD via `NSString`, `UserDefaultsStore`, `AppleDatabase` (SQLDelight native
         driver, in Application Support), `TakeoutCore` (the shared graph), `SwiftCalls`
         (`@Throws` wrappers: an undeclared Kotlin exception crashes Swift), `watch()` for
         Flows, `CodeSignIn`. The klibs compile on Linux
         (`kotlin.native.enableKlibsCrossCompilation`); linking needs the Mac. No SKIE yet: the
         small hand-written bridge is enough and builds here.
   - [x] Skeleton (written on Linux, **not yet compiled**): XcodeGen `project.yml`, Keychain
         store, device-code sign-in, sidebar (Recently Added, New Releases), album pages,
         AVQueuePlayer music with a Now Playing bar, Playback menu, media keys / Control Center.
   - [x] First build on the Mac and fixes (Homebrew JDK fallback, dead-code stripping for
         SQLiter's wrappers, `doNewReleases`). Sign-in and playback not yet tried.
   - [x] Music pages (built, not yet tried with the server): Artists A–Z and artist pages
         (Shuffle / Radio mixes, popular tracks, releases by kind, similar, ♥), Favorites,
         Playlists (when the index has them), Radio, Search (toolbar field, the server's syntax).
   - [x] Recently Played (resumes where it was left), track ♥, a live layout for streams (LIVE,
         the ICY song), Settings → Track Activity, and listens: the shared
         `listen/ListenSession` (ListenTracker's rules as an event-fed state machine, tested in
         commonTest) fed by `AudioPlayer`, reporting through `ListenReporter`. Android's
         `ListenTracker` could move onto `ListenSession` too.
   - [x] Settings: Theme (the shared `PlaySettings.theme`, applied as the app's appearance),
         Show in Menu Bar, Track Activity, ListenBrainz (user token, checked by the shared
         `ListenBrainzAccount`; Submit Listens).
   - [x] Now Playing screen (as the phone's: background, cover, seek, −10 / +30, repeat, ♥,
         queue column), queue popover, Play Next / Add to Queue on tracks.
   - [x] Menu bar mini player (`MenuBarExtra`), app icon (`tools/render-icon.swift` from the
         Play Store artwork).
   - [x] Movies and TV: Watch (Continue Watching, Recently Added, New Releases, TV), Movies and
         TV Shows grids, title pages (Resume / Start Over, seasons, cast, similar, ♥), person
         pages, video results in Search.
   - [x] Video with **VLCKit 3.7.3** (VideoLAN's binary, `apple/fetch-vlckit.sh`; the files are
         MKV with embedded tracks, which AVFoundation can't open): a player window with audio
         and subtitle pickers, resume and saved progress, next episode. VLC can't send the media
         token, and after a redirect it seeks against the presigned URL, which expires in
         15 minutes; so VLC plays from `StreamProxy` (loopback), which fetches every range from
         storage with a fresh-enough presigned URL (re-fetched on a 403). The best audio track and the track labels
         come from `:shared` (`bestAudioTrack`, `audioTrackLabel`; 2026-11-11, #21):
         `AudioTrackRanking.swift` turns VLC's track information into their inputs, with VLC's
         own pick standing in for the "default" flag VLCKit doesn't give. The TV and Linux use
         the shared labels too (2026-11-11; Linux through the `audioTracks` bridge call, which
         replaced `bestAudioTrack`). The phone keeps Media3's settings menu and its track names
         (decided 2026-11-11; its `TrackNameProvider` can't be replaced), with the shared best track.
   - [x] Podcasts: Subscribed / All, podcast pages (Subscribe, episodes with progress, Mark as
         Played), episode pages (Resume / Start Over, show notes with links, older / newer), episodes in the music player resuming where they were left; `AudioPlayer`
         saves their position (by GUID, through `TakeoutProgress`) as Android's
         `PodcastProgress` does.
   - [x] Downloads (`MediaCache`, Swift: Android keeps its index in Media3, so there's no shared
         one): whole files by ETag in Application Support/TakeoutFM/Media with a JSON index,
         grouped by album / playlist / podcast / title; no eviction; playback (AVPlayer and
         VLC) reads them first, so they play offline. Albums, playlists, favorite tracks,
         episodes, movies and TV episodes; a Downloads page.
   - [x] Playback Caching (the shared setting): AVPlayer can't hand over what it streamed, so
         a track or episode played to its end is downloaded into the cache (fetched once
         more) and listed with its queue; skipped ones and streams aren't kept. Downloads
         group by the queue's server location (else its title), the same for manual ones.
11. **Apple — iOS** (started 2026-10-28; same `apple/` project, `TakeoutFM-iOS`,
    `com.takeoutfm.ios`, iOS 17+):
   - [x] `shared` adds `iosArm64` / `iosSimulatorArm64` (appleMain needed no changes).
   - [x] Swift split into shared (`TakeoutFM/`), `macOS/` and `iOS/`; MobileVLCKit 3.7.3 via
         `fetch-vlckit.sh`.
   - [x] iPhone: tabs (Library · Recent · Artists · Radio · Search, decided 2026-10-28, like
         the Android phone app), mini player, Now Playing full screen, Settings (with Sign
         Out); iPad: the Mac's sidebar. Full-screen VLC video player. Background audio.
   - [x] Checked in the iPhone 17 Pro simulator: device-code sign-in, Library, album pages,
         playback, mini player, Now Playing, video (resume, track menus).
   - [x] iPhone design pass (decided 2026-10-28: iPhone conventions, Android's structure): tabs
         Home · Artists · Recent · Radio · Search, a Home tab of artwork rows (Recently Added,
         New Releases, Continue Watching, New Films) over the library's lists; Now Playing as a
         swipe-down card tinted from the cover, swipe the cover to skip; AirPlay (Mac too);
         cover art on the lock screen / Control Center (Mac too); ♥ always shown on touch;
         swipe actions (Play Next / Add to Queue) and haptics.
   - [ ] iPad check; downloads, radio and podcasts on the phone; a real device (needs a team).
   - [ ] Lock screen / Control Center check, picture in picture, CarPlay and Siri.
   - [ ] CarPlay: draft plan in `apple/CARPLAY.md` (2026-11-06), not reviewed or started.
12. **Linux — KDE/Qt** (planned 2026-10-28; `linux/`). No JVM (decided). A Qt 6 / QML app with
    Kirigami (fits Plasma, runs on GNOME too), the shared core as a Kotlin/Native library, and
    libmpv for all playback. Linux is a native Kotlin target, so all of it builds, runs and is
    tested on the dev machine (KDE, Ubuntu 26.04; libmpv 2.5 installed; needs `qt6-base-dev`,
    `qt6-declarative-dev`, `libkirigami-dev`, `cmake`, `libcurl4-openssl-dev`).
   - Why not the others: Compose Desktop needs a JVM; Rust (CXX-Qt / gtk-rs) would reach the
     Kotlin core through the same C library, adding a language for nothing; there are no Qt
     bindings for Kotlin/Native; rewriting the shared logic in C++ means maintaining it twice.
   - Why mpv over VLC: its render API draws into the Qt Quick scene graph (Wayland-native,
     smooth), the best ASS rendering (libass), and it's what KDE's own player (Haruna) uses;
     VLC 3's Qt embedding is awkward under Wayland and VLC-Qt is unmaintained. Embedded MKV
     tracks all work: SRT, ASS/SSA, PGS, VobSub; AC-3/E-AC-3/DTS/TrueHD; 10-bit HEVC; VAAPI.
   - **Shape** (GitLab #14; build plan written 2026-11-01):
     ```
     linux/                       C++ / QML app (CMake + Ninja), app id com.takeoutfm.linux
       src/   Core.{h,cpp}        loads libtakeout.so; call/watch → Qt signals on the main thread
              Player.{h,cpp}      libmpv (one player: music, radio, podcasts, video)
              MpvItem.{h,cpp}     mpv render API in a QQuickFramebufferObject
              Mpris.{h,cpp}       org.mpris.MediaPlayer2 over QtDBus
       qml/   Main.qml, pages (Kirigami), components
     shared/src/linuxMain/        actuals, LinuxCore (the shared graph), the C bridge, downloads
     ```
     The Kotlin library is the Mac's `TakeoutCore` + `SwiftCalls` again, exported as C
     (`linux/src/takeout.h`): `takeout_init(dataDir, userAgent, secrets, saveSecrets, ctx)`,
     `takeout_call(name, argsJson, cb, ctx)` (runs on a Kotlin coroutine; `cb(ctx, resultJson,
     errorJson)` once, on a worker thread), `takeout_watch(name, cb, ctx) → handle` /
     `takeout_unwatch(handle)` for flows (session, library, favorite tracks, Recently Played,
     ListenBrainz, settings). Strings given to callbacks live only for the call, so there's
     no `takeout_free`. Written with `@CName`, so the header is four functions, not
     Kotlin/Native's generated struct API. Results are JSON of the shared domain types
     (`@Serializable`). `Core` turns each call into a `QFuture` / QML promise-style callback
     and re-posts with `QMetaObject::invokeMethod`.
   - **Phases** (each its own commits, checked on this machine against the real server; the
     usual rules about real progress, listens and favorites apply):
     - [x] **L0 — spikes** (done 2026-11-01, branch `spike/linux-l0`; all three pass):
           - (a) `linuxX64` + `ktor-client-curl` 3.1.1: a native test fetches over HTTPS from
             takeout.fm. Ktor 3 links curl and OpenSSL **statically**, so the app needs no
             system libcurl (`libcurl4-openssl-dev` isn't needed). The K/N toolchain is a
             one-time ~300 MB download.
           - (b) A `sharedLib` (13 MB release) with `@CName` functions, called from C++: a plain
             call, a blocking network call, and an async one answering through a C callback
             from a Kotlin thread. The generated header types every pointer `void*`, so the
             app keeps its own small typed header for the four bridge functions.
           - (c) libmpv 0.41 drawing into a `QQuickFramebufferObject` (OpenGL scene graph,
             Wayland, VAAPI on the Radeon 780M) plays a Takeout movie (MKV, H.264) with
             `http-header-fields=Authorization: Bearer <media token>`. Paused 16 min — past the
             15-minute presigned redirect — then seeking to 25:00 **worked**: on a seek ffmpeg
             reopens the original `/location` URL and follows a fresh redirect, unlike VLC.
             **No `StreamProxy` on Linux.**
           - Needed packages: `qt6-base-dev qt6-declarative-dev libkirigami-dev
             extra-cmake-modules libsecret-1-dev`, plus libmpv-dev, cmake and ninja.
     - [x] **L1 — core on Linux** (done 2026-11-01): `linuxX64()` with
           `binaries.sharedLib("takeout")` (19 MB `libtakeout.so`); the SQLite driver and the
           test database moved to `nativeMain` / `nativeTest` (Apple and Linux). `linuxMain`:
           `httpEngine()` = Curl; `decompose()` from a generated table of the 790 precomposed
           Latin, Greek and Cyrillic letters (K/N has no ICU); `LinuxDatabase`; `FileStore`
           (a JSON file per store, written atomically). SQLite is the system's: linked with
           `-L/usr/lib/x86_64-linux-gnu --allow-shlib-undefined`, since K/N's sysroot is glibc
           2.19 and the system library needs newer symbols (present at run time).
           `./gradlew :shared:linuxX64Test` runs `commonTest` natively: 64 tests pass (one
           progress test asserted a single post where Kotlin/Native's slower thread start let
           a second, harmless one through; relaxed). The secret store moved to L2: it's C++
           (libsecret; KWallet answers Secret Service on Plasma 6) reached through the bridge,
           as the Mac's Keychain store is Swift — calling glib from K/N's old sysroot is the
           riskier way. K/N also generates a C API for every public class (35k lines of
           `api.cpp`, a slower link); the app ignores it and declares its four functions.
     - [x] **L2 — bridge** (done 2026-11-01): the domain types (`music/`, `video/`,
           `podcast/`, their enums — K/N needs `@Serializable` on enums too) serialize;
           `linuxMain` `linux/`: `LinuxCore`, `LinuxCalls` (70 named calls: the Mac's
           `SwiftCalls` plus device-code sign-in as `requestCode` / `awaitCode` / `cancelCode`,
           playlist edits, activity, and `listen*` calls feeding one `ListenSession` from the C++
           player) and its watchable flows, and `Bridge.kt` (the four `@CName` functions;
           errors are `{"kind": http|network|argument|unknown|cancelled|failed, "message",
           "status"}`). The secrets are one JSON object the app keeps in the Secret Service:
           `SecretStore` starts from it and hands back the whole object on every change, giving
           the session and ListenBrainz their own key prefixes. `BridgeTest` drives the C
           functions with C callbacks and `MockEngine` (calls, errors, code sign-in saving the
           session, watches); `linux/CMakeLists.txt` has the `bridge_smoke` ctest (init, call
           `version`, watch / unwatch) against the Gradle-built library.
     - [x] **L3 — shell and music browsing** (done 2026-11-01): `linux/CMakeLists.txt` runs
           `:shared:linkReleaseSharedLinuxX64` when shared sources change and copies
           `libtakeout.so` beside the binary (`RPATH $ORIGIN`). The library exports only the
           four `takeout_*` functions (`shared/src/linuxMain/takeout.map`): it links its own
           OpenSSL and curl statically, and with them exported Qt's TLS plugin (loading cover
           images) called into our copy instead of the system `libssl` and crashed. C++:
           `Core` (QML singleton `TakeoutFM.Core`: `call(name, args, (result, error) => …)`
           answered on the main thread, a `failed` signal, and the watched states as
           properties), `SecretStore` (libsecret, one item: schema `com.takeoutfm.linux`), and
           `ImageCache` (a 500 MB `QNetworkDiskCache`; images need no token). QML (Kirigami,
           `org.kde.desktop` style, so the theme follows Plasma): device-code sign-in; a
           global drawer (search field, Home, Artists, Radio, Playlists, Settings — Recent,
           Podcasts, Movies, TV and Downloads come with their phases); Home (Recently Added /
           New Releases), Artists (filter), artist page (Popular, releases by type, similar),
           album page (tracks by disc, similar), Playlists (Favorite Tracks first), Radio by
           kind, search (artists, albums, tracks, stations) and Settings (server, sign out,
           Track Activity). Notes: Kirigami Addons isn't a dependency (so no `Avatar`; round
           photos are `Kirigami.ShadowedImage`, cropped — a hidden `Image` handed to
           `ShadowedTexture` gets squeezed into the circle); grids of tiles are `Flow`s sized from an outer
           width, since a `GridLayout` whose columns follow its own width collapses; pages
           opened with properties see them before `Component.onCompleted`, but their
           `on…Changed` handlers fire too, so load from one or the other. Nothing plays yet.
     - [x] **L4 — music playback** (done 2026-11-01): `Player` (C++, QML singleton
           `TakeoutFM.Core/Player`) keeps the queue as mpv's playlist (`prefetch-playlist`,
           gapless; repeat is `loop-file` / `loop-playlist`); each item is a `loadfile` with
           per-file options (a node map), so the `Authorization` header goes only to streams on
           the signed-in server. Radio streams are the items without an ETag: live, no seek
           bar, no duration (mpv calls the buffered part one); their ICY title
           (`metadata/by-key/icy-title`) shows as "song · artist · station" and feeds
           `ListenSession` and `StreamHistory`. As the Mac's `AudioPlayer`: `listen*` calls,
           Recently Played (`recordPlayed` / `playedProgress` every 5 s and on pause), podcast
           positions (`reportProgress` 10 s in, on pause, complete at the end). The bridge runs
           those reports in the order they were sent (`LinuxCalls.isOrdered`, one-thread
           dispatcher). UI: a mini player under the pages (not Kirigami's window footer, which
           it offsets under the drawer), Now Playing (cover, seek bar, controls, ♥, repeat,
           queue), play / shuffle / radio actions on album, artist, playlist and search
           pages, Recently Played, and Podcasts (series grid, a series' episodes, and an episode page as the
           Mac's: Resume / Play, Start Over, Mark as Played, Download, show notes, older / newer). MPRIS
           (`Mpris`: `org.mpris.MediaPlayer2.takeoutfm`, Raise, play/pause/next/previous,
           Seek / SetPosition, LoopStatus) checked with `qdbus6`. libmpv needs
           `LC_NUMERIC=C`. Not checked here: playing an episode (it changes real progress).
     - **Standard Qt / KDE** (2026-11-01): KDE Frameworks KI18n (QML strings are
           `KI18n.i18n()` / `i18np()` from `import org.kde.ki18n`; `Messages.sh` extracts them),
           KCoreAddons (`KAboutData`: GPL-3.0-or-later, homepage, bugs to the GitLab issues),
           KDBusAddons (`KDBusService::Unique`: a second launch raises the first window),
           KCrash; Kirigami Addons' FormCard for Settings and About. Settings has ListenBrainz as
           the Mac's (user token → Connected as, Submit listens, Disconnect; `Core.listenBrainzUser`
           follows the bridge's `listenBrainz` watch; the token is kept in the Secret Service). `Core`, `Player` and
           `VideoView` register declaratively (`QML_SINGLETON` with `create()` returning
           main()'s instances; `Core` has no default constructor, or QML builds its own) in the
           `TakeoutFM` module, imported by the pages (`import TakeoutFM`: they sit in `qml/`).
           The Now Playing bar is each page's footer (`AppPage`): Kirigami moves the window
           footer left by the sidebar's width. Grids and lists keep no current item until the
           arrow keys pick one (`currentIndex: -1` again on each new model, and the delegate's
           `focus` bound to being current). Colours over video use Kirigami's Complementary set.
     - **Layout as the Mac's** (2026-11-01): the sidebar has the Mac's groups, as
           Kirigami's own drawer actions with separators between them (no group titles, which
           plain actions can't show): Recently Played (the first page), Recently Added, New
           Releases, Artists, Favorites, Playlists, Radio, Podcasts | Watch, Movies, TV Shows |
           Downloads | Settings (which the Mac keeps in its menu); the search field is the
           drawer's header and searches on Enter (`autoAccept: false`). Neither app keeps the
           last search in its sidebar any more (removed 2026-11-01). Recently Added and New Releases are album grids
           (`AlbumGridPage`); Favorites (artists row, tracks with Play All / Download) and
           Watch (Continue Watching, Recently Added, New Releases, TV) are new; Movies and
           TV Shows are poster grids; search lists rows (`CardRow`, horizontal) of artists,
           albums, radio, movies, shows and episodes, then tracks (playing on through the
           results); Recently Played shows "creator · N tracks" and when; the Now Playing bar
           has ♥, a seek bar with times or LIVE, and the queue.
     - [x] **L5 — video** (done 2026-11-01): the best-audio ranking moved to `:shared`
           `video/AudioTrackRanking.kt` (`AudioCodec` instead of Media3 mime types; a table of
           ISO 639-2 codes instead of `java.util.Locale`), its test to `commonTest`;
           `:android-media` keeps `Format.toAudioCandidate()`. New bridge calls
           `bestAudioTrack` and `resumeOffset`. `VideoView` (C++, the spike's `MpvItem`): its
           own mpv (`hwdec=auto-safe`) rendering into a `QQuickFramebufferObject` (so Qt Quick
           runs on OpenGL), listing audio / subtitle tracks with the phone's labels and each
           audio track's ranking candidate. `VideoPlayer.qml`: a popup over the whole window
           (and full screen): resume or start over, best audio (or the language last picked,
           kept in `Settings`), progress every 10 s and on pause, next episode at the end,
           audio / subtitle / speed menus, keys (space, ←/→ 10 s, F, Esc), controls that
           hide. Pages: Movies (Continue Watching with bars, Recently Added, New Releases,
           recommendations, genres; All / Favorites grids), TV, title pages as the phone's
           (facts, Resume · N min left / Start over, cast, crew, seasons or the episode's
           season, More Like This, trailer, favorite), person pages, and video results in
           search. `Core.call` now reports a callback that throws (Qt's JS has no
           `Array.flatMap`). Checked with Godzilla (1954) and Breaking Bad S1E3, each played
           under 10 s from its resume point so nothing was saved.
     - [x] **L6 — downloads and offline** (done 2026-11-01): `linuxMain` `MediaDownloads`, as
           the Mac's `MediaCache`: whole files by ETag in `$XDG_DATA_HOME/takeoutfm/media`
           (keeping the stream's extension), the index (`DownloadEntry`: what it is, its group —
           album / playlist location, `title:<podcast>`, or a video's id) in a `FileStore`, two
           at a time over Ktor (the media token only to Takeout, whose redirect to storage is
           followed without it; streamed to a `.part` file, renamed when whole; the index is
           saved before a download is announced done). Interrupted downloads restart on
           launch. Bridge: `downloadQueue {queue, only?}`, `downloadVideo {id}`,
           `retryDownload`, `removeDownloads`, `removeAllDownloads`, and the `downloads`
           watch; `MediaDownloadsTest` covers the redirect, the token, the file and the index.
           `Core.localFile(etag)`: `Player` and the video player play a download instead of
           streaming. UI: Download / Remove Download on albums, playlists, podcast episodes and
           movie or episode pages (with progress), state on track rows, a Downloads page by
           group (play, retry, remove, Remove All, space used). Offline checked by running
           the app in a network namespace (`unshare -rn`): browsing comes from the response
           cache, downloads play (music and video), but playing an album from its page needs
           the server (its playlist response isn't cached) — as on the phone and the Mac,
           offline playback starts from Downloads. Not ported: Playback Caching.
     - [x] **L7 — packaging** (`.deb` done 2026-11-01): `linux/data/` has
           `com.takeoutfm.linux.desktop` and `.metainfo.xml` (both validate:
           `desktop-file-validate`, `appstreamcli validate`) and the hicolor icons, 16–512 px,
           made by `linux/tools/make-icons.py` from the Mac's source artwork (the exit sign, as
           a rounded square); the 256 px one is also built in as the window icon's fallback.
           `cmake --install` puts `takeoutfm` in `bin`, `libtakeout.so` in `lib/takeoutfm`
           (RUNPATH `$ORIGIN/../lib/takeoutfm`), and the data files in `share`; `cpack` in
           the build directory makes `takeoutfm_0.1_amd64.deb` (13 MB), with shared-library
           dependencies from `dpkg-shlibdeps` plus the QML modules and the Plasma style
           (`qml6-module-org-kde-desktop`) listed by hand. Checked by running the unpacked
           package. Open: the project's licence for the metainfo (`project_license`) and the
           package; Flatpak (KDE runtime 6.x, mpv module) once the app is worth sharing.
     - [ ] **L8 — HTTP through the host: no bundled curl / OpenSSL** (planned 2026-11-08).
           *Why:* Ktor's Linux engine (`ktor-client-curl`, `HttpEngine.linux.kt`) compiles in
           its own libcurl (FTP, telnet, SOCKS… we never use) and **OpenSSL 3.3.2**, ~6 MB of
           `libtakeout.so`'s 20 MB, and frozen: the distribution's OpenSSL security updates
           never reach it. Android (OkHttp) and Apple (Darwin, `NSURLSession`) already use the
           system's TLS; afterwards Linux does too. Only `linuxMain`, the C API and
           `linux/src/core/` change — not `commonMain`, the Android or Apple apps.
           *Who uses HTTP in the core on Linux:* `TakeoutApi` (JSON, all methods, small bodies,
           `HttpTimeout`, refresh on 401), `ListenBrainzAccount`, `MediaDownloads`
           (`followRedirects = false`: it follows Takeout's redirect to presigned storage
           itself, sending the media token only to Takeout; streams GB-size bodies with
           `bodyAsChannel` into `.part` files). mpv and the cover / image loaders already do
           their own HTTP (system libraries) and don't change.
           *Design:*
           - **C API** (`linux/src/takeout.h`): the host registers an HTTP handler before
             `takeout_init` — `takeout_set_http(start, cancel, resume)`. `start(ctx, request)`
             gets one request (JSON: method, URL, headers, timeouts; the body as bytes) and a
             handle; the host answers through `takeout_http_head(handle, status, headers)`,
             `takeout_http_data(handle, bytes, n)` (returns "pause" when the core's buffer is
             full; the host stops reading until `resume(handle)`), and
             `takeout_http_end(handle, error)` (null when complete; else
             `{kind: network|timeout|cancelled, message}`). `cancel(handle)` aborts. Calls from
             the core come on its threads; the host hands them to its main thread.
           - **Kotlin** (`linuxMain` `api/HostEngine.kt`): a Ktor `HttpClientEngine` built on
             those functions (`HttpEngine.linux.kt` returns it instead of `Curl`): the
             response body is a bounded `ByteChannel`, so downloads stream without holding them
             in memory; cancelling the coroutine cancels the host's request; host errors
             become the `IOException`s the rest of the core already treats as "offline";
             `HttpTimeoutCapability` passes connect / socket timeouts to the host (the request
             timeout stays Ktor's). The engine **never follows redirects** — Ktor's plugin and
             `MediaDownloads` decide, so the token rule stays in shared code.
           - **Qt host** (`linux/src/core/HttpHost`, both the KDE app and tofm):
             `QNetworkAccessManager` on the main thread; `ManualRedirectPolicy`; no cache
             (`AlwaysNetwork`) and no cookies for API calls; reads in chunks with
             `setReadBufferSize` for pause / resume; maps `QNetworkReply` errors to the kinds
             above; system proxy settings (`QNetworkProxyFactory::setUseSystemConfiguration`).
             TLS: Qt's OpenSSL backend with the system's libssl, updated by the distribution.
             Careful with Qt decompressing gzip by itself: it changes `Content-Length`, which
             download progress uses (media isn't compressed; JSON is fine).
           *Phases:*
           - [x] **H1** (done 2026-11-08) — the C API (`takeout_set_http`, `takeout_http_head`,
             `takeout_http_data` → 1 to pause, `takeout_http_end`; `linux/HttpBridge.kt`,
             `takeout.h`, exported by `takeout.map` — now a link input) and `api/HostEngine.kt`
             (requests as JSON with merged headers and timeouts; the body through a bounded
             queue into Ktor's ByteChannel — pause above 1 MB unread, resume below 256 KB; the
             call's end cancels a request the host hasn't ended; host errors become
             `IOException`s). `HostEngineTest` (linuxTest, a Kotlin fake host behind the real C
             entry points): GET with headers both ways, POST body and content type, a 302 left
             to the client (and followed by Ktor's plugin as a second request), 8 MB streamed
             with pauses and checked byte by byte (never more than ~2.8 MB ahead of the reader:
             our 1 MB plus Ktor's channel), cancellation, errors before and during the body,
             timeouts passed on. `bridge_smoke` registers a host. The apps still use Curl
             until H3.
           - [x] **H2** (done 2026-11-08) — `linux/src/core/HttpHost` (in `takeoutcore`, so both
             apps; `TakeoutCore::start` installs it before `takeout_init`): a
             `QNetworkAccessManager` on a thread of its own (`takeout-http`, stopped on
             `aboutToQuit`), so a busy UI never stalls HTTP; manual redirects, no cache, no
             cookies, the system proxy; `setReadBufferSize(256 KB)` so a paused request leaves
             the rest in the socket; its own timers for the timeouts (before the head: connect +
             socket; then between reads; none while paused). Qt answers `Accept-Encoding` with
             gzip/deflate/br/zstd and decodes the body, so `Content-Length` / `Content-Encoding`
             aren't passed on then; Qt joins a repeated response header's values ("a, b"). HTTP
             errors (404…) are responses; only connection failures, cut-short bodies and timeouts
             end with an error. `http_host_test` (ctest; a recording sink instead of
             libtakeout.so, a local `QTcpServer`): GET headers both ways, POST body and type, a
             302 not followed, a 404, a deflate body, 8 MB with a slow reader (pauses, never more
             than 1 MB + one read ahead, checked byte by byte), connection refused, a body cut
             short, timeouts before the head and mid-body, cancel (ended once; later cancel /
             resume ignored). The core still uses Curl until H3.
           - [x] **H3** (done 2026-11-08) — `ktor-client-curl` gone (`linuxMain` dependency and the
             version catalog); `HttpEngine.linux.kt` returns `Host`. `libtakeout.so`: 20.3 →
             12.8 MB (9.4 MB stripped); `ldd` lists only libc, libm, sqlite and friends; no
             "OpenSSL" / "libcurl" in its strings; 8 exported `takeout_*` symbols. Shared tests
             (linuxX64, JVM) and the three ctests pass; tofm browses takeout.fm through it
             (playlists, artists, podcasts, "Signed in as", ListenBrainz).
           - **H4** — by hand in both apps: device-code sign-in, browsing, token refresh,
             playback, a download (the redirect to storage, the token only to Takeout),
             ListenBrainz, offline (`unshare -rn`), cancelling (leaving a page mid-load,
             `cancelCode`). So far: a token refresh in the KDE app (a 401 on start, then
             refreshed and saved); a download and playback (checked by the user); offline
             (`unshare -rn build/tofm`: the host's network errors reach the core as "offline",
             and Playlists and Artists come from the saved responses). ListenBrainz submitting (checked by the user). Left:
             cancelling, device-code sign-in.
           *Also, smaller and separate:*
           - [x] Stripped packages (2026-11-08): `CPACK_STRIP_FILES`, plus an install step
             (`takeout_strip_lib`) for `libtakeout.so`, which is installed as a plain file. The
             build directory keeps the debug info. `takeoutfm_*.deb` 13.9 → 3.0 MB (installed
             11 MB), `tofm_*.deb` 9.1 → 2.8 MB (installed 10 MB); before L8 they were 16.4 and
             11.4 MB.
           - [x] One shared core package (2026-11-08): `takeoutfm-core` (named `libtakeout` for a
             day: a lib… name promises a public library with a soname; this one is private to
             our apps; it Conflicts/Replaces `libtakeout`), `/usr/lib/takeoutfm/libtakeout.so`,
             2.4 MB (9.2 MB installed; depends on sqlite); `takeoutfm` (0.55 MB) and `tofm`
             (0.33 MB) depend on `takeoutfm-core (= version)`, as the C API changes with the
             apps, and find it by RPATH `$ORIGIN/../lib/takeoutfm`. Both apps installed: 3.3 MB
             of packages, 12 MB on disk (before L8: 27.8 MB of packages, 84 MB on disk,
             unstripped, a 20 MB library in each).
           *Risks:* Ktor's engine API is lower-level than the client API (follow its
           `CurlClientEngine` / `DarwinClientEngine` as models); the C API's callbacks come
           from Kotlin/Native threads into Qt (marshal everything to the main thread; no
           Kotlin call from inside a callback); downloads must not regress (they're the
           biggest bodies and the token rule matters most there).
   - **Done for #14** when L1–L5 work on this machine (sign in, browse, play music and video,
     listens and progress reported, MPRIS); L6–L7 can follow as their own issues.
   - **Risks**: (L0 settled the libcurl and presigned-URL ones.) K/N has no ICU (sort keys only
     need accent folding); libsecret from K/N cinterop (L1);
     the bridge's JSON cost is fine for pages but not for per-frame state — player position
     stays in C++ (mpv property observers), never crosses the bridge.

13. **Terminal — `tofm`** (planned 2026-11-06; `linux/tui/`). A keyboard-driven terminal client
    for music, radio and podcasts, at home in a desktop terminal, over SSH or on a headless box,
    on the same shared core (`libtakeout.so`). Videos open in an external player.
   - **Toolkit: C++ with FTXUI** (MIT; layouts, scrolling lists, inputs, mouse; renders to a
     string, so screens get snapshot tests). Chosen over Kotlin/Native + Mosaic (no C API,
     but experimental and no reuse of the mpv player), Rust ratatui / Go Bubble Tea (a new
     toolchain for the same C API) and ncurses / notcurses (low-level, or heavier than FTXUI +
     chafa). The KDE app's C++ already does the hard parts (gapless mpv queue, listens, podcast
     progress, ICY titles, MPRIS), so this is mostly screens.
   - **Layout:**

     ```
     linux/src/core/   Qt Core only, for both clients: Core (std::function callbacks, no
                       QJSValue), Player (mpv), Mpris (bus name a parameter), SecretStore
     linux/src/        the KDE app: thin QML wrappers over core/
     linux/tui/        tofm: FTXUI screens over core/, on QCoreApplication (no GUI libraries)
     ```

     One event loop: a `QTimer` drives FTXUI's `Loop::RunOnce()`, so mpv events, D-Bus and
     core callbacks arrive on one thread; redraws via `screen.PostEvent`.
   - **Separate from the KDE app** (decided): its own data (`$XDG_DATA_HOME/tofm`, cache in
     `$XDG_CACHE_HOME/tofm`), its own keyring item (`app = tofm`), MPRIS name
     `org.mpris.MediaPlayer2.tofm`, user agent `TakeoutFM-Terminal/<version>`. Both can run at
     once; each signs in on its own (a separate device on the server). Secrets: libsecret
     when a Secret Service is running, else a file only the user can read (`0600`) in the
     data folder, with a warning the first time (SSH, headless).
   - **Screens:** sign-in (server, then the device code with its `expiresIn` countdown and an
     expired state with a new-code key); a sidebar (Recent, Recently Added, New Releases,
     Artists, Favorites, Playlists, Radio, Podcasts, Movies, TV, Downloads, Settings), the
     current list, and a Now Playing bar (scrolling one-line title, artist, progress with
     times, repeat / ♥). Lists filter as you type (`/`); Enter opens or plays, `a` adds to the
     queue, `n` plays next, `f` favorites. Full Now Playing with the cover and the queue.
     Search with the server's query syntax (artists, albums, tracks, movies, shows). Keys:
     vim-style and arrows, `space` play/pause, `<` `>` previous/next, `[` `]` seek 10 / 30 s,
     `r` repeat (the saved Repeat All), `?` help; the mouse works too.
   - **Album art:** libchafa (`libchafa-dev`) picks the best the terminal can do — the kitty
     graphics protocol with Unicode placeholders (kitty, WezTerm, Ghostty, recent Konsole;
     part of the text grid, so FTXUI redraws keep it), sixel (foot, Konsole, xterm, mlterm;
     redrawn over its reserved box after each render, only when the cover or box changes), or
     24-bit colour half-blocks anywhere. Covers come from the shared core's URLs, fetched with
     Qt Network into `$XDG_CACHE_HOME/tofm/covers`, decoded with `QImage` (no display needed),
     sized from the cell's pixel size (`TIOCGWINSZ`) so they're square, converted once per
     size. Shown on Now Playing (large), album / artist page headers, and optionally in the
     Now Playing bar (off over SSH by default); not in lists. tmux: kitty images with
     `allow-passthrough on`, else half-blocks. Setting: Album art Auto / Graphics / Characters
     / Off.
   - **Video in an external player** (decided): movies and episodes open in `mpv` by default
     (a setting names another command). The media token never goes on the command line,
     where other users can see it in `ps`: the `Authorization` header goes in a temporary
     `0600` mpv config file passed with `--include`, removed when the player exits. Resume with
     `--start=<offset>`; mpv's JSON IPC (`--input-ipc-server` on a socket in
     `$XDG_RUNTIME_DIR`) reports the position, so watch progress is saved through the shared
     `TakeoutProgress` as in the other apps. Downloads play from the local file. Players other
     than mpv get no progress, and only downloaded files (they can't send the header). Over
     SSH with no `$DISPLAY` / `$WAYLAND_DISPLAY`, it says video needs a display.
   - **Command line:** `tofm` opens the UI; `tofm play artist "Beck"` plays straight away via
     the server's search refs (`resolveRef`, as Android voice requests); `tofm --status` prints
     what's playing (for status bars). Control while running: MPRIS (`playerctl -p tofm`).
   - **Phases:**
     - [x] **T0 — split the Linux C++** (done 2026-11-06): `src/core/` (static library
           `takeoutcore`, Qt Core + DBus, libmpv, libsecret): `TakeoutCore` (was `Core`, without
           QML; `start(dataDir, userAgent, secretsApp)`), `Player(core, clientName, userAgent)`,
           `Mpris(player, MprisIdentity{busName, identity, desktopEntry, canRaise})`,
           `SecretStore::load/save(app, …)`. The KDE app's `src/Core` derives from
           `TakeoutCore` for QML (JS-callback `call`, the singleton) and registers `Player` as a
           foreign singleton. Its `ctest` passes; it starts signed in, MPRIS unchanged.
     - [x] **T1 — skeleton** (done 2026-11-06): `linux/tui/` (`main.cpp`, `Tofm`), FTXUI 5
           (`libftxui-dev`) on `QCoreApplication`: a 16 ms `QTimer` runs `Loop::RunOnce()`,
           state changes post `Event::Custom`. Data in `~/.local/share/tofm`, keyring item
           `tofm`, MPRIS `org.mpris.MediaPlayer2.tofm` (no Raise); stderr goes to
           `~/.local/state/tofm/tofm.log`, so Qt / mpv / libsecret warnings don't draw over the
           screen. `SecretStore::setFallbackFile`: with no Secret Service the sign-in goes in
           `secrets.json` (0600, written to a `.part` file and renamed), and the sign-in page
           says so. Sign-in: server field, Enter for a code, the code with its countdown,
           expired state, `n` new code, `Esc` back; signed in: a placeholder page with `s`
           sign out (confirmed) and `q` quit. `tofm --version`, `--help`; refuses to run
           without a terminal. Checked by driving it in a pty (frames read as text): the code
           and countdown, Esc back and quit (terminal restored), and the keyring fallback with
           no D-Bus.
     - [x] **T2 — music** (done 2026-11-06): sidebar (Recent — tofm's own history —, Recently
           Added, New Releases, Artists, Favorites, Now Playing); pages are lists of rows
           (headings, actions like ▶ Play / ⤮ Shuffle / ≋ Radio, albums, artists, tracks) on a
           page stack (`tui/Browse.cpp`): artist (mixes, ♥ artist, top 5 popular, albums /
           singles / other, similar), album (discs, the artist, similar albums), favorites.
           `/` finds as you type. Keys: arrows / `jk` move, Enter / `→` open or play, `←` /
           `Esc` back, `Tab` sidebar, `a` / `n` queue, `f` ♥, space, `<` `>`, **`[` `]` seek
           −10 / +30 s** (the arrows navigate), `r` repeat, `p` Now Playing, `S` sign out, `q`
           quit, mouse wheel. Now Playing bar (scrolling title, ♥ / repeat marks, progress or
           LIVE) and view (`tui/NowPlaying.cpp`: the cover, title, the queue; Enter skips to
           an item). Album art (`tui/Cover.cpp`, `CoverCells.cpp`): fetched with Qt Network
           into `$XDG_CACHE_HOME/tofm/covers`, decoded by `QImage`, turned into block
           characters in 24-bit colour by libchafa (`libchafa-dev`) and drawn as FTXUI cells,
           so redraws keep it and every terminal shows it; sized square from the cell's pixel
           size. Kitty / sixel images move to T4 (they live outside FTXUI's grid). Checked in
           a pty: browsing, finding, playing and pausing, the queue, and the cover (frames
           turned into HTML).
     - [x] **T3 — more** (done 2026-11-06; `tui/Library.cpp`, `tui/Video.cpp`): the sidebar
           shows what the library has (`hasMusic` / `hasPlaylists` / `hasPodcasts` /
           `hasMovies` / `hasShows`, as the KDE app). Search (typing starts on entering it;
           Enter runs music and video searches together: artists, albums, tracks, stations,
           movies, shows, episodes; `/` or `s` for a new one). Playlists (▶ / ⤮, tracks, `a` /
           `n` queue their items), Radio (stations by kind; stream titles via `Player`),
           Podcasts (subscribe, the description, episodes with "12m left" / ✓; they play
           through `episodesQueue`, so `Player` saves their progress). Movies and TV Shows
           (lists with `/`), and a video page: year · rating · length · genres, ▶ Play (a
           series' `playTarget`, e.g. "S1E2") and ↺ Start over, tagline, description, director,
           seasons and episodes, similar. Note rows (descriptions) can't be selected. Video
           plays in **mpv** (`QProcess`; needs a display and mpv, else it says so; one at a
           time; music pauses): the `Authorization` header in a 0600 config file in
           `$XDG_RUNTIME_DIR` passed with `--include` (deleted 5 s later and on exit) — never
           on the command line; `--start` from `resumePositionMs` unless starting over;
           `--no-terminal` and stdin / stdout / stderr to /dev/null so mpv can't touch the
           screen; `--input-ipc-server` socket: `time-pos` / `duration` observed,
           `reportProgress` every 10 s and when mpv closes (or tofm quits). Checked in a pty
           (search, radio, podcasts, movies, a series' seasons) and with a stand-in `mpv` on
           the PATH: the arguments, the 0600 file holding the header, no token on the command
           line, `--start=513.0` (resuming), cleanup; then by the user with real mpv.
     - [x] **T4 — settings and polish** (done 2026-11-08): Settings page (server, user,
           sign out, Track Activity, ListenBrainz connect with a masked token prompt / submit /
           disconnect, Album art On / Off, bar cover — off over SSH; tofm's own in
           `~/.config/tofm/tofm.conf`), `?` help, one-line prompts, covers on album and
           artist pages (~40% of the page) and a 3-line Now Playing bar (cover, "Next:"), the
           terminal's cell size logged. **Decided: covers stay block characters** (no kitty /
           sixel images): one drawing path that works in every terminal, SSH and tmux.
     - [x] **T5 — downloads and packaging** (done 2026-11-08; `tui/Downloads.cpp`):
           ↓ Download / ✕ Remove download on albums, playlists and movies or episodes, `d` on
           a podcast episode; rows show ✓ / ↓ 30% / ✗; a Downloads page by group (size, Enter
           plays the group's downloaded music offline or opens a video, `x` remove, `R` retry,
           ✕ Remove all). Files in `~/.local/share/tofm/media` (the shared core); checked by
           downloading an album and playing it in a network namespace (`unshare -rn`). Rows
           are fitted by hand so their marks always show. `cpack` makes two packages:
           `takeoutfm` (unchanged) and `tofm` (`/usr/bin/tofm`, its own
           `/usr/lib/tofm/libtakeout.so`, `tofm.1`; dependencies from the binary — no
           Kirigami or Qt Quick — and Recommends: mpv).
     - [x] **Later items** (done 2026-11-08): `tofm play [artist|radio|album|song] WORDS…`
           searches (GET, as the Search page) and plays the match the usual way (artist mix
           Popular / Radio, album, matching tracks, a station; plain words: an exact artist,
           album or station name first, then tracks) — **no PATCH of `/api/playlist`**
           (`resolveRef` answered 400 from takeout.fm, and nothing else uses it). A running
           tofm gets the request over its own D-Bus service `com.takeoutfm.tofm` (`/tofm`:
           `Play(kind, words)`, `Status()`); else tofm starts and plays it once signed in.
           `tofm --status` prints "▶ Title · Artist" (⏸ paused) from the running tofm, else
           exits 1. Settings → Video player: a command (`{file}` for the path, else added
           last), empty for mpv; another player opens downloads only and saves no progress
           (only mpv gets the token file and IPC). `tests/tofm_test.cpp` (`ctest`): screens
           rendered off-screen with FTXUI and compared as text (a page with headings, actions,
           a cut title, a note; the sign-in code and expiry; help; settings rows; the bar),
           plus the `play` parsing, `clock`, the scrolling title and the cover conversion; the
           screens run on a core that isn't started (`TofmTest` is a friend of `Tofm`; sizes
           via `columns()` / `rows()`).
           Noticed: *13 Assassins*' video page never finishes loading (`videoDetails`), also
           in T3 — likely the server; not looked into.
   - **Testing:** FTXUI screens rendered to text as `ctest` snapshot tests (half-block covers
     too; kitty / sixel checked for the expected escape codes); the bridge and player tests
     already there; by hand in kitty, Konsole and foot, over SSH, and with video in mpv.
   - **Settled:** the external player is a command with `{file}`; `tofm play …` hands off to a
     running `tofm` over its own D-Bus service.

---

## 6. Testing

- `shared`: unit tests in `commonTest` (all rules from TV, plus caches/sync with fakes);
  a contract test suite against a local Takeout server ❓.
- Android: Compose UI tests for key flows; Media3 playback tested with a fake server;
  Android Auto with the Desktop Head Unit (steps in `takeout_app/ANDROID_AUTO.md`).
- Device matrix: a phone, a tablet/foldable, Android Auto DHU, a Wear OS watch/emulator; TV regression on the
  Chromecast and SHIELD after shared-module changes.

---

## 7. Decisions and open questions

1. Repository: **monorepo** (this repo), `takeout_tv` moved in with its history.
2. Application ID: **`com.takeoutfm.android`** (watch: `com.takeoutfm.wear`), beside the Flutter
   app rather than replacing it (debug builds add `.debug`).
3. Min SDK: **28** (Wear OS 30).
4. Caching: **Media3 cache (chunked) is fine**, playback only; with Playback Caching off, no
   caching at all; **any cached track always plays from the cache**; no caching for live
   streams; per-track cached indicator.
5. Floating play/pause button: **kept** on mobile.
6. Custom radio stations and play next: **in v1**.
7. Picture-in-picture for video: **yes**.
8. Wear OS: **a full native app** (phase 9).
9. Persistence library for `shared`: **SQLDelight** — see below.

### 7.9 Database layer — decided: SQLDelight

What `shared` stores is small and simple: the offline progress and listen queues, Recently
Played, stream history, the downloads index and a JSON response cache — a few tables, a few
hundred rows, keyed lookups and "newest first" lists. Either library handles that easily, so
the choice is about the platforms and the way you like to write it:

| | SQLDelight | Room-KMP |
|---|---|---|
| Style | Write `.sq` files with the SQL; it generates typed Kotlin functions | Annotated `@Entity`/`@Dao` classes; SQL in annotations |
| KMP history | KMP-first for years; widely used on iOS | Stable KMP since Room 2.7 (2025); newer on Apple |
| Apple targets | iOS, macOS, watchOS, tvOS (native driver) | iOS, macOS (check watchOS if an Apple Watch app is ever wanted) |
| Build | Gradle plugin, no annotation processing | KSP for every target (slower native builds) |
| Migrations | `.sqm` files, schema verified at build time | Auto-migrations + schema JSON export |
| Flows | `asFlow().mapToList()` | DAO methods return `Flow` directly |
| Familiar to | anyone comfortable with SQL | Android developers used to Room |

How to choose:
- **If the Apple apps are a real goal (they are) → SQLDelight**: the longest KMP track record
  on Apple, no KSP on Kotlin/Native, and the SQL is the same file every platform runs.
- If you'd rather write Kotlin classes than SQL and only ever cared about Android → Room.
