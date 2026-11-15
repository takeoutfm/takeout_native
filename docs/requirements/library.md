# Library and browsing

What the server's library offers and how the apps show it: home, artists, releases, movies
and TV, search, favorites, playlists, activity, and browsing offline. Design:
[../design/library.md](../design/library.md).

## The library

- **LIB-1** Which sections exist comes from the server's library index (`/api/index`): music,
  movies, TV, podcasts, live channels; an app shows only what the library has.
- **LIB-2** The index is kept on the device and refreshed at start, on returning to the
  foreground, on sign-in and from Home (Reload).
- **LIB-3** Recommendation sets the server offers are shown as lists (the TV gives each a tab).

## Music

- **LIB-4** Home: Recently Added and New Releases as cover grids.
- **LIB-5** Artists A–Z, by artist type where the server has types.
- **LIB-6** An artist: Shuffle, Popular and Radio mixes, releases, singles, popular tracks,
  similar artists; favorite the artist.
- **LIB-7** A release: tracks by disc, play and shuffle, download, similar releases.
- **LIB-8** Favorites: tracks (♥ on any track), artists, and favorite artists' tracks as a
  queue.

## Movies and TV

- **LIB-9** Movies and TV shows as poster grids (recently added, new releases, by genre);
  Continue Watching.
- **LIB-10** A title: facts, cast and crew (directors first), Play / Resume / Start over,
  "N min left", related titles; a series' seasons and episodes.
- **LIB-11** A person: what they starred in, directed and wrote.

## Search, playlists, activity

- **LIB-12** Search across artists, releases, tracks, stations, movies, series and episodes,
  with the server's query syntax (`artist:name`, `guitar:person`).
- **LIB-13** Playlists: list and play; create, add a track, remove a track, delete.
- **LIB-14** Activity: the server's play stats by period (recent, this and last week and
  month, …) for artists, releases and tracks.

## Offline

- **LIB-15** Without the server, what was browsed before shows from the device (the last
  response kept for each page); recent library responses are reused for a while (an hour;
  podcast episode lists 15 minutes) until Reload or an edit. Progress, activity, favorites,
  playlists and search always ask the server.

## Status

| Rule | Phone | TV | Watch | Mac/iOS | Linux | tofm |
|---|---|---|---|---|---|---|
| LIB-1/2 index | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| LIB-4–7 music browsing | ✅ | ✅ | ⚠️ albums, artists, playlists only | ✅ | ✅ | ✅ |
| LIB-6 favorite artist | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ |
| LIB-9–11 movies and TV | ✅ | ✅ | — no video | ✅ | ✅ | ⚠️ no person pages |
| LIB-12 search | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ |
| LIB-13 playlists: create, add | ✅ | ❌ | ❌ | ❌ | ✅ | ❌ |
| LIB-13 playlists: remove, delete | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| LIB-14 activity | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| LIB-15 offline browsing | ✅ | — streams, online only | ✅ | ✅ | ✅ | ✅ |

LIB-3 and LIB-8 are not yet checked app by app. Not done anywhere: the artist page's Want
List and Tracks Like This (in `PLAN.md` §4.3).
