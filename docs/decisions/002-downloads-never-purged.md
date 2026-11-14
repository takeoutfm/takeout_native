# 002 — The viewer's own downloads are never purged; the app warns instead

**Date:** 2026-11-14 · **Status:** accepted · **Rules:** CACHE-3, CACHE-4, CACHE-7, CACHE-12–15

## Context

A purge has to know which files it may remove. Downloads the viewer asked for (an album
for a flight, a 5 GB movie) can fill a device as Playback Caching does.

## Decision

- Each download records whether Playback Caching added it (**cached**) or the viewer did.
  Only cached ones are purged; downloading something cached makes it the viewer's.
- When the viewer's downloads leave too little free, the app **warns** (settings, Downloads
  page) and makes removal easy: largest first, watched videos offered for removal, a
  space check before a big download.
- Downloads from before the flag was kept count as the viewer's (never purged): safe, at
  the cost of old Playback Caching downloads never going on their own.

## Ruled out

- **Purging explicit downloads under pressure**: the viewer would find their offline music
  gone when they need it.
- **Removing watched videos by themselves**: offered instead (after the title ends, and on
  the Downloads page), not between episodes that play on.
