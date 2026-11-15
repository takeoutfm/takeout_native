# 013 — The phone keeps Media3's own player menus

**Date:** 2026-11-11 · **Status:** accepted · **Rules:** TRACKS-2, TRACKS-3

## Decision

The phone's video and live players use Media3's `PlayerView` settings and subtitle menus,
with Media3's track names; the app picks the best audio track by the shared ranking and learns
the viewer's picks from the menus (`TrackPicks`).

## Ruled out

- **The shared track labels in the phone's menus**: `PlayerView`'s `TrackNameProvider` can't
  be replaced; it would mean writing the menus.
