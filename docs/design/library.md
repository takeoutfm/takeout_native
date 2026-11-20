# Library and browsing — design

Requirements: [../requirements/library.md](../requirements/library.md).

- `:shared` `library/TakeoutLibrary`: the `/api/index` library index and the recommendation
  sets, saved on the device (`takeout_library`), refreshed by `refresh(maxAgeMs)`; asks
  `/api/me` for the user's name.
- `music/TakeoutMusicRepository`: home lists, artists, releases, favorites, playlists
  (`createPlaylist`, `addToPlaylist`, `removeFromPlaylist`, `deletePlaylist`), search; ids
  `ar-`, `al-`, `t-`.
- `video/TakeoutVideoRepository`: movies, TV, people, genres, search, Continue Watching
  (through `TakeoutProgress`); ids `m-` (movie), `s-` (series), `e-` (episode).
- `podcast/TakeoutPodcastRepository`: [podcasts.md](podcasts.md).
- `util/Lists.kt`: `listFlow` (sorting and mapping off the main thread), `sortKey`,
  `sortedByTitle`. Never sort the whole library on the main thread (the TV was killed for an
  ANR doing it).
- Offline: `api/ResponseCache.kt` `DbResponseCache`, set as `TakeoutApi.responseCache` by every app but the TV,
  answers a GET that can't reach the server with the last response and reuses recent library
  responses; `TakeoutApi.forgetKept` drops them after an edit or Reload. The guide is never
  kept (`TakeoutApi.guide`): its `Time` is the app's idea of the server's clock.

| App | Screens |
|---|---|
| Phone | `ui/home/` (media bar over `TileGrid`), `ui/music/`, `ui/video/`, `ui/main/TabHost` routes |
| TV | `presentation/screens/` (JetStream's, mapped by `TakeoutMovieRepository`; music in `screens/music/`); [tv.md](tv.md) |
| Watch | `WatchApp` pages |
| Mac/iOS | `TakeoutFM/*Views.swift` |
| Linux | `qml/*Page.qml` |
| tofm | `tui/Browse.cpp`, `Library.cpp`, `Video.cpp` |
