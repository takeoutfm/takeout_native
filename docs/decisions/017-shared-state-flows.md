# 017 — Shared server state is a StateFlow in the core, kept current by its edits

**Date:** 2026-11-23 · **Status:** accepted

## Context

The Mac's Playlists grid kept showing a playlist deleted from the playlist's own page. Each page
loaded its own copy of the list, and the grid reloaded only when it reappeared, which it doesn't
on the Mac (a pushed page leaves the root alive). The first fix was a counter that create and
delete bumped and the grid reloaded on. The phone already does the same (`MainViewModel` bumps
a playlists counter). A counter only says "something changed, fetch again": every page has to
know to watch it, every edit has to remember to bump it, and the list is fetched again for an
edit the app itself just made.

## Decision

State on the server that more than one page shows or edits (playlists, favorite tracks,
podcast subscriptions, episode progress) lives in the shared core as a `StateFlow`:

- loading it sets the flow (`TakeoutMusicRepository.playlists()` sets `currentPlaylists`);
- every edit made through the repository updates the flow once the server has made it
  (create adds, delete removes, adding or removing a track changes the count; where only the
  server knows the result, the repository loads it again itself);
- pages observe the flow (Swift `WatchKt.watch` into `AppModel`, Android `collect`, Linux
  `takeout_watch`) and only ask for a fresh load when they appear, for changes made elsewhere.

`favoriteTrackEtags` was already this shape. Following it:

- `TakeoutMusicRepository.currentPlaylists`: the user's playlists (`PlaylistsTest`).
- `TakeoutPodcastRepository.subscribedSeries`: the subscribed podcasts, changed at once by
  subscribing and put back if the server refuses (`PodcastStateTest`).
- `TakeoutProgress.offsets`: the newest position per ETag, the server's as last fetched merged
  with every report made here. An episode's "12 min left" or Played is computed from it, so it
  follows playback and Mark as Played without the page loading again (`PodcastStateTest`).
  On Mac/iOS it's watched into its own small object (`EpisodeOffsets`), not `AppModel`: it
  changes every 10 s while an episode plays, and only the episode views should redraw.

A load that overlaps an edit must not undo it: on the Mac, Back pressed right after Unsubscribe
reloaded the subscriptions before the server had done it, and the stale answer put the podcast
back. `util/EditedState` holds such state: edits run inside `edit { }`, and a `load` that
overlapped one is dropped (the edit has already changed the state; the next load brings
anything else). `currentPlaylists` and `subscribedSeries` use it.

## Ruled out

- **Reload signals** (a counter, a notification, a "version" id that pages reload on): the
  state isn't held anywhere, so each page refetches and each edit must remember to signal.
- **A store per app** (a Swift `ObservableObject`, an Android `ViewModel`): reactive, but each
  app would keep its own copy of the same rules, which is what the core is for (decision
  [007](007-kmp-core.md)).

## Every app now

- Mac/iOS: as above.
- Phone: Playlists, a playlist's page (its tracks load again when its count changes) and the
  Add to playlist picker follow `currentPlaylists`; `MainViewModel`'s counter is gone.
- Phone and TV podcast pages combine their loaded details with `subscribedSeries` and `offsets`
  through `PodcastDetails.following`, so subscribing, Mark as Played and playback show at once.
  The TV's podcast list takes its Subscribed row from `subscribedSeries`.
- TV Continue Watching is computed from `offsets` (`ProgressRules.selectInProgress`, then
  `TakeoutVideoRepository.continueWatchingOf`); showing Home only asks the server for progress
  made elsewhere.
- Linux: `takeout_watch` has `playlists` (the Playlists grid follows it) and
  `podcastSubscriptions` and `episodeProgress` (each episode's
  "N min left" / Played by GUID, computed in the core), as `Core` properties the podcast pages
  bind to; they no longer reload when playback pauses. Favorites drops a track unfavorited
  anywhere at once, and loads again only for a new favorite (only the server has its details).
- Pages still load once when shown, for changes made on another device.
