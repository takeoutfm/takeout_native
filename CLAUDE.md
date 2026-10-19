# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Takeout TV is an Android TV client for the Takeout media server (a separate Go project), built on
Google's JetStream Compose-for-TV sample (Compose for TV, Hilt, Media3/ExoPlayer, Coil). The full
design — server API usage, every screen's behaviour, commit history, known gaps — is in
**`TAKEOUT_PORT.md`**; read it before larger changes and keep it updated when behaviour changes.

## Commands

The app module is `:jetstream` (`:benchmark` holds macrobenchmarks/baseline-profile generation).
JDK 17; `local.properties` must point `sdk.dir` at the Android SDK (it isn't committed).

```
./gradlew :jetstream:assembleDebug                 # debug APK
./gradlew :jetstream:assembleRelease               # minified release APK (debug-signed)
./gradlew :jetstream:testDebugUnitTest             # JVM unit tests (JUnit 4)
./gradlew :jetstream:testDebugUnitTest --tests '*AudioTrackRankingTest'   # one test class
./gradlew :jetstream:compileDebugKotlin            # fast compile check
```

- `installDebug` installs on **every** connected device. To target one, build and use
  `adb -s <serial> install -r <apk>` (serials from `adb devices -l`).
- Real TV (Chromecast with Google TV, wireless debugging): always install the **release** APK
  (`jetstream/build/outputs/apk/release/jetstream-release.apk`); debug builds are far slower and
  hide/cause problems that don't match real use. Its adb serial/port changes whenever wireless
  debugging is toggled — re-check `adb devices -l`.
- Launch: `adb shell monkey -p com.takeoutfm.tv -c android.intent.category.LEANBACK_LAUNCHER 1`.
- Formatting: Spotless/ktlint is configured via `buildscripts/init.gradle.kts`
  (`./gradlew --init-script buildscripts/init.gradle.kts spotlessApply`) but hasn't been used in
  this port; it would stamp Google's license header (`spotless/copyright.kt`) onto Takeout files.

## Architecture

- **Identity:** `applicationId` is `com.takeoutfm.tv`, but the Kotlin package/namespace is still
  `com.google.jetstream`. Paths below are under `jetstream/src/main/java/com/google/jetstream/`.
- **Data flow:** JetStream's screens talk to the `MovieRepository` interface, bound (Hilt, in
  `JetStreamApplication.kt`) to `data/repositories/TakeoutMovieRepository.kt`, which maps Takeout
  JSON (`data/takeout/ApiModels.kt`, PascalCase, every field defaulted) onto JetStream entities
  (`Movie`, `MovieDetails`, …). Features JetStream lacks (people, episodes, favorites, Continue
  Watching, recommendations, search results) are extra methods on `TakeoutMovieRepository`,
  injected directly where needed. Keep server access inside the `data/` layer.
- **`data/takeout/`:** `TakeoutApi` (OkHttp, bearer access token, refresh on 401, JSON decoded
  off the main thread), `TakeoutSession` (encrypted tokens; streams use the separate *media* token,
  only for URLs on the signed-in server), `TakeoutLibrary` (the `/api/index` library index +
  recommendation sets — decides which tabs exist and which requests are made; saved on device and
  refreshed on launch/foreground/sign-in/Home), `TakeoutProgress` (watch progress keyed by media
  ETag), `TakeoutSettings` (per-device prefs).
- **IDs:** one navigable id space — movies `m-<id>`, TV series `s-<id>`, episodes `e-<id>`;
  `getMovieDetails()` dispatches on the prefix. Progress offsets carry only an ETag, so titles are
  found by matching ETags against cached `/api/movies` and `/api/tv`.
- **Navigation:** `presentation/App.kt` (sign-in gate + top-level NavHost: details, player,
  person, genre pages); `presentation/screens/dashboard/` (tab bar built from `TakeoutLibrary`,
  including one tab per recommendation set).
- **Player:** `presentation/screens/videoPlayer/` — `RememberPlayer.kt` builds ExoPlayer (media
  token via `ResolvingDataSource`, decoder fallback, debug `EventLogger`); track pickers and the
  best-audio-track ranking live in `components/`.
- **Shared UI:** `presentation/common/PosterGrid.kt` (all 6-column grids), `MoviesRow.kt`,
  `Skeletons.kt` (loading states), `presentation/utils/FocusMemory.kt` (return focus to the item
  that was opened).

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
