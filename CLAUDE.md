# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Native clients for the Takeout media server (a separate Go project), in one Gradle build.
**`PLAN.md`** is the roadmap (modules, phases, decisions) for the shared core, the phone/tablet
app and later Wear OS / Apple apps; keep it updated as phases land.

| Module | What |
|---|---|
| `:tv` | Takeout TV — Android TV app built on Google's JetStream Compose-for-TV sample (Compose for TV, Hilt, Media3/ExoPlayer, Coil). Design, every screen's behaviour and known gaps: **`tv/TAKEOUT_PORT.md`**; read it before larger TV changes and keep it updated. |
| `:tv-benchmark` | Macrobenchmarks / baseline-profile generation for `:tv`. |
| `:shared` | Kotlin Multiplatform core shared by all apps (Android target only for now): API models (`com.takeoutfm.shared.model`), listen/progress rules, `TakeoutPaths`, sort keys. No Android or `java.*` imports in `commonMain` (platform bits go in `androidMain` as `actual`s); use kotlinx-datetime, not `java.time`. Tests live in `commonTest` (`kotlin.test`). |

## Commands

JDK 17; `local.properties` must point `sdk.dir` at the Android SDK (it isn't committed).

```
./gradlew :tv:assembleDebug                 # debug APK
./gradlew :tv:assembleRelease               # minified release APK (debug-signed)
./gradlew :tv:testDebugUnitTest             # TV JVM unit tests (JUnit 4)
./gradlew :tv:testDebugUnitTest --tests '*AudioTrackRankingTest'   # one test class
./gradlew :shared:testDebugUnitTest         # shared commonTest, run on the JVM
./gradlew :tv:compileDebugKotlin            # fast compile check
```

- `installDebug` installs on **every** connected device. To target one, build and use
  `adb -s <serial> install -r <apk>` (serials from `adb devices -l`).
- Real TV (Chromecast with Google TV, wireless debugging): always install the **release** APK
  (`tv/build/outputs/apk/release/tv-release.apk`); debug builds are far slower and
  hide/cause problems that don't match real use. Its adb serial/port changes whenever wireless
  debugging is toggled — re-check `adb devices -l`.
- Launch: `adb shell monkey -p com.takeoutfm.tv -c android.intent.category.LEANBACK_LAUNCHER 1`.
- Formatting: Spotless/ktlint is configured via `buildscripts/init.gradle.kts`
  (`./gradlew --init-script buildscripts/init.gradle.kts spotlessApply`) but hasn't been used in
  this port; it would stamp Google's license header (`spotless/copyright.kt`) onto Takeout files.

## TV architecture

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
    *media* token, only for URLs on the signed-in server), `TakeoutUrls` (full server URLs for
    locations, images and release covers).
  - `library/TakeoutLibrary`: the `/api/index` library index + recommendation sets — decides
    which tabs exist and which requests are made; saved on device and refreshed on
    launch/foreground/sign-in/Home.
  - `music/`: `TakeoutMusicRepository` and the music entities.
  - `video/`: `TakeoutVideoRepository` (movies, TV, people, search, Continue Watching via a
    `ProgressSource`, which `TakeoutProgress` implements) and the video entities.
  - `listen/`: `ListenBrainzAccount` (Ktor) and the listen rules; `progress/`, `util/`, `model/`.
  - `store/KeyValueStore`: how shared classes save values; `AndroidStores` (androidMain) gives
    plain and EncryptedSharedPreferences-backed ones.
  - Shared classes have no Hilt annotations: `data/takeout/TakeoutModule.kt` provides them,
    using the same preferences files as before the move (`takeout_session`,
    `takeout_library`, `listenbrainz`) so saved state carries over. HTTP code is tested with
    Ktor's `MockEngine`.
  - **Database** (SQLDelight, `src/commonMain/sqldelight/…/db/*.sq`, file `takeout.db`):
    `progress/TakeoutProgress` (watch progress keyed by media ETag, pending until the server
    has it), `listen/ListenReporter` (unsent plays), `music/MusicHistory` (Recently Played).
    Writes go through `DbWriter` (one at a time, in order, off the caller's thread). Schema
    changes need a `.sqm` migration. Store tests use an in-memory JDBC driver (`testDb()`).
    `data/takeout/LegacyImport` moves pre-database SharedPreferences JSON in once.
- **`data/takeout/`:** `TakeoutSettings` (per-device prefs), `TakeoutModule` (Hilt wiring for the
  shared core). Move more into `:shared` per `PLAN.md` phase 0.
- **IDs:** one navigable id space — movies `m-<id>`, TV series `s-<id>`, episodes `e-<id>`;
  `getMovieDetails()` dispatches on the prefix. Progress offsets carry only an ETag, so titles are
  found by matching ETags against cached `/api/movies` and `/api/tv`.
- **Navigation:** `presentation/App.kt` (sign-in gate + top-level NavHost: details, player,
  person, genre pages); `presentation/screens/dashboard/` (tab bar built from `TakeoutLibrary`,
  including one tab per recommendation set).
- **Player:** `playback/TakeoutPlayerFactory.kt` sets up ExoPlayer for Takeout streams (media
  token via `ResolvingDataSource`, decoder fallback, debug `EventLogger`), shared by video and
  the planned music playback; `presentation/screens/videoPlayer/` — `RememberPlayer.kt` adds the
  video-specific options; track pickers and the best-audio-track ranking live in `components/`.
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
- **Music playback:** `playback/MusicService` (background `MediaSessionService`) plays; the UI
  only talks to `playback/MusicPlayer` (a `MediaController` singleton with `NowPlaying` state).
  The mini player is an overlay in `App.kt`. Video playback stays screen-scoped.
- **Play reporting:** `ListenReporter` (`:shared`; Takeout activity + ListenBrainz, per the
  Music settings, with an on-device retry queue), `ListenBrainzAccount` (`:shared`, encrypted
  token), `data/listen/TokenHandoff` (one-time local web page + QR for entering the token from a phone).
  `playback/ListenTracker` decides when a track counts. Don't test with real accounts: the
  settings change the user's real Takeout stats / ListenBrainz profile.

## Rules learned the hard way

- Never sort/map the whole library or decode large JSON on the main thread — the Movies tab was
  killed for ANR on the Chromecast (it was fine on the emulator). Use `listFlow` (runs on
  `Dispatchers.Default`) / `withContext`, and compute sort keys once (`sortedByTitle`).
- Focus: request initial focus only once an item is laid out (`focusOnInitialVisibility`), never
  straight after the list exists — that crashes lazy grids.
- Subtitles and audio are **embedded tracks only**; don't add server-side subtitles.
- Server limits like New Releases' count come from server config (`Film.RecentLimit`), not the app.

## Testing on devices

- On-device checks use `adb shell input keyevent …` plus `screencap`. Take a screenshot after
  every few key presses and never press select without seeing where focus is — the Profile screen
  has a **Sign out** card.
- The emulator can't decode 10-bit HEVC (e.g. Superman 2025, The Phoenician Scheme) or AC-3 audio.
  H.264 test titles: Breaking Bad episodes (multiple audio/subtitle tracks), Godzilla (1954).
- Playing a title for ≥10 s changes the user's real watch progress on the server; keep test
  playback short.

## Working with the user

- Don't commit until asked; commit messages end with the `Co-Authored-By` trailer. There is no
  git remote.
- After UI changes, verify on the emulator (and the TV when connected) before reporting done.
