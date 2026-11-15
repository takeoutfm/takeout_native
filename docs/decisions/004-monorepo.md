# 004 — One repository for every app

**Date:** October 2026 (PLAN.md §2.3, §7, as of commit 4bce83c) · **Status:** accepted

## Context

The Android TV app (`takeout_tv`) existed on its own; the phone, watch, Apple and Linux apps
were to share its core.

## Decision

A monorepo: `takeout_tv` moved in with its history as `:tv`; the core, the Android library and
every app live together, so a change to the core and every app it touches land in one commit
and one build.

## Ruled out

- **Separate repositories with a published core library**: every core change would need a
  release and a version bump in each app.
