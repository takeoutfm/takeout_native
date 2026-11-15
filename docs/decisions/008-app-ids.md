# 008 — New app ids, installed beside the Flutter apps

**Date:** 2026-11-03 · **Status:** accepted

## Decision

`com.takeoutfm.android` (phone), `com.takeoutfm.wear` (watch), `com.takeoutfm.tv`, Mac
`com.takeoutfm.mac`, iOS `com.takeoutfm.ios`, Linux `com.takeoutfm.linux`; Android debug
builds add `.debug`. The native apps install **beside** the Flutter apps
(`com.takeoutfm.mobile`, `com.takeoutfm.watch`) rather than replacing them.

## Ruled out

- **Reusing the Flutter ids** (the first plan): an update would have replaced the Flutter app
  in place, with no way to run both while the native ones caught up.
