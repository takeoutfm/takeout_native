# 009 — Android caches playback in Media3's cache, keyed by ETag

**Date:** 2026-10-26 · **Status:** accepted (the size and purge: [001](001-cache-budget.md))

## Decision

- Playback reads through a Media3 `SimpleCache` (chunked, playback only), keyed by the file's
  ETag: stream URLs redirect to expiring signed URLs, so a URL is no key.
- With Playback Caching off, nothing is written; anything cached or downloaded always plays
  from the cache.
- Live streams are never cached (no ETag, no length).
- Nothing is fetched ahead: a track the cache holds whole becomes a download, listed with the
  queue it played in. (The Flutter app downloaded the whole queue.)

## Ruled out

- **Downloading the whole queue as it starts** (the Flutter app's way): changed on
  2026-10-26 at the user's request to keep only what plays. The watch still downloads its queue
  ahead (Playback Caching there predates this).
