# 012 — tofm: separate from the KDE app, video in mpv, covers as characters

**Date:** 2026-11-06 to 2026-11-08 · **Status:** accepted

## Decision

- **Separate from the KDE app**: its own data folder, keyring item, MPRIS name and user agent;
  both can run at once, each signed in as its own device. Without a Secret Service, the
  secrets go to a file only the user can read, with a warning.
- **Video in an external player**: mpv by default; the media token goes in a temporary mpv
  config file (never on the command line, where `ps` shows it); progress through mpv's JSON
  IPC. Another player (a setting) plays downloads only.
- **Covers as block characters**: one drawing path that works in every terminal, over SSH and
  in tmux.
- **`tofm play …`** hands off to a running tofm over its own D-Bus service.

## Ruled out

- **Sharing the KDE app's data and sign-in**: one couldn't run beside the other cleanly.
- **kitty / sixel images**: not in every terminal; two drawing paths.
