# 007 — A Kotlin Multiplatform core; UI and playback native per platform

**Date:** October 2026 (PLAN.md §2.2, as of commit 4bce83c) · **Status:** accepted

## Context

About 40 endpoints, every model, and the rules (listens, progress, offline queues, caching)
are the same everywhere, and that's where the bugs were.

## Decision

- `:shared` is Kotlin Multiplatform from day one: Android, Apple (static framework) and Linux
  (shared library). No platform imports in common code.
- UI and playback stay native: Compose and Media3 on Android, SwiftUI with AVFoundation and
  VLCKit on Apple, Qt with mpv on Linux.
- Swift calls the core directly (`SwiftCalls` with `@Throws`, a `watch` helper for Flows); no
  SKIE so far.

## Ruled out

- **Compose Multiplatform UI on Apple**: a media app needs the native players, Now Playing and
  system integration.
- **Separate cores per platform**: every rule written and fixed several times.
