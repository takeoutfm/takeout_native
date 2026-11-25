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
- **LIB-16** An artist's **Want List** (`/api/artists/{id}/wantlist`): the official studio albums
  the library doesn't have (the server's choice: an ASIN and a release country it's set for),
  each with links to look for it: Amazon and CamelCamelCamel (when there's an ASIN),
  MusicBrainz, a web search. Reached from the bottom of the artist page.
- **LIB-7** A release: tracks by disc, play and shuffle, download, similar releases.
- **LIB-8** Favorites: tracks (♥ on any track), artists, and favorite artists' tracks as a
  queue.

## Movies and TV

- **LIB-9** Movies and TV shows as poster grids (recently added, new releases, by genre);
  Continue Watching, where a screen for movies lists only movies and one for TV shows only
  episodes.
- **LIB-10** A title: facts, its whole description (where it's cut short, selecting it shows
  the rest), cast and crew (directors first), Play / Resume / Start over, "N min left",
  related titles; a series' seasons and episodes.
- **LIB-11** A person: their photo (TMDB `profile_path`; a person icon when there is none),
  life and biography, then what they starred in, directed and wrote.

## Search, playlists, activity

- **LIB-12** Search across artists, releases, tracks, stations, movies, series and episodes,
  with the server's query syntax (`artist:name`, `guitar:person`).
- **LIB-13** Playlists: list and play; create, add a track, remove a track, delete (asking
  first). Shown as covers: Favorite Tracks first, then each playlist as a mosaic of its first
  four albums' covers; a playlist's page has an album's header (the mosaic, name, track count,
  Play / Shuffle / Download).
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
| LIB-3 recommendation lists | ✅ | ✅ tabs | — | ✅ rows on Watch (not yet seen: this server has none) | ✅ rows on Watch (not yet seen: the same) | ✅ a Recommended section (the same) |
| LIB-4–7 music browsing | ✅ | ✅ | ⚠️ albums, artists, playlists only | ✅ | ✅ | ✅ |
| LIB-6 favorite artist | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ |
| LIB-8 favorite tracks | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| LIB-8 favorite artists list | ✅ (the Artists tab's Favorites view) | ✅ | ❌ | ✅ | ✅ | ✅ |
| LIB-16 Want List | ✅ (bottom of the artist page) | ❌ | — | ❌ | ❌ | ❌ |
| LIB-9–11 movies and TV | ✅ | ✅ | — no video | ✅ | ✅ | ⚠️ no person pages |
| LIB-12 search | ✅ | ✅ (movies and shows first, then music) | — deferred (not useful on a watch) | ✅ | ✅ | ✅ |
| LIB-13 playlists: create, add | ✅ | ✅ (a track's long press) | — deferred (not useful on a watch) | ✅ | ✅ | ✅ (+ on a track) |
| LIB-13 playlists: remove, delete | ✅ | ✅ | — deferred (not useful on a watch) | ✅ | ✅ | ✅ (D on a track) |
| LIB-13 playlists as covers | ✅ | ✅ | — deferred (not useful on a watch) | ✅ | ✅ | — text |
| LIB-14 activity | ✅ | ✅ (a music tab) | — deferred (not useful on a watch) | ✅ | ✅ | ✅ |
| LIB-15 offline browsing | ✅ | — streams, online only | ✅ | ✅ | ✅ | ✅ |

Not done anywhere: the artist page's Tracks Like This.
