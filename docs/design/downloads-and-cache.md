# Downloads and the cache — design

How each app keeps downloads and runs the cache purge. Requirements:
[../requirements/downloads-and-cache.md](../requirements/downloads-and-cache.md).

## The shared rule

`:shared` `offline/CachePolicy.kt` decides; each app lists its entries, asks, and removes what
comes back. Nothing in it touches files.

- `CacheEntry(etag, bytes, lastUsedMs, cached, partial, finishedPodcast)`: one download (or,
  on Android, one track's chunks). `cached = false` is never removed.
- `CacheLimit`: `Auto` or `Fixed(bytes)`; `CachePolicy.Choices` is the list every app offers.
- `CachePolicy.plan(entries, limit, totalBytes, freeBytes)` → `CachePlan(remove, budgetBytes,
  cachedBytes, downloadedBytes, lowSpace)`: the purge (**CACHE-5–7**, **CACHE-12**).
- `CachePolicy.makeRoom(entries, totalBytes, freeBytes, needBytes)` → `RoomPlan(remove, enough,
  freeableBytes, fits)`: room for a big download (**CACHE-13/14**).
- Tests: `commonTest` `offline/CachePolicyTest`.

The size is `PlaySettings.cacheLimit` (key `cacheLimit`: `auto` or a byte count), so the phone,
watch, Mac/iOS and Linux keep it in their usual settings store.

Finished podcast episodes and watched videos come from `TakeoutProgress.latest()` with
`ProgressRules.isFinished` (podcasts: the last 2 minutes; video: the last 8% too).

## Phone and watch (`:android-media`)

| Piece | Where |
|---|---|
| Files | Media3 `SimpleCache` in `filesDir/media`, keyed by ETag (a custom cache key) |
| Index | Media3's own SQLite database, `exoplayer_internal.db`: the download index (one row per download; our `DownloadInfo` JSON, with `cached`, in the request's data) and the cache's file metadata |
| Last used | `TouchingEvictor`: evicts nothing, but asks `SimpleCache` to touch a file's spans when read (a row update, as the cache keeps its metadata in the database) |
| Kept as it plays | `offline/PlayedDownloads` (in the music service): a track the cache holds whole becomes a download with `cached = true`, completed from the cache |
| Watch | `WatchViewModel.addDownloads(queue, cached = true)` downloads the queue as it starts |
| Purge | `offline/CachePurge`: lists `cache.keys`; a key with a completed download is that download (cached or not); one with a download in progress is left; any other key is a cached entry, `partial` unless the whole file is there. What `MusicPlayer` has loaded (`NowPlaying.loadedEtags`: current and next) is left. A download is removed through the `DownloadManager` (main thread), anything else with `cache.removeResource`. Volume: `StatFs` on the cache folder |
| Runs | `start()` from `MainActivity.onStart`: when the download index is read, 5 s after downloads change, when the size changes; `refresh()` when Settings or Downloads opens; `clearCached()`; `makeRoom()` before a video download |
| UI | `CachePurge.usage` (`CacheUsage`: cached, downloaded, budget, low space, watched). Phone: Settings `CacheSize`, `DownloadsScreen`, `VideoScreens` `SpaceQuestion`, `VideoPlayerActivity.offerRemoval`. Watch: `WatchApp` settings and downloads |

The TV passes no cache: it always streams.

## Linux and tofm (`:shared` `linuxMain`, `linux/`)

| Piece | Where |
|---|---|
| Files and index | `linux/MediaDownloads`: whole files in `$XDG_DATA_HOME/takeoutfm/media` (tofm: `…/tofm/media`), index `downloads.json` (`DownloadEntry`, with `cached` and `lastUsedMs`) |
| Kept as it plays | `Player::ended` (C++, shared by both apps) reports `keepPlayed` with the item, Recently Played id and queue title; the core downloads it with `cached = true` if Playback Caching is on |
| Last used | the `listenTrack` report sets `LinuxCore.playingEtag` and `MediaDownloads.touch` |
| Purge | `linux/LinuxCachePurge`: the finished downloads as entries; what's playing kept; volume by `statvfs`. Runs at start, 3 s after the set of finished downloads changes, when the size changes; `refreshCache`, `clearCached` calls |
| Calls | `keepPlayed`, `clearCached`, `refreshCache`, `setSettings {cacheLimit}` (0 Auto), `downloadVideo {id, check, anyway}` → `VideoDownload {started, needBytes, freeableBytes, fits}`; watch `cacheUsage` (`CacheUsage`, with the size set and the choices) |
| UI | `TakeoutCore.cacheUsage`. KDE: `SettingsPage` Storage, `DownloadsPage`, `VideoPage` dialog, `VideoPlayer` notice. tofm: `Settings.cpp`, `Downloads.cpp` (`downloadVideo`) |

## Mac/iOS (`apple/`)

| Piece | Where |
|---|---|
| Files and index | `MediaCache`: whole files in Application Support `TakeoutFM/Media`, index `downloads.json` (`Entry`; `cached` and `lastUsedMs` are optionals so older indexes decode) |
| Kept as it plays | `AudioPlayer.keepIfCaching`: a track or episode played to its end is downloaded with `cached: true` |
| Last used | `AudioPlayer.trackChanged` → `MediaCache.touch` |
| Purge | `MediaCache.purge(clear:)` through `SwiftCalls` (`cachePlan`, `cacheRoom`, `finishedEtags`, `cacheLimitBytes`, `cacheChoices`, `cacheLimitFor`); what `AudioPlayer.current` is kept; volume by `volumeAvailableCapacityForImportantUsage` |
| Runs | at load, when a cached download finishes, when the size changes (`AppModel.cacheLimitBytes`), when Downloads appears |
| UI | `MediaCache.usage`; `SettingsView` `CacheSize`, `DownloadsView`, `VideoPage` alert, `offerWatchedRemoval` in both video players |
