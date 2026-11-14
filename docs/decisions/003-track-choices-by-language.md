# 003 — Track choices are remembered by language only, shared by the video and live players

**Date:** 2026-11-14 · **Status:** accepted · **Rules:** TRACKS-5–10

## Context

Files often have the same language twice (English VobSub and English ASS; stereo and 5.1
audio). The TV already remembered choices by language; the other apps didn't, or only within
one player, and the phone's video player ignored the remembered audio language.

## Decision

- Audio: each title gets the best track by the shared ranking, **preferring the language last
  picked by hand**.
- Subtitles: the **language last picked** ("" for off); nothing picked, or none in that
  language: the title's own default.
- Only the viewer's picks are saved, never the app's own choices.
- Saved on the device and **shared by the video and live players**; every app the same.

## Ruled out (for now)

- **Remembering format and flags too** (ASS over VobSub, SDH, forced): asked about and left
  for later; language alone was enough for now.
- **Per-player memory**: a pick in the live player should carry to movies and back.
