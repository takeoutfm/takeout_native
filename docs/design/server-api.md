# The Takeout server API, as the apps use it

Every call goes through `:shared` `api/TakeoutApi` (Ktor); paths are on the signed-in server.
The server is a separate Go project; the apps need no changes to it (custom stations are
waiting on one: there's no `POST /api/radio`).

## Auth

| Endpoint | Used for |
|---|---|
| `GET /api/code`, `POST /api/code` | Device-code sign-in: get a code, then poll with its token until it's approved at `<server>/link` |
| `POST /api/token` | Password sign-in (user, password, passcode); with the refresh token as bearer, a token refresh on 401 |
| `POST /api/link` | Approve another device's code (the phone's Link page) |
| `GET /api/me` | The signed-in user's name ("Connected as") |

API calls send the **access token**; streams the **media token**, only to URLs on the
signed-in server (`TakeoutSession.mediaAuthorization`).

## Library

| Endpoint | Used for |
|---|---|
| `GET /api/index` | The library index: which sections exist, movie genres, whether there's a guide |
| `GET /api/home` | Home lists and recommendation sets |
| `GET /api/search?q=` | Search: music, movies, TV (server query syntax) |
| `GET /api/favorite`; `PUT`/`DELETE /api/favorite/{kind}/{id}` | Favorites: tracks, artists, movies, shows |

## Music

| Endpoint | Used for |
|---|---|
| `GET /api/artists`, `/api/artists/{id}`, `/api/artists/{id}/popular` | Artists, an artist page, popular tracks |
| `GET /api/releases/{id}` | A release page |
| `GET /api/radio` | Stations by type |
| `GET /api/playlists`; `POST /api/playlists`; `DELETE /api/playlists/{id}`; `PATCH /api/playlists/{id}/playlist` | Playlists: list, create, delete, add or remove a track (JSON patch) |
| `GET …/playlist` | A queue as a spiff playlist: `/api/releases/{id}/playlist`, `/api/artists/{id}/{shuffle,popular,radio}/playlist`, `/api/radio/{id}/playlist`, `/api/playlists/{id}/playlist`, `/api/tracks/{id}/playlist` (a track and similar ones), `/api/favorite/tracks/playlist` |
| `PATCH /api/playlist` | The user's server-side current playlist (the phone's voice requests only) |
| `GET /api/activity/tracks/{interval}/stats`, `/api/activity/tracks/recent` | Activity stats, recent tracks |
| `POST /api/activity` | Track Activity: a listened track |
| `/api/tracks/{uuid}/location` | A track's stream (redirects to storage) |

## Movies and TV

| Endpoint | Used for |
|---|---|
| `GET /api/movies`, `/api/movies/{id}` | Every movie (also to match progress by ETag); a movie's details |
| `GET /api/movie-genres/{name}` | A genre page |
| `GET /api/tv`, `/api/tv/series`, `/api/tv/series/{id}`, `/api/tv/episodes/{id}` | Every series and episode (with ETags), the shows, a series with its episodes, an episode |
| `GET /api/profiles/{id}` | A person: bio and credits |
| `GET /api/progress`; `POST /api/progress` | Watch and episode progress (offsets by ETag) |
| `GET /api/guide[?start=&end=]` | Live channels: the schedule for a window |
| `Location` URLs (`/api/movies/{uuid}/location`, …) | A title's stream (redirects to storage) |

## Podcasts

| Endpoint | Used for |
|---|---|
| `GET /api/podcasts`, `/api/podcasts/subscribed` | All series, the subscribed |
| `GET /api/series/{id}` | A series and its episodes |
| `PUT`/`DELETE /api/series/{id}/subscribed` | Subscribe, unsubscribe |
| `/api/episodes/{id}/location` | An episode's stream |

## Images

- `/img/tm/{size}/{path}`: TMDB images through the server (posters `w342`, backdrops `w1280`,
  stills `w300`, profiles `w185` / `w342`).
- `/img/mb/re/{reid}`, `/img/mb/rg/{rgid}`: release covers through the server's Cover Art
  Archive proxy (`TakeoutPaths.releaseCoverPath`).

## Streams

A `…/location` URL answers with a redirect to a presigned storage URL (15 minutes). Android and
Linux follow it once and give the player the storage URL, asking again when it's old; Apple's
video goes through `StreamProxy` ([decision 015](../decisions/015-apple-stream-proxy.md)).
Downloads and the cache are keyed by the file's ETag, never the URL.

## Kept responses (offline browsing)

`DbResponseCache` keeps every GET's last response. Library responses are reused without asking
for an hour, podcast episode lists for 15 minutes; never reused: the index, subscriptions,
progress, activity, favorites, playlists and queues (`…/playlist`), search and the guide. A
write to favorites, playlists or subscriptions forgets what was kept (`forgetKept`).

## Elsewhere

- ListenBrainz: `https://api.listenbrainz.org` (`playing_now` and listens, token check).
