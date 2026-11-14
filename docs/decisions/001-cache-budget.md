# 001 — The cache's size: Auto is 10% of the volume, at most 10 GB, with a free-space floor

**Date:** 2026-11-14 · **Status:** accepted · **Rules:** CACHE-5, CACHE-6

## Context

Playback Caching kept everything played and nothing removed it, so it filled devices. The
Flutter app had an LRU purge by a share of the disk. The apps run on volumes from a watch's
few GB to multi-terabyte desktops.

## Decision

- The cache holds at most the **smaller** of its size (Auto: 10% of the volume, capped at
  10 GB; or a fixed 1, 2, 5 or 10 GB) and what leaves a **free-space floor** free: 2 GB or 10%
  of the volume, **whichever is smaller**.
- Over that, it goes down to **90%** of it, so it doesn't purge after every track.
- Order: partial tracks, finished podcast episodes, then least recently used.
- One rule for every app, in `:shared` (`CachePolicy`), each app listing and removing its own.

## Ruled out

- **A plain share of the disk**: 10% of 2 TB is 200 GB; hence the 10 GB cap.
- **The larger of 2 GB and 10% as the floor**: it would hold back 200 GB on a 2 TB disk.
- **Purging to exactly the budget**: every new track would trigger a purge.
- **Media3's `LeastRecentlyUsedCacheEvictor`**: it would evict the viewer's downloads too.
