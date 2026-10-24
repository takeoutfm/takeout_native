# Takeout TV (JetStream-based)

An Android TV app for the Takeout media server, built on Google's **JetStreamCompose** sample
(Compose for TV, Hilt, Media3/ExoPlayer, Coil). The old Leanback/Fragments app
(Google's Leanback `ReferenceAppKotlin` sample adapted for Takeout) is used only as a reference for the Takeout server
API, its icon/banner assets, and screen ideas.

## Status (2026-10-19)

Runs on a **Chromecast with Google TV** (Android 14, release build) and on an Android TV
(Google TV, API 34) emulator, against `https://takeout.fm`. Sign-in, browsing, playback,
resume, Continue Watching, episodes, audio/subtitle tracks, person pages, poster grids,
recommendation tabs and favorites all work; the user is testing day-to-day use on the TV.

All work is committed on `master` (no remote yet). See [Progress](#progress) for the history.

## How it fits together

JetStream's screens talk to one interface, `MovieRepository`, which is bound to
`TakeoutMovieRepository`. That class maps Takeout's JSON onto JetStream's existing entities
(`Movie`, `MovieDetails`, ...), so most stock screens work unchanged. Features JetStream doesn't
have (people, episodes, recommendations) are extra methods on `TakeoutMovieRepository`, injected
directly where needed.

| Piece | File |
|---|---|
| Wire models (Takeout JSON, PascalCase, all fields defaulted) | `data/takeout/ApiModels.kt` |
| HTTP client, bearer auth, refresh on 401 | `data/takeout/TakeoutApi.kt` |
| Encrypted token storage | `data/takeout/TakeoutSession.kt` |
| Watch progress (resume, sync, in-progress list) | `data/takeout/TakeoutProgress.kt` |
| Per-device settings (Continue Watching window) | `data/takeout/TakeoutSettings.kt` |
| Takeout → JetStream mapping, TTL cache with shared in-flight loads | `data/repositories/TakeoutMovieRepository.kt` |
| Library index (tabs/requests), saved + refreshed | `data/takeout/TakeoutLibrary.kt` |
| Device-code sign-in UI / sign-in gate | `presentation/screens/login/*`, `presentation/App.kt` |
| Top bar tabs (fixed + recommendation tabs) | `.../dashboard/DashboardTopBar.kt`, `DashboardScreen.kt`, `DashboardViewModel.kt` |
| Shared 6-column poster grid | `presentation/common/PosterGrid.kt` |
| Movie/series details, Resume / Start over / Favorite | `.../screens/movies/MovieDetails*.kt` |
| Home rows incl. Continue Watching (progress bars on cards) | `.../screens/home/*`, `presentation/common/MoviesRow.kt` |
| Favorites tab | `.../screens/favourites/*` |
| Season/episode rows | `.../screens/movies/EpisodesList.kt` |
| Cast & Crew row (photos, opens person page) | `.../screens/movies/CastAndCrewList.kt` |
| Person page | `.../screens/person/*` |
| Player (stream auth, progress, errors, release) | `.../videoPlayer/VideoPlayerScreen.kt`, `components/RememberPlayer.kt` |
| Audio/subtitle/speed pickers, subtitle rendering | `.../videoPlayer/components/TrackSelection.kt` |
| Best audio track ranking (unit-tested) | `.../videoPlayer/components/AudioTrackRanking.kt` |
| Skeleton loading screens, player spinner | `presentation/common/Skeletons.kt` |
| Focus return to the opened item | `presentation/utils/FocusMemory.kt` |
| Server info + Sign out | `.../profile/AccountsSection.kt` |
| Continue Watching window picker | `.../profile/ContinueWatchingSection.kt` |

### Identity and build
- `applicationId` is `com.takeoutfm.tv`; the Kotlin package/namespace is still
  `com.google.jetstream` (kept to make the first build low-risk; rename later with Android
  Studio's Refactor > Rename).
- `versionCode` is 1. The old Leanback app on devices is `com.takeoutfm.tv` **version 403** with
  a different signing key, so it must be uninstalled first; to ship as an update instead, the
  version code must exceed 403 and the release must use the old app's signing key.
- Branding: launcher icon, TV banner (`mipmap/ic_banner`) and top bar text "TAKEOUT" come from
  the old app. Google TV's launcher caches icons, so an emulator may keep showing the old one.

### Library index
`TakeoutLibrary` holds what the server's library has, from `/api/index` (`HasMovies`,
`HasShows`, `HasRecommendMovies`, `MovieGenres`), plus the current recommendation sets from
`/api/home`. It shapes the UI and which requests are made:
- Tabs: Movies and Genres only with movies, Shows only with shows, a tab per recommendation set
  only when there are recommendations. If the shown tab disappears, the app goes to Home.
- Continue Watching and Search skip `/api/movies` / `/api/tv` when the library lacks them.
- It is saved on the device, so the tab bar is right immediately on launch, then refreshed:
  on launch, sign-in and return to the foreground (if older than 30 s) and when Home is shown
  (if older than 5 min). Signing out or changing servers clears it.
- It is also the one place `/api/home` is fetched (shared by Home's rows and the tabs).

### Navigation and IDs
- One ID space for everything the screens navigate with: movies `m-<id>`, TV series `s-<id>`,
  TV episodes `e-<id>`. `getMovieDetails()` dispatches on the prefix.
- Routes: `MovieDetails/{id}`, `VideoPlayer/{id}?startOver={bool}`, `Person/{peid}`,
  `CategoryMovieList/{genre}`, and on the dashboard `Recommended/{index}`.
- The player gets the real ID (stock JetStream navigated with the literal `{movieId}`
  placeholder, which is why playback first failed).

### Server API used
| Endpoint | Used for |
|---|---|
| `GET /api/code`, `POST /api/code` | Device-code sign-in (approve at `<server>/link`) |
| `GET /api/token` | Refresh tokens (on 401) |
| `GET /api/index` | Library index: which tabs/requests apply, `MovieGenres` for Genres |
| `GET /api/home` | Home rows and recommendation sets (`RecommendMovies`) |
| `GET /api/movies`, `/api/movies/{id}` | Movies tab (and ETag → movie for progress), movie details (`Location`, `ETag`, `IsFavorite`) |
| `GET /api/movie-genres/{name}` | Genre pages |
| `GET /api/tv` | Every series and episode (with ETags); used to match episode progress |
| `GET /api/tv/series`, `/api/tv/series/{id}`, `/api/tv/episodes/{id}` | Shows tab, series details + episodes (`IsFavorite`), episode stream |
| `GET /api/profiles/{peid}` | Person page (bio + movie/TV credits) |
| `GET /api/search?q=` | Search (movies and TV episodes; series are matched in the app) |
| `GET/POST /api/progress` | Watch progress |
| `GET /api/favorite` | Favorites tab (movies + shows) |
| `PUT/DELETE /api/favorite/movies/{imid}`, `/api/favorite/shows/{tvid}` | Favorite button |
| `GET /img/tm/{size}/{path}` | TMDB images via the server (posters w342, backdrops w1280, stills w300, profiles w185/w342) |
| `Location` URLs (`/api/movies/{uuid}/location`, ...) | Streams; redirect to the media file |

Auth: API calls use the **access token**; streams use the **media token**, attached only to
URLs on the signed-in server (`TakeoutSession.mediaAuthorization`).

## Screens

### Search
Movies and TV episodes from `/api/search`, plus TV series whose names match (the server doesn't
search series, so the app matches the cached series list). Up to three rows: Movies, TV Shows,
Episodes; empty ones are hidden. The server takes a Bleve query string where "evil dead" means
evil OR dead, so the app sends every word as required (`+evil +dead`) and lists movies whose
title contains the whole phrase first. Enter, Search, Done and Go all submit; the query stays
in the field and previous results stay visible while new ones load.
**Voice**: a mic button right of the field listens with Android's `SpeechRecognizer` inside the
app (`VoiceSearch.kt`), not Google's voice bar (which draws over the top of the screen): the mic
turns red and the field says "Listening…", words appear as they're heard (the recognizer's sure
words plus its `UNSTABLE_TEXT` guess, which is often all there is during a short phrase), and
it searches when
speaking stops; pressing the mic again stops early. Needs `RECORD_AUDIO`, asked the first time.
Hidden when the device has no recognition service (`<queries>` for `RecognitionService` makes
that check work). Assistant's global search ("Hey Google, find …") doesn't include Takeout; that
would need Google's partner catalog integration.

### Profile
Accounts (server, Sign out), Continue Watching (window setting) and About (app version and
application ID). JetStream's placeholder sections were removed.

### Top bar
Home · *one tab per current recommendation set* · Genres · Movies · Shows · Favorites · Search,
plus the profile avatar. Recommendation sets come from the server config and depend on the date
(e.g. "Halloween Movies" in October); with none, the tab bar is the fixed set. The tab list is a
`DashboardTab` list (fixed `Screens` tabs + recommendation tabs) with focus requesters sized to
it. Many simultaneous sets could crowd the bar.
The highlight pill follows the focused tab immediately, with no slide or colour fade. Landing on
a tab still opens its page, but the pill no longer waits for that navigation (it used to, which
left the newly focused tab dim gray until the page was built, then slid over).

### Home
Featured carousel = new releases (8) · **Continue Watching** · Recently Added row ·
**first recommendation set** (titled with its name, e.g. "Halloween Movies", every movie A–Z on
wide backdrop cards; hidden when there are no recommendations) · New Releases row. New Releases
and Recently Added are limited by the server (`Film.Recent`, default 1 year; `Film.RecentLimit`,
default 50).

**Continue Watching** is one landscape row of the movies and TV episodes the user is part-way
through, most recently watched first, each card with a thin progress bar (offset / duration,
falling back to runtime). Progress offsets only carry the media file's ETag (the server can't look
movies up by ETag), so the app matches them against the cached `/api/movies` list and `/api/tv`
(all series + episodes, joined by `TVID`); unmatched offsets (e.g. music) are dropped. "In
progress" uses the Resume rule below; local saves are merged in so a just-watched title appears
at once. Movies show their backdrop, episodes their still ("Series · S1E2 Name"); selecting a card
opens its details page (Resume / Start over). The row refreshes whenever Home is shown again and
is hidden when empty. How far back it looks is a per-device setting on the Profile screen
(Continue Watching section): 7, 14, **30** (default), 90 days or All time.

### Poster grids (Movies, Shows, genre pages, recommendation tabs)
`PosterGrid`: 6 columns, focused poster shows its title, dashboard top bar hides on scroll.
Movies, Shows and genre pages are A–Z by the server's `SortTitle` / `SortName` (falling back to
the title); recommendation sets are A–Z too. A–Z ignores case, accents and punctuation, so
"Airplane!" comes before "Airplane II". Genre pages add a title header and start focused on
the first poster. Coming back from a title (or, on Genres, from a genre) puts focus back on the
item that was opened. When the screen that had focus goes away (after its exit animation), focus
falls to the first focusable thing, the profile avatar; `DashboardScreen` waits up to 2 s for
that after a return and moves focus down into the page, whose `FocusMemory` picks the item.

### Loading
Browsing screens show pulsing skeletons in the shape of the page (Home, grids, Genres, details,
person); the player shows a spinner while a title loads or buffers. All wait 300 ms, so quick
loads don't flash.

### Favorites
The user's favorite movies and shows from `/api/favorite` (not cached; favorites change from the
other Takeout apps), together A–Z in `PosterGrid`. Movies / TV Shows chips filter by kind (neither
selected = all). With no favorites the tab says so.

### Genres
Formerly JetStream's "Categories". The list comes from `/api/index` (only genres present in the
library), A–Z.

### Details (movies and series)
- **Play** when there's no saved progress; otherwise **Resume from m:ss** plus **Start over**
  (plays from 0:00 via `startOver=true`).
- Series: Play targets the next episode to watch and says so ("Play S1E3", "Resume S1E1 from
  30:33"): the most recently watched episode if unfinished, else the one after it; with no
  progress (or after the finale) S1E1. Specials only play when there are no regular seasons.
- Long titles step down a size and wrap to two lines; credits wrap to three, with "…".
- A failed load shows "Couldn't load this" with a focused **Try again** (details, player and
  person pages).
- **♡ Add to favorites** / **♥ Favorite**: an outlined button after those, from the view's
  `IsFavorite`; toggles with PUT/DELETE (keyed by the movie's IMID or the series' TVID), flipping
  immediately and reverting if the server call fails. All buttons share one rounded-rectangle
  shape, outline and focus highlight included; the row may extend past the text column.
- Credits: Director always; Screenplay and Music only when the server has names (it currently
  doesn't), sharing the row's width. Budget/Revenue as dollars ("$325,000"), "—" when unknown.
- Empty metadata (e.g. an episode's genres) and empty Cast & Crew / Reviews sections are hidden.
- Series: one row per season (specials last), episode cards with still, number, name, runtime;
  selecting an episode plays it.
- Cast & Crew cards show TMDB profile photos (coloured block if none) and open the person page.
  The directors come first, subtitled "Director"; a director who also acts gets one card,
  "Director · as <role>". Writers aren't shown (the server sends `Writing`; they'd push the
  actors right).
- Back from a person page (or from the player, for an episode) returns focus to the cast or
  episode card that was opened, scrolled as it was. The card is remembered in the ViewModel, and
  coming back after the 5 s `WhileSubscribed` window refreshes the details in the background
  instead of showing the skeleton again (a failed refresh keeps what's shown). Pressing Play or
  Start over forgets the card, so Back from the player lands on the buttons.
- Row cards have a fixed width so a long focused title ellipsizes instead of shoving the row.

### Person page
Photo, name, birth (and death) year · birthplace, and the bio, followed by one poster row per
credit type: Movies, Movies · Director, Movies · Writer, TV Shows, TV Shows · Director,
TV Shows · Writer (non-empty only, newest first). Only titles in the library appear. Focus
starts on the bio (keeps the header on screen); selecting it opens the full bio, scrollable a
paragraph at a time.

### Player
- ExoPlayer with decoder fallback; seek steps 10 s; player released on exit.
- Title line: name, then "year • tagline" (episodes use the series' tagline).
- Controls: movies have ♪ (audio), CC (subtitles) and the gear (speed). Episodes add ⏮/⏭ for
  the previous/next episode in play order (hidden at the ends); choosing one replaces the
  player, so Back still returns to where playback started. JetStream's playlist buttons and
  repeat are gone.
- The screen is kept on only while video is playing (screensaver allowed when paused).
- **Media session**: a Media3 `MediaSession` (title in its metadata) lives as long as the player
  screen, so media keys (play/pause, play, pause, fast-forward/rewind = 10 s; from a TV remote over
  HDMI-CEC or a Bluetooth remote) and Assistant voice commands reach the player. The overlay and
  play/pause button follow `isPlaying` as Compose state, so changes made that way show too. No
  `MediaSessionService`: playback stops on leaving the app, so there's no background session.
- Streams: the `Location` URL redirects (307) to the file. ExoPlayer opens it several times per
  start (MKV header, the Cues index near the end, the resume point) and again on seeks, and
  follows the redirect each time, so the server logs ~3 `/location` hits per start.
- **Progress**: `/api/progress`, keyed by the media file's ETag; offset/duration in seconds, ISO
  UTC date, newest wins. Saved every 30 s while playing, on pause and on leaving the player —
  only after playback actually started, so a failed load never resets progress. Resume if ≥ 10 s
  in and not within the last 2 minutes. Recent saves are also kept in memory so reopening right
  away resumes even before the server has the update.
- **Errors**: a "Can't play this title" overlay with the reason (unsupported codec, HTTP status,
  network) instead of a stalled player.
- **Speed**: the gear button opens a picker: 0.5×, 0.75×, Normal, 1.25×, 1.5×, 2×.
- **Audio / subtitles**: embedded tracks only (no server subtitles, by design). ♪ = audio
  picker, CC = subtitle picker with Off. Tracks are labelled by language, name, channel layout or
  Forced/SDH, and codec; tracks this device can't decode are listed greyed out ("Not supported
  on this device"). Subtitles render with Media3's `SubtitleView` (text and PGS/VobSub bitmaps),
  full-screen so they don't move with the controls. Pickers sit outside the auto-hiding controls.
- **Best audio track**: each title starts on the best track the device can play: preferred
  language (last ♪ pick, else the device's; else the file's default), not commentary, most
  channels, better codec (TrueHD/DTS-HD > E-AC-3 > DTS > AC-3 > AAC/Opus), higher bitrate.
  Picked once per title, so a ♪ choice stands. Passthrough formats count when the TV/receiver
  accepts them.
- **Remembered on the device**: the subtitle choice (Off stays off; a track sets the preferred
  subtitle language) and the audio language.

## Music (design — not built yet)

A proposal for adding the server's music library alongside video. Nothing below exists in the
app yet; it records the plan and what the server offers, for review before any code.

### Mode switch
- The top bar's TAKEOUT logo becomes a focusable, labelled switch (`TAKEOUT · Video ▾` /
  `TAKEOUT · Music ▾`). Selecting it swaps the whole tab set; a hidden toggle on the plain logo
  would be hard to discover on a TV.
- **Video**: today's tabs. **Music**: Home · Artists · Favorites · Playlists · Radio · Search.
  Shown only when `/api/index` says `HasMusic`; Playlists only with `HasPlaylists`. Without
  music, the logo stays a plain logo. No Albums tab (an all-releases list is too big; albums are
  reached through artists, search and Home), and no Podcasts for now.
- The last mode is saved per device (`TakeoutSettings`) and the app starts in it. Search is
  shared; it shows the current mode's results first (the server's `/api/search` already returns
  `Artists`, `Releases`, `Tracks` and `Stations` alongside video).

### Screens (reusing JetStream pieces)
| Screen | Built from | Data |
|---|---|---|
| Music Home: Recently Played, Recently Added, New Releases | `MoviesRow`, square tiles | Recently Played from the local play history (below); `/api/home` (`AddedReleases`, `NewReleases`) |
| Artists (A–Z) | `PosterGrid`, square artist images | `/api/artists` |
| Favorites: Play all, favorite artists row, favorite tracks list | `MoviesRow` + a track list | `/api/favorite` (`Artists`, `Tracks`); Play all = `/api/favorite/tracks/playlist`; add/remove with `PUT/DELETE /api/favorite/artists/{arid}`, `…/tracks/{etag}` |
| Artist page: background image, name, genre/years, Play / Shuffle / Radio, rows of albums, singles, popular tracks, similar artists | Person page + details layout | `/api/artists/{id}` (`Image`, `Background`, `Releases`, `Similar`), `/{res}` = `popular`, `singles` |
| Album page: cover, artist, year, Play / Shuffle, track list, similar albums | details screen with a track list in place of Cast & Crew | `/api/releases/{id}` (`Tracks`, `Similar`, `Image`, `Background`) |
| Playlists | grid of named tiles | `/api/playlists`, `/api/playlists/{id}/playlist` |
| Radio | grouped rows (Artist, Genre, Similar, Period, Series, Other, Stream) | `/api/radio`, `/api/radio/{id}/playlist` |

Focus memory, skeletons, voice search and the 6-column grid carry over unchanged. Covers come
from `/img/mb/re/{reid}/front` or `/img/mb/rg/{rgid}/front` (the server proxies Cover Art
Archive), artist images from `/img/fa/{arid}/t/…` and backgrounds from `/img/fa/{arid}/b/…`;
the API responses already carry these paths.

### Playback
- **Queues come from the server**: every "play this" endpoint (`/api/releases/{id}/playlist`,
  `/api/artists/{id}/{res}/playlist`, `/api/radio/{id}/playlist`, `/api/playlists/{id}/playlist`,
  `/api/favorite/tracks/playlist`) returns a spiff playlist: `{playlist: {title, image, track:
  [{title, creator, album, image, location: ["/api/tracks/{uuid}/location"], identifier: [etag],
  size}]}, index, position, type}`. Entries map one-to-one onto Media3 `MediaItem`s (title,
  artist, album, artwork in `MediaMetadata`; the location resolved like video streams, with the
  media token).
- **A background service owns the music player**: a Media3 `MediaSessionService` (later
  `MediaLibraryService` if browsing from Google TV's own UI is wanted) with its own ExoPlayer and
  queue, so music keeps playing while browsing, survives leaving the player screen, and is
  controlled by remote keys and Assistant anywhere ("next song", "pause"). The UI connects with
  a `MediaController`. The video player stays as it is (screen-scoped); starting a video takes
  audio focus and pauses music.
- **Mini player**: a slim bar at the bottom of every music screen while something is queued
  (art, title, artist, play/pause); selecting it opens Now Playing.
- **Now Playing**: large cover over the artist background, title/artist/album, seek bar,
  previous / play-pause / next, shuffle, repeat, and the queue as a row. Left/right on the
  remote seek like video; up opens the queue. Doubles as a "screensaver" view (keep the screen
  on only while playing, as video does).
- **Server activity (opt-in)**: post `{"TrackEvents": [{"ETag", "Date"}]}` to
  `POST /api/activity` once a track has played for half its length or 4 minutes (the Takeout
  Flutter app's rule), behind a per-device setting like its `enableTrackActivity` (off by
  default). Radio stream entries without an ETag are skipped.
- **Play history (local)**: every queue that starts playing is saved on the device as its spiff
  (title, image, entries) plus the index and position reached, most recent first, capped at 50;
  replaying an existing queue moves it to the front instead of adding a copy (matched by the
  playlist's location/ref, else its title and first entry). This is Music Home's **Recently
  Played** row; selecting an entry plays it again from where it was left. It needs no server
  support and is separate from the server activity reporting above.

### Code structure
Music sits beside the video code rather than inside it: video types and screens stay as they
are, the few shared pieces move into their own classes first.

**Shared refactors first (no behaviour change, own commits)**
- `TakeoutUrls`: `absolute()` and `imageUrl()` move out of `TakeoutMovieRepository` into an
  injected class both repositories use; music adds cover / artist image / background paths.
- `TakeoutPlayerFactory`: the stream setup in `rememberPlayer` (media-token
  `ResolvingDataSource`, decoder fallback, debug `EventLogger`) becomes a plain factory used by
  the video screen and the music service, so stream authorization lives in one place.
- Sorting helpers (`sortKey`, `sortedByTitle`, `listFlow`) move to a shared util, since Artists
  are a large A–Z list too (keeps the "no sorting on the main thread" rule easy).
- `TileGrid<T>` / `TileRow<T>`: generic versions of `PosterGrid` / `MoviesRow` taking key,
  image, title and aspect ratio (2:3 poster or 1:1 square), with focus memory and
  `focusOnInitialVisibility` inside; `PosterGrid` becomes a thin wrapper.

**Data: `data/music/`**
- `MusicApiModels.kt`: artists, releases, tracks, stations, playlists and the spiff playlist,
  same conventions as `ApiModels.kt` (PascalCase `@SerialName`, every field defaulted).
- `TakeoutApi` gains the music GETs and `postActivity`; same client, auth and refresh.
- Entities in `data/entities/music/` (`Artist`, `Album`, `Track`, `Station`, `MusicPlaylist`),
  deliberately not `Movie`; JetStream's movie-shaped `MovieRepository` isn't used for music.
- `TakeoutMusicRepository` (Hilt singleton): maps API → entities, caches the big lists, returns
  queues.
- IDs: `ar-` artist, `al-` album, `st-` station, `pl-` playlist, separate from `m-`/`s-`/`e-`.
- `TakeoutLibrary` exposes `hasMusic`/`hasPlaylists`; `TakeoutSettings` adds `mode` and
  `reportTrackActivity`.
- `MusicHistory`: the local Recently Played list (≤ 50 spiffs with index/position), saved on the
  device like `TakeoutProgress`'s local copy; written by the playback service when a queue
  starts and as it advances, read by Music Home.

**Playback: `playback/` (outside `presentation/`, since music outlives screens)**
- `MusicService : MediaSessionService`: owns the music ExoPlayer (from `TakeoutPlayerFactory`),
  its `MediaSession`, queue and audio focus; declared in the manifest with the media-playback
  foreground service type.
- `MusicPlayer` (Hilt singleton): wraps a `MediaController` to the service, exposes
  `StateFlow<NowPlaying>` (queue, index, position, playing) and `play(queue, start)`,
  `playPause()`, `next()`, `shuffle()`. ViewModels use only this.
- `SpiffMapper`: spiff entry → `MediaItem` (metadata, artwork, ETag); pure, unit-tested.
- `ListenReporter`: player listener in the service applying the half-or-4-minutes rule and
  posting `/api/activity` when the setting is on; unit-tested like `TakeoutProgress`.
- The video player stays screen-scoped and unchanged; its audio focus pauses music.

**UI: `presentation/screens/music/`**
- One package per screen (`home`, `artists`, `favorites`, `artist`, `album`, `radio`,
  `playlists`, `nowplaying`), each a screen + ViewModel as for video.
- Mode: `DashboardViewModel` holds it; `dashboardTabs()` takes it and returns the video or music
  tab list; the TAKEOUT logo becomes the switch; the dashboard's inner `NavHost` gets the music
  tab routes; `Screens` splits tab entries into video and music sets.
- `Artist`, `Album` and `NowPlaying` are top-level routes in `App.kt` next to `MovieDetails` and
  `Person`, so Back and focus return work the same way.
- Mini player: an overlay at the `App.kt` level above the `NavHost`, visible across dashboard
  and artist/album pages, hidden on the video player and Now Playing.

**Unchanged**: the package name, video screens, `MovieRepository`, and the CLAUDE.md rules
(server access only in `data/`, no main-thread sorting, initial focus only after layout).

### Phases
Each step is its own commit(s), checked on the emulator and TV.
0. **Shared refactors** (above), with video re-checked for no change.
1. **Browse**: music API models and repository methods, the mode switch, Music Home (Recently
   Added, New Releases), Artists, artist and album pages; Play starts a minimal in-screen
   queue.
2. **Player**: `MediaSessionService`, `MediaController` wiring, mini player, Now Playing, audio
   focus with video.
3. **More sources**: Favorites (artists, tracks, add/remove), Radio, Playlists, and the local
   Recently Played history on Music Home.
4. **Polish**: server activity reporting (opt-in), music search tuning, Google TV now-playing
   card artwork.

### Open questions / server gaps
- Podcasts: left out for now; decide later whether they join Music mode or get their own.
- Lyrics/visualizer: not planned.

## Progress

| Commit | Change |
|---|---|
| `0548968` | Initial import of the JetStream-based port; builds first time |
| `e51d9f6` | Playback fixed (real ID to the player); 10 s seeks; "Play" label |
| `2931b8f` | Watch progress resume/sync, season/episode browser, playback error overlay, Takeout branding |
| `883d733` | Embedded audio track and subtitle selection; subtitle rendering |
| `2ff9a10` | Cast photos; fixed-width row cards |
| `e89bb81` | Start over button next to Resume |
| `e372eb5` | Person page from Cast & Crew |
| `29bee73` | Movies and Shows tabs as A–Z poster grids |
| `cfe586b` | Categories → Genres, list from `/api/index`; genre pages use PosterGrid |
| `260a4e7` | Genre pages sorted A–Z |
| `ce4ef78` | A top bar tab per recommendation set |
| `3c2a2c6` | Design doc rewrite |
| `9b4f7dd` | Favorites tab from `/api/favorite` |
| `a66daf2` | Favorite button on details |
| `b11363f` | Continue Watching row on Home + window setting |
| `451cb38` | Hide empty details sections; doc update |
| `ef71d11` | Next-episode Play, search shows/episodes, speed picker, remembered subtitles, Profile trim, Try again errors, A–Z fix, unit tests |
| `154ddc5` | Search: submit from any keyboard, keep the query, all words must match |
| `fdeaf31` | Whole recommendation set on Home, no Top 10 numbering |
| `919d314`, `9168ea4` | Hide empty Screenplay/Music; shown credits share the row |
| `51be799` | Budget/Revenue as dollars |
| `780465b` | Unsupported tracks shown greyed out; debug EventLogger |
| `0a687f9` | Start on the best playable audio track |
| `60b3e29` | Tagline in the player |
| `23d6bfb` | Previous/next episode buttons; playlist/repeat removed |
| `f1afaef` | Skeleton loading screens, player spinner |
| `f8a2862` | ANR fix on the TV (sorting off the main thread, cheaper A–Z keys, shared requests) |
| `94b78fb` | "Favorites" spelling |
| `5e2a14c` | Library index drives tabs and requests, saved and refreshed; genre focus crash fix |
| `a918af3` | Focus returns to the opened tile/poster |
| `98b9a84` | Outlined button borders match the button shape |
| `cc05ad5` | Keep the screen on while playing |

## Known gaps / next steps
- No Android TV Watch Next row or home-screen channels (proposed as a separate task), and no
  Cast Connect (needs a Cast receiver app first).
- `versionCode` is still 1 and release builds are signed with the debug key, so it can't install
  as an update over the old Leanback app (version 403, different key).
- Next episode doesn't auto-play when one ends.
- Playback speed resets to Normal for each title.
- Each stream start hits `/location` ~3 times (see Player); could follow the redirect once.
- Hiding tabs for a library without movies or shows is untested (the test library has both).
- No music, radio or podcasts yet: see **Music (design — not built yet)** for the plan.
- No chapters: Media3 (1.6) ignores MKV/MP4 chapter markers and the server doesn't store them.
  Plan: the server reads them at scan time (`ffprobe -show_chapters`) and returns
  `Chapters: [{Title, Start}]`; the player then shows ticks on the seek bar and a Chapters picker.

## Testing notes
- Unit tests: `./gradlew :jetstream:testDebugUnitTest` (JUnit 4; Continue Watching window and
  "in progress"/"finished" rules, best audio track ranking, dollar formatting, directors first in Cast & Crew).
- **Use the release build on real TVs**; the debug build is much slower there. Problems that only
  show on the TV (e.g. the Movies tab ANR) don't reproduce on an emulator running on a laptop.
- Debug builds log every title's tracks, decoder support and errors to logcat (`EventLogger`).
- The Chromecast connects over wireless debugging; its port changes whenever wireless debugging
  is toggled, so reconnect (`adb connect` / pairing) after it drops.
- The emulator can't decode 10-bit HEVC (e.g. Superman 2025, The Phoenician Scheme); those show
  the error overlay there but should play on real Google TV hardware.
- H.264 titles that play on the emulator: Breaking Bad episodes (multiple audio/subtitle tracks),
  Godzilla (1954).

## Build and run
Open this folder in Android Studio (JDK 17), sync, and run the `jetstream` configuration on an
Android TV emulator (API 28+). From the command line (with `local.properties` pointing at the
SDK):

```
emulator -avd <android-tv-avd> &
./gradlew :jetstream:installDebug
adb shell monkey -p com.takeoutfm.tv -c android.intent.category.LEANBACK_LAUNCHER 1
```

On a real TV, install the release build (minified, with the baseline profile); with several
devices connected, add `-s <device>`. `installDebug` installs on every connected device.

```
./gradlew :jetstream:assembleRelease
adb install -r jetstream/build/outputs/apk/release/jetstream-release.apk
```

On the emulator, a server on the host machine is `http://10.0.2.2:<port>` (cleartext is allowed).
