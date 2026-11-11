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
         own pick standing in for the "default" flag VLCKit doesn't give.
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
   - **Done for #14** when L1–L5 work on this machine (sign in, browse, play music and video,
     listens and progress reported, MPRIS); L6–L7 can follow as their own issues.
   - **Risks**: (L0 settled the libcurl and presigned-URL ones.) K/N has no ICU (sort keys only
     need accent folding); libsecret from K/N cinterop (L1);
     the bridge's JSON cost is fine for pages but not for per-frame state — player position
     stays in C++ (mpv property observers), never crosses the bridge.

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
