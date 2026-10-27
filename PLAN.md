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
   reporting, offline cache — shared by `takeout_tv` and `takeout_android`, so a fix lands in
   both.
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
takeout_android/                     (this repo: one Gradle build — see 2.3)
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
- **Not shared**: UI and playback. SwiftUI and AVFoundation give the best native result on
  Apple; Compose Multiplatform for iOS is possible later but not recommended for a media app
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

- **Application ID**: reuse `com.takeoutfm.mobile` so the native app *replaces* the Flutter app
  in place (same signing key, `versionCode` above 2600). Debug builds use an
  `applicationIdSuffix = ".debug"` so they install beside the Flutter app during development.
- Wear OS: reuse `com.takeoutfm.watch` (the Flutter watch app's id) so it also replaces it in place
  (same signing key).
- Kotlin package: `com.takeoutfm.*` for all new code (the TV app still uses JetStream's
  `com.google.jetstream`; rename it when moving into the monorepo).

---

## 3. What comes from `takeout_tv`

Already written, tested on devices, and extracted into `shared`/`android-media` in phase 0:

- `TakeoutApi` (bearer + media token, refresh on 401, decoding off the main thread),
  `TakeoutSession` (encrypted tokens), `TakeoutUrls`, `TakeoutLibrary` (index + home, saved),
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
      - With Playback Caching on, starting a queue also enqueues a `DownloadRequest` per track
        in Media3's `DownloadManager`: tracks finish even if skipped, show on the Downloads
        page, and share the same cache (a half-downloaded track that's playing isn't fetched
        twice).
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
1. **App skeleton** — done (2026-10-25): `:android` (`com.takeoutfm.mobile`, versionCode 3000);
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
5. **Podcasts** — done (2026-10-25): Home's Podcasts (all / subscribed, shown when the index
   has podcasts), podcast pages (Subscribe, description, episodes with date, "N min left" /
   Complete), playback through the music service with progress saved by GUID (shared
   `TakeoutPodcastRepository`, `:android-media` `PodcastProgress`; episodes aren't listens),
   resume. Downloads come with phase 6.
6. **Offline** — mostly done (2026-10-25): `:android-media` `offline/TakeoutMediaCache`
   (one `SimpleCache` in `files/media`, keyed by ETag via the media items' custom cache key,
   `NoOpCacheEvictor`; playback reads it always and writes only while Playback Caching is on;
   unset-length requests (live streams) bypass it; video isn't cached), Media3
   `DownloadManager` + `TakeoutDownloadService` (the app's `DownloadService`), downloads of
   releases, playlists, favorite tracks and podcast episodes, Playback Caching downloading the
   whole queue when on (off by default, as Flutter), ✓ / progress badges, the Downloads page
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
   - [x] Module `wear/` (`com.takeoutfm.watch`, `.debug` for debug builds; versionCode 3000 over
         Flutter's 2600; release key shared with the phone): Wear Compose Material (1.4) home
         list, Recent, New Music, Artists, album/artist pages, Playlists (+ Favorites), Radio,
         Podcasts, Downloads, Settings; Now Playing with progress ring, LIVE badge and the
         stream's song, crown volume. `TakeoutMusicService.configure()` lets the watch
         suppress playback on the speaker (release builds only, so the emulator can play), and
         `onPlayerCreated()` adds Media3's `WearUnsuitableOutputPlaybackSuppressionResolverListener`
         (`media3-ui`), which opens the output picker — suppression alone shows nothing.
   - [x] Tile (title + play/pause, refreshed when the player changes) and complication.
   - [x] Checked on the `Wear_Round_API_36` emulator (2026-10-26): browse, album play, radio
         (LIVE), tile play/pause.
   - [x] Real watch (Galaxy Watch, 2026-10-26): release installed over the Flutter app;
         browsing, and the output picker when no headphones are connected.
   - [ ] Not yet checked: downloads and offline play, the complication, crown volume;
         downloads aren't limited to Wi-Fi yet.
10. **Later — Apple**: add `iosArm64`/`iosSimulatorArm64`/`macosArm64` targets to `shared`, then
   SwiftUI apps with AVFoundation, Now Playing, CarPlay and Siri intents.

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
2. Application ID: **`com.takeoutfm.mobile`**, replacing the Flutter app (debug builds add
   `.debug`).
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
