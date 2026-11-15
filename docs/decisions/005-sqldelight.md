# 005 — SQLDelight for the shared database

**Date:** October 2026 (PLAN.md §7.9, as of commit 4bce83c) · **Status:** accepted

## Context

The core keeps a few small tables (progress and listen queues, Recently Played, Stream
History, the response cache) on every platform, Apple included.

## Decision

SQLDelight: `.sq` files with the SQL, typed Kotlin generated from them, `.sqm` migrations
checked at build time; the native driver on Apple and Linux.

## Ruled out

- **Room-KMP**: stable on KMP only since 2025, newer on Apple, and needs KSP on every native
  target (slower builds). Fine for an Android-only app.
