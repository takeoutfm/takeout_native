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
| Watch progress (resume, sync) | `data/takeout/TakeoutProgress.kt` |
| Season/episode rows | `.../screens/movies/EpisodesList.kt` |
| Server info + Sign out | `.../profile/AccountsSection.kt` |

- Package/namespace is still `com.google.jetstream` (to keep the first build low-risk);
  `applicationId` is `com.takeoutfm.tv`. Use Android Studio's Refactor > Rename on the package
  later if you want them to match.
- IDs: movies are `m-<id>`, TV series are `s-<id>`, TV episodes are `e-<id>`.
- Watch progress uses `/api/progress`, keyed by the media file's ETag (seconds, ISO UTC date).
  Saved every 30 s, on pause and on leaving the player; Play becomes "Resume from m:ss".
- Home rows: Featured = new releases, Recently Added, Recommended, New Releases.
- Categories = standard TMDB genre list -> `/api/movie-genres/<name>`.
- Sign-in: device code. Enter server, press Get code, approve at `<server>/link`.

## NOT built yet / known gaps
- Series Play starts the first episode, not the next unwatched one.
- Favourites tab is empty (Takeout has watch progress, not favourites).
- No Continue Watching row, Watch Next, home channels, Cast Connect.
- Search covers movies only (what `/api/search` returns for video).
- Profile screen's other sections (About, Language, ...) are JetStream placeholders.
- Failed detail loads show an "Unable to load" stub rather than a proper error state.

- The emulator can't decode 10-bit HEVC; such titles show a "Can't play this title" message there.

## Build
Open this folder in Android Studio (JDK 17), sync, run the `jetstream` configuration on an
Android TV emulator (API 28+).
