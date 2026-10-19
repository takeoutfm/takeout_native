# Takeout TV (JetStream-based)

A fresh Android TV app for Takeout built on Google's **JetStreamCompose** sample
(Compose for TV, Hilt, Media3, Coil). The old Leanback/Fragments app was used only as a
reference for the Takeout server API.

## How it fits together

JetStream's screens talk to one interface, `MovieRepository`. That interface is now bound
to `TakeoutMovieRepository`, which maps Takeout data onto JetStream's existing entities
(`Movie`, `MovieDetails`, ...). The stock screens are otherwise unchanged.

| Piece | File |
|---|---|
| Wire models (Takeout JSON) | `data/takeout/ApiModels.kt` |
| Encrypted token storage | `data/takeout/TakeoutSession.kt` |
| HTTP client + token refresh | `data/takeout/TakeoutApi.kt` |
| Takeout -> JetStream mapping | `data/repositories/TakeoutMovieRepository.kt` |
| Device-code sign-in UI | `presentation/screens/login/*` |
| Sign-in gate | `presentation/App.kt` |
| Stream auth (media token) | `.../videoPlayer/components/RememberPlayer.kt` |
| Server info + Sign out | `.../profile/AccountsSection.kt` |

- Package/namespace is still `com.google.jetstream` (to keep the first build low-risk);
  `applicationId` is `com.takeoutfm.tv`. Use Android Studio's Refactor > Rename on the package
  later if you want them to match.
- IDs: movies are `m-<id>`, TV series are `s-<id>`.
- Home rows: Featured = new releases, Recently Added, Recommended, New Releases.
- Categories = standard TMDB genre list -> `/api/movie-genres/<name>`.
- Sign-in: device code. Enter server, press Get code, approve at `<server>/link`.

## NOT built yet / known gaps
- **Not compiled or run.** Written without an Android toolchain; expect a build error or two.
- TV series play the first episode only (no season/episode browser).
- Favourites tab is empty (Takeout has watch progress, not favourites).
- No resume/watch-progress sync (`/api/progress`), Watch Next, home channels, Cast Connect.
- Search covers movies only (what `/api/search` returns for video).
- Launcher icon / TV banner are still JetStream's. Replace `mipmap/app_banner` + launcher icons.
- Profile screen's other sections (About, Language, ...) are JetStream placeholders.
- Failed detail loads show an "Unable to load" stub rather than a proper error state.

## Build
Open this folder in Android Studio (JDK 17), sync, run the `jetstream` configuration on an
Android TV emulator (API 28+).
